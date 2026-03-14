package ch.oliverlanz.memento.infrastructure.worldscan

import ch.oliverlanz.memento.domain.worldmap.ChunkKey
import ch.oliverlanz.memento.domain.worldmap.ChunkScanProvenance
import ch.oliverlanz.memento.domain.worldmap.ChunkScanUnresolvedReason
import ch.oliverlanz.memento.domain.worldmap.ChunkSignals
import ch.oliverlanz.memento.infrastructure.async.GlobalAsyncExclusionGate
import ch.oliverlanz.memento.infrastructure.observability.MementoConcept
import ch.oliverlanz.memento.infrastructure.observability.MementoLog
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Optional
import java.util.concurrent.Future
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException
import kotlin.math.max
import kotlin.math.ceil
import kotlin.math.log2
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtSizeTracker
import net.minecraft.world.storage.ChunkCompressionFormat

/**
 * File-primary metadata provider with single-pass policy.
 *
 * Behavior:
 * - One pass attempts all discovered chunk work units from [WorldDiscoveryPlan]
 * - Terminal completion after that pass (no hidden retry pass)
 * - Reconciliation is explicit operator control via re-running `/memento do scan`
 *
 * Threading:
 * - All file IO and NBT decode runs off-thread via global async exclusion gate.
 * - World map mutation stays on tick thread via [ScanMetadataIngestionPort].
 */
