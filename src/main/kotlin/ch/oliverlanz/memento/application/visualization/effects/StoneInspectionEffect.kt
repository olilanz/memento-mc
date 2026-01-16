package ch.oliverlanz.memento.application.visualization.effects

import ch.oliverlanz.memento.application.time.GameHours
import ch.oliverlanz.memento.application.visualization.samplers.SingleChunkSurfaceSampler
import ch.oliverlanz.memento.application.visualization.samplers.StoneBlockSampler
import ch.oliverlanz.memento.domain.stones.StoneView
import ch.oliverlanz.memento.domain.stones.WitherstoneView
import net.minecraft.particle.ParticleTypes

class StoneInspectionEffect(private val stone: StoneView) : EffectBase() {

    override fun onConfigure(profile: EffectProfile) {
        // Visualize: short-lived, very intense inspection burst.
        profile.lifetime = GameHours(0.25) // 15 game minutes
        profile.surfaceVerticalSpan = 0..0 // ground-level only

        profile.anchorSampler = StoneBlockSampler(stone)
        // Anchors are always green and sparkly (default), but inspection is denser.
        profile.anchorSystem = profile.anchorSystem?.copy(
            count = 22,
            spreadX = 0.20,
            spreadY = 0.20,
            spreadZ = 0.20,
            speed = 0.02,
        )
        profile.anchorTotalEmissions = 1200

        profile.surfaceSampler = SingleChunkSurfaceSampler(stone)
        // Surface: intense but visually distinct from the anchor.
        profile.surfaceSystem = when (stone) {
            is WitherstoneView -> EffectBase.ParticleSystemPrototype(
                particle = ParticleTypes.ASH,
                count = 14,
                spreadX = 0.30,
                spreadY = 0.10,
                spreadZ = 0.30,
                speed = 0.02,
                baseYOffset = 1.0,
            )
            else -> EffectBase.ParticleSystemPrototype(
                particle = ParticleTypes.END_ROD,
                count = 10,
                spreadX = 0.28,
                spreadY = 0.08,
                spreadZ = 0.28,
                speed = 0.02,
                baseYOffset = 1.0,
            )
        }
        // 1200 emissions over 15 minutes => 4800 per game hour.
        profile.surfaceEmissionsPerGameHour = 4800
    }
}
