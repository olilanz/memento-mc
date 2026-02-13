package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.ChunkScanProvenance
import ch.oliverlanz.memento.domain.worldmap.ChunkScanUnresolvedReason
import ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry
import ch.oliverlanz.memento.domain.worldmap.WorldMementoMap
import ch.oliverlanz.memento.infrastructure.chunk.ChunkAvailabilityListener
import ch.oliverlanz.memento.infrastructure.chunk.ChunkLoadProvider
import ch.oliverlanz.memento.infrastructure.chunk.ChunkRef
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import ch.oliverlanz.memento.infrastructure.worldscan.FileMetadataProvider
import java.lang.Math
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.WorldChunk

/**
 * World scanner.
 *
 * Responsibility boundaries:
 * - Owns demand (desired chunk set) derived from the WorldMementoMap.
 * - Owns reconciliation (a chunk stops being demanded once metadata is attached).
 * - Receives chunk availability via the ChunkLoadDriver's propagation callback.
 *
 * This class implements both:
 * - [ChunkLoadProvider] (driver pulls demand)
 * - [ChunkAvailabilityListener] (driver pushes availability)
 */
class WorldScanner : ChunkLoadProvider, ChunkAvailabilityListener {

    private var server: MinecraftServer? = null

    /**
     * Scan mode:
     * - active=true -> propose chunks to the driver (baseline scan)
     * - active=false -> passive/reactive only (keep map fresh opportunistically)
     */
    private val activeScan = AtomicBoolean(false)

    /** The world map (single source of truth). Kept in memory across proactive runs. */
    private var map: WorldMementoMap? = null

    /** Bounded, tick-thread-only application path for external scan metadata facts. */
    private var metadataApplier: BoundedScanMetadataTickApplier? = null

    private var consumer: ChunkMetadataConsumer? = null

    /** Optional file-primary provider used before engine fallback demand is enabled. */
    private var fileMetadataProvider: FileMetadataProvider? = null

    /**
     * Active scan demand phase:
     * - [FILE_PRIMARY]: file provider is responsible for first-pass resolution, proactive demand OFF.
     * - [ENGINE_FALLBACK]: driver demand is active for unresolved leftovers only.
     */
    private var demandPhase: DemandPhase = DemandPhase.ENGINE_FALLBACK

    /** Observability only. */
    private var plannedChunks: Int = 0

    /**
     * Current scanner demand set (ChunkKey), regulated by high/low watermarks.
     *
     * - Scanner expresses intent by exposing this set via [desiredChunks].
     * - The driver remains responsible for ticket pacing and pressure balancing.
     * - The map remains the sole authority for scan completion (scanTick).
     */
    private val desiredKeys = LinkedHashSet<ChunkKey>()

    /** Refill cadence guard (absolute world tick). */
    private var lastRefillTick: Long = 0L

    /** Active-scan heartbeat cadence guard (absolute world tick). */
    private var lastHeartbeatTick: Long = 0L

    /** Scanner progress sample used to derive heartbeat delta. */
    private var lastHeartbeatScanned: Int = 0

    private val listeners = CopyOnWriteArrayList<WorldScanListener>()

    /** True after the current active scan has emitted ScanCompleted. */
    private var completionEmittedForCurrentScan: Boolean = false

    fun attach(server: MinecraftServer) {
        this.server = server
        ensureSubstrate()
    }

    fun attachFileMetadataProvider(provider: FileMetadataProvider) {
        fileMetadataProvider = provider
    }

    /**
     * Per-tick scanner runtime processing.
     *
     * This intentionally does not generate scan demand. It only drains externally queued facts into
     * the substrate map on the tick thread.
     */
    fun tick() {
        metadataApplier?.tick()
    }

    /** Application ingestion boundary for future providers (e.g. file-based readers). */
    fun metadataIngestionPort(): ScanMetadataIngestionPort {
        ensureSubstrate()
        return metadataApplier ?: NoopScanMetadataIngestionPort
    }

    fun detach() {
        val providerStatus = fileMetadataProvider?.status()
        val pendingFacts = metadataApplier?.pendingCount() ?: 0

        if (providerStatus != null) {
            MementoLog.info(
                    MementoConcept.SCANNER,
                    "scan shutdown summary providerLifecycle={} firstPass={}/{} secondPass={}/{} emittedFacts={} unappliedQueuedFacts={}",
                    providerStatus.lifecycle,
                    providerStatus.firstPassProcessed,
                    providerStatus.firstPassTotal,
                    providerStatus.secondPassProcessed,
                    providerStatus.secondPassTotal,
                    providerStatus.emittedFacts,
                    pendingFacts,
            )
        }

        server = null
        activeScan.set(false)
        map = null
        demandPhase = DemandPhase.ENGINE_FALLBACK
        fileMetadataProvider?.close()
        fileMetadataProvider = null
        metadataApplier = null
        consumer = null
        plannedChunks = 0
        desiredKeys.clear()
        lastRefillTick = 0L
        lastHeartbeatTick = 0L
        lastHeartbeatScanned = 0
        completionEmittedForCurrentScan = false
        listeners.clear()
    }

