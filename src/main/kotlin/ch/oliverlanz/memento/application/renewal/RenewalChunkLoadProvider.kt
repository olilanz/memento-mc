package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.domain.renewal.BatchCompleted
import ch.oliverlanz.memento.domain.renewal.BatchRemoved
import ch.oliverlanz.memento.domain.renewal.BatchWaitingForRenewal
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import ch.oliverlanz.memento.infrastructure.chunk.ChunkAvailabilityListener
import ch.oliverlanz.memento.infrastructure.chunk.ChunkLoadProvider
import ch.oliverlanz.memento.infrastructure.chunk.ChunkRef
import net.minecraft.registry.RegistryKey
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import net.minecraft.world.chunk.WorldChunk
import org.slf4j.LoggerFactory

/**
 * Proactive chunk request source for Renewal.
 *
 * Simplified model (locked):
 * - Renewal works in *batches* (existing containment).
 * - At any time, at most one batch is actively waiting for renewal.
 * - This provider exposes chunks for the *current* waiting batch only.
 * - No synthetic scheduling/compaction logic; the driver owns pacing and priority.
 */
class RenewalChunkLoadProvider : ChunkLoadProvider, ChunkAvailabilityListener {

    private val log = LoggerFactory.getLogger("memento")

    private var activeBatchName: String? = null
    private var activeDimension: RegistryKey<World>? = null
    private val remainingChunks: MutableSet<ChunkPos> = mutableSetOf()

    override fun desiredChunks(): Sequence<ChunkRef> {
        val dim = activeDimension ?: return emptySequence()
        if (remainingChunks.isEmpty()) return emptySequence()

        // Deterministic ordering within the batch.
        val ordered = remainingChunks.sortedWith(compareBy<ChunkPos> { it.x }.thenBy { it.z })
        return ordered.asSequence().map { chunkPos -> ChunkRef(dim, chunkPos) }
    }

    override fun onChunkLoaded(world: ServerWorld, chunk: WorldChunk) {
        val dim = activeDimension ?: return
        if (world.registryKey != dim) return

        // Once a chunk has been observed accessible, it no longer needs to be requested proactively.
        val removed = remainingChunks.remove(chunk.pos)
        if (removed) {
            log.debug(
                "[RENEWAL] observed batch={} dim={} x={} z={} remaining={}",
                activeBatchName,
                dim.value,
                chunk.pos.x,
                chunk.pos.z,
                remainingChunks.size
            )
        }
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
        remainingChunks.clear()
        remainingChunks.addAll(event.chunks)

        log.debug(
            "[RENEWAL] armed batch={} dim={} chunks={}",
            event.batchName,
            event.dimension.value,
            remainingChunks.size
        )
    }

    private fun clearIfActive(batchName: String) {
        if (activeBatchName != batchName) return

        log.debug(
            "[RENEWAL] cleared batch={} dim={} remainingBeforeClear={}",
            activeBatchName,
            activeDimension?.value,
            remainingChunks.size
        )

        activeBatchName = null
        activeDimension = null
        remainingChunks.clear()
    }
}
