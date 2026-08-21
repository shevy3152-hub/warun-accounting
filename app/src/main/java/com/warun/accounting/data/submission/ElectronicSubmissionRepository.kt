package com.warun.accounting.data.submission

import com.warun.accounting.data.local.ElectronicSubmissionRecord
import com.warun.accounting.data.local.MonthlySubmission
import com.warun.accounting.data.local.WarunDao
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

interface ElectronicSubmissionRepository {
    fun observeRecords(): Flow<List<ElectronicSubmissionRecord>>

    fun observeRecordsByMonth(targetMonth: String): Flow<List<ElectronicSubmissionRecord>>

    fun observePaperRecords(): Flow<List<MonthlySubmission>>

    suspend fun getRecord(id: String): ElectronicSubmissionRecord?

    suspend fun createRecord(record: ElectronicSubmissionRecord)

    suspend fun updateNote(id: String, note: String?, updatedAt: Long): Boolean

    suspend fun markSubmitted(id: String, submittedAt: Long, updatedAt: Long): Boolean
}

class OfflineElectronicSubmissionRepository @Inject constructor(
    private val dao: WarunDao
) : ElectronicSubmissionRepository {
    override fun observeRecords(): Flow<List<ElectronicSubmissionRecord>> =
        dao.observeElectronicSubmissionRecords()

    override fun observeRecordsByMonth(
        targetMonth: String
    ): Flow<List<ElectronicSubmissionRecord>> =
        dao.observeElectronicSubmissionRecordsByMonth(targetMonth)

    override fun observePaperRecords(): Flow<List<MonthlySubmission>> =
        dao.observeMonthlySubmissions()

    override suspend fun getRecord(id: String): ElectronicSubmissionRecord? =
        dao.getElectronicSubmissionRecord(id)

    override suspend fun createRecord(record: ElectronicSubmissionRecord) =
        dao.insertElectronicSubmissionRecord(record)

    override suspend fun updateNote(id: String, note: String?, updatedAt: Long): Boolean =
        dao.updateElectronicSubmissionNote(id, note, updatedAt) == 1

    override suspend fun markSubmitted(
        id: String,
        submittedAt: Long,
        updatedAt: Long
    ): Boolean = dao.markElectronicSubmissionSubmitted(id, submittedAt, updatedAt) == 1
}