    fun addListener(listener: WorldScanListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: WorldScanListener) {
        listeners.remove(listener)
    }

    /** Entry point used by /memento scan. */
    fun startActiveScan(source: ServerCommandSource): Int {
        val srv = source.server
        this.server = srv

        if (activeScan.get()) {
            source.sendError(Text.literal("Memento: scan is already running."))
            return 0
        }

        // (Re)discover existing chunks and ensure they exist in the in-memory map.
        val scanMap = ensureSubstrate()
        val worlds = WorldDiscovery().discover(srv)
        val discoveredRegions = RegionDiscovery().discover(srv, worlds)
        val discoveredChunks = ChunkDiscovery().discover(discoveredRegions)

        var ensured = 0
        discoveredChunks.worlds.forEach { world ->
            world.regions.forEach { region ->
                region.chunks.forEach { slot ->
                    val chunkX = region.x * 32 + slot.localX
                    val chunkZ = region.z * 32 + slot.localZ
                    val key =
                            ChunkKey(
                                    world = world.world,
                                    regionX = region.x,
                                    regionZ = region.z,
                                    chunkX = chunkX,
                                    chunkZ = chunkZ,
                            )
                    scanMap.ensureExists(key)
                    ensured++
                }
            }
        }

        if (ensured == 0 && scanMap.totalChunks() == 0) {
            source.sendFeedback(
                    { Text.literal("Memento: no existing chunks discovered; nothing to scan.") },
                    false
            )
            MementoLog.debug(MementoConcept.SCANNER, "active=false scan aborted reason=no_chunks")
            return 1
        }

        // Substrate consumer is always kept attached to the current map.
        ensureSubstrate()

        plannedChunks = scanMap.totalChunks()
        completionEmittedForCurrentScan = false
        desiredKeys.clear()
        // Force an immediate first refill (cadence guard) on the next desiredChunks() call.
        val tickNow = srv.overworld.time
        lastRefillTick =
                tickNow - MementoConstants.MEMENTO_SCAN_DESIRE_REFILL_EVERY_TICKS
        lastHeartbeatTick = tickNow
        lastHeartbeatScanned = scanMap.scannedChunks()
        demandPhase = DemandPhase.ENGINE_FALLBACK

        // If already complete, emit completion immediately without entering active scan mode.
        if (scanMap.isComplete()) {
            emitCompleted(reason = "already_complete", map = scanMap)
            MementoLog.debug(
                    MementoConcept.SCANNER,
                    "scan already_complete plannedChunks={} scannedChunks={}",
                    plannedChunks,
                    scanMap.scannedChunks()
            )
            source.sendFeedback({ Text.literal("Memento: scan already complete.") }, false)
            return 1
        }

        val provider = fileMetadataProvider
        if (provider != null) {
            val started = provider.start(discoveredChunks, tickNow)
            if (started) {
                demandPhase = DemandPhase.FILE_PRIMARY
                MementoLog.info(
                        MementoConcept.SCANNER,
                        "scan file-primary phase started chunks={}",
                        ensured,
                )
            } else {
                MementoLog.warn(
                        MementoConcept.SCANNER,
                        "scan file-primary phase skipped reason=provider_busy",
                )
            }
        }

        activeScan.set(true)
        MementoLog.info(
                MementoConcept.SCANNER,
                "World scan started. Planned chunks: {}. Scanned: {}. Missing: {}.",
                plannedChunks,
                scanMap.scannedChunks(),
                scanMap.missingCount(),
        )
        source.sendFeedback(
                { Text.literal("Memento: scan started. Planned chunks: $plannedChunks") },
                false
        )
        return 1
    }

