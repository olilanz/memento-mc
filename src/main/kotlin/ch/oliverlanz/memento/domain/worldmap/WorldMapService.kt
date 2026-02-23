package ch.oliverlanz.memento.domain.worldmap

import ch.oliverlanz.memento.domain.renewal.projection.RenewalProjectionEvents
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import net.minecraft.server.MinecraftServer
import net.minecraft.util.WorldSavePath

/**
 * Domain-owned lifecycle and ingestion authority for the authoritative world map.
 *
 * Infrastructure components stage/prepare metadata, but authoritative map mutation happens only
 * through [applyFactOnTickThread] on the server tick thread.
 */
class WorldMapService {
    private val map = WorldMementoMap()
    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Volatile private var attached: Boolean = false
    @Volatile private var server: MinecraftServer? = null
    @Volatile private var firstFullScanCompleted: Boolean = false

    private data class PersistedState(
        val firstFullScanCompleted: Boolean = false,
    )

    fun attach(server: MinecraftServer) {
        this.server = server
        loadState(server)
        attached = true
        MementoLog.info(
            MementoConcept.WORLD,
            "world-map service attached firstFullScanCompleted={}",
            firstFullScanCompleted,
        )
    }

    fun detach() {
        server?.let { saveState(it) }
        attached = false
        server = null
        MementoLog.info(MementoConcept.WORLD, "world-map service detached")
    }

    fun hasInitialScanCompleted(): Boolean = firstFullScanCompleted

    fun markInitialScanCompletedOnTickThread(reason: String) {
        if (!attached) return
        if (firstFullScanCompleted) return

        firstFullScanCompleted = true
        server?.let { saveState(it) }
        MementoLog.info(
            MementoConcept.SCANNER,
            "initial scan completion recorded reason={}",
            reason,
        )
    }

    fun substrate(): WorldMementoMap = map

    /**
     * Applies one metadata fact directly on the server tick thread.
     *
     * Caller owns bounded batching policy; this service owns authoritative map mutation semantics.
     */
    fun applyFactOnTickThread(fact: ChunkMetadataFact) {
        if (!attached) return
        val signalsChanged = fact.signals?.let { signals ->
            map.upsertSignals(fact.key, signals)
        } ?: false

        val firstScannedMark = map.markScanned(
            key = fact.key,
            scanTick = fact.scanTick,
            provenance = fact.source,
            unresolvedReason = fact.unresolvedReason,
        )

        // Debug signal for projection churn analysis:
        // if both are false, we received another fact for an already-known, already-scanned key
        // without factual signal change.
        if (!signalsChanged && !firstScannedMark) {
            MementoLog.debug(
                MementoConcept.PROJECTION,
                "world-map duplicate fact key=({}, {}) world={} source={} unresolvedReason={}",
                fact.key.chunkX,
                fact.key.chunkZ,
                fact.key.world.value,
                fact.source,
                fact.unresolvedReason,
            )
        }

        // Projection recomputation trigger is strictly signal-change based, not scan-discovery based.
        if (signalsChanged) {
            RenewalProjectionEvents.emitFactApplied(fact)
        }
    }

    /**
     * Removes all map entries for one world-region tuple on the tick thread.
     *
     * Projection recomputation is explicitly triggered for each removed key so derived renewal
     * decisions can drop stale region evidence immediately.
     */
    fun expungeRegionOnTickThread(
        world: net.minecraft.registry.RegistryKey<net.minecraft.world.World>,
        regionX: Int,
        regionZ: Int,
        scanTick: Long,
    ): Int {
        if (!attached) return 0
        val removed = map.expungeRegion(world, regionX, regionZ)
        if (removed.isEmpty()) return 0

        removed.forEach { key ->
            RenewalProjectionEvents.emitFactApplied(
                ChunkMetadataFact(
                    key = key,
                    source = ChunkScanProvenance.FILE_PRIMARY,
                    unresolvedReason = ChunkScanUnresolvedReason.FILE_MISSING,
                    signals = null,
                    scanTick = scanTick,
                )
            )
        }
        return removed.size
    }

    private fun statePath(server: MinecraftServer): Path {
        return server.getSavePath(WorldSavePath.ROOT).resolve("memento_worldmap_state.json")
    }

    @Synchronized
    private fun saveState(server: MinecraftServer) {
        val path = statePath(server)
        val json = gson.toJson(PersistedState(firstFullScanCompleted = firstFullScanCompleted))
        Files.write(path, json.toByteArray(StandardCharsets.UTF_8))
    }

    @Synchronized
    private fun loadState(server: MinecraftServer) {
        val path = statePath(server)
        if (!Files.exists(path)) {
            firstFullScanCompleted = false
            return
        }

        val raw = Files.readString(path, StandardCharsets.UTF_8).trim()
        if (raw.isEmpty()) {
            firstFullScanCompleted = false
            return
        }

        val element = runCatching { JsonParser.parseString(raw) }.getOrNull()
        val obj = element?.takeIf { it.isJsonObject }?.asJsonObject
        firstFullScanCompleted = obj?.get("firstFullScanCompleted")?.asBoolean ?: false
    }
}
