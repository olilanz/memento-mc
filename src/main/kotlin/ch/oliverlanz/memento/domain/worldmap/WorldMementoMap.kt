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
 * - A chunk is considered *scanned* when [scanTick] is present. Signals may legitimately be missing
 * (best-effort scanning under engine pressure).
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
     * IMPORTANT: ConcurrentHashMap does not allow null values. We store an explicit record with
     * nullable signals to represent "known to exist, but not yet scanned".
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

    /** True when the chunk exists and has not yet been scanned (scanTick == null). */
    fun isMissing(key: ChunkKey): Boolean = records[key]?.scanTick == null

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
     * Scanner semantics:
     * - Deterministic global ordering by (world, regionX, regionZ, chunkX, chunkZ).
     * - Region-first progression naturally follows from the ordering.
     * - Missing chunks are exposed across subsequent ordered regions up to [limit].
     *
     * Note: returns at most [limit] keys to keep provider calls cheap.
     */
    fun missingSignals(limit: Int): List<ChunkKey> {
        if (limit <= 0) return emptyList()
        return records.entries
                .asSequence()
                .filter { it.value.scanTick == null }
                .map { it.key }
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

    /** Deterministic snapshot of scanned chunks (signals present). */
    fun snapshot(): List<ChunkScanSnapshotEntry> {
        return records.entries
                .asSequence()
                .mapNotNull { (k, v) ->
                    v.scanTick?.let { tick -> ChunkScanSnapshotEntry(k, v.signals, tick) }
                }
                .sortedWith(
                        compareBy(
                                { it.key.world.value.toString() },
                                { it.key.regionX },
                                { it.key.regionZ },
                                { it.key.chunkX },
                                { it.key.chunkZ },
                        )
                )
                .toList()
    }
}
