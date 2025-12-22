package ch.oliverlanz.memento.application.stone

import ch.oliverlanz.memento.application.MementoAnchors
import ch.oliverlanz.memento.application.land.ChunkGroupForgetting
import ch.oliverlanz.memento.infrastructure.MementoDebug
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World

/**
 * Coordinates the lifecycle of Memento stones (Witherstones / Lorestones).
 *
 * Responsibilities:
 * - Evaluate stone maturity (time-based)
 * - Delegate land / chunk-group lifecycle to [ChunkGroupForgetting]
 */
object MementoStoneLifecycle {

    private var server: MinecraftServer? = null

    fun currentServerOrNull(): MinecraftServer? = server

    fun attachServer(server: MinecraftServer) {
        this.server = server
        MementoDebug.info(server, "MementoStoneLifecycle attached")
    }

    fun detachServer(server: MinecraftServer) {
        if (this.server === server) {
            this.server = null
        }
    }

    /**
     * Called after persistence is loaded on SERVER_START.
     * Rebuilds derived chunk groups for already-matured witherstones.
     */
    fun rebuildMarkedGroups(server: MinecraftServer) {
        ChunkGroupForgetting.rebuildFromAnchors(
            server = server,
            anchors = snapshotAnchors(),
            trigger = StoneMaturityTrigger.SERVER_START,
        )
    }

    /**
     * Called once per overworld day transition.
     */
    fun ageAnchorsOnce(server: MinecraftServer) {
        val maturedCount = MementoAnchors.ageOnce(server)
        if (maturedCount > 0) {
            MementoDebug.info(server, "Stone maturity trigger=NIGHTLY_TICK → $maturedCount stone(s) matured")
        }

        // After maturity changes, rebuild derived groups so operators can inspect immediately.
        ChunkGroupForgetting.rebuildFromAnchors(
            server = server,
            anchors = snapshotAnchors(),
            trigger = StoneMaturityTrigger.NIGHTLY_TICK,
        )
    }

    /**
     * Best-effort tick-time sweep/re-evaluation.
     */
    fun sweep(server: MinecraftServer) {
        ChunkGroupForgetting.refreshAllReadiness(server)
    }

    fun onChunkUnloaded(server: MinecraftServer, world: ServerWorld, pos: ChunkPos) {
        ChunkGroupForgetting.onChunkUnloaded(server, world, pos)
    }

    fun tick(server: MinecraftServer) {
        ChunkGroupForgetting.tick(server)
    }

    fun isChunkRenewalQueued(dimension: RegistryKey<World>, pos: ChunkPos): Boolean =
        ChunkGroupForgetting.isChunkRenewalQueued(dimension, pos)

    fun onChunkRenewalObserved(server: MinecraftServer, dimension: RegistryKey<World>, pos: ChunkPos) {
        ChunkGroupForgetting.onChunkRenewalObserved(server, dimension, pos)
    }

    private fun snapshotAnchors(): Map<String, MementoAnchors.Anchor> =
        MementoAnchors.list().associateBy { it.name }
}
