package com.warun.accounting.camera

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReceiptImageStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun captureIdReconstructsSameFileAndDeleteRemovesIt() {
        val directory = temporaryFolder.newFolder("pending")
        val store = ReceiptImageStore(directory)
        val file = store.prepareFile("capture-1")
        file.writeText("image")

        assertEquals(file.canonicalFile, store.fileFor("capture-1").canonicalFile)
        assertTrue(store.delete("capture-1"))
        assertFalse(file.exists())
    }

    @Test
    fun cleanupDeletesOnlyExpiredPendingReceiptImages() {
        val now = 10_000_000L
        val directory = temporaryFolder.newFolder("pending")
        val store = ReceiptImageStore(directory, now = { now })
        val expired = store.prepareFile("expired").apply {
            writeText("old")
            setLastModified(now - 1_001L)
        }
        val recent = store.prepareFile("recent").apply {
            writeText("new")
            setLastModified(now - 999L)
        }
        val unrelated = File(directory, "keep.txt").apply {
            writeText("keep")
            setLastModified(0L)
        }

        assertEquals(1, store.cleanupExpired(retentionMillis = 1_000L))
        assertFalse(expired.exists())
        assertTrue(recent.exists())
        assertTrue(unrelated.exists())
    }
}
