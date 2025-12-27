package ch.oliverlanz.memento.application.land

import ch.oliverlanz.memento.domain.renewal.RenewalBatch
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

/**
 * Inspection utilities used by `/memento inspect`.
 *
 * Structural refactor only:
 * - No behavior changes
 * - Updated world height access for 1.21.x
 */
object ChunkInspection {

    enum class BlockerKind {
        PLAYER_NEARBY,
        ENTITY_TICKING,
        SPAWN_CHUNK_OR_FORCED,
        UNKNOWN
    }

    data class Blocker(
        val kind: BlockerKind,
        val description: String
    )

    data class ChunkReport(
        val dimension: RegistryKey<World>,
        val pos: ChunkPos,
        val isLoaded: Boolean,
        val blockers: List<Blocker>,
        val summary: String
    )

    fun inspectBatch(
        server: MinecraftServer,
        batch: RenewalBatch
    ): List<ChunkReport> {
        val world = server.getWorld(batch.dimension) ?: return emptyList()

        return batch.chunks
            .map { inspectOne(world, server, it) }
            .sortedWith(compareBy({ it.pos.x }, { it.pos.z }))
    }

    fun inspectAll(server: MinecraftServer): List<ChunkReport> {
        val batches = RenewalBatchForgetting.snapshotBatches()
        if (batches.isEmpty()) return emptyList()

        val reports = mutableListOf<ChunkReport>()
        for (b in batches) {
            val world = server.getWorld(b.dimension) ?: continue
            for (pos in b.chunks) {
                reports += inspectOne(world, server, pos)
            }
        }

        return reports.sortedWith(
            compareBy(
                { it.dimension.value.toString() },
                { it.pos.x },
                { it.pos.z }
            )
        )
    }

    private fun inspectOne(
        world: ServerWorld,
        server: MinecraftServer,
        pos: ChunkPos
    ): ChunkReport {

        val blockers = mutableListOf<Blocker>()

        // ---- Players nearby -------------------------------------------------
        val nearbyPlayers = playersNearChunk(world, pos)
        if (nearbyPlayers.isNotEmpty()) {
            blockers += Blocker(
                BlockerKind.PLAYER_NEARBY,
                "players nearby: ${nearbyPlayers.joinToString(", ")}"
            )
        }

        // ---- Entities ticking -----------------------------------------------
        val entitySummary = entitiesKeepingChunkBusy(world, pos)
        if (entitySummary.isNotEmpty()) {
            blockers += Blocker(
                BlockerKind.ENTITY_TICKING,
                entitySummary
            )
        }

        // ---- Loaded check ---------------------------------------------------
        val isLoaded =
            world.chunkManager.getChunk(pos.x, pos.z, ChunkStatus.EMPTY, false) != null

        if (isLoaded) {
            blockers += Blocker(
                BlockerKind.UNKNOWN,
                "chunk is loaded (cause not precisely known)"
            )
        }

        val summary =
            if (blockers.isEmpty()) "free"
            else "blocked: ${blockers.joinToString { it.kind.name.lowercase() }}"

        return ChunkReport(
            dimension = world.registryKey,
            pos = pos,
            isLoaded = isLoaded,
            blockers = blockers,
            summary = summary
        )
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private fun playersNearChunk(
        world: ServerWorld,
        pos: ChunkPos
    ): List<String> {

        val minX = pos.startX.toDouble()
        val minZ = pos.startZ.toDouble()
        val maxX = (pos.startX + 16).toDouble()
        val maxZ = (pos.startZ + 16).toDouble()

        val box = Box(
            minX - 32.0,
            world.bottomY.toDouble(),
            minZ - 32.0,
            maxX + 32.0,
            (world.topYInclusive + 1).toDouble(),
            maxZ + 32.0
        )

        return world
            .getEntitiesByType(EntityType.PLAYER, box) { true }
            .filterIsInstance<ServerPlayerEntity>()
            .map { it.gameProfile.name }
            .sorted()
    }

    private fun entitiesKeepingChunkBusy(
        world: ServerWorld,
        pos: ChunkPos
    ): String {

        val minX = pos.startX.toDouble()
        val minZ = pos.startZ.toDouble()
        val maxX = (pos.startX + 16).toDouble()
        val maxZ = (pos.startZ + 16).toDouble()

        val box = Box(
            minX,
            world.bottomY.toDouble(),
            minZ,
            maxX,
            (world.topYInclusive + 1).toDouble(),
            maxZ
        )

        val entities = world.getOtherEntities(null, box) { true }
        if (entities.isEmpty()) return ""

        val mobs = entities.filterIsInstance<MobEntity>().size
        val items = entities.filterIsInstance<ItemEntity>().size
        val tameables = entities.filterIsInstance<TameableEntity>().size
        val others = entities.size - mobs - items - tameables

        val parts = mutableListOf<String>()
        if (mobs > 0) parts += "mobs=$mobs"
        if (items > 0) parts += "items=$items"
        if (tameables > 0) parts += "tameables=$tameables"
        if (others > 0) parts += "otherEntities=$others"

        return "entities present: ${parts.joinToString(", ")}"
    }

    // Best-effort helper (unchanged, defensive)
    private fun countBlockEntitiesBestEffort(chunk: WorldChunk): Int? =
        try {
            chunk.blockEntities.size
        } catch (_: Throwable) {
            null
        }
}
