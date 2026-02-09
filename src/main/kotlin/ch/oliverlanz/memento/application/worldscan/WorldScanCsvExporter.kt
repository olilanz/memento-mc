package ch.oliverlanz.memento.application.worldscan

import net.minecraft.server.MinecraftServer

/**
 * Writes the baseline scan snapshot to CSV when a scan completes.
 *
 * This is intentionally a listener (not scanner responsibility). It exists only to establish
 * a reliable baseline artifact for debugging and analysis.
 */
object WorldScanCsvExporter : WorldScanListener {

    override fun onWorldScanCompleted(server: MinecraftServer, event: WorldScanCompleted) {
        MementoCsvWriter.writeSnapshot(server, event.snapshot)
    }
}
