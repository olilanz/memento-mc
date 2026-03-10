package ch.oliverlanz.memento.infrastructure.ambient

import ch.oliverlanz.memento.MementoConstants
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.minecraft.server.MinecraftServer
import net.minecraft.util.WorldSavePath
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * World-root persisted configuration for acceptance-gated ambient automation.
 *
 * Ownership:
 * - persistence of operator acceptance state only
 * - no scheduling, execution, or derivation logic
 */
object MementoConfigStore {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    private data class Config(
        val ambientRenewalAccepted: Boolean = false,
    )

    @Volatile
    private var state: Config = Config()

    fun attach(server: MinecraftServer) {
        load(server)
    }

    fun detach() {
        state = Config()
    }

    fun isAmbientRenewalAccepted(): Boolean = state.ambientRenewalAccepted

    @Synchronized
    fun accept(server: MinecraftServer): Boolean {
        val previous = state
        val next = previous.copy(ambientRenewalAccepted = true)
        return runCatching {
            save(server, next)
            state = next
            true
        }.getOrElse { t ->
            state = previous
            MementoLog.error(MementoConcept.STORAGE, "ambient config write failed", t)
            false
        }
    }

    private fun filePath(server: MinecraftServer): Path {
        return server.getSavePath(WorldSavePath.ROOT).resolve(MementoConstants.AMBIENT_CONFIG_FILE)
    }

    @Synchronized
    private fun load(server: MinecraftServer) {
        val path = filePath(server)
        if (!Files.exists(path)) {
            state = Config(ambientRenewalAccepted = false)
            return
        }

        val raw = runCatching { Files.readString(path, StandardCharsets.UTF_8).trim() }.getOrElse { t ->
            MementoLog.warn(MementoConcept.STORAGE, "ambient config read failed; using defaults", t)
            state = Config(ambientRenewalAccepted = false)
            return
        }

        if (raw.isEmpty()) {
            state = Config(ambientRenewalAccepted = false)
            return
        }

        val accepted = runCatching {
            val element = JsonParser.parseString(raw)
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject
            obj?.get("ambientRenewalAccepted")?.asBoolean ?: false
        }.getOrElse { t ->
            MementoLog.warn(MementoConcept.STORAGE, "ambient config malformed; using defaults", t)
            false
        }

        state = Config(ambientRenewalAccepted = accepted)
    }

    private fun save(server: MinecraftServer, config: Config) {
        val path = filePath(server)
        Files.createDirectories(path.parent)
        Files.write(path, gson.toJson(config).toByteArray(StandardCharsets.UTF_8))
    }
}
