package ch.oliverlanz.memento.infrastructure

import ch.oliverlanz.memento.domain.stones.*
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

object StoneRegisterPersistence {

    private const val FILE_NAME = "memento_stone_register.json"

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun load(server: MinecraftServer): List<Stone> {
        val file = filePath(server)
        if (!Files.exists(file)) return emptyList()

        return try {
            val json = Files.readString(file, StandardCharsets.UTF_8)
            val root = JsonParser.parseString(json).asJsonObject
            val arr = root.getAsJsonArray("stones") ?: JsonArray()

            val out = mutableListOf<Stone>()
            for (e in arr) {
                val obj = e.asJsonObject
                val type = obj["type"]?.asString ?: continue
                val name = obj["name"]?.asString ?: continue
                val dim = obj["dimension"]?.asString ?: continue
                val pos = obj["pos"]?.asJsonObject ?: continue

                val x = pos["x"]?.asInt ?: continue
                val y = pos["y"]?.asInt ?: continue
                val z = pos["z"]?.asInt ?: continue

                val radius = obj["radius"]?.asInt ?: MementoConstants.DEFAULT_CHUNKS_RADIUS
                val dimensionKey = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(dim))

                when (type) {
                    "WITHERSTONE" -> {
                        val days = obj["daysToMaturity"]?.asInt ?: MementoConstants.DEFAULT_DAYS_TO_MATURITY
                        val stateStr = obj["state"]?.asString ?: WitherstoneState.MATURING.name
                        val state = runCatching { WitherstoneState.valueOf(stateStr) }
                            .getOrDefault(WitherstoneState.MATURING)

                        val s = Witherstone(
                            name = name,
                            dimension = dimensionKey,
                            position = BlockPos(x, y, z),
                            daysToMaturity = days,
                            state = state
                        )
                        s.radius = radius
                        out.add(s)
                    }
                    "LORESTONE" -> {
                        val s = Lorestone(
                            name = name,
                            dimension = dimensionKey,
                            position = BlockPos(x, y, z)
                        )
                        s.radius = radius
                        out.add(s)
                    }
                }
            }
            out
        } catch (t: Throwable) {
            MementoDebug.warn(server, "StoneRegister load failed, starting empty: ${t.message}")
            emptyList()
        }
    }

    fun save(server: MinecraftServer, stones: List<Stone>) {
        val file = filePath(server)
        val root = JsonObject()
        val arr = JsonArray()

        for (s in stones) {
            val obj = JsonObject()
            when (s) {
                is Witherstone -> {
                    obj.addProperty("type", "WITHERSTONE")
                    obj.addProperty("daysToMaturity", s.daysToMaturity)
                    obj.addProperty("state", s.state.name)
                }
                is Lorestone -> obj.addProperty("type", "LORESTONE")
            }
            obj.addProperty("name", s.name)
            obj.addProperty("dimension", s.dimension.value.toString())
            obj.addProperty("radius", s.radius)

            val pos = JsonObject()
            pos.addProperty("x", s.position.x)
            pos.addProperty("y", s.position.y)
            pos.addProperty("z", s.position.z)
            obj.add("pos", pos)

            arr.add(obj)
        }

        root.add("stones", arr)
        Files.createDirectories(file.parent)
        Files.writeString(file, gson.toJson(root), StandardCharsets.UTF_8)
    }

    private fun filePath(server: MinecraftServer): Path =
        server.getSavePath(WorldSavePath.ROOT).resolve(FILE_NAME)
}