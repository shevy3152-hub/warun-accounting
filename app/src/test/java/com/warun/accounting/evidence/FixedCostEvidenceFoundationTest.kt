package com.warun.accounting.evidence

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FixedCostEvidenceFoundationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun jpegPngAndPdfArePromotedWithoutChangingShaOrSize() {
        val store = FixedCostEvidenceFileStore(folder.newFolder("pending")!!, folder.newFolder("stored")!!)
        listOf(
            "image/jpeg" to byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 0xff.toByte(), 0xd9.toByte()),
            "image/png" to byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1),
            "application/pdf" to "%PDF-1.7\nbody\n%%EOF".toByteArray()
        ).forEachIndexed { index, (mime, bytes) ->
            val id = "e$index"
            store.pendingFileFor(id, mime).apply { requireNotNull(parentFile).mkdirs(); writeBytes(bytes) }
            val result = store.promotePending(id, mime)
            assertEquals(bytes.size.toLong(), result.byteSize)
            assertEquals(sha256(bytes), result.sha256)
            assertEquals(bytes.toList(), File(result.finalPath).readBytes().toList())
        }
    }

    @Test
    fun unsupportedOrMismatchedContentIsRejected() {
        val store = FixedCostEvidenceFileStore(folder.newFolder("pending")!!, folder.newFolder("stored")!!)
        val id = "bad"
        store.pendingFileFor(id, "image/png").apply { requireNotNull(parentFile).mkdirs(); writeBytes("%PDF-".toByteArray()) }
        assertThrows(FixedCostEvidenceFileException::class.java) {
            store.promotePending(id, "image/png")
        }
    }

    @Test
    fun pdfWithTrailingWhitespaceIsAcceptedWithoutChangingItsBytes() {
        val store = FixedCostEvidenceFileStore(folder.newFolder("pending")!!, folder.newFolder("stored")!!)
        val bytes = "%PDF-1.7\nbody\n%%EOF\n\u0000\u0000".toByteArray()
        val pending = store.pendingFileFor("pdf-trailing", "application/pdf").apply {
            requireNotNull(parentFile).mkdirs()
            writeBytes(bytes)
        }
        val result = store.promotePending("pdf-trailing", "application/pdf")
        assertEquals(bytes.size.toLong(), result.byteSize)
        assertEquals(bytes.toList(), File(result.finalPath).readBytes().toList())
        assertFalse(pending.exists())
    }

    @Test
    fun exactlyMaxBytesIsAllowedAndOneByteOverIsRejected() {
        val store = FixedCostEvidenceFileStore(folder.newFolder("pending")!!, folder.newFolder("stored")!!)
        val exact = store.pendingFileFor("exact", "image/jpeg").apply {
            requireNotNull(parentFile).mkdirs()
            writeJpegOfSize(FixedCostEvidenceFileStore.MaxBytes)
        }
        val accepted = store.promotePending("exact", "image/jpeg")
        assertEquals(FixedCostEvidenceFileStore.MaxBytes, accepted.byteSize)
        assertEquals(FixedCostEvidenceFileStore.MaxBytes + 1L, writeJpegOfSize(FixedCostEvidenceFileStore.MaxBytes + 1L, store.pendingFileFor("over", "image/jpeg")))
        assertThrows(FixedCostEvidenceFileException::class.java) {
            store.promotePending("over", "image/jpeg")
        }
        assertEquals(false, exact.exists())
    }

    @Test
    fun journalIsIdempotentAndCorruptFilesAreQuarantined() {
        val journal = FixedCostFinalizationJournal(folder.newFolder("journal")!!)
        val evidence = FixedCostJournalEvidence("e1", "image/jpeg", "pending", "final", "", 0, 0, FixedCostFinalizationState.Prepared)
        val first = journal.prepare("app1", "receipt1", "report1", FixedCostType.Electricity, "現金", 100, listOf(evidence))
        assertEquals(first, journal.prepare("app1", "receipt1", "report1", FixedCostType.Electricity, "現金", 100, listOf(evidence)))
        journal.markPendingSaved("app1")
        journal.markEvidenceStored("app1", "e1", "final", "a".repeat(64), 10)
        journal.complete("app1")
        val corrupt = journal.journalFileFor("app2").apply { requireNotNull(parentFile).mkdirs(); writeText("broken") }
        assertThrows(FixedCostJournalCorruptException::class.java) { journal.find("app2") }
        assertEquals(false, corrupt.exists())
        assertEquals(1, journal.quarantineDirectory().listFiles().orEmpty().size)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun File.writeJpegOfSize(size: Long): Long = writeJpegOfSize(size, this)

    private fun writeJpegOfSize(size: Long, file: File): Long {
        require(size >= 5L)
        requireNotNull(file.parentFile).mkdirs()
        FileOutputStream(file).use { output ->
            output.write(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))
            var remaining = size - 5L
            val zeros = ByteArray(8192)
            while (remaining > 0L) {
                val count = minOf(remaining, zeros.size.toLong()).toInt()
                output.write(zeros, 0, count)
                remaining -= count
            }
            output.write(byteArrayOf(0xff.toByte(), 0xd9.toByte()))
        }
        return file.length()
    }
}
