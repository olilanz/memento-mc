package ch.oliverlanz.memento.infrastructure.chunk

/**
 * Declarative source of chunk-load intent.
 *
 * Providers are passive:
 * - They expose the *current* set of desired chunk loads.
 * - They must not talk to engine mechanics (tickets, Fabric events, etc.).
 *
 * The [ChunkLoadDriver] is the only active component:
 * - It decides when/if to issue tickets (politeness + throttling).
 * - It chooses which provider to serve first (precedence).
 */
interface ChunkLoadProvider {
    /** Stable identifier used for logging and diagnosis. */
    val name: String

    /**
     * @return a (potentially empty) sequence of desired chunk loads.
     *
     * Important:
     * - The driver may call this frequently; it must be fast.
     * - Returning a [Sequence] allows lazy evaluation and avoids allocating large collections.
     * - The driver does not assume that any returned request will ever be fulfilled.
     */
    fun desiredChunkLoads(): Sequence<ChunkLoadRequest>
}
