package ch.oliverlanz.memento.domain.stones

import net.minecraft.util.math.BlockPos
import org.slf4j.LoggerFactory

/**
 * New-generation stone register.
 *
 * This register is currently OBSERVATIONAL ONLY.
 * It mirrors legacy behavior but does not influence gameplay.
 *
 * Responsibilities (current slice):
 * - Accept add / remove calls
 * - Maintain in-memory integrity
 * - Emit explicit, structured logs
 *
 * NOT YET:
 * - persistence
 * - lifecycle decisions
 * - authority
 */
object StoneRegister {

    private val log = LoggerFactory.getLogger("memento")

    private val stones: MutableMap<String, Stone> = mutableMapOf()

    init {
        log.info("[STONE-REGISTER] initialized (empty)")
    }

    /**
     * Add a stone to the register.
     * Mirrors legacy add semantics.
     */
    fun add(stone: Stone) {
        val replaced = stones.put(stone.name, stone) != null

        log.info(
            buildString {
                append("[STONE-REGISTER] ")
                append(if (replaced) "replace" else "add")
                append(" ${stone::class.simpleName} '${stone.name}'\n")
                append("  pos=${formatPos(stone.position)}\n")
                append("  radius=${stone.radius}")

                if (stone is Witherstone) {
                    append("\n  maturesInDays=${stone.daysToMaturity}")
                }
            }
        )
    }

    /**
     * Remove a stone explicitly (admin action or consumption).
     */
    fun remove(name: String, reason: RemoveReason = RemoveReason.EXPLICIT) {
        val removed = stones.remove(name)

        if (removed != null) {
            log.info(
                "[STONE-REGISTER] remove '${removed.name}' (reason=$reason)"
            )
        } else {
            log.warn(
                "[STONE-REGISTER] remove '$name' requested, but no such stone exists"
            )
        }
    }

    /**
     * Enumerate stones (used for future inspect commands).
     */
    fun list(): Collection<Stone> {
        log.info("[STONE-REGISTER] list -> ${stones.size} stone(s)")
        return stones.values.toList()
    }

    /**
     * Lookup by name.
     */
    fun get(name: String): Stone? =
        stones[name]

    // ---------------------------------------------------------------------

    enum class RemoveReason {
        EXPLICIT,
        CONSUMED
    }

    private fun formatPos(pos: BlockPos): String =
        "(${pos.x},${pos.y},${pos.z})"
}