    override fun desiredChunks(): Sequence<ChunkRef> {
        if (!activeScan.get()) return emptySequence()
        val m = map ?: return emptySequence()

        if (demandPhase == DemandPhase.FILE_PRIMARY && !transitionToFallbackWhenReady(m)) {
            // While file-primary is running (or queued facts are draining), proactive driver demand
            // remains OFF and only passive/unsolicited enrichment applies.
            return emptySequence()
        }

        val tickNow = server?.overworld?.time ?: 0L
        reconcileAndRefillDemand(tickNow, m)
        maybeEmitActiveHeartbeat(tickNow, m)

        if (desiredKeys.isEmpty()) {
            // Defensive: under the locked invariants, exhaustion may only happen when the map is complete.
            // If we still have missing entries, force a refill once (cadence-safe) before giving up.
            if (!m.isComplete() && m.missingCount() > 0) {
                lastRefillTick =
                        tickNow - MementoConstants.MEMENTO_SCAN_DESIRE_REFILL_EVERY_TICKS
                reconcileAndRefillDemand(tickNow, m)
            }
            if (desiredKeys.isEmpty()) {
                emitCompleted(
                        reason = if (m.isComplete()) "exhausted" else "exhausted_but_missing",
                        map = m
                )
                return emptySequence()
            }
        }

        return desiredKeys.asSequence().map { key ->
            ChunkRef(key.world, ChunkPos(key.chunkX, key.chunkZ))
        }
    }

    private fun reconcileAndRefillDemand(tickNow: Long, map: WorldMementoMap) {
        // 1) Reconcile: drop any keys that no longer need fallback demand.
        if (desiredKeys.isNotEmpty()) {
            val it = desiredKeys.iterator()
            while (it.hasNext()) {
                val key = it.next()
                if (!needsFallbackDemand(map, key)) {
                    it.remove()
                }
            }
        }

        // 2) Watermark refill, bounded by cadence.
        val activeBeforeRefill = desiredKeys.size
        if (activeBeforeRefill >= MementoConstants.MEMENTO_SCAN_DESIRE_LOW_WATERMARK) return
        if (tickNow != 0L &&
                        (tickNow - lastRefillTick) <
                                MementoConstants.MEMENTO_SCAN_DESIRE_REFILL_EVERY_TICKS
        )
                return

        lastRefillTick = tickNow

        val target = MementoConstants.MEMENTO_SCAN_DESIRE_HIGH_WATERMARK
        val refillAdded = mutableListOf<ChunkKey>()
        while (desiredKeys.size < target) {
            val needed = target - desiredKeys.size
            // Fallback demand is sourced from unresolved leftovers after file-primary pass
            // (signals absent and/or still unscanned), not from the original discovery set.
            val candidates = fallbackCandidates(map, desiredKeys.size + needed)
            if (candidates.isEmpty()) break
            var addedAny = false
            for (k in candidates) {
                if (desiredKeys.size >= target) break
                if (desiredKeys.add(k)) {
                    refillAdded += k
                    addedAny = true
                }
            }
            // Safety: if the map returned only keys already desired, stop to avoid tight loops.
            if (!addedAny) break
        }

        if (refillAdded.isNotEmpty()) {
            val head = refillAdded.first()
            val tail = refillAdded.last()
            MementoLog.info(
                    MementoConcept.SCANNER,
                    "Scanner fallback demand refill from unresolved leftovers. active={} added={} activeNow={} head={} r=({}, {}) c=({}, {}) tail={} r=({}, {}) c=({}, {}).",
                    activeBeforeRefill,
                    refillAdded.size,
                    desiredKeys.size,
                    head.world.value.toString(),
                    head.regionX,
                    head.regionZ,
                    head.chunkX,
                    head.chunkZ,
                    tail.world.value.toString(),
                    tail.regionX,
                    tail.regionZ,
                    tail.chunkX,
                    tail.chunkZ,
            )
        }
    }

    private fun maybeEmitActiveHeartbeat(tickNow: Long, map: WorldMementoMap) {
        if (tickNow != 0L &&
                        (tickNow - lastHeartbeatTick) <
                                MementoConstants.MEMENTO_SCAN_HEARTBEAT_EVERY_TICKS
        ) {
            return
        }

        val scanned = map.scannedChunks()
        val deltaScanned = scanned - lastHeartbeatScanned
        val progressPct =
                if (plannedChunks <= 0) 0.0
                else (scanned.toDouble() * 100.0) / plannedChunks.toDouble()
        val progressPctText = String.format(Locale.ROOT, "%.1f", progressPct)
        MementoLog.info(
                MementoConcept.SCANNER,
                "scan heartbeat scanned={}/{} ({}%) deltaScanned={} desiredActive={}",
                scanned,
                plannedChunks,
                progressPctText,
                deltaScanned,
                desiredKeys.size,
        )

        lastHeartbeatTick = tickNow
        lastHeartbeatScanned = scanned
    }

