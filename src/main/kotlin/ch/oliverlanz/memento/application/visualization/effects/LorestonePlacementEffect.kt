package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.infrastructure.time.GameHours
import ch.oliverlanz.memento.domain.stones.LorestoneView

/**
 * Lorestone placement effect: immediate, unambiguous confirmation of protective intent.
 *
 * Lane intent:
 * - Anchor point/chunk: steady rate signal to pin local origin.
 * - Influence area: pulsating expansion pulses for footprint readability.
 * - Influence outline: running wrapped perimeter cursor with spacing trail illusion.
 */
class LorestonePlacementEffect(stone: LorestoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        // Global
        profile.lifetime = GameHours(0.25)  // Placement: short-lived, intense, unmistakable.

        // Anchor point lane
        // use defaults

        // Anchor chunk lane
        // use defaults

        // Influence area/outline lanes
        // use defaults
    }
}
