package ch.oliverlanz.memento.infrastructure.worldscan

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.ChunkMetadataFact
import ch.oliverlanz.memento.domain.worldmap.ChunkScanProvenance
import ch.oliverlanz.memento.domain.worldmap.ChunkScanUnresolvedReason
import ch.oliverlanz.memento.domain.worldmap.ChunkScanSnapshotEntry
import ch.oliverlanz.memento.domain.worldmap.WorldMementoMap
import ch.oliverlanz.memento.domain.worldmap.WorldMapService
import ch.oliverlanz.memento.infrastructure.chunk.ChunkAvailabilityListener
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import ch.oliverlanz.memento.infrastructure.observability.OperatorMessages
import ch.oliverlanz.memento.infrastructure.worldscan.FileMetadataProvider
import java.lang.Math
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.math.ChunkPos

/**
 * World scanner.
 *
 * Responsibility boundaries:
 * - Owns scan lifecycle and map convergence accounting.
 * - Owns file-primary scan startup and metadata ingestion into the map.
 * - Receives chunk availability via the ChunkLoadDriver's propagation callback for passive
 *   ambient enrichment.
 *
 * This class implements [ChunkAvailabilityListener] (driver pushes availability).
 */
class WorldScanner : ChunkAvailabilityListener {

    data class StatusView(
        val active: Boolean,
        val plannedChunks: Int,
        val pendingQueuedFacts: Int,
        val worldMapTotal: Int,
        val worldMapScanned: Int,
        val worldMapMissing: Int,
        val providerStatus: FileMetadataProviderStatus?,
        val runningDurationMs: Long?,
        val lastCompletedDurationMs: Long?,
        val lastCompletedAtMs: Long?,
        val lastCompletionReason: String?,
    )

    private var server: MinecraftServer? = null

    /**
     * Scan mode:
     * - active=true -> run active filesystem-first scan lifecycle
     * - active=false -> passive/reactive only (keep map fresh opportunistically)
     */
    private val activeScan = AtomicBoolean(false)

    /** Domain-owned world-map lifecycle/ingestion authority. */
    private var worldMapService: WorldMapService? = null

    /** File-provider facts are queued and drained on tick thread by scanner runtime. */
    private val pendingFileFacts = ConcurrentLinkedQueue<ScanMetadataFact>()

    private var consumer: ChunkMetadataConsumer? = null

    /** Optional file-primary provider used for filesystem-first scan enrichment. */
    private var fileMetadataProvider: FileMetadataProvider? = null

    /** Observability only. */
    private var plannedChunks: Int = 0

    /** Scanner progress sample used to derive heartbeat delta. */
    private var lastHeartbeatScanned: Int = 0

    /** Next scan-progress threshold (percentage points) for heartbeat emission. */
    private var nextHeartbeatProgressPct: Int = 5

    private val listeners = CopyOnWriteArrayList<WorldScanListener>()

    /** True after the current active scan has emitted ScanCompleted. */
    private var completionEmittedForCurrentScan: Boolean = false

    /** Retry ownership remains in scanner: provider busy waits for next MEDIUM pulse retry. */
    private var pendingProviderStartPlan: WorldDiscoveryPlan? = null
    private var pendingProviderStartScanTick: Long? = null
    private var providerRetryPending: Boolean = false

    private var activeSinceMs: Long? = null
    private var lastCompletedDurationMs: Long? = null
    private var lastCompletedAtMs: Long? = null
    private var lastCompletionReason: String? = null

    fun attach(server: MinecraftServer) {
        this.server = server
        MementoLog.info(
                MementoConcept.SCANNER,
                "scanner attach(server): worldMapServiceAttached={}",
                worldMapService != null,
        )
        ensureSubstrate()
    }

    fun attachWorldMapService(service: WorldMapService) {
        worldMapService = service
        MementoLog.info(MementoConcept.SCANNER, "scanner attachWorldMapService: service bound")
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
        drainQueuedFileFacts()

        if (!activeScan.get()) return
        val m = mapSnapshot() ?: return
        maybeEmitActiveHeartbeat(m)
        maybeFinalizeActiveScan(m)
    }

