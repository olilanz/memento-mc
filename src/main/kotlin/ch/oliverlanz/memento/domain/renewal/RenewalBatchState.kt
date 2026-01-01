package ch.oliverlanz.memento.domain.renewal

enum class RenewalBatchState {

    /** Batch exists, waiting for all chunks to be unloaded */
    WAITING_FOR_UNLOAD,

    /** All chunks observed unloaded (transitional checkpoint) */
    UNLOAD_COMPLETED,

    /** Batch is waiting for renewal */
    WAITING_FOR_RENEWAL,

    /** All chunks have been renewed */
    RENEWAL_COMPLETE
}
