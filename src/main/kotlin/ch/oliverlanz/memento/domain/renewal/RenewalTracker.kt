
package ch.oliverlanz.memento.domain.renewal

object RenewalTracker {
    private val batches = mutableMapOf<String, RenewalBatch>()

    fun register(batch: RenewalBatch) {
        batches[batch.name] = batch
    }

    fun onChunkUnloaded(batchName: String, chunkKey: String) {
        batches[batchName]?.onChunkUnloaded(chunkKey)
    }

    fun onChunkRenewed(batchName: String, chunkKey: String) {
        batches[batchName]?.onChunkRenewed(chunkKey)
    }

    fun snapshot(): List<RenewalBatch> = batches.values.toList()
}
