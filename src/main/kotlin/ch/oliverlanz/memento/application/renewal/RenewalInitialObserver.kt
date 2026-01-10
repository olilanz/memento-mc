package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.domain.renewal.BatchCreated
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import ch.oliverlanz.memento.domain.renewal.RenewalTracker
import ch.oliverlanz.memento.domain.renewal.RenewalTrigger
import net.minecraft.server.MinecraftServer
import net.minecraft.world.chunk.ChunkStatus
import org.slf4j.LoggerFactory

/**
 * Seeds load status for existing renewal batches on server startup.
 *
 * This is an observational convenience to seed unload-gate flags without waiting
 * for fresh unload events after restart.
 *
 * NOTE: This must not guess.
 * We seed the unload gate using the server's actual loaded-chunk view without loading anything.
 */
class RenewalInitialObserver {

    private val log = LoggerFactory.getLogger("memento")

    private var server: MinecraftServer? = null
    private var attached = false

    fun attach(server: MinecraftServer) {
        if (attached) return
        this.server = server
        attached = true

        applyStartupSnapshot()
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

    private fun applyStartupSnapshot() {
        val batches = RenewalTracker.snapshotBatches()
        if (batches.isEmpty()) return
        log.info("[RENEWAL] Seeding batch load status for {} batch(es)", batches.size)
        for (b in batches) {
            applySnapshotForSnapshot(b)
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