    fun onMediumPulse() {
        if (!activeScan.get()) return
        if (!providerRetryPending) return
        val plan = pendingProviderStartPlan ?: return
        val scanTick = pendingProviderStartScanTick ?: return
        attemptStartFileProvider(plan = plan, scanTick = scanTick, fromRetry = true)
    }

    /** Application ingestion boundary for future providers (e.g. file-based readers). */
    fun metadataIngestionPort(): ScanMetadataIngestionPort {
        ensureSubstrate()
        return scannerQueueIngestionPort
    }

    /**
     * Lightweight read-only status surface for operator inspection.
     */
    fun statusView(): StatusView {
        val map = mapSnapshot()
        val now = System.currentTimeMillis()
        return StatusView(
            active = activeScan.get(),
            plannedChunks = plannedChunks,
            pendingQueuedFacts = pendingFileFacts.size,
            worldMapTotal = map?.totalChunks() ?: 0,
            worldMapScanned = map?.scannedChunks() ?: 0,
            worldMapMissing = map?.missingCount() ?: 0,
            providerStatus = fileMetadataProvider?.status(),
            runningDurationMs = activeSinceMs?.let { (now - it).coerceAtLeast(0L) },
            lastCompletedDurationMs = lastCompletedDurationMs,
            lastCompletedAtMs = lastCompletedAtMs,
            lastCompletionReason = lastCompletionReason,
        )
    }

