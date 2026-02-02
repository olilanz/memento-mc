package ch.oliverlanz.memento.application.visualization

import ch.oliverlanz.memento.application.time.GameClock
import ch.oliverlanz.memento.application.time.GameClockEvents
import ch.oliverlanz.memento.application.visualization.effects.*
import ch.oliverlanz.memento.domain.events.StoneDomainEvents
import ch.oliverlanz.memento.domain.events.StoneLifecycleState
import ch.oliverlanz.memento.domain.events.StoneLifecycleTransition
import ch.oliverlanz.memento.domain.renewal.RenewalBatchLifecycleTransition
import ch.oliverlanz.memento.domain.renewal.RenewalBatchState
import ch.oliverlanz.memento.domain.stones.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import kotlin.reflect.KClass

/**
 * Application-level visualization host.
 *
 * Owns the lifecycle of visual projections (effects) and ticks them based on [GameClock].
 *
 * Effects are projections only; they must remain safe to expire and re-create.
 */
class EffectsHost(
    private val server: MinecraftServer
) {

    
    private data class EffectKey(
        val stoneName: String,
        val effectClass: KClass<out EffectBase>
    )

    private data class EffectEntry(val stone: StoneView, val effect: EffectBase)

    private val effectsByKey = mutableMapOf<EffectKey, EffectEntry>()

    init {
        StoneDomainEvents.subscribeToLifecycleTransitions(::onLifecycleTransition)
        GameClockEvents.subscribe(::onTick)
    }

    /* ---------- Public semantic API ---------- */

    fun visualizeStone(stone: StoneView) {
        add(stone, StoneInspectionEffect(stone))
    }

    /* ---------- Domain event handling ---------- */

    fun onRenewalEvent(event: RenewalBatchLifecycleTransition) {
        val stoneName = event.batch.name
        val stone = StoneTopology.get(stoneName) as? WitherstoneView

        if (stone == null) {
            removeAllEffectsForStone(stoneName)
            return
        }

        when {
            event.to == RenewalBatchState.WAITING_FOR_UNLOAD ->
                add(stone, WitherstoneWaitingEffect(stone))

            event.from == RenewalBatchState.WAITING_FOR_UNLOAD ->
                remove(stoneName, WitherstoneWaitingEffect::class)
        }
    }

    private fun onLifecycleTransition(event: StoneLifecycleTransition) {
        when (event.to) {
            StoneLifecycleState.PLACED -> when (event.stone) {
                is WitherstoneView ->
                    add(event.stone, WitherstonePlacementEffect(event.stone))
                is LorestoneView ->
                    add(event.stone, LorestonePlacementEffect(event.stone))
            }

            StoneLifecycleState.CONSUMED ->
                removeAllEffectsForStone(event.stone.name)

            else -> Unit
        }
    }

    /* ---------- Tick / lifecycle ---------- */

    private fun onTick(clock: GameClock) {
        val it = effectsByKey.entries.iterator()
        while (it.hasNext()) {
            val (key, entry) = it.next()
            val world: ServerWorld =
                server.getWorld(entry.stone.dimension) ?: continue

            if (!entry.effect.tick(world, clock)) {
                it.remove()
            }
        }
    }

    /* ---------- Internal mechanics ---------- */

    private fun add(stone: StoneView, effect: EffectBase) {
        effectsByKey[EffectKey(stone.name, effect::class)] = EffectEntry(stone, effect)
    }

    private fun remove(stoneName: String, effectClass: KClass<out EffectBase>) {
        effectsByKey.remove(EffectKey(stoneName, effectClass))
    }

    private fun removeAllEffectsForStone(stoneName: String): Int {
        var removed = 0
        val it = effectsByKey.entries.iterator()
        while (it.hasNext()) {
            if (it.next().key.stoneName == stoneName) {
                it.remove()
                removed++
            }
        }
        return removed
    }
}
