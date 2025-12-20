package ch.oliverlanz.memento.mixin

import ch.oliverlanz.memento.chunkutils.ChunkForgetPredicate
import net.minecraft.registry.RegistryKey
import net.minecraft.nbt.NbtCompound
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import net.minecraft.world.storage.StorageKey
import net.minecraft.world.storage.VersionedChunkStorage
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.util.Optional
import java.util.concurrent.CompletableFuture

@Mixin(VersionedChunkStorage::class)
abstract class VersionedChunkStorageMixin {

    @Shadow
    protected abstract fun getStorageKey(): StorageKey

    @Inject(
        method = ["getNbt"],
        at = [At("HEAD")],
        cancellable = true
    )
    private fun interceptGetNbt(
        chunkPos: ChunkPos,
        cir: CallbackInfoReturnable<CompletableFuture<Optional<NbtCompound>>>
    ) {
        // VersionedChunkStorage is dimension-scoped. We derive dimension from its StorageKey.
        // StorageKey.dimension() is a RegistryKey<World>.
        @Suppress("UNCHECKED_CAST")
        val dimensionKey = getStorageKey().dimension() as RegistryKey<World>

        if (ChunkForgetPredicate.shouldForget(dimensionKey, chunkPos)) {
            // Returning Optional.empty() means "no chunk NBT exists" => chunk will be generated.
            cir.returnValue = CompletableFuture.completedFuture(Optional.empty())
        }
    }
}
