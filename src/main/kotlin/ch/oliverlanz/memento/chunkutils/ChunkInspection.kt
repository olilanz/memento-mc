package ch.oliverlanz.memento.chunkutils

import ch.oliverlanz.memento.chunkutils.ChunkGroupForgetting
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.ItemEntity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.entity.passive.TameableEntity
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.Box
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import net.minecraft.world.chunk.ChunkStatus
import net.minecraft.world.chunk.WorldChunk
import org.slf4j.LoggerFactory
import java.lang.reflect.Method
import java.util.Locale

object ChunkInspection {

    private val logger = LoggerFactory.getLogger("memento")

    enum class BlockerKind {
        PLAYER_NEARBY,
        PERSISTENT_ENTITIES_PRESENT,
        VILLAGERS_PRESENT,
        ITEMS_PRESENT,
        OTHER_ENTITIES_PRESENT,
        BLOCK_ENTITIES_PRESENT,
        UNKNOWN_RETENTION
    }

    data class Blocker(
        val kind: BlockerKind,
        val details: String
    )

    data class ChunkReport(
        val dimension: RegistryKey<World>,
        val pos: ChunkPos,
        val isLoaded: Boolean,
        val blockers: List<Blocker>,
        val summary: String
    )

    /**
     * For the current slice, "forgettable" means:
     * the chunks that belong to eligible (due) forget-groups.
     */
    fun listForgettableChunks(): List<Pair<RegistryKey<World>, ChunkPos>> {
        val unique = LinkedHashSet<Pair<RegistryKey<World>, ChunkPos>>()
        for (g in ChunkGroupForgetting.snapshotEligibleGroups()) {
            for (pos in g.chunks) {
                unique.add(g.dimension to pos)
            }
        }
        return unique.toList()
    }

    fun inspectAll(server: MinecraftServer): List<ChunkReport> {
        val targets = listForgettableChunks()
        if (targets.isEmpty()) return emptyList()

        val byWorld = targets.groupBy({ it.first }, { it.second })
        val reports = mutableListOf<ChunkReport>()

        for ((dim, chunkPositions) in byWorld) {
            val world = server.getWorld(dim)
            if (world == null) {
                for (pos in chunkPositions) {
                    reports += ChunkReport(
                        dim,
                        pos,
                        isLoaded = false,
                        blockers = listOf(Blocker(BlockerKind.UNKNOWN_RETENTION, "dimension not loaded")),
                        summary = "dimension not loaded"
                    )
                }
                continue
            }

            for (pos in chunkPositions) {
                reports += inspectOne(server, world, pos)
            }
        }

        return reports.sortedWith(compareBy({ it.dimension.value.toString() }, { it.pos.x }, { it.pos.z }))
    }

    private fun inspectOne(server: MinecraftServer, world: ServerWorld, pos: ChunkPos): ChunkReport {
        val blockers = mutableListOf<Blocker>()

        val nearbyPlayers = playersNearChunk(server, world, pos)
        if (nearbyPlayers.isNotEmpty()) {
            blockers += Blocker(
                BlockerKind.PLAYER_NEARBY,
                "players nearby: ${nearbyPlayers.joinToString(", ")}"
            )
        }

        val chunk = getLoadedChunk(world, pos)
        val isLoaded = chunk != null

        if (chunk != null) {
            blockers += inspectEntities(world, pos)

            val beCount = countBlockEntitiesBestEffort(chunk)
            if (beCount != null && beCount > 0) {
                blockers += Blocker(
                    BlockerKind.BLOCK_ENTITIES_PRESENT,
                    "block entities: $beCount"
                )
            }
        }

        if (isLoaded && blockers.isEmpty()) {
            blockers += Blocker(
                BlockerKind.UNKNOWN_RETENTION,
                "no obvious blockers found; waiting for server eviction"
            )
        }

        val summary = when {
            !isLoaded -> "unloaded"
            blockers.isEmpty() -> "loaded"
            else -> "loaded; blocked"
        }

        return ChunkReport(world.registryKey, pos, isLoaded, blockers, summary)
    }

