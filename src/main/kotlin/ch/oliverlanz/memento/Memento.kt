package ch.oliverlanz.memento

import ch.oliverlanz.memento.application.stone.WitherstoneLifecycle
import ch.oliverlanz.memento.domain.stones.StoneRegisterHooks
import ch.oliverlanz.memento.domain.renewal.advanceTime
import ch.oliverlanz.memento.domain.renewal.onChunkLoaded
import ch.oliverlanz.memento.domain.renewal.onChunkUnloaded
import ch.oliverlanz.memento.infrastructure.MementoConstants
import ch.oliverlanz.memento.infrastructure.MementoDebug
import ch.oliverlanz.memento.infrastructure.MementoPersistence
import ch.oliverlanz.memento.infrastructure.MementoState
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld

object Memento : ModInitializer {

    private const val MOD_ID = "memento"

    // Legacy mode remains authoritative.
    internal const val LEGACY_MODE = true

    // Shadow mode for new-generation components.
    internal const val NEW_MODE = true

    override fun onInitialize() {
        
        Commands.register()
MementoDebug.info(null, "Initializing Memento...")

        initializeLegacyComponents()
        initializeShadowComponents()
    }

    private fun initializeLegacyComponents() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            MementoState.load(server)
            MementoPersistence.load(server)
            WitherstoneLifecycle.attachServer(server)
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            WitherstoneLifecycle.detachServer(server)
            MementoPersistence.save(server)
            MementoState.save(server)
            if (NEW_MODE) {
                StoneRegisterHooks.onServerStopping()
            }
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            onServerTick(server)
        }
    }

    private fun onServerTick(server: MinecraftServer) {
        // Primary: legacy lifecycle tick (authoritative)
        if (LEGACY_MODE) {
            WitherstoneLifecycle.tick(server)
        }

        // Shadow: keep derived state time-aware for observability only
        if (NEW_MODE) {
            advanceTime(server.ticks.toLong())
        }

        // Nightly checkpoint (03:00), aligned with constants.
        //
        // We process at most once per overworld day. This remains robust under:
        // - /time add (jumps)
        // - /time set
        // - sleeping
        val overworld: ServerWorld = server.overworld
        val timeOfDay = overworld.timeOfDay
        val overworldDay = timeOfDay / 24000L
        val dayTick = timeOfDay % 24000L

        val state = MementoState.get()

        // If we're before the checkpoint tick, the latest checkpoint that could have happened
        // is from the previous day. This makes full-day time jumps behave sensibly.
        val targetDayToProcess =
            if (dayTick >= MementoConstants.RENEWAL_CHECKPOINT_TICK) overworldDay else (overworldDay - 1)

        if (targetDayToProcess > state.lastProcessedOverworldDay) {
            val daysToProcess = (targetDayToProcess - state.lastProcessedOverworldDay).toInt()

            if (LEGACY_MODE) {
                repeat(daysToProcess) {
                    WitherstoneLifecycle.ageAnchorsOnce(server)
                    WitherstoneLifecycle.sweep(server)
                }
            }

            if (NEW_MODE) {
                StoneRegisterHooks.onNightlyCheckpoint(daysToProcess)
            }

            MementoState.set(MementoState.State(lastProcessedOverworldDay = targetDayToProcess))
            MementoState.save(server)
        }
    }

    private fun initializeShadowComponents() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            // Shadow components are non-authoritative. They may load their own persistence for inspection/verification.
            StoneRegisterHooks.onServerStarted(server)
            advanceTime(server.ticks.toLong())
        }

        // Shadow unload/load observers (dimension-aware).
        ServerChunkEvents.CHUNK_UNLOAD.register { world, chunk ->
            onChunkUnloaded(
                server = world.server,
                dimension = world.registryKey,
                chunk = chunk.pos,
                gameTime = world.time,
            )
        }

        ServerChunkEvents.CHUNK_LOAD.register { world, chunk ->
            onChunkLoaded(
                server = world.server,
                dimension = world.registryKey,
                chunk = chunk.pos,
                gameTime = world.time,
            )
        }
    }
}