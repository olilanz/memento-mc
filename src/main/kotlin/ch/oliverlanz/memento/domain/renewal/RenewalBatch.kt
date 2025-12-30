
package ch.oliverlanz.memento.domain.renewal

class RenewalBatch(
    val name: String,
    private val chunks: Set<String>
) {
    enum class State { MARKED, BLOCKED, FREE, FORGETTING, RENEWED }

    var state: State = State.MARKED
        private set

    private val unloaded = mutableSetOf<String>()
    private val renewed = mutableSetOf<String>()

    fun onChunkUnloaded(chunk: String) {
        if (chunk !in chunks) return
        unloaded += chunk
        if (unloaded.size == chunks.size) state = State.FREE else state = State.BLOCKED
    }

    fun onChunkRenewed(chunk: String) {
        if (state != State.FORGETTING) return
        renewed += chunk
        if (renewed.size == chunks.size) state = State.RENEWED
    }
}
