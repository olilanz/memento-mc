package ch.oliverlanz.memento.domain.stones

import ch.oliverlanz.memento.MementoConstants
import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

/**
 * Immutable view interface for stones.
 *
 * Domain events and cross-cutting concerns should depend on this interface hierarchy,
 * not on mutable concrete stone implementations.
 *
 * Concrete stones are owned and mutated exclusively by StoneTopology.
 */
interface StoneView {
    val name: String
    val dimension: RegistryKey<World>
    val position: BlockPos
    val radius: Int
}

/**
 * Immutable view for a Witherstone.
 */
interface WitherstoneView : StoneView {
    val daysToMaturity: Int
    val state: WitherstoneState
}

/**
 * Immutable view for a Lorestone.
 */
interface LorestoneView : StoneView

/**
 * Base type for all new-generation (shadow) Memento stones.
 *
 * Stones are mutable entities owned by StoneTopology.
 * Identity is stable; selected attributes may evolve over time.
 */
sealed class Stone protected constructor(
    final override val name: String,
    final override val dimension: RegistryKey<World>,
    final override val position: BlockPos,
    final override var radius: Int,
) : StoneView

/**
 * Witherstone lifecycle governs when renewal is initiated.
 *
 * States are defined in ARCHITECTURE.md and must not be extended.
 */
enum class WitherstoneState {
    PLACED,
    MATURING,
    MATURED,
    CONSUMED,
}

/**
 * A Witherstone triggers proactive renewal once matured.
 *
 * Radius and daysToMaturity are initialized from constants,
 * but may be amended later by StoneTopology or admin actions.
 */
class Witherstone(
    name: String,
    dimension: RegistryKey<World>,
    position: BlockPos,
    override var daysToMaturity: Int = MementoConstants.DEFAULT_DAYS_TO_MATURITY,
    override var state: WitherstoneState = WitherstoneState.MATURING,
) : Stone(
    name = name,
    dimension = dimension,
    position = position,
    radius = MementoConstants.DEFAULT_CHUNKS_RADIUS,
), WitherstoneView

/**
 * A Lorestone represents a non-renewal narrative stone.
 *
 * Radius may be amended later.
 */
class Lorestone(
    name: String,
    dimension: RegistryKey<World>,
    position: BlockPos,
) : Stone(
    name = name,
    dimension = dimension,
    position = position,
    radius = MementoConstants.DEFAULT_CHUNKS_RADIUS,
), LorestoneView
