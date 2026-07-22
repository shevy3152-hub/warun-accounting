package com.warun.accounting.evidence

import com.warun.accounting.camera.ReceiptImageStore
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EvidenceFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun promotionVerifiesMovesAndCanBeResolvedAfterStoreRecreation() {
        val fixture = fixture()
        val bytes = jpegBytes(1, 2, 3)
        fixture.writePending("capture-1", bytes)

        val reference = fixture.store.promotePendingImage("capture-1")

        assertFalse(fixture.pendingFile("capture-1").exists())
        assertTrue(fixture.storedFile("capture-1").isFile)
        assertArrayEquals(bytes, fixture.storedFile("capture-1").readBytes())
        assertEquals(bytes.size.toLong(), reference.byteSize)
        assertTrue(reference.sha256.isNotBlank())

        val recreatedStore = EvidenceFileStore(fixture.pendingDirectory, fixture.storedDirectory)
        assertEquals(reference, recreatedStore.resolve("capture-1"))
        assertEquals(listOf(reference), recreatedStore.listReferences())
    }

    @Test
    fun retryAfterSuccessfulPromotionIsIdempotentAndDoesNotDuplicate() {
        val fixture = fixture()
        fixture.writePending("capture-retry", jpegBytes(1, 2, 3))
        val first = fixture.store.promotePendingImage("capture-retry")

        val retried = EvidenceFileStore(fixture.pendingDirectory, fixture.storedDirectory)
            .promotePendingImage("capture-retry")

        assertEquals(first, retried)
        assertEquals(1, fixture.formalFiles().size)
    }

    @Test
    fun matchingPendingOnRetryIsConsumedWithoutCreatingDuplicate() {
        val fixture = fixture()
        val bytes = jpegBytes(4, 5, 6)
        fixture.writePending("capture-same", bytes)
        val first = fixture.store.promotePendingImage("capture-same")
        fixture.writePending("capture-same", bytes)

        val retried = fixture.store.promotePendingImage("capture-same")

        assertEquals(first, retried)
        assertFalse(fixture.pendingFile("capture-same").exists())
        assertEquals(1, fixture.formalFiles().size)
    }

    @Test
    fun differentPendingForExistingEvidenceIsRejectedAndBothFilesRemain() {
        val fixture = fixture()
        fixture.writePending("capture-conflict", jpegBytes(1, 2, 3))
        fixture.store.promotePendingImage("capture-conflict")
        fixture.writePending("capture-conflict", jpegBytes(9, 8, 7))

        assertThrows(EvidenceFileStoreException::class.java) {
            fixture.store.promotePendingImage("capture-conflict")
        }

        assertTrue(fixture.pendingFile("capture-conflict").exists())
        assertTrue(fixture.storedFile("capture-conflict").exists())
        assertEquals(1, fixture.formalFiles().size)
    }

    @Test
    fun zeroBytePendingIsRejectedAndKept() {
        val fixture = fixture()
        fixture.writePending("capture-empty", byteArrayOf())

        assertThrows(EvidenceFileStoreException::class.java) {
            fixture.store.promotePendingImage("capture-empty")
        }

        assertTrue(fixture.pendingFile("capture-empty").exists())
        assertNull(fixture.store.resolve("capture-empty"))
    }

    @Test
    fun malformedJpegIsRejectedAndKept() {
        val fixture = fixture()
        fixture.writePending("capture-corrupt", byteArrayOf(1, 2, 3, 4))

        assertThrows(EvidenceFileStoreException::class.java) {
            fixture.store.promotePendingImage("capture-corrupt")
        }

        assertTrue(fixture.pendingFile("capture-corrupt").exists())
        assertNull(fixture.store.resolve("capture-corrupt"))
    }

    @Test
    fun storageFailureKeepsPendingAndDoesNotCreateFormalFile() {
        val root = temporaryFolder.newFolder("storage-failure")
        val pendingDirectory = File(root, "pending")
        val storedPath = File(root, "stored").apply { writeText("not-a-directory") }
        val pendingStore = ReceiptImageStore(pendingDirectory)
        pendingStore.prepareFile("capture-failure").writeBytes(jpegBytes(1, 2, 3))
        val store = EvidenceFileStore(pendingDirectory, storedPath)

        assertThrows(EvidenceFileStoreException::class.java) {
            store.promotePendingImage("capture-failure")
        }

        assertTrue(pendingStore.fileFor("capture-failure").exists())
        assertFalse(File(storedPath, "evidence_capture-failure.jpg").exists())
    }

    @Test
    fun missingPendingIsRejectedWithoutCreatingFormalFile() {
        val fixture = fixture()

        assertThrows(EvidenceFileStoreException::class.java) {
            fixture.store.promotePendingImage("capture-missing")
        }

        assertFalse(fixture.storedFile("capture-missing").exists())
    }

    @Test
    fun invalidFormalFileFromInterruptedMoveIsQuarantinedAndRetrySucceeds() {
        val fixture = fixture()
        val expected = jpegBytes(7, 8, 9)
        fixture.writePending("capture-interrupted", expected)
        fixture.storedFile("capture-interrupted").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        val reference = fixture.store.promotePendingImage("capture-interrupted")

        assertFalse(fixture.pendingFile("capture-interrupted").exists())
        assertArrayEquals(expected, fixture.storedFile("capture-interrupted").readBytes())
        assertEquals(expected.size.toLong(), reference.byteSize)
        val quarantineDirectory = File(fixture.storedDirectory.parentFile, "quarantine")
        assertEquals(1, quarantineDirectory.listFiles().orEmpty().count { it.extension == "bad" })
    }

    private fun fixture(): Fixture {
        val root = temporaryFolder.newFolder()
        val pendingDirectory = File(root, "pending")
        val storedDirectory = File(root, "stored")
        return Fixture(
            pendingDirectory = pendingDirectory,
            storedDirectory = storedDirectory,
            pendingStore = ReceiptImageStore(pendingDirectory),
            store = EvidenceFileStore(pendingDirectory, storedDirectory)
        )
    }

    private fun jpegBytes(vararg payload: Int): ByteArray = buildList {
        add(0xFF.toByte())
        add(0xD8.toByte())
        payload.forEach { add(it.toByte()) }
        add(0xFF.toByte())
        add(0xD9.toByte())
    }.toByteArray()

    private data class Fixture(
        val pendingDirectory: File,
        val storedDirectory: File,
        val pendingStore: ReceiptImageStore,
        val store: EvidenceFileStore
    ) {
        fun writePending(captureId: String, bytes: ByteArray) {
            pendingStore.prepareFile(captureId).writeBytes(bytes)
        }

        fun pendingFile(captureId: String): File = pendingStore.fileFor(captureId)

        fun storedFile(captureId: String): File = store.fileFor(captureId)

        fun formalFiles(): List<File> = storedDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("evidence_") && it.name.endsWith(".jpg") }
    }
}
