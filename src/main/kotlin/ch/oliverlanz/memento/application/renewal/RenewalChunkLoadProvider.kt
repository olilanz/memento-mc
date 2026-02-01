package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.domain.renewal.BatchCompleted
import ch.oliverlanz.memento.domain.renewal.BatchRemoved
import ch.oliverlanz.memento.domain.renewal.BatchWaitingForRenewal
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import ch.oliverlanz.memento.infrastructure.chunk.ChunkLoadProvider
import ch.oliverlanz.memento.infrastructure.chunk.ChunkRef
import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import org.slf4j.LoggerFactory

/**
 * Proactive chunk request source for Renewal.
 *
 * Simplified and locked model:
 * - Renewal works in *batches* (existing containment).
 * - At any time, at most one batch is actively waiting for renewal.
 * - This provider exposes chunks for the *current* waiting batch only.
 * - No per-chunk progress tracking.
 * - The renewal engine is authoritative for completion.
 */
class RenewalChunkLoadProvider : ChunkLoadProvider {

    private val log = LoggerFactory.getLogger("memento")

    private var activeBatchName: String? = null
    private var activeDimension: RegistryKey<World>? = null
    private val batchChunks: MutableSet<ChunkPos> = mutableSetOf()

    override fun desiredChunks(): Sequence<ChunkRef> {
        val dim = activeDimension ?: return emptySequence()
        if (batchChunks.isEmpty()) return emptySequence()

        // Deterministic ordering within the batch.
        val ordered = batchChunks.sortedWith(compareBy<ChunkPos> { it.x }.thenBy { it.z })
        return ordered.asSequence().map { chunkPos -> ChunkRef(dim, chunkPos) }
    }

    fun onRenewalEvent(event: RenewalEvent) {
        when (event) {
            is BatchWaitingForRenewal -> armIfIdle(event)
            is BatchCompleted -> clearIfActive(event.batchName)
            is BatchRemoved -> clearIfActive(event.batchName)
            else -> {
                // Intentionally ignored.
            }
        }
    }

    private fun armIfIdle(event: BatchWaitingForRenewal) {
        // Renewal batches are sequential; ignore if already armed.
        if (activeBatchName != null) return

        activeBatchName = event.batchName
        activeDimension = event.dimension
        batchChunks.clear()
        batchChunks.addAll(event.chunks)

        log.debug(
            "[RENEWAL] armed batch={} dim={} chunks={}",
            event.batchName,
            event.dimension.value,
            batchChunks.size
        )
    }

    private fun clearIfActive(batchName: String) {
        if (activeBatchName != batchName) return

        log.debug(
            "[RENEWAL] cleared batch={} dim={} chunks={}",
            activeBatchName,
            activeDimension?.value,
            batchChunks.size
        )

        activeBatchName = null
        activeDimension = null
        batchChunks.clear()
    }
}
