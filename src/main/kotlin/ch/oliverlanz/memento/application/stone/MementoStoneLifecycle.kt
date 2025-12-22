package ch.oliverlanz.memento.application.stone

import ch.oliverlanz.memento.application.MementoAnchors
import ch.oliverlanz.memento.application.land.ChunkGroupForgetting
import ch.oliverlanz.memento.infrastructure.MementoConstants
import ch.oliverlanz.memento.infrastructure.MementoDebug
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World

/**
 * Facade coordinating the lifecycle of memento stones and their derived effects on land.
 *
 * Responsibilities (locked intent):
 * - MementoAnchors owns aging/maturity of stones (persisted)
 * - ChunkGroupForgetting owns derived groups + execution (not persisted)
 * - This facade wires the two together so the mod entry point stays thin
 */
object MementoStoneLifecycle {

    fun attachServer(server: MinecraftServer) {
        rebuildMarkedGroups(server, ChunkGroupForgetting.StoneMaturityTrigger.NIGHTLY_TICK)
        MementoDebug.info(server, "MementoStoneLifecycle attached")
    }

    fun detachServer(server: MinecraftServer) {
        // Detach is intentionally a no-op today; kept to preserve call sites.
        // (We log for symmetry and debuggability.)
        MementoDebug.info(server, "MementoStoneLifecycle detached")
    }

    fun rebuildMarkedGroups(server: MinecraftServer) {
        rebuildMarkedGroups(server, ChunkGroupForgetting.StoneMaturityTrigger.SERVER_START)
    }

    fun rebuildMarkedGroups(server: MinecraftServer, trigger: ChunkGroupForgetting.StoneMaturityTrigger) {
        ChunkGroupForgetting.rebuildFromAnchors(server, trigger)
    }

    /**
     * Age anchors once per Overworld day.
     *
     * Important wiring:
     * - When one or more stones mature, we must rebuild derived groups so land becomes marked.
     */
    fun ageAnchorsOnce(server: MinecraftServer): Int {
        // Stone maturity trigger: NIGHTLY_TICK.
        // If a stone is already matured and still present, renewal has been blocked for >= 1 full day.
        val alreadyMatured = MementoAnchors.list()
            .filter { it.kind == MementoAnchors.Kind.FORGET }
            .filter { it.state == MementoAnchors.WitherstoneState.MATURED }

        for (a in alreadyMatured) {
            MementoDebug.warn(
                server,
                "Witherstone '${a.name}' is still awaiting chunk renewal after one full day. " +
                    "Stone matured earlier but renewal is blocked (dim=${a.dimension.value}, pos=${a.pos.x},${a.pos.y},${a.pos.z})."
            )
        }


        val matured = MementoAnchors.ageOnce(server)
        if (matured > 0) {
            rebuildMarkedGroups(server, ChunkGroupForgetting.StoneMaturityTrigger.NIGHTLY_TICK)
            MementoDebug.info(server, "Daily maturation complete: $matured witherstone(s) matured")
        }
        return matured
    }

    fun sweep(server: MinecraftServer) {
        for (world in server.worlds) {
            if (world is ServerWorld) {
                ChunkGroupForgetting.refresh(world, server)
            }
        }
    }

    /**
     * Budgeted regeneration execution step.
     */
    fun tick(server: MinecraftServer) {
        ChunkGroupForgetting.tick(server, MementoConstants.REGENERATION_CHUNK_INTERVAL_TICKS)
    }

    /**
     * Primary trigger for re-evaluating BLOCKED -> FREE.
     */
    fun onChunkUnloaded(
        server: MinecraftServer,
        world: ServerWorld,
        pos: ChunkPos
    ) {
        ChunkGroupForgetting.onChunkUnloaded(world, server, pos)
    }

    fun onChunkRenewalObserved(
        server: MinecraftServer,
        dimension: RegistryKey<World>,
        pos: ChunkPos
    ) {
        ChunkGroupForgetting.onChunkRenewed(server, dimension, pos)
    }

    fun shouldForgetNow(
        dimension: RegistryKey<World>,
        pos: ChunkPos
    ): Boolean =
        ChunkGroupForgetting.isMarked(dimension, pos)
}
