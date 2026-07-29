package com.zzy.quizforge.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "import_reports",
    foreignKeys = [
        ForeignKey(
            entity = QuizBankEntity::class,
            parentColumns = ["id"],
            childColumns = ["bankId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bankId")],
)
data class ImportReportEntity(
    @PrimaryKey val reportId: String,
    val bankId: Long?,
    val fileName: String,
    val importMode: String,
    val startedAt: Long,
    val finishedAt: Long,
    val totalSourceBlocks: Int,
    val candidateQuestionCount: Int,
    val acceptedQuestionCount: Int,
    val rejectedQuestionCount: Int,
    val nonQuestionCount: Int,
    val unsupportedCount: Int,
    val imageCount: Int,
    val tableCount: Int,
    val usedApi: Boolean,
    val apiRequestCount: Int,
    val warningsJson: String,
    val ledgerComplete: Boolean,
)

@Entity(
    tableName = "import_report_records",
    foreignKeys = [
        ForeignKey(
            entity = ImportReportEntity::class,
            parentColumns = ["reportId"],
            childColumns = ["reportId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("reportId")],
)
data class ImportReportRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reportId: String,
    val sourceIdsJson: String,
    val originalQuestionNumber: Int?,
    val rawText: String,
    val status: String,
    val reasonCode: String?,
    val reasonMessage: String?,
    val createdQuestionIdsJson: String,
    val apiAttempted: Boolean,
)
