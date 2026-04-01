package ch.oliverlanz.memento.infrastructure.chunk

import kotlin.test.Test
import kotlin.test.assertNull
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.world.World

class ChunkLoadDriverContractTest {

    @Test
    fun get_metadata_if_loaded_returns_null_when_driver_is_unattached() {
        val driver = ChunkLoadDriver()

        // Contract lock: without attached server, metadata lookup is immediate null (no retries,
        // no load-chasing side effects).
        val metadata = driver.getMetadataIfLoaded(overworld(), 10, 20)

        assertNull(metadata)
    }

    private fun overworld(): RegistryKey<World> =
        RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft:overworld"))
}

