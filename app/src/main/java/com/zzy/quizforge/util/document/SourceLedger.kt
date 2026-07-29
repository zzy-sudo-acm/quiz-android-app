package com.zzy.quizforge.util.document

/**
 * Coverage ledger for every non-empty source block. Status precedence prevents a later generic
 * label from hiding a block that already produced (or failed to produce) a question.
 */
class SourceLedger(sourceBlocks: List<ImportSourceBlock>) {
    private val sourceById = sourceBlocks.filter { it.isNonEmpty }.associateBy { it.sourceId }
    private val statusById = linkedMapOf<String, SourceLedgerStatus>()

    fun mark(sourceIds: Collection<String>, status: SourceLedgerStatus) {
        for (sourceId in sourceIds) {
            if (sourceId !in sourceById) continue
            val current = statusById[sourceId]
            if (current == null || priority(status) > priority(current)) {
                statusById[sourceId] = status
            }
        }
    }

    fun status(sourceId: String): SourceLedgerStatus? = statusById[sourceId]

    fun uncoveredIds(): List<String> = sourceById.keys.filter { it !in statusById }

    fun isComplete(): Boolean = uncoveredIds().isEmpty()

    fun counts(): Map<SourceLedgerStatus, Int> =
        SourceLedgerStatus.entries.associateWith { expected -> statusById.values.count { it == expected } }

    fun source(sourceId: String): ImportSourceBlock? = sourceById[sourceId]

    fun allSources(): List<ImportSourceBlock> = sourceById.values.toList()

    private fun priority(status: SourceLedgerStatus): Int = when (status) {
        SourceLedgerStatus.NON_QUESTION_CONTENT -> 0
        SourceLedgerStatus.UNSUPPORTED_CONTENT -> 1
        SourceLedgerStatus.REJECTED_QUESTION -> 2
        SourceLedgerStatus.ACCEPTED_QUESTION -> 3
    }
}
