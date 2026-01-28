package ch.oliverlanz.memento.domain.memento

import java.util.concurrent.ConcurrentHashMap

/** Domain-owned view of extracted chunk signals for a single run. */
class WorldMementoSubstrate {

    private val records = ConcurrentHashMap<ChunkKey, ChunkSignals>()

    fun contains(key: ChunkKey): Boolean = records.containsKey(key)

    fun upsert(key: ChunkKey, signals: ChunkSignals) {
        records[key] = signals
    }

    fun size(): Int = records.size

    fun snapshot(): List<Pair<ChunkKey, ChunkSignals>> {
        return records.entries
            .map { it.key to it.value }
            .sortedWith(compareBy(
                { it.first.world.value.toString() },
                { it.first.regionX },
                { it.first.regionZ },
                { it.first.chunkX },
                { it.first.chunkZ },
            ))
    }
}
