package ch.oliverlanz.memento.mixin

import ch.oliverlanz.memento.infrastructure.renewal.RenewalRegenerationBridge
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.RegistryKey
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
    abstract fun getStorageKey(): StorageKey

    @Inject(
        method = ["getNbt(Lnet/minecraft/util/math/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"],
        at = [At("HEAD")],
        cancellable = true
    )
    private fun mementoInterceptChunkNbtLoad(
        chunkPos: ChunkPos,
        cir: CallbackInfoReturnable<CompletableFuture<Optional<NbtCompound>>>
    ) {
        @Suppress("UNCHECKED_CAST")
        val dimensionKey = getStorageKey().dimension() as RegistryKey<World>

        if (RenewalRegenerationBridge.shouldRegenerate(dimensionKey, chunkPos)) {
            cir.returnValue = CompletableFuture.completedFuture(Optional.empty())
        }
    }
}
