package ch.oliverlanz.memento.application.stone

import ch.oliverlanz.memento.application.MementoStones
import ch.oliverlanz.memento.application.land.RenewalBatchForgetting
import ch.oliverlanz.memento.infrastructure.MementoDebug
import ch.oliverlanz.memento.infrastructure.MementoPersistence
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World

/**
 * Coordinates the lifecycle of Witherstones.
 *
 * Responsibilities:
 * - Evaluate stone maturity (time-based)
 * - Delegate land / renewal-batch lifecycle to [RenewalBatchForgetting]
 */
object WitherstoneLifecycle {

    @Volatile
    private var server: MinecraftServer? = null

    fun currentServerOrNull(): MinecraftServer? = server

    fun attachServer(server: MinecraftServer) {
        this.server = server
        MementoDebug.info(server, "WitherstoneLifecycle attached")
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
        RenewalBatchForgetting.rebuildFromAnchors(
            server = server,
            stones = snapshotStones(),
            trigger = StoneMaturityTrigger.SERVER_START,
        )

        // Startup reconciliation: on an existing world, no "day rollover" happens at boot.
        // We must therefore explicitly re-evaluate loaded/unloaded chunk facts so FREE groups can be queued immediately.
        RenewalBatchForgetting.refreshAllReadiness(server)
    }

    /**
     * Called once per overworld day transition.
     */
    fun ageAnchorsOnce(server: MinecraftServer) {
        val maturedCount = MementoStones.ageOnce(server)
        if (maturedCount > 0) {
            MementoDebug.info(server, "Stone maturity trigger=NIGHTLY_TICK → $maturedCount stone(s) matured")
        }

        // After maturity changes, rebuild derived groups so operators can inspect immediately.
        RenewalBatchForgetting.rebuildFromAnchors(
            server = server,
            stones = snapshotStones(),
            trigger = StoneMaturityTrigger.NIGHTLY_TICK,
        )
    }

    /**
     * Best-effort tick-time sweep/re-evaluation.
     */
    fun sweep(server: MinecraftServer) {
        RenewalBatchForgetting.refreshAllReadiness(server)
    }

    fun onChunkUnloaded(server: MinecraftServer, world: ServerWorld, pos: ChunkPos) {
        RenewalBatchForgetting.onChunkUnloaded(server, world, pos)
    }

    
/**
 * Terminal action: once a WITHERSTONE-driven chunk group has completed renewal, the stone is consumed
 * (removed from persistence) and the derived group can be discarded.
 *
 * Lorestone/REMEMBER anchors are never consumed automatically.
 */
fun onRenewalBatchRenewed(server: MinecraftServer, stoneName: String) {
    val a = MementoStones.get(stoneName) ?: return
    if (a.kind != MementoStones.Kind.FORGET) return

    // Witherstone is one-shot: remove it from persistence so it cannot re-trigger.
    val removed = MementoStones.remove(stoneName)
    if (removed) {
        MementoPersistence.save(server)
        MementoDebug.info(server, "Witherstone '$stoneName' consumed (removed) after successful renewal")
    }
}

fun tick(server: MinecraftServer) {
        RenewalBatchForgetting.tick(server)
    }

    fun isChunkRenewalQueued(dimension: RegistryKey<World>, pos: ChunkPos): Boolean =
        RenewalBatchForgetting.isChunkRenewalQueued(dimension, pos)

    fun onChunkRenewalObserved(server: MinecraftServer, dimension: RegistryKey<World>, pos: ChunkPos) {
        RenewalBatchForgetting.onChunkRenewalObserved(server, dimension, pos)
    }

    private fun snapshotStones(): Map<String, MementoStones.Stone> =
        MementoStones.list().associateBy { it.name }
}
