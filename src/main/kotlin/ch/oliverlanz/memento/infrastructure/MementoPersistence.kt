package ch.oliverlanz.memento.infrastructure

import ch.oliverlanz.memento.application.MementoStones
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
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
    private const val FILE_NAME = "memento_anchors.json"

    private fun filePath(server: MinecraftServer): Path =
        server.getSavePath(WorldSavePath.ROOT).resolve(FILE_NAME)

    @Synchronized
    fun save(server: MinecraftServer) {
        saveAnchors(server)
    }

    @Synchronized
    fun saveAnchors(server: MinecraftServer) {
        val root = JsonObject()
        val arr = JsonArray()

        for (a in MementoStones.list()) {
            val o = JsonObject()
            o.addProperty("name", a.name)
            o.addProperty("kind", a.kind.name)
            o.addProperty("dimension", a.dimension.value.toString())
            o.addProperty("x", a.pos.x)
            o.addProperty("y", a.pos.y)
            o.addProperty("z", a.pos.z)
            o.addProperty("radius", a.radius)

            if (a.days != null) o.addProperty("days", a.days)
            if (a.state != null) o.addProperty("state", a.state.name)

            o.addProperty("createdGameTime", a.createdGameTime)
            arr.add(o)
        }

        root.add("anchors", arr)
        val json = gson.toJson(root)
        Files.write(filePath(server), json.toByteArray(StandardCharsets.UTF_8))
    }

    @Synchronized
    fun load(server: MinecraftServer) {
        val path = filePath(server)
        if (!Files.exists(path)) {
            MementoStones.clear()
            return
        }

        val raw = Files.readString(path, StandardCharsets.UTF_8).trim()
        if (raw.isEmpty()) {
            MementoStones.clear()
            return
        }

        val root = JsonParser.parseString(raw).asJsonObject
        val arr = root.getAsJsonArray("anchors") ?: JsonArray()

        val loaded = linkedMapOf<String, MementoStones.Stone>()

        for (e in arr) {
            val o = e.asJsonObject

            val name = o.get("name").asString
            val kind = MementoStones.Kind.valueOf(o.get("kind").asString)

            val dimId = Identifier.tryParse(o.get("dimension").asString)
                ?: Identifier.of("minecraft", "overworld")
            val dimKey =
                RegistryKey.of<World>(RegistryKeys.WORLD, dimId)

            val x = o.get("x").asInt
            val y = o.get("y").asInt
            val z = o.get("z").asInt
            val radius = o.get("radius").asInt

            val days = if (o.has("days")) o.get("days").asInt else null
            val state =
                if (o.has("state"))
                    MementoStones.WitherstoneState.valueOf(o.get("state").asString)
                else
                    null

            val created = if (o.has("createdGameTime")) o.get("createdGameTime").asLong else 0L

            loaded[name] = MementoStones.Stone(
                name = name,
                kind = kind,
                dimension = dimKey,
                pos = BlockPos(x, y, z),
                radius = radius,
                days = days,
                state = state,
                createdGameTime = created
            )
        }

        MementoStones.clear()
        MementoStones.putAll(loaded)
    }
}
