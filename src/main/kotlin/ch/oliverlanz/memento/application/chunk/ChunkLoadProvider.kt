package ch.oliverlanz.memento.application.chunk

/**
 * Passive provider of chunk-load intent.
 *
 * The [ChunkLoadDriver] is the only active component: it calls providers when it
 * needs more work. Providers must not push work into the driver.
 */
fun interface ChunkLoadProvider {
    /**
     * @return the next chunk load request to attempt, or null if no work is currently available.
     */
    fun nextChunkLoad(): ChunkLoadRequest?
}
