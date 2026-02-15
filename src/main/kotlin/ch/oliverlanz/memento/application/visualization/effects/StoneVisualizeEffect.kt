package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.infrastructure.time.GameHours
import ch.oliverlanz.memento.domain.stones.StoneView

/**
 * Visualize effect: short-lived, high-information reveal of stone scope.
 *
 * Lane intent:
 * - Anchor point/chunk: steady anchor/context signal via rate plans.
 * - Influence area: pulsating field that reads as a breathing footprint.
 * - Influence outline: running wrapped perimeter cursor with spacing-based trail
 *   illusion so operators can read boundary motion direction.
 */
class StoneVisualizeEffect(stone: StoneView) : EffectBase(stone) {

    override fun onConfigure(profile: EffectProfile) {
        // Global
        profile.lifetime = GameHours(0.30)  // Visualize: short-lived and high signal.

        // Anchor point lane
        // use defaults

        // Anchor chunk lane
        // use defaults

        // Influence area/outline lanes
        // use defaults
    }
}
