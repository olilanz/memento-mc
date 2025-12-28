package ch.oliverlanz.memento.domain.stones

import ch.oliverlanz.memento.infrastructure.MementoConstants
import net.minecraft.util.math.BlockPos

/**
 * Base type for all Memento stones.
 *
 * Stones are mutable entities owned by StoneRegister.
 * Identity is stable; selected attributes may evolve over time.
 */
sealed class Stone protected constructor(
    val name: String,
    val position: BlockPos,
    var radius: Int
)

/**
 * A Witherstone triggers proactive renewal once matured.
 *
 * Radius and daysToMaturity are initialized from constants,
 * but may be amended later by StoneRegister or admin actions.
 */
class Witherstone(
    name: String,
    position: BlockPos,
    var daysToMaturity: Int = MementoConstants.DEFAULT_DAYS_TO_MATURITY
) : Stone(
    name = name,
    position = position,
    radius = MementoConstants.DEFAULT_CHUNKS_RADIUS
)

/**
 * A Lorestone represents a non-renewal narrative stone.
 *
 * Radius may be amended later.
 */
class Lorestone(
    name: String,
    position: BlockPos
) : Stone(
    name = name,
    position = position,
    radius = MementoConstants.DEFAULT_CHUNKS_RADIUS
)