class RegionFileMetadataProvider(
    private val ingestionPort: ScanMetadataIngestionPort,
) : FileMetadataProvider {

    private val lifecycle = AtomicReference(FileMetadataProviderLifecycle.IDLE)
    private val processedWorkUnits = AtomicInteger(0)
    private val emittedFacts = AtomicInteger(0)

    @Volatile
    private var totalWorkUnits: Int = 0

    @Volatile
    private var runFuture: Future<*>? = null

    @Synchronized
    override fun start(plan: WorldDiscoveryPlan, scanTick: Long): Boolean {
        if (lifecycle.get() == FileMetadataProviderLifecycle.RUNNING) return false

        val work = flatten(plan)
        totalWorkUnits = work.size
        processedWorkUnits.set(0)
        emittedFacts.set(0)
        lifecycle.set(FileMetadataProviderLifecycle.RUNNING)

        val submit = GlobalAsyncExclusionGate.submitIfIdle(
            concept = MementoConcept.SCANNER,
            owner = "scanner-file-provider",
        ) {
            Callable {
                runCatching {
                    runSinglePass(work, scanTick)
                }.onFailure { t ->
                    if (lifecycle.get() == FileMetadataProviderLifecycle.CANCELLED || t is InterruptedException) {
                        MementoLog.debug(MementoConcept.SCANNER, "file metadata provider cancelled")
                    } else {
                        MementoLog.error(MementoConcept.SCANNER, "file metadata provider failed", t)
                    }
                }

                if (lifecycle.get() != FileMetadataProviderLifecycle.CANCELLED) {
                    lifecycle.set(FileMetadataProviderLifecycle.COMPLETE)
                }
            }
        }

        when (submit) {
            is GlobalAsyncExclusionGate.SubmitResult.Accepted -> {
                runFuture = submit.future
            }

            is GlobalAsyncExclusionGate.SubmitResult.Busy -> {
                lifecycle.set(FileMetadataProviderLifecycle.IDLE)
                return false
            }
        }

        return true
    }

    override fun status(): FileMetadataProviderStatus {
        return FileMetadataProviderStatus(
            lifecycle = lifecycle.get(),
            totalWorkUnits = totalWorkUnits,
            processedWorkUnits = processedWorkUnits.get(),
            emittedFacts = emittedFacts.get(),
        )
    }

    override fun isComplete(): Boolean {
        return when (lifecycle.get()) {
            FileMetadataProviderLifecycle.COMPLETE,
            FileMetadataProviderLifecycle.CANCELLED -> true
            else -> false
        }
    }

    override fun close() {
        lifecycle.set(FileMetadataProviderLifecycle.CANCELLED)
        runFuture?.cancel(true)
    }

    private fun runSinglePass(work: List<FileChunkWorkUnit>, scanTick: Long) {
        val unresolvedByReason = linkedMapOf<ChunkScanUnresolvedReason, Int>()
        var success = 0
        var unresolved = 0
        var missingSurface = 0
        var missingBiome = 0
        for (unit in work) {
            if (Thread.currentThread().isInterrupted) return

            val result = readChunkMetadata(unit)
            emitFact(unit.key, result, scanTick)
            processedWorkUnits.incrementAndGet()

            if (result.signals != null) {
                if (result.signals.surfaceY == null) missingSurface++
                if (result.signals.biomeId == null) missingBiome++
            }

            if (result.unresolvedReason == null) {
                success++
            } else {
                unresolved++
                unresolvedByReason[result.unresolvedReason] =
                    (unresolvedByReason[result.unresolvedReason] ?: 0) + 1
            }
        }

        MementoLog.info(
            MementoConcept.SCANNER,
            "scan file-pass summary processed={}/{} success={} unresolved={} unresolvedByReason={} emittedFacts={}",
            processedWorkUnits.get(),
            totalWorkUnits,
            success,
            unresolved,
            formatReasonCounts(unresolvedByReason),
            emittedFacts.get(),
        )

        if (missingSurface > 0 || missingBiome > 0) {
            MementoLog.warn(
                MementoConcept.SCANNER,
                "scan file-pass metadata gaps surfaceYMissing={} biomeMissing={} policy=null-on-missing",
                missingSurface,
                missingBiome,
            )
        }
    }

    private fun emitFact(key: ChunkKey, result: ChunkReadResult, scanTick: Long) {
        ingestionPort.ingest(
            ScanMetadataFact(
                key = key,
                source = ChunkScanProvenance.FILE_PRIMARY,
                unresolvedReason = result.unresolvedReason,
                signals = result.signals,
                scanTick = scanTick,
            )
        )
        emittedFacts.incrementAndGet()
    }

    private fun readChunkMetadata(unit: FileChunkWorkUnit): ChunkReadResult {
        return try {
            val root = readChunkNbtFromRegion(unit.regionFile, unit.chunk)
            val inhabited =
                readLong(root, "InhabitedTime")
                    ?: root.getCompound("Level").flatMap { level -> level.getLong("InhabitedTime") }.orElse(null)
            val lastUpdate =
                readLong(root, "LastUpdate")
                    ?: root.getCompound("Level").flatMap { level -> level.getLong("LastUpdate") }.orElse(null)
            val surfaceY = extractSurfaceY(root)
            val biome = extractBiomeId(root)

            ChunkReadResult(
                unresolvedReason = null,
                signals =
                    ChunkSignals(
                        inhabitedTimeTicks = inhabited,
                        lastUpdateTicks = lastUpdate,
                        surfaceY = surfaceY,
                        biomeId = biome,
                        isSpawnChunk = false,
                    ),
                isTransientFailure = false,
            )
        } catch (t: Throwable) {
            val reason = classifyFailure(t)
            ChunkReadResult(
                unresolvedReason = reason,
                signals = null,
                isTransientFailure = reason == ChunkScanUnresolvedReason.FILE_LOCKED || reason == ChunkScanUnresolvedReason.FILE_IO_ERROR,
            )
        }
    }

    private fun readChunkNbtFromRegion(regionFile: Path, chunk: ChunkRef): NbtCompound {
        Files.newByteChannel(regionFile, StandardOpenOption.READ).use { channel ->
            val slotOffsetBytes = chunk.sectorOffset.toLong() * SECTOR_BYTES
            val slotCapacityBytes = chunk.sectorCount.toLong() * SECTOR_BYTES
            if (slotCapacityBytes < 5L) {
                throw EOFException("chunk slot too small for header")
            }

            val fileSize = channel.size()
            if (slotOffsetBytes >= fileSize) {
                throw EOFException("chunk slot starts beyond file size")
            }

            channel.position(slotOffsetBytes)
            val lengthBytes = readExact(channel, 4)
            val declaredLength =
                ((lengthBytes[0].toInt() and 0xFF) shl 24) or
                    ((lengthBytes[1].toInt() and 0xFF) shl 16) or
                    ((lengthBytes[2].toInt() and 0xFF) shl 8) or
                    (lengthBytes[3].toInt() and 0xFF)

            if (declaredLength <= 0) {
                throw EOFException("chunk payload length is non-positive")
            }

            val payloadWithCompression = declaredLength.toLong()
            val availableInSlot = slotCapacityBytes - 4L
            if (payloadWithCompression > availableInSlot) {
                throw EOFException("chunk payload exceeds slot capacity")
            }

            val compressionType = readExact(channel, 1)[0].toInt() and 0xFF
            val compressedSize = max(0, declaredLength - 1)
            val payload = readExact(channel, compressedSize)

            val decoded = when (compressionType) {
                COMPRESSION_GZIP -> decode(payload) { input -> GZIPInputStream(input) }
                COMPRESSION_ZLIB -> decode(payload) { input -> InflaterInputStream(input) }
                COMPRESSION_NONE -> decode(payload) { input -> input }
                COMPRESSION_LZ4 -> decode(payload) { input -> ChunkCompressionFormat.LZ4.wrap(input) }
                else -> throw UnsupportedCompressionException(compressionType)
            }

            return DataInputStream(ByteArrayInputStream(decoded)).use { dataInput ->
                NbtIo.readCompound(dataInput, NbtSizeTracker.ofUnlimitedBytes())
            }
        }
    }

    private fun decode(payload: ByteArray, opener: (InputStream) -> InputStream): ByteArray {
        return ByteArrayInputStream(payload).use { raw ->
            opener(raw).use { wrapped ->
                wrapped.readBytes()
            }
        }
    }

    private fun readExact(channel: SeekableByteChannel, length: Int): ByteArray {
        if (length < 0) throw EOFException("negative read length")
        val out = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val read = channel.read(java.nio.ByteBuffer.wrap(out, filled, length - filled))
            if (read < 0) throw EOFException("unexpected EOF")
            filled += read
        }
        return out
    }

    private fun readLong(compound: NbtCompound, key: String): Long? {
        return if (compound.contains(key)) compound.getLong(key).orElse(null) else null
    }

    private fun extractSurfaceY(root: NbtCompound): Int? {
        val heightmaps = root.getCompound("Heightmaps").orElse(null)
            ?: root.getCompound("Level").flatMap { it.getCompound("Heightmaps") }.orElse(null)
            ?: return null

        val packed = runCatching {
            heightmaps.getLongArray("WORLD_SURFACE").orElse(null)
        }.getOrNull() ?: return null

        if (packed.isEmpty()) return null

        // We sample the center of the chunk (local 8,8 => linear index 8 + 8 * 16 = 136).
        val index = 8 + 8 * 16
        val bitsPerEntry = max(1, ceil(log2(384.0)).toInt())
        val bitIndex = index * bitsPerEntry
        val longIndex = bitIndex / 64
        val bitOffset = bitIndex % 64
        if (longIndex >= packed.size) return null

        val mask = (1L shl bitsPerEntry) - 1L
        var value = (packed[longIndex] ushr bitOffset) and mask
        if (bitOffset + bitsPerEntry > 64 && longIndex + 1 < packed.size) {
            val spill = bitOffset + bitsPerEntry - 64
            value = value or ((packed[longIndex + 1] and ((1L shl spill) - 1L)) shl (bitsPerEntry - spill))
        }

        if (value <= 0L) return null
        return (value - 1L).toInt()
    }

    private fun extractBiomeId(root: NbtCompound): String? {
        val sections =
            root.getList("sections").orElse(null)
                ?: root.getCompound("Level").flatMap { level -> level.getList("Sections") }.orElse(null)
                ?: return null

        var selectedSectionY: Int? = null
        var selectedBiome: String? = null

        for (i in 0 until sections.size) {
            val section = sections.getCompound(i).orElse(null) ?: continue
            val biomes = section.getCompound("biomes").orElse(null) ?: continue
            val palette = biomes.getList("palette").orElse(null) ?: continue
            val biome = palette.getString(0).orElse(null) ?: continue
            val sectionY = section.getByte("Y").orElse(0).toInt()

            if (selectedSectionY == null || sectionY > selectedSectionY) {
                selectedSectionY = sectionY
                selectedBiome = biome
            }
        }

        return selectedBiome
    }

    private fun classifyFailure(t: Throwable): ChunkScanUnresolvedReason {
        if (t is UnsupportedCompressionException) {
            return ChunkScanUnresolvedReason.FILE_COMPRESSION_UNSUPPORTED
        }

        if (isLockedFailure(t)) {
            return ChunkScanUnresolvedReason.FILE_LOCKED
        }

        if (t is EOFException || t is ZipException || t is IllegalArgumentException) {
            return ChunkScanUnresolvedReason.FILE_CORRUPT_OR_TRUNCATED
        }

        if (t is IOException || t is SecurityException) {
            return ChunkScanUnresolvedReason.FILE_IO_ERROR
        }

        return ChunkScanUnresolvedReason.FILE_CORRUPT_OR_TRUNCATED
    }

    private fun isLockedFailure(t: Throwable): Boolean {
        if (t is AccessDeniedException) return true
        if (t is FileSystemException) {
            val m = t.message?.lowercase() ?: ""
            if ("lock" in m || "used by another process" in m || "resource busy" in m) return true
        }
        val m = t.message?.lowercase() ?: ""
        return "file locked" in m || "locked by another process" in m
    }

    private fun flatten(plan: WorldDiscoveryPlan): List<FileChunkWorkUnit> {
        val out = ArrayList<FileChunkWorkUnit>()
        plan.worlds.forEach { world ->
            world.regions.forEach { region ->
                region.chunks.forEach { chunk ->
                    val chunkX = region.x * REGION_WIDTH + chunk.localX
                    val chunkZ = region.z * REGION_WIDTH + chunk.localZ
                    out.add(
                        FileChunkWorkUnit(
                            key =
                                ChunkKey(
                                    world = world.world,
                                    regionX = region.x,
                                    regionZ = region.z,
                                    chunkX = chunkX,
                                    chunkZ = chunkZ,
                                ),
                            regionFile = region.file,
                            chunk = chunk,
                        )
                    )
                }
            }
        }
        return out
    }

    private data class FileChunkWorkUnit(
        val key: ChunkKey,
        val regionFile: Path,
        val chunk: ChunkRef,
    )

    private data class ChunkReadResult(
        val unresolvedReason: ChunkScanUnresolvedReason?,
        val signals: ChunkSignals?,
        val isTransientFailure: Boolean,
    )

    private class UnsupportedCompressionException(compressionType: Int) :
        IllegalArgumentException("unsupported region chunk compression=$compressionType")

    private fun formatReasonCounts(counts: Map<ChunkScanUnresolvedReason, Int>): String {
        return ChunkScanUnresolvedReason.values()
            .joinToString(prefix = "[", postfix = "]", separator = ",") { reason ->
                "${reason.name}=${counts[reason] ?: 0}"
            }
    }

    private companion object {
        private const val REGION_WIDTH = 32
        private const val SECTOR_BYTES = 4096L
        private const val COMPRESSION_GZIP = 1
        private const val COMPRESSION_ZLIB = 2
        private const val COMPRESSION_NONE = 3
        private const val COMPRESSION_LZ4 = 4
    }
}
