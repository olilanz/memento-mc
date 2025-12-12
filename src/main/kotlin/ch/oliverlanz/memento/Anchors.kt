package ch.oliverlanz.memento

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World

enum class AnchorType {
    LORE,
    WITHER
}

data class Anchor(
    val type: AnchorType,
    val worldRegistryKey: net.minecraft.registry.RegistryKey<World>,
    val pos: BlockPos
)

object Anchors {
    private val anchors = mutableListOf<Anchor>()

    fun add(anchor: Anchor) {
        anchors.add(anchor)
    }

    fun removeAt(pos: BlockPos, world: World) {
        anchors.removeIf { it.worldRegistryKey == world.registryKey && it.pos == pos }
    }

    fun getAll(): List<Anchor> = anchors.toList()
}
