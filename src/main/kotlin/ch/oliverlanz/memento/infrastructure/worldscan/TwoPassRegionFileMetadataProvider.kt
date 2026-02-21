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
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtSizeTracker
import net.minecraft.world.storage.ChunkCompressionFormat

/**
 * File-primary metadata provider with locked two-pass policy.
 *
 * Behavior:
 * - Pass 1: attempts all discovered chunk work units from [WorldDiscoveryPlan]
 * - Pass 2: attempts only transient failures ([ChunkScanUnresolvedReason.FILE_LOCKED],
 *   [ChunkScanUnresolvedReason.FILE_IO_ERROR])
 * - Terminal completion after pass 2 (or pass 1 if no transient failures)
 *
 * Threading:
 * - All file IO and NBT decode runs off-thread via global async exclusion gate.
 * - World map mutation stays on tick thread via [ScanMetadataIngestionPort].
 */
class TwoPassRegionFileMetadataProvider(
    private val ingestionPort: ScanMetadataIngestionPort,
    private val delayedReconciliationMillis: Long = DEFAULT_DELAYED_RECONCILIATION_MILLIS,
) : FileMetadataProvider {

    private val lifecycle = AtomicReference(FileMetadataProviderLifecycle.IDLE)
    private val firstPassProcessed = AtomicInteger(0)
    private val secondPassProcessed = AtomicInteger(0)
    private val emittedFacts = AtomicInteger(0)

    @Volatile
    private var firstPassTotal: Int = 0

    @Volatile
    private var secondPassTotal: Int = 0

    @Volatile
    private var runFuture: Future<*>? = null

    @Synchronized
    override fun start(plan: WorldDiscoveryPlan, scanTick: Long): Boolean {
        if (lifecycle.get() == FileMetadataProviderLifecycle.RUNNING) return false

        val work = flatten(plan)
        firstPassTotal = work.size
        secondPassTotal = 0
        firstPassProcessed.set(0)
        secondPassProcessed.set(0)
        emittedFacts.set(0)
        lifecycle.set(FileMetadataProviderLifecycle.RUNNING)

        val submit = GlobalAsyncExclusionGate.submitIfIdle(
            concept = MementoConcept.SCANNER,
            owner = "scanner-file-provider",
        ) {
            Callable {
                runCatching {
                    runTwoPass(work, scanTick)
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
            firstPassTotal = firstPassTotal,
            firstPassProcessed = firstPassProcessed.get(),
            secondPassTotal = secondPassTotal,
            secondPassProcessed = secondPassProcessed.get(),
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

    private fun runTwoPass(work: List<FileChunkWorkUnit>, scanTick: Long) {
        val transientRetry = ArrayList<FileChunkWorkUnit>()
        val pass1UnresolvedByReason = linkedMapOf<ChunkScanUnresolvedReason, Int>()
        var pass1Success = 0
        var pass1Unresolved = 0
        var pass1TransientCandidates = 0

        // Pass 1: all discovered chunks.
        for (unit in work) {
            if (Thread.currentThread().isInterrupted) return

            val result = readChunkMetadata(unit)
            emitFact(unit.key, result, scanTick)
            firstPassProcessed.incrementAndGet()

            if (result.unresolvedReason == null) {
                pass1Success++
            } else {
                pass1Unresolved++
                pass1UnresolvedByReason[result.unresolvedReason] =
                    (pass1UnresolvedByReason[result.unresolvedReason] ?: 0) + 1
            }

            if (result.isTransientFailure) {
                transientRetry.add(unit)
                pass1TransientCandidates++
            }
        }

        MementoLog.info(
            MementoConcept.SCANNER,
            "scan file-pass1 summary processed={}/{} success={} unresolved={} transientRetryCandidates={} unresolvedByReason={}",
            firstPassProcessed.get(),
            firstPassTotal,
            pass1Success,
            pass1Unresolved,
            pass1TransientCandidates,
            formatReasonCounts(pass1UnresolvedByReason),
        )

        // Pass 2: delayed reconciliation for transient failures only.
        secondPassTotal = transientRetry.size
        if (transientRetry.isEmpty()) {
            MementoLog.info(
                MementoConcept.SCANNER,
                "scan file-pass2 summary skipped processed=0/0 resolvedFromTransient=0 stillUnresolved=0 stillTransient=0 unresolvedByReason=[]"
            )
            MementoLog.info(
                MementoConcept.SCANNER,
                "scan file-two-pass summary pass1Processed={} pass2Processed={} transientCandidates={} transientResolved={} transientRemaining={} emittedFacts={}",
                firstPassProcessed.get(),
                secondPassProcessed.get(),
                pass1TransientCandidates,
                0,
                0,
                emittedFacts.get(),
            )
            return
        }

        if (delayedReconciliationMillis > 0L) {
            Thread.sleep(delayedReconciliationMillis)
        }

        val pass2UnresolvedByReason = linkedMapOf<ChunkScanUnresolvedReason, Int>()
        var pass2ResolvedFromTransient = 0
        var pass2StillUnresolved = 0
        var pass2StillTransient = 0

        for (unit in transientRetry) {
            if (Thread.currentThread().isInterrupted) return

            val result = readChunkMetadata(unit)
            emitFact(unit.key, result, scanTick)
            secondPassProcessed.incrementAndGet()

            if (result.unresolvedReason == null) {
                pass2ResolvedFromTransient++
            } else {
                pass2StillUnresolved++
                pass2UnresolvedByReason[result.unresolvedReason] =
                    (pass2UnresolvedByReason[result.unresolvedReason] ?: 0) + 1
                if (result.isTransientFailure) pass2StillTransient++
            }
        }

        MementoLog.info(
            MementoConcept.SCANNER,
            "scan file-pass2 summary processed={}/{} resolvedFromTransient={} stillUnresolved={} stillTransient={} unresolvedByReason={}",
            secondPassProcessed.get(),
            secondPassTotal,
            pass2ResolvedFromTransient,
            pass2StillUnresolved,
            pass2StillTransient,
            formatReasonCounts(pass2UnresolvedByReason),
        )

        MementoLog.info(
            MementoConcept.SCANNER,
            "scan file-two-pass summary pass1Processed={} pass2Processed={} transientCandidates={} transientResolved={} transientRemaining={} emittedFacts={}",
            firstPassProcessed.get(),
            secondPassProcessed.get(),
            pass1TransientCandidates,
            pass2ResolvedFromTransient,
            pass2StillUnresolved,
            emittedFacts.get(),
        )
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

            ChunkReadResult(
                unresolvedReason = null,
                signals =
                    ChunkSignals(
                        inhabitedTimeTicks = inhabited,
                        lastUpdateTicks = lastUpdate,
                        surfaceY = null,
                        biomeId = null,
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
        private const val DEFAULT_DELAYED_RECONCILIATION_MILLIS = 250L
    }
}
