package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.domain.renewal.BatchCreated
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import ch.oliverlanz.memento.domain.renewal.RenewalTracker
import ch.oliverlanz.memento.domain.renewal.RenewalTrigger
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

/**
 * Applies an initial load snapshot for existing renewal batches on server startup.
 *
 * This is an observational convenience to seed unload-gate flags without waiting
 * for fresh unload events after restart.
 *
 * NOTE: We intentionally keep this conservative: we treat tracked chunks as 'loaded'
 * on startup unless we have explicit evidence otherwise.
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
        RenewalTracker.resumeAfterStartup()
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
        log.info("[RENEWAL] Applying initial snapshot to {} batch(es)", batches.size)
        for (b in batches) {
            applySnapshotForSnapshot(b)
        }
    }

    private fun applySnapshotFor(batchName: String) {
        val snap = RenewalTracker.snapshotBatches().firstOrNull { it.name == batchName } ?: return
        applySnapshotForSnapshot(snap)
    }

    private fun applySnapshotForSnapshot(snap: RenewalTracker.RenewalBatchSnapshot) {
        // Conservative: assume all tracked chunks are loaded at startup.
        RenewalTracker.applyInitialSnapshot(
            batchName = snap.name,
            loadedChunks = snap.chunks,
            trigger = RenewalTrigger.INITIAL_SNAPSHOT
        )
    }
}
