package ch.oliverlanz.memento.application.renewal

import ch.oliverlanz.memento.domain.renewal.GatePassed
import ch.oliverlanz.memento.domain.renewal.RenewalBatchState
import ch.oliverlanz.memento.domain.renewal.RenewalEvent
import ch.oliverlanz.memento.domain.renewal.RenewalTracker
import ch.oliverlanz.memento.domain.stones.StoneRegister
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import net.minecraft.world.chunk.ChunkStatus
import org.slf4j.LoggerFactory

/**
 * ProactiveRenewer performs renewal work over time.
 *
 * Responsibilities:
 * - Listen to RenewalTracker successful transitions
 * - When chunks are queued for renewal, reload one chunk at a time
 * - Rely on Minecraft to regenerate missing chunks (no manual regeneration)
 * - Allow RenewalTracker to observe completion through natural chunk load events
 * - When a batch is complete, consume the corresponding witherstone
 */
class ProactiveRenewer(
    private val chunksPerTick: Int = 1
) {

    private val log = LoggerFactory.getLogger("memento")

    @Volatile
    private var server: MinecraftServer? = null

    @Volatile
    private var running: Boolean = false

    fun attach(server: MinecraftServer) {
        this.server = server
        this.running = true
    }

    fun detach() {
        this.running = false
        this.server = null
    }

    fun onRenewalEvent(e: RenewalEvent) {
        when (e) {
            is GatePassed -> {
                // Behavior boundaries:
                // - When a batch becomes QUEUED_FOR_RENEWAL, ticking will pick it up.
                // - When a batch becomes RENEWAL_COMPLETE, we finalize by consuming the stone.
                if (e.to == RenewalBatchState.RENEWAL_COMPLETE) {
                    StoneRegister.consume(e.batchName)
                    log.info("[RENEW] batch='{}' completed -> witherstone consumed", e.batchName)
                }
            }
            else -> Unit
        }
    }

    fun tick() {
        if (!running) return
        val s = server ?: return

        var budget = chunksPerTick

        // Deterministic iteration order.
        val batches = RenewalTracker
            .snapshotBatches()
            .sortedBy { it.name }

        for (batch in batches) {
            if (budget <= 0) break
            if (batch.state != RenewalBatchState.QUEUED_FOR_RENEWAL) continue

            val next = batch.nextUnrenewedChunk() ?: continue
            val world = s.getWorld(batch.dimension) ?: continue

            if (reloadChunkBestEffort(world, next)) {
                budget -= 1
            }
        }
    }

    private fun reloadChunkBestEffort(world: ServerWorld, pos: ChunkPos): Boolean {
        return try {
            // Intentional: this requests generation if the chunk is missing.
            world.chunkManager.getChunk(pos.x, pos.z, ChunkStatus.FULL, true)
            log.info("[RENEW] requested chunk load dim='{}' chunk=({}, {})", world.registryKey.value, pos.x, pos.z)
            true
        } catch (t: Throwable) {
            log.warn(
                "[RENEW] failed to request chunk load dim='{}' chunk=({}, {}) err={}",
                world.registryKey.value,
                pos.x,
                pos.z,
                t.toString()
            )
            false
        }
    }
}

private fun MinecraftServer.getWorld(key: RegistryKey<World>): ServerWorld? =
    this.getWorld(key) as? ServerWorld
