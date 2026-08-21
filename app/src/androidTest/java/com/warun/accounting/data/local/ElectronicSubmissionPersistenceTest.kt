package com.warun.accounting.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warun.accounting.data.submission.OfflineElectronicSubmissionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElectronicSubmissionPersistenceTest {
    private lateinit var database: WarunDatabase
    private lateinit var repository: OfflineElectronicSubmissionRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WarunDatabase::class.java
        ).build()
        repository = OfflineElectronicSubmissionRepository(database.warunDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun sameMonthCreatesSeparateHistoryRecordsAndLimitedUpdatesPreserveGenerationFields() = runBlocking {
        val first = record(id = "record-1", generatedAt = 100L)
        val second = record(id = "record-2", generatedAt = 200L)

        repository.createRecord(first)
        repository.createRecord(second)

        assertEquals(listOf("record-2", "record-1"), repository.observeRecords().first().map { it.id })
        assertEquals(2, repository.observeRecordsByMonth("2026-06").first().size)
        assertEquals(true, repository.updateNote("record-2", "MyKomonへ手動提出済み", 250L))
        assertEquals(true, repository.markSubmitted("record-2", 300L, 300L))
        assertEquals(false, repository.markSubmitted("record-2", 400L, 400L))

        val updated = repository.getRecord("record-2")
        assertEquals(ElectronicSubmissionStatus.Submitted, updated?.status)
        assertEquals(300L, updated?.submittedAt)
        assertEquals("MyKomonへ手動提出済み", updated?.note)
        assertEquals(second.targetMonth, updated?.targetMonth)
        assertEquals(second.generatedAt, updated?.generatedAt)
        assertEquals(second.dailyReportFileName, updated?.dailyReportFileName)
        assertEquals(second.expenseDetailFileName, updated?.expenseDetailFileName)
        assertEquals(second.createdAt, updated?.createdAt)
        assertNull(repository.getRecord("missing"))
    }

    private fun record(id: String, generatedAt: Long) = ElectronicSubmissionRecord(
        id = id,
        targetMonth = "2026-06",
        generatedAt = generatedAt,
        dailyReportFileName = "2026年6月_日報.xlsx",
        expenseDetailFileName = "2026年6月_支出明細.xlsx",
        receiptPdfFileName = null,
        status = ElectronicSubmissionStatus.NotSubmitted,
        submittedAt = null,
        note = null,
        createdAt = generatedAt,
        updatedAt = generatedAt
    )
}
