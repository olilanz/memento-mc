package ch.oliverlanz.memento.infrastructure.renewal

import ch.oliverlanz.memento.domain.renewal.projection.RenewalProjectionStableListener
import ch.oliverlanz.memento.domain.renewal.projection.RenewalStableSnapshot
import ch.oliverlanz.memento.domain.worldmap.WorldMapService
import ch.oliverlanz.memento.infrastructure.worldscan.MementoCsvWriter
import net.minecraft.server.MinecraftServer

/**
 * CSV adapter that reacts to projection stabilization.
 *
 * Boundary rules:
 * - Consumes only stable projection snapshots.
 * - Reads factual world-map snapshot for row baseline.
 * - Never triggers recomputation and never inspects projection work-set internals.
 */
object RenewalProjectionCsvExporter : RenewalProjectionStableListener {
    @Volatile
    private var server: MinecraftServer? = null

    @Volatile
    private var worldMapService: WorldMapService? = null

    fun attach(server: MinecraftServer, worldMapService: WorldMapService) {
        this.server = server
        this.worldMapService = worldMapService
    }

    fun detach() {
        this.server = null
        this.worldMapService = null
    }

    override fun onProjectionStable(snapshot: RenewalStableSnapshot) {
        val srv = server ?: return
        val service = worldMapService ?: return
        val factualSnapshot = service.substrate().snapshot()
        MementoCsvWriter.writeProjectionSnapshot(
            server = srv,
            snapshot = factualSnapshot,
            metricsByChunk = snapshot.metricsByChunk,
        )
    }
}
