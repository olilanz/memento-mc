package ch.oliverlanz.memento.domain.renewal

import net.minecraft.util.math.ChunkPos
import net.minecraft.registry.RegistryKey
import net.minecraft.world.World

class RenewalBatch(
    val name: String,
    private val world: RegistryKey<World>,
    chunks: Set<ChunkPos>,
    private val ratePerSecond: Int
) {

    enum class State {
        CREATED,
        WAITING_FOR_UNLOAD,
        BLOCKED,
        ALL_UNLOADED,
        RENEWING,
        COMPLETED
    }

    var state: State = State.CREATED
        private set

    private val unloadFlags = chunks.associateWith { false }.toMutableMap()
    private val renewedFlags = chunks.associateWith { false }.toMutableMap()
    private var lastTickTime: Long = -1

    fun onChunkUnloaded(world: RegistryKey<World>, pos: ChunkPos): RenewalEvent? {
        if (world != this.world) return null
        if (!unloadFlags.containsKey(pos)) return null

        unloadFlags[pos] = true
        return evaluateUnloadGate()
    }

    fun onChunkLoaded(world: RegistryKey<World>, pos: ChunkPos): RenewalEvent? {
        if (world != this.world) return null
        if (!unloadFlags.containsKey(pos)) return null

        unloadFlags[pos] = false
        return RenewalEvent.UnloadAttemptBlocked(name)
    }

    private fun evaluateUnloadGate(): RenewalEvent {
        return if (unloadFlags.values.all { it }) {
            state = State.ALL_UNLOADED
            RenewalEvent.AllChunksUnloaded(name)
        } else {
            state = State.BLOCKED
            RenewalEvent.UnloadAttemptBlocked(name)
        }
    }

    fun tick(gameTime: Long): RenewalEvent? {
        if (state != State.ALL_UNLOADED && state != State.RENEWING) return null

        if (lastTickTime < 0) {
            state = State.RENEWING
            lastTickTime = gameTime
            return RenewalEvent.RenewalStarted(name)
        }

        val ticksPassed = gameTime - lastTickTime
        if (ticksPassed <= 0) return null

        var renewedThisTick = 0
        for ((chunk, done) in renewedFlags) {
            if (!done && renewedThisTick < ratePerSecond) {
                renewedFlags[chunk] = true
                renewedThisTick++
            }
        }

        lastTickTime = gameTime

        return if (renewedFlags.values.all { it }) {
            state = State.COMPLETED
            RenewalEvent.RenewalCompleted(name)
        } else {
            RenewalEvent.RenewalProgressed(name, renewedThisTick)
        }
    }
}
