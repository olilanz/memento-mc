package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.domain.renewal.BatchCreated
import ch.oliverlanz.memento.domain.renewal.BatchUpdated
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import ch.oliverlanz.memento.domain.renewal.RenewalTracker
import ch.oliverlanz.memento.domain.renewal.RenewalTrigger
import ch.oliverlanz.memento.infrastructure.chunk.ChunkLoading
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import org.slf4j.LoggerFactory

/**
 * Establishes initial "chunk loaded/unloaded" ground truth for renewal batches.
 *
 * This is deliberately an observation step:
 * - It does not assume chunks are unloaded on server start.
 * - It snapshots current load state whenever a batch is created or updated.
 *
 * Keeping this outside RenewalTracker preserves the domain boundary: the tracker stays passive,
 * while infrastructure queries happen in the application layer.
 */
class RenewalInitialObserver {

    private val log = LoggerFactory.getLogger("memento")

    private var server: MinecraftServer? = null

    fun attach(server: MinecraftServer) {
        this.server = server
    }

    fun detach() {
        this.server = null
    }

    fun onRenewalEvent(e: RenewalEvent) {
        val server = this.server ?: return

        when (e) {
            is BatchCreated -> applySnapshot(server, e.batchName)
            is BatchUpdated -> applySnapshot(server, e.batchName)
            else -> Unit
        }
    }

    private fun applySnapshot(server: MinecraftServer, batchName: String) {
        val batch = RenewalTracker.snapshotBatches().firstOrNull { it.name == batchName } ?: return
        val world: ServerWorld = server.getWorld(batch.dimension) ?: return

        val loaded = batch.chunks.filter { pos ->
            ChunkLoading.isChunkLoadedBestEffort(world, pos)
        }.toSet()

        log.info("[BRIDGE] initial snapshot batch='{}' loaded={} unloaded={}",
            batchName, loaded.size, batch.chunks.size - loaded.size)

        RenewalTracker.applyInitialSnapshot(batchName, loadedChunks = loaded, trigger = RenewalTrigger.INITIAL_SNAPSHOT)
    }
}