    private fun transitionToFallbackWhenReady(map: WorldMementoMap): Boolean {
        val providerComplete = fileMetadataProvider?.isComplete() ?: true
        val ingestionDrained = (metadataApplier?.pendingCount() ?: 0) <= 0
        if (!providerComplete || !ingestionDrained) {
            return false
        }

        demandPhase = DemandPhase.ENGINE_FALLBACK
        val tickNow = server?.overworld?.time ?: 0L
        lastRefillTick = tickNow - MementoConstants.MEMENTO_SCAN_DESIRE_REFILL_EVERY_TICKS
        MementoLog.info(
                MementoConcept.SCANNER,
                "scan transitioned to engine fallback demand unresolvedLeftovers={}",
                unresolvedFallbackCount(map),
        )
        return true
    }

    private fun fallbackCandidates(map: WorldMementoMap, limit: Int): List<ChunkKey> {
        if (limit <= 0) return emptyList()

        val unresolvedScanned =
                map.snapshot()
                        .asSequence()
                        .filter { it.signals == null }
                        .map { it.key }

        val unscanned = map.missingSignals(limit)

        return (unresolvedScanned + unscanned.asSequence())
                .distinct()
                .sortedWith(
                        compareBy(
                                { it.world.value.toString() },
                                { it.regionX },
                                { it.regionZ },
                                { it.chunkX },
                                { it.chunkZ },
                        )
                )
                .take(limit)
                .toList()
    }

    private fun unresolvedFallbackCount(map: WorldMementoMap): Int {
        val unresolvedScanned = map.snapshot().count { it.signals == null }
        val unscanned = map.missingCount()
        return unresolvedScanned + unscanned
    }

    private fun needsFallbackDemand(map: WorldMementoMap, key: ChunkKey): Boolean {
        return map.isMissing(key) || !map.hasSignals(key)
    }

    override fun onChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        val m = ensureSubstrate()

        val chunkX = chunk.pos.x
        val chunkZ = chunk.pos.z
        val key =
                ChunkKey(
                        world = world.registryKey,
                        regionX = Math.floorDiv(chunkX, 32),
                        regionZ = Math.floorDiv(chunkZ, 32),
                        chunkX = chunkX,
                        chunkZ = chunkZ,
                )

        // Always consume metadata into the map (passive/reactive behavior).
        consumer?.onChunkLoaded(world, chunk)
        m.markScanned(key, world.time)
        desiredKeys.remove(key)
        // If not active scan, we do not drive demand; we only enrich the map.
        if (!activeScan.get()) return

