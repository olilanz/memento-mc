package ch.oliverlanz.memento.application.worldscan

import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.ChunkSignals
import net.minecraft.server.MinecraftServer

/**
 * Scan lifecycle events.
 *
 * The scanner is always attached and continuously maintains the WorldMementoMap.
 * "Scan" refers to an *active baseline pass* that converges until all known chunks
 * have metadata attached, after which the scanner switches into passive mode.
 */

/** Listener interface owned by the scanner boundary (application layer). */
fun interface WorldScanListener {
    fun onWorldScanCompleted(server: MinecraftServer, event: WorldScanCompleted)
}

/** Emitted exactly once per completed active scan. */
data class WorldScanCompleted(
    val reason: String,
    val plannedChunks: Int,
    val scannedChunks: Int,
    val missingChunks: Int,
    /** Snapshot of scanned chunks at completion time (signals present). */
    val snapshot: List<Pair<ChunkKey, ChunkSignals>>,
)
