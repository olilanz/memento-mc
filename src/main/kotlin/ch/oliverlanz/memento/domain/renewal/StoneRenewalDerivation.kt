package ch.oliverlanz.memento.domain.renewal

import ch.oliverlanz.memento.domain.stones.Lorestone
import ch.oliverlanz.memento.domain.stones.StoneMapService
import ch.oliverlanz.memento.domain.stones.StoneSpatial
import ch.oliverlanz.memento.domain.stones.StoneAuthority
import ch.oliverlanz.memento.domain.stones.Witherstone
import ch.oliverlanz.memento.domain.stones.WitherstoneState

/**
 * Renewal-side derivation from stone lifecycle state + stone-map dominance projection.
 *
 * Ownership boundary:
 * - Stone lifecycle remains owned by [StoneAuthority].
 * - Dominance projection is consumed through [StoneMapService].
 * - Renewal batch definitions are owned by [RenewalTracker].
 */
object StoneRenewalDerivation {

    private val log = ch.oliverlanz.memento.infrastructure.observability.MementoLog

    fun ensureForMaturedWitherstone(stoneName: String, reason: String): Boolean {
        val witherstone = StoneAuthority.get(stoneName) as? Witherstone ?: return false
        if (witherstone.state != WitherstoneState.MATURED) return false

        ensureForWitherstone(witherstone, reason = reason)
        return true
    }

    fun ensureForAllMaturedWitherstones(reason: String): Int {
        val matured = StoneAuthority.list()
            .asSequence()
            .mapNotNull { it as? Witherstone }
            .filter { it.state == WitherstoneState.MATURED }
            .toList()

        for (w in matured) {
            ensureForWitherstone(w, reason = reason)
        }

        return matured.size
    }

    fun ensureForMaturedWitherstonesAffectedByLorestone(lorestone: Lorestone, reason: String) {
        val affected = StoneAuthority.list()
            .asSequence()
            .mapNotNull { it as? Witherstone }
            .filter { it.dimension == lorestone.dimension }
            .filter { it.state == WitherstoneState.MATURED }
            .filter { StoneSpatial.overlaps(it, lorestone) }
            .toList()

        if (affected.isEmpty()) {
            log.debug(ch.oliverlanz.memento.infrastructure.observability.MementoConcept.STONE,
                "lorestone topology change reason={} lorestone='{}' affectedWitherstones=0",
                reason,
                lorestone.name,
            )
            return
        }

        log.info(ch.oliverlanz.memento.infrastructure.observability.MementoConcept.STONE,
            "lorestone topology change reason={} lorestone='{}' affectedWitherstones={}",
            reason,
            lorestone.name,
            affected.size,
        )

        for (w in affected) {
            ensureForWitherstone(w, reason = "lorestone_${reason}")
        }
    }

    fun onWitherstoneRemoved(stoneName: String) {
        RenewalTracker.removeBatch(stoneName, trigger = RenewalTrigger.MANUAL)
    }

    private fun ensureForWitherstone(witherstone: Witherstone, reason: String) {
        val influenced = StoneMapService.influencedChunks(witherstone)
        val dominantByChunk = StoneMapService.dominantByChunk(witherstone.dimension)

        val eligible = influenced
            .asSequence()
            .filter { chunk -> dominantByChunk[chunk] != Lorestone::class }
            .toCollection(LinkedHashSet())

        RenewalTracker.upsertBatchDefinition(
            name = witherstone.name,
            dimension = witherstone.dimension,
            chunks = eligible,
            trigger = RenewalTrigger.SYSTEM,
        )

        log.debug(ch.oliverlanz.memento.infrastructure.observability.MementoConcept.RENEWAL,
            "renewal intent ensured stone='{}' reason={} chunks={}",
            witherstone.name,
            reason,
            eligible.size,
        )
    }
}

