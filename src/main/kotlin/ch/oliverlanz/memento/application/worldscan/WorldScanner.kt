package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.ChunkScanProvenance
import ch.oliverlanz.memento.domain.worldmap.ChunkScanUnresolvedReason
import ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry
import ch.oliverlanz.memento.domain.worldmap.WorldMementoMap
import ch.oliverlanz.memento.infrastructure.chunk.ChunkAvailabilityListener
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
 * - Owns scan lifecycle and map convergence accounting.
 * - Owns file-primary scan startup and metadata ingestion into the map.
 * - Receives chunk availability via the ChunkLoadDriver's propagation callback for passive
 *   unsolicited enrichment.
 *
 * This class implements [ChunkAvailabilityListener] (driver pushes availability).
 */
class WorldScanner : ChunkAvailabilityListener {

    private var server: MinecraftServer? = null

    /**
     * Scan mode:
     * - active=true -> run active filesystem-first scan lifecycle
     * - active=false -> passive/reactive only (keep map fresh opportunistically)
     */
    private val activeScan = AtomicBoolean(false)

    /** The world map (single source of truth). Kept in memory across active runs. */
    private var map: WorldMementoMap? = null

    /** Bounded, tick-thread-only application path for external scan metadata facts. */
    private var metadataApplier: BoundedScanMetadataTickApplier? = null

    private var consumer: ChunkMetadataConsumer? = null

    /** Optional file-primary provider used for filesystem-first scan enrichment. */
    private var fileMetadataProvider: FileMetadataProvider? = null

    /** Observability only. */
    private var plannedChunks: Int = 0

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

        if (!activeScan.get()) return
        val m = map ?: return
        val tickNow = server?.overworld?.time ?: 0L
        maybeEmitActiveHeartbeat(tickNow, m)
        maybeFinalizeActiveScan(m)
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
        fileMetadataProvider?.close()
        fileMetadataProvider = null
        metadataApplier = null
        consumer = null
        plannedChunks = 0
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
        val tickNow = srv.overworld.time
        lastHeartbeatTick = tickNow
        lastHeartbeatScanned = scanMap.scannedChunks()

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

    private fun maybeFinalizeActiveScan(map: WorldMementoMap) {
        if (map.isComplete()) {
            emitCompleted(reason = "complete", map = map)
            return
        }

        val providerComplete = fileMetadataProvider?.isComplete() ?: true
        val ingestionDrained = (metadataApplier?.pendingCount() ?: 0) <= 0
        if (providerComplete && ingestionDrained) {
            emitCompleted(reason = "exhausted_but_missing", map = map)
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
                0,
        )

        lastHeartbeatTick = tickNow
        lastHeartbeatScanned = scanned
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

        if (!activeScan.get()) return
        if (m.isComplete()) {
            emitCompleted(reason = "complete", map = m)
        }
    }

    /** Ensures the scanner substrate exists outside active scan mode. */
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

    private fun emitCompleted(reason: String, map: WorldMementoMap) {
        if (!activeScan.compareAndSet(true, false)) return
        if (completionEmittedForCurrentScan) return
        completionEmittedForCurrentScan = true

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
