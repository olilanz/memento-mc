package ch.oliverlanz.memento

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.WorldChunk
import net.minecraft.world.World
import ch.oliverlanz.memento.chunkutils.ChunkGroupForgetting

object Memento : ModInitializer {

    override fun onInitialize() {
        // Use MementoDebug so operators can follow the lifecycle in-game while testing.
        MementoDebug.info(null, "Memento initializing")

        Commands.register()

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            MementoDebug.info(server, "Loading anchors and state")
            MementoPersistence.load(server)
            MementoState.load(server)

            // Initialize the "once per world day" marker. If we don't have persisted state yet,
            // we start from the current Overworld day to avoid an immediate decrement on startup.
            val overworld = server.getWorld(World.OVERWORLD)
            // Use *timeOfDay* (not game time) so sleeping and /time set advance the day index.
            val currentDay = overworld?.timeOfDay?.div(MementoConstants.OVERWORLD_DAY_TICKS) ?: -1L
            if (MementoState.get().lastProcessedOverworldDay < 0 && currentDay >= 0) {
                MementoState.set(MementoState.State(lastProcessedOverworldDay = currentDay))
                MementoState.save(server)
            }

            // Rebuild the marked land set from persisted anchors.
            ChunkGroupForgetting.rebuildMarkedGroups(server)
            // Some groups may already be fully unloaded. Sweep once to execute immediately.
            ChunkGroupForgetting.sweep(server)
            MementoDebug.info(server, "Loaded ${MementoAnchors.list().size} anchors")
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            MementoDebug.info(server, "Saving anchors and state")
            MementoPersistence.save(server)
            MementoState.save(server)
        }

        // Anchor aging trigger ("once per Overworld day").
        //
        // Design rationale:
        // - Players can sleep and ops can use /time set, which may jump time.
        // - For algorithmic simplicity we treat "day" as the Overworld day index
        //   (world.timeOfDay / OVERWORLD_DAY_TICKS).
        // - If the day index advances, we age anchors exactly once.
        ServerTickEvents.END_SERVER_TICK.register(ServerTickEvents.EndTick { server ->
            val overworld = server.getWorld(World.OVERWORLD) ?: return@EndTick
            val dayIndex = overworld.timeOfDay / MementoConstants.OVERWORLD_DAY_TICKS
            val last = MementoState.get().lastProcessedOverworldDay

            if (last >= 0 && dayIndex > last) {
                // Nightly/daily aging trigger.
                ChunkGroupForgetting.ageAnchorsOnce(server)
                MementoState.set(MementoState.State(lastProcessedOverworldDay = dayIndex))
                MementoState.save(server)

                // After aging, some Witherstones may mature. Attempt execution for those already unloaded.
                ChunkGroupForgetting.sweep(server)
            }
        })

        // Chunk lifecycle triggers.
        //
        // The mod is intentionally event-driven:
        // - day change marks land for forgetting (Witherstone maturity)
        // - chunk unload is the natural trigger to check whether marked land has become free
        //
        // We still subscribe to CHUNK_LOAD/CHUNK_UNLOAD so future observability can be added
        // without touching wiring. At the moment we keep OP chat high-signal and do not narrate
        // per-chunk load/unload noise.

        ServerChunkEvents.CHUNK_LOAD.register(ServerChunkEvents.Load { _, _ ->
            // Intentionally no-op (observability hook only).
        })

        ServerChunkEvents.CHUNK_UNLOAD.register(ServerChunkEvents.Unload { world: ServerWorld, chunk: WorldChunk ->
            val pos: ChunkPos = chunk.pos
            // Unload-trigger: every unload is a chance for marked land to become free for forgetting.
            ChunkGroupForgetting.onChunkUnloaded(world.server, world, pos)
        })
    }
}


// Observability helper
fun logGroupFootprint(logger: org.slf4j.Logger, name: String, anchorCx: Int, anchorCz: Int, chunks: Set<Pair<Int,Int>>) {
    val xs = chunks.map{it.first}; val zs = chunks.map{it.second}
    val minX = xs.minOrNull(); val maxX = xs.maxOrNull(); val minZ = zs.minOrNull(); val maxZ = zs.maxOrNull()
    val includesAnchor = chunks.contains(anchorCx to anchorCz)
    logger.info("Group footprint for '{}': chunks={} box x=[{}..{}] z=[{}..{}] includesAnchorChunk={}", name, chunks.size, minX, maxX, minZ, maxZ, includesAnchor)
    if (chunks.size <= 18) logger.info("Chunks for '{}': {}", name, chunks.joinToString())
}
