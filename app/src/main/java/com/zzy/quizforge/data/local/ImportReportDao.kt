package com.zzy.quizforge.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zzy.quizforge.data.local.entity.ImportReportEntity
import com.zzy.quizforge.data.local.entity.ImportReportRecordEntity

@Dao
interface ImportReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReport(report: ImportReportEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecords(records: List<ImportReportRecordEntity>)

    @Query("SELECT * FROM import_reports WHERE bankId = :bankId ORDER BY finishedAt DESC LIMIT 1")
    suspend fun getLatestForBank(bankId: Long): ImportReportEntity?

    @Query("SELECT * FROM import_report_records WHERE reportId = :reportId ORDER BY id")
    suspend fun getRecords(reportId: String): List<ImportReportRecordEntity>

    @Query("DELETE FROM import_reports WHERE reportId = :reportId")
    suspend fun deleteReport(reportId: String)
}
