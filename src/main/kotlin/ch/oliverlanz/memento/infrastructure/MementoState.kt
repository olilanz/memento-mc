package ch.oliverlanz.memento.infrastructure

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.minecraft.server.MinecraftServer
import net.minecraft.util.WorldSavePath
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tiny persisted state for Memento.
 *
 * Design notes (locked for this slice):
 * - Witherstone anchors age once per *Memento day* (03:00 boundary).
 * - Players may sleep and ops may use /time set, which can jump time.
 * - We therefore track the last processed Overworld day index to avoid
 *   decrementing counters twice for the same world day.
 */
object MementoState {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    data class State(
        val lastProcessedOverworldDay: Long
    )

    @Volatile
    private var state: State = State(lastProcessedOverworldDay = -1L)

    fun get(): State = state

    fun set(newState: State) {
        state = newState
    }

    private fun filePath(server: MinecraftServer): Path {
        // World root folder (same place as level.dat)
        return server.getSavePath(WorldSavePath.ROOT).resolve("memento_state.json")
    }

    @Synchronized
    fun save(server: MinecraftServer) {
        val json = gson.toJson(state)
        Files.write(filePath(server), json.toByteArray(StandardCharsets.UTF_8))
    }

    @Synchronized
    fun load(server: MinecraftServer) {
        val path = filePath(server)
        if (!Files.exists(path)) {
            state = State(lastProcessedOverworldDay = -1L)
            return
        }

        val raw = Files.readString(path, StandardCharsets.UTF_8).trim()
        if (raw.isEmpty()) {
            state = State(lastProcessedOverworldDay = -1L)
            return
        }

        val element = runCatching { JsonParser.parseString(raw) }.getOrNull()
        val obj = element?.takeIf { it.isJsonObject }?.asJsonObject
        val lastDay = obj?.get("lastProcessedOverworldDay")?.asLong ?: -1L
        state = State(lastProcessedOverworldDay = lastDay)
    }
}