    private fun playersNearChunk(
        server: MinecraftServer,
        world: ServerWorld,
        pos: ChunkPos
    ): List<String> {
        val maxDist = server.playerManager.viewDistance

        return world.players
            .filter {
                val dx = kotlin.math.abs(it.chunkPos.x - pos.x)
                val dz = kotlin.math.abs(it.chunkPos.z - pos.z)
                dx <= maxDist && dz <= maxDist
            }
            .map { it.gameProfile.name }
    }

    private fun inspectEntities(world: ServerWorld, pos: ChunkPos): List<Blocker> {
        val blockers = mutableListOf<Blocker>()
        val box = chunkBox(pos)

        val entities = world.getOtherEntities(null, box)
            .filter { it !is ServerPlayerEntity }

        if (entities.isEmpty()) return blockers

        var villagers = 0
        var items = 0
        var persistent = 0
        var other = 0

        val typeCounts = mutableMapOf<String, Int>()

        for (e in entities) {
            val typeId = entityTypeId(e)
            typeCounts[typeId] = (typeCounts[typeId] ?: 0) + 1

            when {
                e.type == EntityType.VILLAGER -> villagers++
                e is ItemEntity -> items++
                isPersistentMob(e) -> persistent++
                else -> other++
            }
        }

        if (villagers > 0) blockers += Blocker(BlockerKind.VILLAGERS_PRESENT, "villagers: $villagers")
        if (persistent > 0) blockers += Blocker(BlockerKind.PERSISTENT_ENTITIES_PRESENT, "persistent mobs: $persistent")
        if (items > 0) blockers += Blocker(BlockerKind.ITEMS_PRESENT, "items: $items")
        if (other > 0) blockers += Blocker(BlockerKind.OTHER_ENTITIES_PRESENT, "other entities: $other")

        val sample = typeCounts.entries
            .sortedByDescending { it.value }
            .take(6)
            .joinToString(", ") { "${it.key} x${it.value}" }

        if (sample.isNotBlank()) {
            blockers += Blocker(BlockerKind.OTHER_ENTITIES_PRESENT, "entity types: $sample")
        }

        return blockers
    }

    private fun chunkBox(pos: ChunkPos): Box =
        Box(
            pos.startX.toDouble(),
            -64.0,
            pos.startZ.toDouble(),
            (pos.startX + 16).toDouble(),
            320.0,
            (pos.startZ + 16).toDouble()
        )

    private fun entityTypeId(e: Entity): String =
        e.type.toString().lowercase(Locale.ROOT)

    private fun isPersistentMob(e: Entity): Boolean {
        if (e !is MobEntity) return false
        if (e.hasCustomName()) return true
        if (e is TameableEntity && e.isTamed) return true

        return when (e.type) {
            EntityType.DONKEY,
            EntityType.MULE,
            EntityType.HORSE,
            EntityType.LLAMA,
            EntityType.TRADER_LLAMA,
            EntityType.CAT,
            EntityType.WOLF,
            EntityType.VILLAGER,
            EntityType.FOX,
            EntityType.GOAT,
            EntityType.SHEEP,
            EntityType.COW,
            EntityType.PIG,
            EntityType.CHICKEN,
            EntityType.RABBIT,
            EntityType.BEE -> true
            else -> false
        }
    }

    // ---- chunk access (best effort, no forced load) ----

    private fun getLoadedChunk(world: ServerWorld, pos: ChunkPos): WorldChunk? {
        val cm = world.chunkManager
        return try {
            val m = findGetChunkMethod(cm)
            m.invoke(cm, pos.x, pos.z, ChunkStatus.FULL, false) as? WorldChunk
        } catch (t: Throwable) {
            logger.debug("(memento) getLoadedChunk reflection failed: {}", t.toString())
            null
        }
    }

    private fun findGetChunkMethod(cm: Any): Method =
        cm.javaClass.methods.first {
            it.name == "getChunk" &&
                    it.parameterTypes.size == 4 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[3] == Boolean::class.javaPrimitiveType
        }

    private fun countBlockEntitiesBestEffort(chunk: WorldChunk): Int? =
        try {
            chunk.blockEntities.size
        } catch (_: Throwable) {
            null
        }
}
