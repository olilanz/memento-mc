package ch.oliverlanz.memento.domain.renewal

import net.minecraft.registry.RegistryKey
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import org.slf4j.LoggerFactory

/**
 * New-generation RenewalTracker.
 *
 * OBSERVABILITY ONLY.
 *
 * This tracker mirrors legacy renewal behavior and logs:
 * - lifecycle triggers
 * - batch creation / removal
 * - state transitions
 *
 * It does NOT:
 * - control chunk forgetting
 * - advance lifecycle decisions
 * - persist state
 */
object RenewalTracker {

    private val log = LoggerFactory.getLogger("memento")

    private val batches: MutableMap<String, RenewalBatchMirror> = mutableMapOf()

    init {
        log.info("[RENEWAL-TRACKER] initialized (empty)")
    }

    // ---------------------------------------------------------------------
    // Lifecycle hooks (observability only)
    // ---------------------------------------------------------------------

    fun advanceTime(tick: Long) {
        log.info("[RENEWAL-TRACKER] advanceTime observed (tick=$tick)")
    }

    fun onChunkLoaded(
        dimension: RegistryKey<World>,
        chunk: ChunkPos
    ) {
        log.info(
            "[RENEWAL-TRACKER] trigger observed: CHUNK_LOADED dim=${dimension.value} chunk=(${chunk.x},${chunk.z})"
        )
    }

    fun onChunkUnloaded(
        dimension: RegistryKey<World>,
        chunk: ChunkPos
    ) {
        log.info(
            "[RENEWAL-TRACKER] trigger observed: CHUNK_UNLOAD dim=${dimension.value} chunk=(${chunk.x},${chunk.z})"
        )
    }

    fun onServerStart() {
        log.info("[RENEWAL-TRACKER] trigger observed: SERVER_START")
    }

    // ---------------------------------------------------------------------
    // Batch mirroring (observability only)
    // ---------------------------------------------------------------------

    fun onStoneMatured(
        name: String,
        dimension: RegistryKey<World>,
        chunks: Set<ChunkPos>
    ) {
        log.info(
            buildString {
                append("[RENEWAL-TRACKER] create batch '$name'\n")
                append("  trigger=STONE_MATURED\n")
                append("  dim=${dimension.value}\n")
                append("  chunks=${chunks.size}\n")
                append("  initialState=MARKED")
            }
        )

        batches[name] = RenewalBatchMirror(
            name = name,
            dimension = dimension,
            chunks = chunks,
            state = RenewalState.MARKED
        )
    }

    fun onBatchStateTransition(
        name: String,
        from: RenewalState,
        to: RenewalState,
        detail: String? = null
    ) {
        val suffix = detail?.let { " ($it)" } ?: ""

        log.info(
            "[RENEWAL-TRACKER] batch '$name' $from -> $to$suffix"
        )

        batches[name]?.state = to
    }

    fun onBatchCompleted(name: String) {
        log.info(
            "[RENEWAL-TRACKER] remove batch '$name' (reason=COMPLETED)"
        )
        batches.remove(name)
    }

    // ---------------------------------------------------------------------
    // Inspection (debug / future commands)
    // ---------------------------------------------------------------------

    fun listBatches(): Collection<RenewalBatchMirror> {
        log.info("[RENEWAL-TRACKER] list -> ${batches.size} batch(es)")
        return batches.values.toList()
    }

    // ---------------------------------------------------------------------

    data class RenewalBatchMirror(
        val name: String,
        val dimension: RegistryKey<World>,
        val chunks: Set<ChunkPos>,
        var state: RenewalState
    )

    /**
     * MUST match ARCHITECTURE.md exactly.
     */
    enum class RenewalState {
        MARKED,
        BLOCKED,
        FREE,
        FORGETTING,
        RENEWED
    }

    fun onChunkLoaded(chunk: ChunkPos) {
        log.info(
            "[RENEWAL-TRACKER] trigger observed: CHUNK_LOADED chunk=(${chunk.x},${chunk.z})"
        )
    }

    fun onChunkUnloaded(chunk: ChunkPos) {
        log.info(
            "[RENEWAL-TRACKER] trigger observed: CHUNK_UNLOAD chunk=(${chunk.x},${chunk.z})"
        )
    }
}
