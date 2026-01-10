package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.domain.renewal.BatchCreated
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import ch.oliverlanz.memento.domain.renewal.RenewalTracker
import ch.oliverlanz.memento.domain.renewal.RenewalTrigger
import net.minecraft.server.MinecraftServer
import net.minecraft.world.chunk.ChunkStatus
import org.slf4j.LoggerFactory

/**
 * Observes renewal batches and seeds unload-gate evidence for newly created batches.
 *
 * Locked semantics:
 * - Startup is not a separate code path.
 * - When stones are loaded, they are registered normally.
 * - When derived batches are created, we seed their initial unload evidence based on the server's
 *   current loaded-chunk view (without loading anything).
 */
class RenewalInitialObserver {

    private val log = LoggerFactory.getLogger("memento")

    private var server: MinecraftServer? = null
    private var attached = false

    fun attach(server: MinecraftServer) {
        if (attached) return
        this.server = server
        attached = true
    }

    fun detach() {
        attached = false
        server = null
    }

    fun onRenewalEvent(e: RenewalEvent) {
        if (e is BatchCreated && attached) {
            applySnapshotFor(e.batchName)
        }
    }

    private fun applySnapshotFor(batchName: String) {
        val snap = RenewalTracker.snapshotBatches().firstOrNull { it.name == batchName } ?: return
        applySnapshotForSnapshot(snap)
    }

    private fun applySnapshotForSnapshot(snap: RenewalTracker.RenewalBatchSnapshot) {
        val s = server ?: return
        val world = s.getWorld(snap.dimension)

        // Seed using the server's *current* loaded-chunk view without loading anything.
        // If the world is unavailable, fall back to "nothing loaded" (safe for renewal gating).
        val loadedChunks = if (world == null) {
            emptySet()
        } else {
            snap.chunks
                .asSequence()
                .filter { pos ->
                    // create=false: do not load; only report if already present.
                    world.chunkManager.getChunk(pos.x, pos.z, ChunkStatus.FULL, false) != null
                }
                .toSet()
        }

        RenewalTracker.applyInitialSnapshot(
            batchName = snap.name,
            loadedChunks = loadedChunks,
            trigger = RenewalTrigger.INITIAL_SNAPSHOT
        )
    }
}