        if (m.isComplete()) {
            emitCompleted(reason = "complete", map = m)
        }
    }

    override fun onChunkUnloaded(world: ServerWorld, pos: ChunkPos) {
        // Scanner currently does not need unload signals.
    }

    override fun onChunkLoadExpired(world: ServerWorld, pos: ChunkPos) {
        val m = ensureSubstrate()

        val chunkX = pos.x
        val chunkZ = pos.z
        val key =
                ChunkKey(
                        world = world.registryKey,
                        regionX = Math.floorDiv(chunkX, 32),
                        regionZ = Math.floorDiv(chunkZ, 32),
                        chunkX = chunkX,
                        chunkZ = chunkZ,
                )

        // Best-effort coverage: record scan tick even when no chunk was accessible for propagation.
        m.markScanned(key, world.time)
        desiredKeys.remove(key)

        if (!activeScan.get()) return
        if (m.isComplete()) {
            emitCompleted(reason = "complete", map = m)
        }
    }

    /**
     * Ensures the scanner substrate exists outside active scan mode.
     *
     * This keeps unsolicited engine chunk-load callbacks observable before `/memento scan` starts,
     * while preserving the demand boundary (`desiredChunks()` remains empty unless active scan is on).
     */
    private fun ensureSubstrate(): WorldMementoMap {
        val existing = map
        if (existing != null) {
            if (consumer == null) consumer = ChunkMetadataConsumer(existing)
            if (metadataApplier == null) {
                metadataApplier =
                        BoundedScanMetadataTickApplier(
                                map = existing,
                                maxAppliesPerTick =
                                        MementoConstants.MEMENTO_SCAN_METADATA_APPLIER_MAX_PER_TICK,
                        )
            }
            return existing
        }

        val created = WorldMementoMap()
        map = created
        consumer = ChunkMetadataConsumer(created)
        metadataApplier =
                BoundedScanMetadataTickApplier(
                        map = created,
                        maxAppliesPerTick = MementoConstants.MEMENTO_SCAN_METADATA_APPLIER_MAX_PER_TICK,
                )
        return created
    }

    private object NoopScanMetadataIngestionPort : ScanMetadataIngestionPort {
        override fun ingest(fact: ScanMetadataFact) {
            // Defensive no-op fallback; should not be reachable after ensureSubstrate().
        }
    }

    private enum class DemandPhase {
        FILE_PRIMARY,
        ENGINE_FALLBACK,
    }

    private fun emitCompleted(reason: String, map: WorldMementoMap) {
        if (!activeScan.compareAndSet(true, false)) return
        if (completionEmittedForCurrentScan) return
        completionEmittedForCurrentScan = true

        desiredKeys.clear()

        val srv = server
        if (srv == null) {
            MementoLog.warn(
                    MementoConcept.SCANNER,
                    "scan completed reason={} but server=null; listeners skipped",
                    reason
            )
            return
        }

        val snapshot = map.snapshot()
        val provenanceCounts = aggregateProvenanceCounts(snapshot)
        val unresolvedReasonCounts = aggregateUnresolvedReasonCounts(snapshot)
        val unresolvedWithoutReasonCount =
                snapshot.count { it.signals == null && it.unresolvedReason == null }

        val event =
                WorldScanCompleted(
                        reason = reason,
                        plannedChunks = plannedChunks,
                        scannedChunks = map.scannedChunks(),
                        missingChunks = map.missingCount(),
                        provenanceCounts = provenanceCounts,
                        unresolvedReasonCounts = unresolvedReasonCounts,
                        unresolvedWithoutReasonCount = unresolvedWithoutReasonCount,
                        snapshot = snapshot,
                )

        listeners.forEach { l ->
            try {
                l.onWorldScanCompleted(srv, event)
            } catch (t: Throwable) {
                MementoLog.error(MementoConcept.SCANNER, "scan completion listener failed", t)
            }
        }

        if (map.isComplete()) {
            MementoLog.info(
                    MementoConcept.SCANNER,
                    "World scan completed. Scanned: {}. Missing: {}. Provenance={} UnresolvedReasons={} unresolvedWithoutReason={}",
                    event.scannedChunks,
                    event.missingChunks,
                    formatProvenanceCounts(event.provenanceCounts),
                    formatUnresolvedReasonCounts(event.unresolvedReasonCounts),
                    event.unresolvedWithoutReasonCount,
            )
        } else if (reason == "exhausted_but_missing") {
            MementoLog.info(
                    MementoConcept.SCANNER,
                    "World scan paused-with-missing. Missing chunks remain: {}. Provenance={} UnresolvedReasons={} unresolvedWithoutReason={}",
                    event.missingChunks,
                    formatProvenanceCounts(event.provenanceCounts),
                    formatUnresolvedReasonCounts(event.unresolvedReasonCounts),
                    event.unresolvedWithoutReasonCount,
            )
        } else {
            MementoLog.info(
                    MementoConcept.SCANNER,
                    "World scan completed. Scanned: {}. Missing: {}. Provenance={} UnresolvedReasons={} unresolvedWithoutReason={}",
                    event.scannedChunks,
                    event.missingChunks,
                    formatProvenanceCounts(event.provenanceCounts),
                    formatUnresolvedReasonCounts(event.unresolvedReasonCounts),
                    event.unresolvedWithoutReasonCount,
            )
        }
    }

    private fun aggregateProvenanceCounts(snapshot: List<ChunkScanSnapshotEntry>): Map<ChunkScanProvenance, Int> {
        val counts = linkedMapOf<ChunkScanProvenance, Int>()
        ChunkScanProvenance.values().forEach { provenance ->
            counts[provenance] = snapshot.count { it.provenance == provenance }
        }
        return counts
    }

    private fun aggregateUnresolvedReasonCounts(
            snapshot: List<ChunkScanSnapshotEntry>
    ): Map<ChunkScanUnresolvedReason, Int> {
        val unresolved = snapshot.asSequence().filter { it.signals == null }
        val counts = linkedMapOf<ChunkScanUnresolvedReason, Int>()
        ChunkScanUnresolvedReason.values().forEach { reason ->
            counts[reason] = unresolved.count { it.unresolvedReason == reason }
        }
        return counts
    }

    private fun formatProvenanceCounts(counts: Map<ChunkScanProvenance, Int>): String {
        return ChunkScanProvenance.values()
                .joinToString(prefix = "[", postfix = "]", separator = ",") { provenance ->
                    "${provenance.name}=${counts[provenance] ?: 0}"
                }
    }

    private fun formatUnresolvedReasonCounts(counts: Map<ChunkScanUnresolvedReason, Int>): String {
        return ChunkScanUnresolvedReason.values()
                .joinToString(prefix = "[", postfix = "]", separator = ",") { reason ->
                    "${reason.name}=${counts[reason] ?: 0}"
                }
    }
}