    fun detach() {
        val providerStatus = fileMetadataProvider?.status()
        val pendingFacts = pendingFileFacts.size

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
        fileMetadataProvider?.close()
        fileMetadataProvider = null
        worldMapService = null
        pendingFileFacts.clear()
        consumer = null
        plannedChunks = 0
        lastHeartbeatScanned = 0
        nextHeartbeatProgressPct = 5
        completionEmittedForCurrentScan = false
        pendingProviderStartPlan = null
        pendingProviderStartScanTick = null
        providerRetryPending = false
        activeSinceMs = null
        lastCompletedDurationMs = null
        lastCompletedAtMs = null
        lastCompletionReason = null
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
            source.sendError(Text.literal("[Memento] scan is already running."))
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
                    { Text.literal("[Memento] no existing chunks discovered; nothing to scan.") },
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
        lastHeartbeatScanned = scanMap.scannedChunks()
        val baselineProgress =
                if (plannedChunks <= 0) 0
                else ((lastHeartbeatScanned * 100) / plannedChunks)
        nextHeartbeatProgressPct = (((baselineProgress / 5) + 1) * 5).coerceAtMost(100)

        // If already complete, emit completion immediately without entering active scan mode.
        if (scanMap.isComplete()) {
            emitCompleted(reason = "already_complete", map = scanMap)
            MementoLog.debug(
                    MementoConcept.SCANNER,
                    "scan already_complete plannedChunks={} scannedChunks={}",
                    plannedChunks,
                    scanMap.scannedChunks()
            )
            source.sendFeedback({ Text.literal("[Memento] scan already complete.") }, false)
            return 1
        }

        val provider = fileMetadataProvider
        if (provider != null) {
            pendingProviderStartPlan = discoveredChunks
            pendingProviderStartScanTick = tickNow
            providerRetryPending = true
            attemptStartFileProvider(plan = discoveredChunks, scanTick = tickNow, fromRetry = false)
        }

        activeScan.set(true)
        activeSinceMs = System.currentTimeMillis()
        lastCompletionReason = null
        MementoLog.info(
                MementoConcept.SCANNER,
                "World scan started. Planned chunks: {}. Scanned: {}. Missing: {}.",
                plannedChunks,
                scanMap.scannedChunks(),
                scanMap.missingCount(),
        )
        source.sendFeedback(
                { Text.literal("[Memento] scan started. Planned chunks: $plannedChunks") },
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
        val ingestionDrained = pendingFileFacts.isEmpty()
        if (providerComplete && ingestionDrained) {
            emitCompleted(reason = "exhausted_but_missing", map = map)
        }
    }

    private fun maybeEmitActiveHeartbeat(map: WorldMementoMap) {
        if (plannedChunks <= 0) return

        val scanned = map.scannedChunks()
        val progressPctInt = ((scanned * 100) / plannedChunks).coerceIn(0, 100)
        if (progressPctInt < nextHeartbeatProgressPct) return

        val deltaScanned = scanned - lastHeartbeatScanned
        val progressPct = (scanned.toDouble() * 100.0) / plannedChunks.toDouble()
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
        lastHeartbeatScanned = scanned
        nextHeartbeatProgressPct = (nextHeartbeatProgressPct + 5).coerceAtMost(100)
    }

    override fun onChunkMetadata(world: ServerWorld, fact: ChunkMetadataFact) {
        val m = mapSnapshot() ?: return
        worldMapService?.applyFactOnTickThread(fact)
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
        val m = mapSnapshot() ?: return

        if (!activeScan.get()) return
        if (m.isComplete()) {
            emitCompleted(reason = "complete", map = m)
        }
    }

    /** Ensures the scanner substrate exists outside active scan mode. */
    private fun ensureSubstrate(): WorldMementoMap {
        val existing = mapSnapshot()
        if (existing != null) {
            if (consumer == null) consumer = ChunkMetadataConsumer(existing)
            return existing
        }

        error("WorldScanner requires attached WorldMapService before use")
    }

    private fun mapSnapshot(): WorldMementoMap? = worldMapService?.substrate()

    fun emitChunkMetadataFromLoadedChunk(
            world: ServerWorld,
            chunk: net.minecraft.world.chunk.WorldChunk,
            source: ChunkScanProvenance,
            scanTick: Long,
    ) {
        val m = ensureSubstrate()
        val c = consumer ?: ChunkMetadataConsumer(m).also { consumer = it }
        val fact = c.extractFact(world = world, chunk = chunk, source = source, scanTick = scanTick)
        worldMapService?.applyFactOnTickThread(fact)
    }

    private fun drainQueuedFileFacts() {
        val service = worldMapService ?: return
        if (MementoConstants.MEMENTO_SCAN_METADATA_APPLIER_MAX_PER_TICK <= 0) return

        var forwarded = 0
        while (forwarded < MementoConstants.MEMENTO_SCAN_METADATA_APPLIER_MAX_PER_TICK) {
            val fact = pendingFileFacts.poll() ?: break
            service.applyFactOnTickThread(fact)
            forwarded++
        }
    }

    private val scannerQueueIngestionPort = ScanMetadataIngestionPort { fact -> pendingFileFacts.add(fact) }

    private fun emitCompleted(reason: String, map: WorldMementoMap) {
        if (!activeScan.compareAndSet(true, false)) return
        if (completionEmittedForCurrentScan) return
        completionEmittedForCurrentScan = true

        val completedAtMs = System.currentTimeMillis()
        val durationMs = activeSinceMs?.let { (completedAtMs - it).coerceAtLeast(0L) }
        lastCompletedDurationMs = durationMs
        lastCompletedAtMs = completedAtMs
        lastCompletionReason = reason
        activeSinceMs = null
        providerRetryPending = false
        pendingProviderStartPlan = null
        pendingProviderStartScanTick = null

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

        val durationText = durationMs?.let { " in ${formatDuration(it)}" } ?: ""
        val message = if (map.isComplete()) {
            "The world survey is complete$durationText. ${event.scannedChunks} chunks were confirmed."
        } else {
            "The world survey paused$durationText. ${event.missingChunks} chunks still need confirmation."
        }
        OperatorMessages.info(srv, message)
    }

    private fun attemptStartFileProvider(plan: WorldDiscoveryPlan, scanTick: Long, fromRetry: Boolean) {
        val provider = fileMetadataProvider ?: run {
            providerRetryPending = false
            pendingProviderStartPlan = null
            pendingProviderStartScanTick = null
            return
        }

        val started = provider.start(plan, scanTick)
        if (started) {
            providerRetryPending = false
            pendingProviderStartPlan = null
            pendingProviderStartScanTick = null
            MementoLog.info(
                MementoConcept.SCANNER,
                "scan file-primary phase started retry={} plannedChunks={}",
                fromRetry,
                plannedChunks,
            )
            return
        }

        providerRetryPending = true
        MementoLog.debug(
            MementoConcept.SCANNER,
            "scan file-primary gate busy; retryScheduled=medium-cadence active={}",
            activeScan.get(),
        )
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = Math.max(0L, durationMs / 1000L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0L) "${minutes}m ${seconds}s" else "${seconds}s"
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
