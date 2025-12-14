package ch.oliverlanz.memento

import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

object MementoAnchors {

    const val DEFAULT_RADIUS: Int = 5
    const val DEFAULT_DAYS: Int = 5

    enum class Kind { REMEMBER, FORGET }

    data class Anchor(
        val name: String,
        val kind: Kind,
        val dimension: RegistryKey<World>,
        val pos: BlockPos,
        val radius: Int,
        val days: Int?,              // only for FORGET; null for REMEMBER
        val createdGameTime: Long
    )

    private val anchors = mutableMapOf<String, Anchor>()

    fun list(): Collection<Anchor> = anchors.values

    fun get(name: String): Anchor? = anchors[name]

    fun add(anchor: Anchor): Boolean {
        if (anchors.containsKey(anchor.name)) return false
        anchors[anchor.name] = anchor
        return true
    }

    fun remove(name: String): Boolean =
        anchors.remove(name) != null

    fun clear() {
        anchors.clear()
    }

    fun putAll(newAnchors: Collection<Anchor>) {
        anchors.clear()
        for (a in newAnchors) {
            anchors[a.name] = a
        }
    }
}
