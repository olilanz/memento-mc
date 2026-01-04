package ch.oliverlanz.memento.application.visualization

import ch.oliverlanz.memento.domain.events.StoneCreated
import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.events.StoneKind
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.Vec3d

/**
 * Minimal, server-side visualization engine.
 *
 * Slice 1 responsibilities:
 * - Subscribe to stone creation events
 * - Emit a short particle cue at the stone position
 *
 * This intentionally does NOT:
 * - enumerate chunks
 * - track long-lived visual state
 * - interpret influence topology
 *
 * Parameterized particles (e.g. DustParticleEffect) are
 * intentionally avoided here to ensure compile stability
 * on MC 1.21.10 Yarn. Visual refinement comes later.
 */
object StoneVisualizationEngine {

    private var server: MinecraftServer? = null

    fun attach(server: MinecraftServer) {
        this.server = server
        StoneDomainEvents.subscribeToStoneCreated(::onStoneCreated)
    }

    fun detach() {
        StoneDomainEvents.unsubscribeFromStoneCreated(::onStoneCreated)
        this.server = null
    }

    private fun onStoneCreated(event: StoneCreated) {
        val server = this.server ?: return
        val world: ServerWorld = server.getWorld(event.dimension) ?: return

        val pos: Vec3d = event.position
            .toCenterPos()
            .add(0.0, 0.8, 0.0) // slightly above the block

        val particle = when (event.kind) {
            StoneKind.LORESTONE -> ParticleTypes.HAPPY_VILLAGER
            StoneKind.WITHERSTONE -> ParticleTypes.SMOKE
        }

        // Explicit overload required in MC 1.21.10 (Yarn)
        world.spawnParticles(
            particle,
            false, // force
            true,  // important
            pos.x,
            pos.y,
            pos.z,
            24,    // count
            0.3,   // offsetX
            0.3,   // offsetY
            0.3,   // offsetZ
            0.02   // speed
        )
    }
}
