package ch.oliverlanz.memento.mixin

import ch.oliverlanz.memento.chunkutils.ChunkForgetPredicate
import net.minecraft.nbt.NbtCompound
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import net.minecraft.world.storage.VersionedChunkStorage
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.util.Optional
import java.util.concurrent.CompletableFuture

@Mixin(VersionedChunkStorage::class)
abstract class VersionedChunkStorageMixin {

    @Inject(
        method = ["getNbt"],
        at = [At("HEAD")],
        cancellable = true
    )
    private fun `memento$interceptGetNbt`(
        chunkPos: ChunkPos,
        cir: CallbackInfoReturnable<CompletableFuture<Optional<NbtCompound>>>
    ) {
        // EXPERIMENT: overworld only
        if (ChunkForgetPredicate.shouldForget(World.OVERWORLD, chunkPos)) {
            cir.returnValue = CompletableFuture.completedFuture(Optional.empty())
        }
    }
}
