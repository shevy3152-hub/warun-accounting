package com.warun.accounting.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun manifestRoundTripPreservesAllVerificationFields() {
        val fixture = fixture()
        val bytes = BackupArchive.manifestBytes(fixture.manifest)
        val restored = BackupManifestXml.read(ByteArrayInputStream(bytes))

        assertEquals(fixture.manifest, restored)
    }

    @Test
    fun archiveRoundTripValidatesDatabaseAndMultipleEvidence() {
        val fixture = fixture(evidenceCount = 2)
        val bytes = ByteArrayOutputStream().use { output ->
            BackupArchive.write(output, fixture.manifest, fixture.database, fixture.evidence)
            output.toByteArray()
        }

        val restored = BackupArchive.extractAndValidate(
            ByteArrayInputStream(bytes),
            File(temporaryFolder.root, "restored")
        )

        assertEquals(fixture.manifest, restored.manifest)
        assertEquals(fixture.database.readBytes().toList(), restored.databaseFile.readBytes().toList())
        assertEquals(setOf("ev-1", "ev-2"), restored.evidenceFiles.keys)
    }

    @Test
    fun checksumMismatchIsRejected() {
        val fixture = fixture()
        val bytes = rawZip(
            BackupContract.ManifestEntry to BackupArchive.manifestBytes(fixture.manifest),
            BackupContract.DatabaseEntry to "tampered-db".toByteArray(),
            BackupContract.evidenceEntry("ev-1") to fixture.evidence.getValue("ev-1").readBytes()
        )

        assertFailure(BackupFailure.SizeMismatch, BackupFailure.ChecksumMismatch) {
            BackupArchive.extractAndValidate(
                ByteArrayInputStream(bytes),
                File(temporaryFolder.root, "checksum")
            )
        }
    }

    @Test
    fun missingEntryIsRejected() {
        val fixture = fixture()
        val bytes = rawZip(
            BackupContract.ManifestEntry to BackupArchive.manifestBytes(fixture.manifest),
            BackupContract.DatabaseEntry to fixture.database.readBytes()
        )

        assertFailure(BackupFailure.MissingArchiveEntry) {
            BackupArchive.extractAndValidate(
                ByteArrayInputStream(bytes),
                File(temporaryFolder.root, "missing")
            )
        }
    }

    @Test
    fun unsafePathIsRejectedBeforeExtraction() {
        val bytes = rawZip("../escape" to "bad".toByteArray())

        assertFailure(BackupFailure.UnsafeArchivePath) {
            BackupArchive.extractAndValidate(
                ByteArrayInputStream(bytes),
                File(temporaryFolder.root, "unsafe")
            )
        }
        assertTrue(!File(temporaryFolder.root.parentFile, "escape").exists())
    }

    @Test
    fun duplicateEntryIsRejected() {
        val bytes = rawZip(
            BackupContract.ManifestEntry to "one".toByteArray(),
            "manifesz.xml" to "two".toByteArray()
        ).replaceAscii("manifesz.xml", BackupContract.ManifestEntry)

        assertFailure(BackupFailure.DuplicateArchiveEntry) {
            BackupArchive.extractAndValidate(
                ByteArrayInputStream(bytes),
                File(temporaryFolder.root, "duplicate")
            )
        }
    }

    @Test
    fun unsupportedSchemaIsRejectedFromManifest() {
        val fixture = fixture()
        val manifest = fixture.manifest.copy(roomSchemaVersion = 19)

        assertFailure(BackupFailure.UnsupportedSchema) {
            BackupArchive.manifestBytes(manifest)
        }
    }

    @Test
    fun previousVersion15SchemaIsAcceptedForRestore() {
        val fixture = fixture()

        BackupArchive.manifestBytes(
            fixture.manifest.copy(
                roomSchemaVersion = BackupContract.PreviousRoomSchemaVersion
            )
        )
    }

    @Test
    fun formatVersion1ManifestRemainsReadableAfterFormatVersion2() {
        val fixture = fixture()
        val legacy = fixture.manifest.copy(formatVersion = BackupContract.LegacyFormatVersion)

        assertEquals(BackupContract.LegacyFormatVersion,
            BackupManifestXml.read(ByteArrayInputStream(BackupArchive.manifestBytes(legacy))).formatVersion)
    }

    @Test
    fun formatVersion2PreservesJpegPngAndPdfBytes() {
        val database = temporaryFolder.newFile("multi-db").apply { writeBytes(byteArrayOf(1)) }
        val files = listOf(
            "jpeg" to ("image/jpeg" to byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 0xff.toByte(), 0xd9.toByte())),
            "png" to ("image/png" to byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1)),
            "pdf" to ("application/pdf" to "%PDF-1.7\n%%EOF".toByteArray())
        ).associate { (id, pair) -> id to temporaryFolder.newFile("$id.data").apply { writeBytes(pair.second) } }
        val types = mapOf("jpeg" to "image/jpeg", "png" to "image/png", "pdf" to "application/pdf")
        val entries = files.map { (id, file) ->
            BackupEvidenceEntry(id, file.toURI().toString(), BackupArchiveEntry(BackupContract.evidenceEntry(id, types.getValue(id)), file.length(), BackupArchive.sha256(file)), types.getValue(id))
        }
        val manifest = fixture().manifest.copy(
            formatVersion = BackupContract.FormatVersion,
            database = BackupArchiveEntry(BackupContract.DatabaseEntry, database.length(), BackupArchive.sha256(database)),
            evidence = entries,
            summary = fixture().manifest.summary.copy(evidenceCount = entries.size.toLong())
        )
        val restored = BackupArchive.extractAndValidate(
            ByteArrayInputStream(ByteArrayOutputStream().use { output -> BackupArchive.write(output, manifest, database, files); output.toByteArray() }),
            File(temporaryFolder.root, "multi-restored")
        )
        files.forEach { (id, file) -> assertEquals(file.readBytes().toList(), restored.evidenceFiles.getValue(id).readBytes().toList()) }
    }

    @Test
    fun restoreValidationRejectsFutureSchemaManifest() {
        val fixture = fixture()
        val futureManifest = String(
            BackupArchive.manifestBytes(fixture.manifest.copy(roomSchemaVersion = BackupContract.PriorRoomSchemaVersion)),
            Charsets.UTF_8
        ).replace("roomSchemaVersion=\"17\"", "roomSchemaVersion=\"19\"")
        val bytes = rawZip(
            BackupContract.ManifestEntry to futureManifest.toByteArray(),
            BackupContract.DatabaseEntry to fixture.database.readBytes(),
            BackupContract.evidenceEntry("ev-1") to fixture.evidence.getValue("ev-1").readBytes()
        )

        assertFailure(BackupFailure.UnsupportedSchema) {
            BackupArchive.extractAndValidate(
                ByteArrayInputStream(bytes),
                File(temporaryFolder.root, "future-schema")
            )
        }
    }

    @Test
    fun corruptZipIsRejected() {
        assertFailure(BackupFailure.CorruptArchive, BackupFailure.MissingArchiveEntry) {
            BackupArchive.extractAndValidate(
                ByteArrayInputStream("not-a-zip".toByteArray()),
                File(temporaryFolder.root, "corrupt")
            )
        }
    }

    @Test
    fun manifestRejectsDoctypeBeforeXmlParserCanResolveEntities() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE backup [<!ENTITY external SYSTEM "file:///etc/passwd">]>
            <warun-accounting-backup>&external;</warun-accounting-backup>
        """.trimIndent()

        assertFailure(BackupFailure.InvalidManifest) {
            BackupManifestXml.read(ByteArrayInputStream(xml.toByteArray()))
        }
    }

    @Test
    fun manifestRejectsNullBytesAndAlternateWideEncodings() {
        assertFailure(BackupFailure.InvalidManifest) {
            BackupManifestXml.read(
                ByteArrayInputStream(
                    byteArrayOf('<'.code.toByte(), 0, '!'.code.toByte(), 0)
                )
            )
        }
    }

    private fun fixture(evidenceCount: Int = 1): Fixture {
        val database = temporaryFolder.newFile("db-${System.nanoTime()}").apply {
            writeBytes("sqlite-test-content-${System.nanoTime()}".toByteArray())
        }
        val evidence = (1..evidenceCount).associate { index ->
            val id = "ev-$index"
            id to temporaryFolder.newFile("$id-${System.nanoTime()}.jpg").apply {
                writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), index.toByte(), 0xff.toByte(), 0xd9.toByte()))
            }
        }
        val evidenceEntries = evidence.map { (id, file) ->
            BackupEvidenceEntry(
                evidenceId = id,
                storedUri = "file:/evidence_$id.jpg",
                archiveEntry = BackupArchiveEntry(
                    BackupContract.evidenceEntry(id),
                    file.length(),
                    BackupArchive.sha256(file)
                )
            )
        }
        val manifest = BackupManifest(
            formatVersion = BackupContract.FormatVersion,
            createdAtEpochMillis = 1L,
            appVersion = "test",
            roomSchemaVersion = BackupContract.CurrentRoomSchemaVersion,
            database = BackupArchiveEntry(
                BackupContract.DatabaseEntry,
                database.length(),
                BackupArchive.sha256(database)
            ),
            evidence = evidenceEntries,
            summary = BackupSummary(
                dailyReportCount = 1,
                receiptCount = 1,
                expenseCount = 1,
                evidenceCount = evidenceCount.toLong(),
                cancellationCount = 1,
                prepaidAccountCount = 1,
                prepaidTransactionCount = 2
            )
        )
        return Fixture(database, evidence, manifest)
    }

    private fun rawZip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

    private fun ByteArray.replaceAscii(old: String, new: String): ByteArray {
        require(old.length == new.length)
        val result = copyOf()
        val oldBytes = old.toByteArray()
        val newBytes = new.toByteArray()
        for (start in 0..result.size - oldBytes.size) {
            if (oldBytes.indices.all { offset -> result[start + offset] == oldBytes[offset] }) {
                newBytes.copyInto(result, start)
            }
        }
        return result
    }

    private fun assertFailure(
        vararg expected: BackupFailure,
        block: () -> Unit
    ) {
        val error = runCatching(block).exceptionOrNull() as? BackupException
        assertTrue("Expected one of ${expected.toList()}, but was $error", error?.failure in expected)
    }

    private data class Fixture(
        val database: File,
        val evidence: Map<String, File>,
        val manifest: BackupManifest
    )
}
