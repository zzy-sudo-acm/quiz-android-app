package com.zzy.quizforge.util.document

/**
 * 解析过程中记录的结构性警告。
 *
 * 警告不中断解析，但用于标记数据质量问题和未来需修复的路径。
 */
data class DocumentWarning(
    val level: DocumentWarningLevel,
    val message: String,
)

enum class DocumentWarningLevel {
    /** 信息：非错误，但值得注意的结构特征。 */
    INFO,
    /** 警告：部分数据丢失或降级，如嵌套表格未展开。 */
    WARN,
}
