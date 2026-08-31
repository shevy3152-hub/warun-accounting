package com.warun.accounting.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.warun.accounting.data.local.DailyReport
import com.warun.accounting.data.local.DailyReportStatus
import com.warun.accounting.data.local.EvidenceRecord
import com.warun.accounting.data.local.EvidenceRecordState
import com.warun.accounting.data.local.FixedCostEvidenceLinkRecord
import com.warun.accounting.data.local.FixedCostReceiptApplicationRecord
import com.warun.accounting.data.local.ReceiptRecord
import com.warun.accounting.data.local.WarunDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupFixedCostV2InstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var database: WarunDatabase
    private lateinit var root: File
    private lateinit var liveEvidence: File

    @Before
    fun setUp() {
        root = File(context.cacheDir, "backup-fixed-cost-v2-${UUID.randomUUID()}").also { it.mkdirs() }
        liveEvidence = File(root, "live-evidence").also { it.mkdirs() }
        database = Room.databaseBuilder(
            context,
            WarunDatabase::class.java,
            "backup-fixed-cost-v2-${UUID.randomUUID()}.db"
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun v2RoundTripPreservesFixedCostRecordsAndOriginalBytes() = kotlinx.coroutines.runBlocking {
        val dao = database.warunDao()
        val report = report()
        val receipt = ReceiptRecord("receipt-v2", report.reportDate, report.reportDate, 1, "固定費", 12_345, 0, null, null, true, null, 1)
        dao.insertDailyReport(report)
        dao.insertReceipt(receipt)
        val application = FixedCostReceiptApplicationRecord("application-v2", receipt.id, report.id, "electricity", "現金", 2, 2)
        dao.insertFixedCostReceiptApplication(application)
        val source = listOf(
            "image/jpeg" to byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 0xff.toByte(), 0xd9.toByte()),
            "image/png" to byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 2),
            "application/pdf" to "%PDF-1.7\nfixed-cost\n%%EOF".toByteArray()
        )
        val evidence = source.mapIndexed { index, (mime, bytes) ->
            val id = "evidence-v2-$index"
            val file = File(liveEvidence, "evidence_$id.${extension(mime)}").apply { writeBytes(bytes) }
            dao.insertEvidenceRecord(EvidenceRecord(id, "capture-v2-$index", file.toURI().toString(), bytes.size.toLong(), sha256(bytes), EvidenceRecordState.Stored, 1, 1, 1, mime))
            dao.insertFixedCostEvidenceLink(FixedCostEvidenceLinkRecord(application.applicationId, id, index, 2))
            id to Triple(mime, bytes, file)
        }
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val dbFile = context.getDatabasePath("backup-fixed-cost-v2-${UUID.randomUUID()}.db")
        database.openHelper.writableDatabase.path?.let { File(it).copyTo(dbFile, true) }
        val inspection = BackupDatabaseInspector().inspect(dbFile)
        val evidenceFiles = evidence.associate { it.first to it.second.third }
        val manifest = BackupManifest(
            BackupContract.FormatVersion, 3, "0.2.2", 17,
            BackupArchiveEntry(BackupContract.DatabaseEntry, dbFile.length(), BackupArchive.sha256(dbFile)),
            evidence.map { (id, triple) ->
                BackupEvidenceEntry(
                    id,
                    triple.third.toURI().toString(),
                    BackupArchiveEntry(BackupContract.evidenceEntry(id, triple.first), triple.second.size.toLong(), sha256(triple.second)),
                    triple.first
                )
            },
            inspection.summary
        )
        val archive = ByteArrayOutputStream().also { out ->
            BackupArchive.write(out, manifest, dbFile, evidenceFiles)
        }.toByteArray()
        val extracted = BackupArchive.extractAndValidate(ByteArrayInputStream(archive), File(root, "candidate"))
        val restoredDb = File(root, "restored/warun-accounting.db").also { it.parentFile!!.mkdirs() }
        extracted.databaseFile.copyTo(restoredDb, true)
        val restored = Room.databaseBuilder(context, WarunDatabase::class.java, restoredDb.name)
            .createFromFile(restoredDb)
            .build()
        try {
            val restoredDao = restored.warunDao()
            assertEquals(12_345L, restoredDao.getDailyReport(report.id)!!.electricityExpense)
            assertTrue(restoredDao.getReceipt(receipt.id)!!.isConfirmed)
            assertEquals(application, restoredDao.getFixedCostReceiptApplication(application.applicationId))
            assertEquals(listOf(0, 1, 2), restoredDao.getFixedCostEvidenceLinks(application.applicationId).map { it.sortOrder })
            evidence.forEach { (id, triple) ->
                val restoredEvidence = restoredDao.getEvidenceRecord(id)!!
                assertEquals(triple.first, restoredEvidence.mediaType)
                assertEquals(triple.second.size.toLong(), restoredEvidence.byteSize)
                assertEquals(sha256(triple.second), restoredEvidence.sha256)
                assertEquals(triple.second.toList(), extracted.evidenceFiles[id]!!.readBytes().toList())
            }
        } finally {
            restored.close()
        }

        val v1Manifest = manifest.copy(
            formatVersion = BackupContract.LegacyFormatVersion,
            evidence = listOf(manifest.evidence.first()),
            summary = manifest.summary.copy(evidenceCount = 1)
        )
        val v1Archive = ByteArrayOutputStream().also { out ->
            BackupArchive.write(out, v1Manifest, dbFile, mapOf(evidence.first().first to evidence.first().second.third))
        }.toByteArray()
        assertEquals(BackupContract.LegacyFormatVersion, BackupArchive.extractAndValidate(ByteArrayInputStream(v1Archive), File(root, "v1-candidate")).manifest.formatVersion)

        val before = dbFile.readBytes().toList()
        val invalidManifest = manifest.copy(database = manifest.database.copy(sha256 = "0".repeat(64)))
        assertThrows(BackupException::class.java) {
            BackupArchive.write(ByteArrayOutputStream(), invalidManifest, dbFile, evidenceFiles)
        }
        assertEquals(before, dbFile.readBytes().toList())
    }

    private fun report() = DailyReport(
        id = "report-v2", reportDate = "2026-08-31", status = DailyReportStatus.Draft, authorName = null,
        cashSales = 0, cardSales = 0, qrSales = 0, accountsReceivableSales = 0, otherSales = 0,
        foodPurchases = 0, alcoholPurchases = 0, consumablesExpense = 0, utilitiesExpense = 0,
        electricityExpense = 12_345, gasExpense = 0, waterExpense = 0, communicationExpense = 0,
        rentExpense = 0, accountantFeeExpense = 0, miscellaneousExpense = 0, otherExpense = 0,
        openingCash = 0, actualClosingCash = 0, customerCount = 0, groupCount = 0, memo = null,
        createdAt = 1, updatedAt = 1, hasActualClosingCash = false
    )

    private fun extension(mediaType: String) = when (mediaType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        else -> "pdf"
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
