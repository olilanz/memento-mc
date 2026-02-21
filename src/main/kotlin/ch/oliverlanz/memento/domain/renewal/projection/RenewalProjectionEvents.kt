package ch.oliverlanz.memento.domain.renewal.projection

import ch.oliverlanz.memento.domain.worldmap.ChunkMetadataFact
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Typed domain event emitted after a factual world-map metadata fact has been applied.
 *
 * Projection listens to this boundary event to enqueue recomputation work without coupling
 * directly to map mutation internals.
 */
data class WorldMapFactApplied(val fact: ChunkMetadataFact)

/** Listener boundary for [WorldMapFactApplied] events. */
fun interface WorldMapFactAppliedListener {
    fun onWorldMapFactApplied(event: WorldMapFactApplied)
}

/**
 * Event hub for renewal-projection boundary triggers.
 *
 * This keeps detection-side fact application decoupled from evaluation-side recomputation.
 */
object RenewalProjectionEvents {
    private val factAppliedListeners = CopyOnWriteArraySet<WorldMapFactAppliedListener>()

    fun subscribeFactApplied(listener: WorldMapFactAppliedListener) {
        factAppliedListeners += listener
    }

    fun unsubscribeFactApplied(listener: WorldMapFactAppliedListener) {
        factAppliedListeners -= listener
    }

    fun emitFactApplied(fact: ChunkMetadataFact) {
        val event = WorldMapFactApplied(fact)
        factAppliedListeners.forEach { it.onWorldMapFactApplied(event) }
    }
}
