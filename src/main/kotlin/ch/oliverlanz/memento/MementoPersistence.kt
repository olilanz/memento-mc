package ch.oliverlanz.memento

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Identifier
import net.minecraft.util.WorldSavePath
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object MementoPersistence {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    private data class AnchorDto(
        val name: String,
        val kind: String,
        val dimension: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val radius: Int,
        val days: Int?,
        val createdGameTime: Long
    )

    private fun filePath(server: MinecraftServer): Path {
        // World root folder (same place as level.dat)
        return server.getSavePath(WorldSavePath.ROOT).resolve("memento_anchors.json")
    }

    @Synchronized
    fun save(server: MinecraftServer) {
        val dtos = MementoAnchors.list()
            .sortedBy { it.name.lowercase() }
            .map { a ->
                AnchorDto(
                    name = a.name,
                    kind = a.kind.name,
                    dimension = a.dimension.value.toString(),
                    x = a.pos.x,
                    y = a.pos.y,
                    z = a.pos.z,
                    radius = a.radius,
                    days = a.days,
                    createdGameTime = a.createdGameTime
                )
            }

        val json = gson.toJson(dtos)
        val path = filePath(server)
        Files.write(path, json.toByteArray(StandardCharsets.UTF_8))
    }

    @Synchronized
    fun load(server: MinecraftServer) {
        val path = filePath(server)
        if (!Files.exists(path)) {
            MementoAnchors.clear()
            return
        }

        val raw = Files.readString(path, StandardCharsets.UTF_8).trim()
        if (raw.isEmpty()) {
            MementoAnchors.clear()
            return
        }

        val element = JsonParser.parseString(raw)
        if (!element.isJsonArray) {
            // bad file format – start clean rather than crash the server
            MementoAnchors.clear()
            return
        }

        val loaded = mutableListOf<MementoAnchors.Anchor>()
        for (e in element.asJsonArray) {
            if (!e.isJsonObject) continue
            val o = e.asJsonObject

            val name = o.get("name")?.asString ?: continue
            val kindStr = o.get("kind")?.asString ?: continue
            val dimStr = o.get("dimension")?.asString ?: continue

            val x = o.get("x")?.asInt ?: continue
            val y = o.get("y")?.asInt ?: continue
            val z = o.get("z")?.asInt ?: continue

            val radius = o.get("radius")?.asInt ?: MementoConstants.DEFAULT_RADIUS_CHUNKS
            val days = if (o.has("days") && !o.get("days").isJsonNull) o.get("days").asInt else null
            val created = o.get("createdGameTime")?.asLong ?: 0L

            val kind = runCatching { MementoAnchors.Kind.valueOf(kindStr) }.getOrNull() ?: continue
            val dimId = runCatching { Identifier.of(dimStr) }.getOrNull() ?: continue
            val dimKey: RegistryKey<World> = RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, dimId)

            loaded += MementoAnchors.Anchor(
                name = name,
                kind = kind,
                dimension = dimKey,
                pos = BlockPos(x, y, z),
                radius = radius,
                days = days,
                createdGameTime = created
            )
        }

        // If duplicates exist in file, last one wins
        MementoAnchors.putAll(loaded)
    }
}
