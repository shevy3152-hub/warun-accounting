package com.warun.accounting.evidence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.data.local.WarunDatabase
import com.warun.accounting.data.fixedcost.FixedCostSaveResult
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FixedCostEvidenceSaveInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var database: WarunDatabase
    private lateinit var dao: com.warun.accounting.data.local.WarunDao
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(context.cacheDir, "fixed-cost-instrumented-${UUID.randomUUID()}").also { it.mkdirs() }
        val databaseFile = context.getDatabasePath("fixed-cost-instrumented-${UUID.randomUUID()}.db")
        database = Room.databaseBuilder(context, WarunDatabase::class.java, databaseFile.name).build()
        dao = database.warunDao()
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun realResolverPersistsThreeMediaTypesAndAppliesOnce() = runBlocking {
        val fixture = fixture()
        val bytes = listOf(
            "image/jpeg; charset=binary" to byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 0xff.toByte(), 0xd9.toByte()),
            "image/png" to byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 2),
            "application/pdf" to "%PDF-1.7\nfixed-cost\n%%EOF".toByteArray()
        )
        val files = bytes.mapIndexed { index, (_, content) -> File(root, "input$index").apply { writeBytes(content) } }
        val sources = bytes.mapIndexed { index, (mime, _) ->
            FixedCostEvidenceSource(android.net.Uri.fromFile(files[index]), mime, index)
        }
        val store = FixedCostEvidenceFileStore(File(root, "pending"), File(root, "stored"))
        val journal = FixedCostFinalizationJournal(File(root, "journal"))
        FixedCostEvidenceSaveCoordinator(dao, store, journal, NoOpFixedCostFailureInjector)
            .save(context.contentResolver, FixedCostEvidenceSaveRequest(
                fixture.receipt.id, fixture.report.id, FixedCostType.Electricity, "現金", 7_000L, sources
            ))

        val application = dao.getFixedCostReceiptApplicationByReceipt(fixture.receipt.id)
        assertNotNull(application)
        assertEquals("現金", application!!.paymentMethod)
        assertEquals(3, dao.getFixedCostEvidenceLinks(application.applicationId).size)
        assertEquals(listOf(0, 1, 2), dao.getFixedCostEvidenceLinks(application.applicationId).map { it.sortOrder })
        assertEquals(7_000L, dao.getDailyReport(fixture.report.id)!!.electricityExpense)
        assertTrue(dao.getReceipt(fixture.receipt.id)!!.isConfirmed)
        assertEquals(0L, count("expense_records"))
        assertEquals(0, journal.loadAll().entries.size)
        files.forEachIndexed { index, file ->
            val link = dao.getFixedCostEvidenceLinks(application.applicationId)[index]
            val evidence = dao.getEvidenceRecord(link.evidenceId)!!
            assertEquals(bytes[index].second.size.toLong(), evidence.byteSize)
            assertEquals(sha256(bytes[index].second), evidence.sha256)
            assertEquals(bytes[index].first.substringBefore(';'), evidence.mediaType)
            assertEquals(bytes[index].second.toList(), File(java.net.URI(evidence.storedUri)).readBytes().toList())
            assertFalse(store.pendingFileFor(link.evidenceId, evidence.mediaType).exists())
        }
    }

    @Test
    fun invalidSignatureAndMissingReportDoNotConfirmReceipt() = runBlocking {
        val fixture = fixture()
        val input = File(root, "invalid").apply { writeText("%PDF-not-png") }
        val store = FixedCostEvidenceFileStore(File(root, "pending"), File(root, "stored"))
        val journal = FixedCostFinalizationJournal(File(root, "journal"))
        val badRequest = FixedCostEvidenceSaveRequest(
            fixture.receipt.id, fixture.report.id, FixedCostType.Water, "現金", 100L,
            listOf(FixedCostEvidenceSource(android.net.Uri.fromFile(input), "image/png", 0))
        )
        assertTrue(runCatching {
            FixedCostEvidenceSaveCoordinator(dao, store, journal, NoOpFixedCostFailureInjector)
                .save(context.contentResolver, badRequest)
        }.isFailure)
        assertFalse(dao.getReceipt(fixture.receipt.id)!!.isConfirmed)
        assertEquals(0L, count("evidence_records"))

        val missingRequest = badRequest.copy(
            dailyReportId = "missing-report",
            sources = listOf(FixedCostEvidenceSource(android.net.Uri.fromFile(input), "application/pdf", 0))
        )
        assertTrue(runCatching {
            FixedCostEvidenceSaveCoordinator(dao, store, journal, NoOpFixedCostFailureInjector)
                .save(context.contentResolver, missingRequest)
        }.isFailure)
        assertFalse(dao.getReceipt(fixture.receipt.id)!!.isConfirmed)
    }

    @Test
    fun contentResolverStreamFailureLeavesNoPartialEvidenceAndJournalCanRetry() = runBlocking {
        val fixture = fixture("stream-receipt", "stream-report")
        val input = File(root, "stream-input")
        val store = FixedCostEvidenceFileStore(
            File(root, "stream-pending"),
            File(root, "stream-stored"),
            inputOpener = FixedCostEvidenceInputOpener { _, _ ->
                object : InputStream() {
                    private var sent = false
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (sent) throw IOException("injected stream failure")
                        buffer[offset] = 0xff.toByte()
                        buffer[offset + 1] = 0xd8.toByte()
                        buffer[offset + 2] = 0xff.toByte()
                        sent = true
                        return 3
                    }

                    override fun read(): Int = throw IOException("injected stream failure")
                }
            }
        )
        val journal = FixedCostFinalizationJournal(File(root, "stream-journal"))
        val request = FixedCostEvidenceSaveRequest(
            fixture.receipt.id, fixture.report.id, FixedCostType.Gas, "銀行振込", 7_000L,
            listOf(FixedCostEvidenceSource(android.net.Uri.fromFile(input), "image/jpeg", 0))
        )
        assertTrue(runCatching {
            FixedCostEvidenceSaveCoordinator(dao, store, journal, NoOpFixedCostFailureInjector)
                .save(context.contentResolver, request)
        }.isFailure)
        assertFalse(store.pendingFileFor("missing", "image/jpeg").exists())
        assertEquals(0, File(root, "stream-pending").listFiles().orEmpty().size)
        assertEquals(0, File(root, "stream-stored").listFiles().orEmpty().size)
        assertEquals(0L, count("evidence_records"))
        assertEquals(0L, count("fixed_cost_receipt_applications"))
        assertEquals(0L, count("fixed_cost_evidence_links"))
        assertEquals(0L, dao.getDailyReport(fixture.report.id)!!.gasExpense)
        assertFalse(dao.getReceipt(fixture.receipt.id)!!.isConfirmed)
        val entry = journal.loadAll().entries.single()
        assertEquals(FixedCostFinalizationState.Prepared, entry.state)
        FixedCostEvidenceSaveCoordinator(dao, store, journal, NoOpFixedCostFailureInjector).recover()
        assertEquals(0L, count("evidence_records"))
        assertEquals(FixedCostFinalizationState.Prepared, journal.loadAll().entries.single().state)
    }

    @Test
    fun failureAfterStoredAndAfterDatabaseAreRecoverableAndIdempotent() = runBlocking {
        listOf(FixedCostFailurePoint.FirstEvidenceStored, FixedCostFailurePoint.AllEvidenceStored, FixedCostFailurePoint.DatabaseApplied)
            .forEachIndexed { index, point ->
                val fixture = fixture("r$index", "d$index")
                val input = File(root, "recover$index").apply {
                    writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), index.toByte(), 0xff.toByte(), 0xd9.toByte()))
                }
                val store = FixedCostEvidenceFileStore(File(root, "pending$index"), File(root, "stored$index"))
                val journal = FixedCostFinalizationJournal(File(root, "journal$index"))
                val injector = FixedCostFailureInjector { actual -> if (actual == point) error("injected-$point") }
                val request = FixedCostEvidenceSaveRequest(
                    fixture.receipt.id, fixture.report.id, FixedCostType.Communication, "現金", 7_000L,
                    listOf(FixedCostEvidenceSource(android.net.Uri.fromFile(input), "image/jpeg", 0))
                )
                assertTrue(runCatching { FixedCostEvidenceSaveCoordinator(dao, store, journal, injector).save(context.contentResolver, request) }.isFailure)
                FixedCostEvidenceSaveCoordinator(dao, store, journal, NoOpFixedCostFailureInjector).recover()
                FixedCostEvidenceSaveCoordinator(dao, store, journal, NoOpFixedCostFailureInjector).recover()
                assertTrue(dao.getReceipt(fixture.receipt.id)!!.isConfirmed)
                assertEquals(7_000L, dao.getDailyReport(fixture.report.id)!!.communicationExpense)
                assertEquals(1, dao.getFixedCostEvidenceLinks(dao.getFixedCostReceiptApplicationByReceipt(fixture.receipt.id)!!.applicationId).size)
                assertEquals(0, journal.loadAll().entries.size)
            }
    }

    @Test
    fun sameAmountSkipsDailyReportUpdateAndOnlyAddsEvidence() = runBlocking {
        val fixture = fixture("same-receipt", "same-report")
        dao.updateFixedCostAmountIfEmpty(fixture.report.id, FixedCostType.Electricity, 7_000L, 2)
        val before = dao.getDailyReport(fixture.report.id)!!
        val input = File(root, "same-input").apply {
            writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 0xff.toByte(), 0xd9.toByte()))
        }
        val result = FixedCostEvidenceSaveCoordinator(
            dao, FixedCostEvidenceFileStore(File(root, "same-pending"), File(root, "same-stored")),
            FixedCostFinalizationJournal(File(root, "same-journal")), NoOpFixedCostFailureInjector
        ).saveResult(context.contentResolver, FixedCostEvidenceSaveRequest(
            fixture.receipt.id, fixture.report.id, FixedCostType.Electricity, "現金", 7_000L,
            listOf(FixedCostEvidenceSource(android.net.Uri.fromFile(input), "image/jpeg", 0))
        ))
        assertEquals(FixedCostSaveResult.Success, result)
        assertEquals(before, dao.getDailyReport(fixture.report.id))
        assertTrue(dao.getReceipt(fixture.receipt.id)!!.isConfirmed)
        assertEquals(1L, count("fixed_cost_receipt_applications"))
        assertEquals(1L, count("fixed_cost_evidence_links"))
    }

    @Test
    fun conflictIsRejectedBeforeJournalOrFileCreation() = runBlocking {
        val fixture = fixture("conflict-receipt", "conflict-report")
        dao.updateFixedCostAmountIfEmpty(fixture.report.id, FixedCostType.Water, 6_000L, 2)
        val input = File(root, "conflict-input").apply {
            writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 0xff.toByte(), 0xd9.toByte()))
        }
        val store = FixedCostEvidenceFileStore(File(root, "conflict-pending"), File(root, "conflict-stored"))
        val journal = FixedCostFinalizationJournal(File(root, "conflict-journal"))
        val result = FixedCostEvidenceSaveCoordinator(dao, store, journal, NoOpFixedCostFailureInjector)
            .saveResult(context.contentResolver, FixedCostEvidenceSaveRequest(
                fixture.receipt.id, fixture.report.id, FixedCostType.Water, "現金", 7_000L,
                listOf(FixedCostEvidenceSource(android.net.Uri.fromFile(input), "image/jpeg", 0))
            ))
        assertEquals(FixedCostSaveResult.AmountConflict, result)
        assertFalse(dao.getReceipt(fixture.receipt.id)!!.isConfirmed)
        assertEquals(0L, count("fixed_cost_receipt_applications"))
        assertEquals(0L, count("fixed_cost_evidence_links"))
        assertEquals(0L, count("evidence_records"))
        assertTrue(journal.loadAll().entries.isEmpty())
        assertTrue(store.pendingFileFor("not-created", "image/jpeg").parentFile!!.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun gasUsesBankTransferPaymentMethod() = runBlocking {
        val fixture = fixture("gas-receipt", "gas-report")
        val input = File(root, "gas-input").apply {
            writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 0xff.toByte(), 0xd9.toByte()))
        }
        val result = FixedCostEvidenceSaveCoordinator(
            dao, FixedCostEvidenceFileStore(File(root, "gas-pending"), File(root, "gas-stored")),
            FixedCostFinalizationJournal(File(root, "gas-journal")), NoOpFixedCostFailureInjector
        ).saveResult(context.contentResolver, FixedCostEvidenceSaveRequest(
            fixture.receipt.id, fixture.report.id, FixedCostType.Gas, "銀行振込", 7_000L,
            listOf(FixedCostEvidenceSource(android.net.Uri.fromFile(input), "image/jpeg", 0))
        ))
        assertEquals(FixedCostSaveResult.Success, result)
        assertEquals("銀行振込", dao.getFixedCostReceiptApplicationByReceipt(fixture.receipt.id)!!.paymentMethod)
        assertEquals(7_000L, dao.getDailyReport(fixture.report.id)!!.gasExpense)
    }

    private data class Fixture(val report: DailyReport, val receipt: ReceiptRecord)

    private suspend fun fixture(receiptId: String = "receipt", reportId: String = "report"): Fixture {
        val report = DailyReport(
            id = reportId, reportDate = "2026-08-31", status = DailyReportStatus.Draft, authorName = null,
            cashSales = 0, cardSales = 0, qrSales = 0, accountsReceivableSales = 0, otherSales = 0,
            foodPurchases = 0, alcoholPurchases = 0, consumablesExpense = 0, utilitiesExpense = 0,
            electricityExpense = 0, gasExpense = 0, waterExpense = 0, communicationExpense = 0,
            rentExpense = 0, accountantFeeExpense = 0, miscellaneousExpense = 0, otherExpense = 0,
            openingCash = 0, actualClosingCash = 0, customerCount = 0, groupCount = 0, memo = null,
            createdAt = 1, updatedAt = 1, hasActualClosingCash = false
        )
        val receipt = ReceiptRecord(receiptId, "2026-08-31", "2026-08-31", 1, "fixed-cost", 7_000, 0, null, null, false, null, 1)
        dao.insertDailyReport(report)
        dao.insertReceipt(receipt)
        return Fixture(report, receipt)
    }

    private fun count(table: String): Long = database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst(); it.getLong(0)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
