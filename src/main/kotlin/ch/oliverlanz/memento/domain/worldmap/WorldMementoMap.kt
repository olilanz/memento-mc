package ch.oliverlanz.memento.domain.worldmap

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Domain-owned world map for /memento run.
 *
 * Semantics:
 * - The map is created from region file discovery (chunk existence).
 * - Metadata extraction progressively refines the map by attaching [ChunkSignals] to keys.
 * - A chunk is considered *scanned* when [ChunkSignals] are present.
 *
 * This is intentionally the single source of truth:
 * - "needs scanning" == "exists in map AND signals missing".
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
    )

    private val records = ConcurrentHashMap<ChunkKey, ChunkRecord>()
    private val scannedCount = AtomicInteger(0)

    /** Ensures the chunk exists in the map (without signals yet). */
    fun ensureExists(key: ChunkKey) {
        records.putIfAbsent(key, ChunkRecord())
    }

    fun contains(key: ChunkKey): Boolean = records.containsKey(key)

    fun hasSignals(key: ChunkKey): Boolean = records[key]?.signals != null

    /**
     * Attach/replace signals for a chunk.
     *
     * - If the chunk was not previously known, it will be added.
     * - If this is the first time signals are attached, scan progress advances.
     */
    fun upsertSignals(key: ChunkKey, signals: ChunkSignals) {
        val record = records.computeIfAbsent(key) { ChunkRecord() }
        val previous = record.signals
        record.signals = signals
        if (previous == null) {
            scannedCount.incrementAndGet()
        }
    }

    /** Total number of existing chunks in the map. */
    fun totalChunks(): Int = records.size

    /** Number of chunks with signals attached (i.e. scanned). */
    fun scannedChunks(): Int = scannedCount.get()

    fun isComplete(): Boolean = records.isNotEmpty() && scannedCount.get() >= records.size

    /**
     * Deterministic view of chunks that still need metadata extraction.
     *
     * Note: returns at most [limit] keys to keep provider calls cheap.
     */
    fun missingSignals(limit: Int): List<ChunkKey> {
        return records.entries.asSequence()
            .filter { it.value.signals == null }
            .map { it.key }
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
    fun snapshot(): List<Pair<ChunkKey, ChunkSignals>> {
        return records.entries.asSequence()
            .mapNotNull { (k, v) -> v.signals?.let { k to it } }
            .sortedWith(
                compareBy(
                    { it.first.world.value.toString() },
                    { it.first.regionX }, { it.first.regionZ },
                    { it.first.chunkX }, { it.first.chunkZ },
                )
            )
            .toList()
    }
}
