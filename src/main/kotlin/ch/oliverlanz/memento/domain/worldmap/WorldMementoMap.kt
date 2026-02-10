package ch.oliverlanz.memento.domain.worldmap

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ChunkScanSnapshotEntry(
    val key: ChunkKey,
    val signals: ChunkSignals?,
    val scanTick: Long,
)

/**
 * Domain-owned world map for /memento scan.
 *
 * Semantics:
 * - The map is created from region file discovery (chunk existence).
 * - Metadata extraction progressively refines the map by attaching [ChunkSignals] to keys.
 * - A chunk is considered *scanned* when [scanTick] is present.
 *   Signals may legitimately be missing (best-effort scanning under engine pressure).
 *
 * Single source of truth (scanner invariant):
 * - missing == scanTick == null
 *
 * Runtime safety:
 * - The server may generate new chunks after the initial discovery run.
 * - Load events for such chunks must not crash the system; they are added on demand.
 */
class WorldMementoMap {

    /**
     * IMPORTANT: ConcurrentHashMap does not allow null values.
     * We store an explicit record with nullable signals to represent
     * "known to exist, but not yet scanned".
     */
    private data class ChunkRecord(
        @Volatile var signals: ChunkSignals? = null,
        @Volatile var scanTick: Long? = null,
    )

    private val records = ConcurrentHashMap<ChunkKey, ChunkRecord>()
    private val scannedCount = AtomicInteger(0)

    /** Ensures the chunk exists in the map (without signals yet). */
    fun ensureExists(key: ChunkKey) {
        records.putIfAbsent(key, ChunkRecord())
    }

    fun contains(key: ChunkKey): Boolean = records.containsKey(key)

    fun hasSignals(key: ChunkKey): Boolean = records[key]?.signals != null


    /** Instrumentation helper: expose record for a key for scanner debug logs. */
    fun debugRecord(key: ChunkKey): Any? = records[key]
    /**
     * Attach/replace signals for a chunk.
     *
     * - If the chunk was not previously known, it will be added.
     * - If this is the first time signals are attached, scan progress advances.
     */
    fun upsertSignals(key: ChunkKey, signals: ChunkSignals): Boolean {
        val record = records.computeIfAbsent(key) { ChunkRecord() }
        val previous = record.signals
        record.signals = signals
        return (previous == null)
    }

    /** Marks a chunk as scanned at the given absolute world tick. */
    fun markScanned(key: ChunkKey, scanTick: Long): Boolean {
        val record = records.computeIfAbsent(key) { ChunkRecord() }
        val first = (record.scanTick == null)
        record.scanTick = scanTick
        if (first) {
            scannedCount.incrementAndGet()
        }
        return first
    }

    /** Total number of existing chunks in the map. */
    fun totalChunks(): Int = records.size

    /** Number of chunks with signals attached (i.e. scanned). */
    fun scannedChunks(): Int = scannedCount.get()

    /** Number of chunks still missing signals (best-effort, monotonic under normal use). */
    fun missingCount(): Int = (records.size - scannedCount.get()).coerceAtLeast(0)

    fun isComplete(): Boolean = records.isNotEmpty() && scannedCount.get() >= records.size

    /**
     * Deterministic view of chunks that still need metadata extraction.
     *
     * Scanner semantics (0.9.7):
     * - Work one region at a time (per-world, per-region deterministic ordering).
     * - Within the chosen region, prefer anchor chunks on the local (1,1) modulo-3 grid
     *   (local coords relative to region origin). This reduces cross-region spillover
     *   from side-effect chunk loads.
     * - Expose only missing chunks on the modulo-3 grid anchored by the chosen anchor.
     * - If no missing chunk exists on the preferred (1,1) grid, fall back to anchoring
     *   on the next missing chunk in the region (same algorithm, different residue).
     *
     * Note: returns at most [limit] keys to keep provider calls cheap.
     */
    fun missingSignals(limit: Int): List<ChunkKey> {
        if (limit <= 0) return emptyList()

        // 1) Choose the next region to work on: the smallest (world, regionX, regionZ) that still has missing chunks.
        data class RegionId(val worldKey: String, val regionX: Int, val regionZ: Int)

        fun regionIsBefore(a: RegionId, b: RegionId): Boolean {
            val wc = a.worldKey.compareTo(b.worldKey)
            if (wc != 0) return wc < 0
            if (a.regionX != b.regionX) return a.regionX < b.regionX
            return a.regionZ < b.regionZ
        }

        var chosenRegion: RegionId? = null
        for ((k, v) in records.entries) {
            if (v.scanTick != null) continue
            val rid = RegionId(k.world.value.toString(), k.regionX, k.regionZ)
            val current = chosenRegion
            if (current == null || regionIsBefore(rid, current)) {
                chosenRegion = rid
            }
        }

        val region = chosenRegion ?: return emptyList()

        fun localMod3(global: Int, regionCoord: Int): Int {
            val local = global - (regionCoord * 32)
            val m = local % 3
            return if (m < 0) m + 3 else m
        }

        // 2) Choose an anchor within the region.
        // Prefer anchors on the local (1,1) grid; otherwise use the first missing chunk.
        var preferredAnchor: ChunkKey? = null
        var fallbackAnchor: ChunkKey? = null

        for ((k, v) in records.entries) {
            if (v.scanTick != null) continue
            if (k.world.value.toString() != region.worldKey) continue
            if (k.regionX != region.regionX || k.regionZ != region.regionZ) continue

            // deterministic "best": smallest (chunkX, chunkZ)
            if (fallbackAnchor == null || k.chunkX < fallbackAnchor.chunkX || (k.chunkX == fallbackAnchor.chunkX && k.chunkZ < fallbackAnchor.chunkZ)) {
                fallbackAnchor = k
            }

            val lx = localMod3(k.chunkX, k.regionX)
            val lz = localMod3(k.chunkZ, k.regionZ)
            if (lx == 1 && lz == 1) {
                if (preferredAnchor == null || k.chunkX < preferredAnchor.chunkX || (k.chunkX == preferredAnchor.chunkX && k.chunkZ < preferredAnchor.chunkZ)) {
                    preferredAnchor = k
                }
            }
        }

        val anchor = preferredAnchor ?: fallbackAnchor ?: return emptyList()

        val anchorRx = localMod3(anchor.chunkX, anchor.regionX)
        val anchorRz = localMod3(anchor.chunkZ, anchor.regionZ)

        // 3) Expose missing chunks in the chosen region that are on the anchor's modulo-3 grid.
        return records.entries.asSequence()
            .filter { it.value.scanTick == null }
            .map { it.key }
            .filter { it.world.value.toString() == region.worldKey && it.regionX == region.regionX && it.regionZ == region.regionZ }
            .filter { localMod3(it.chunkX, it.regionX) == anchorRx && localMod3(it.chunkZ, it.regionZ) == anchorRz }
            .sortedWith(
                compareBy(
                    { it.world.value.toString() },
                    { it.regionX }, { it.regionZ },
                    { it.chunkX }, { it.chunkZ },
                )
            )
            .take(limit)
            .toList()
    }

    /** Deterministic snapshot of scanned chunks (signals present). */
    fun snapshot(): List<ChunkScanSnapshotEntry> {
        return records.entries.asSequence()
            .mapNotNull { (k, v) -> v.scanTick?.let { tick -> ChunkScanSnapshotEntry(k, v.signals, tick) } }
            .sortedWith(
                compareBy(
                    { it.key.world.value.toString() },
                    { it.key.regionX }, { it.key.regionZ },
                    { it.key.chunkX }, { it.key.chunkZ },
                )
            )
            .toList()
    }
}
