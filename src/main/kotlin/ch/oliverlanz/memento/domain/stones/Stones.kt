package ch.oliverlanz.memento.domain.stones

import ch.oliverlanz.memento.infrastructure.MementoConstants
import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

/**
 * Base type for all new-generation (shadow) Memento stones.
 *
 * Stones are mutable entities owned by StoneRegister.
 * Identity is stable; selected attributes may evolve over time.
 */
sealed class Stone protected constructor(
    val name: String,
    val dimension: RegistryKey<World>,
    val position: BlockPos,
    var radius: Int,
)

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
 * but may be amended later by StoneRegister or admin actions.
 */
class Witherstone(
    name: String,
    dimension: RegistryKey<World>,
    position: BlockPos,
    var daysToMaturity: Int = MementoConstants.DEFAULT_DAYS_TO_MATURITY,
    var state: WitherstoneState = WitherstoneState.MATURING,
) : Stone(
    name = name,
    dimension = dimension,
    position = position,
    radius = MementoConstants.DEFAULT_CHUNKS_RADIUS,
)

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
)
