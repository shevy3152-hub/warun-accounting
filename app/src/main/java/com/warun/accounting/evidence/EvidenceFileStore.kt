package com.warun.accounting.evidence

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class EvidenceFileReference(
    val evidenceId: String,
    val localUri: String,
    val byteSize: Long,
    val sha256: String,
    val storedAt: Long
)

interface EvidenceFilePromoter {
    fun promotePendingImage(evidenceId: String): EvidenceFileReference
}

class EvidenceFileStore(
    private val pendingDirectory: File,
    private val storedDirectory: File
) : EvidenceFilePromoter {
    companion object {
        private const val PendingPrefix = "receipt_"
        private const val StoredPrefix = "evidence_"
        private const val ImageSuffix = ".jpg"
        private const val TempSuffix = ".tmp"
        private const val JpegMarkerPrefix = 0xFF
        private const val JpegStartOfImage = 0xD8
        private const val JpegEndOfImage = 0xD9
        private val ValidId = Regex("[A-Za-z0-9-]+")
    }

    @Synchronized
    override fun promotePendingImage(evidenceId: String): EvidenceFileReference {
        validateId(evidenceId)
        val pendingFile = pendingFileFor(evidenceId)
        val storedFile = fileFor(evidenceId)

        if (storedFile.exists()) {
            val storedReference: EvidenceFileReference? = try {
                referenceFor(storedFile, evidenceId)
            } catch (error: Exception) {
                if (!pendingFile.exists()) throw error
                validateReadableFile(pendingFile, "pending画像")
                quarantineInvalidStoredFile(storedFile, evidenceId, error)
                null
            }
            if (storedReference != null) {
                if (pendingFile.exists()) {
                    validateReadableFile(pendingFile, "pending画像")
                    if (sha256(pendingFile) != storedReference.sha256) {
                        throw EvidenceFileStoreException("同じIDで内容が異なる正式証憑が存在します")
                    }
                    deletePendingOrThrow(pendingFile)
                }
                return storedReference
            }
        }

        validateReadableFile(pendingFile, "pending画像")
        ensureStoredDirectory()
        val pendingSize = pendingFile.length()
        val pendingHash = sha256(pendingFile)
        val tempFile = File(
            storedDirectory,
            ".${storedFile.name}.${UUID.randomUUID()}$TempSuffix"
        )

        try {
            copyAndSync(pendingFile, tempFile)
            validateReadableFile(tempFile, "書き込み中の証憑")
            if (tempFile.length() != pendingSize || sha256(tempFile) != pendingHash) {
                throw EvidenceFileStoreException("証憑画像の書き込み検証に失敗しました")
            }
            moveWithoutReplacement(tempFile, storedFile)
            val storedReference = referenceFor(storedFile, evidenceId)
            if (storedReference.byteSize != pendingSize || storedReference.sha256 != pendingHash) {
                throw EvidenceFileStoreException("正式証憑の読み戻し検証に失敗しました")
            }
            deletePendingOrThrow(pendingFile)
            return storedReference
        } catch (error: EvidenceFileStoreException) {
            tempFile.delete()
            throw error
        } catch (error: Exception) {
            tempFile.delete()
            throw EvidenceFileStoreException("証憑画像を正式保存できませんでした", error)
        }
    }

    fun resolve(evidenceId: String): EvidenceFileReference? {
        validateId(evidenceId)
        val file = fileFor(evidenceId)
        return if (file.exists()) referenceFor(file, evidenceId) else null
    }

    fun listReferences(): List<EvidenceFileReference> {
        if (!storedDirectory.exists()) return emptyList()
        check(storedDirectory.isDirectory) { "正式証憑の保存先がディレクトリではありません" }
        return storedDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.startsWith(StoredPrefix) && it.name.endsWith(ImageSuffix) }
            .map { file ->
                val evidenceId = file.name.removePrefix(StoredPrefix).removeSuffix(ImageSuffix)
                validateId(evidenceId)
                referenceFor(file, evidenceId)
            }
            .sortedBy { it.evidenceId }
            .toList()
    }

    fun fileFor(evidenceId: String): File {
        validateId(evidenceId)
        return File(storedDirectory, "$StoredPrefix$evidenceId$ImageSuffix")
    }

    private fun pendingFileFor(evidenceId: String): File =
        File(pendingDirectory, "$PendingPrefix$evidenceId$ImageSuffix")

    private fun ensureStoredDirectory() {
        if (storedDirectory.exists()) {
            if (!storedDirectory.isDirectory) {
                throw EvidenceFileStoreException("正式証憑の保存先がディレクトリではありません")
            }
            return
        }
        if (!storedDirectory.mkdirs()) {
            throw EvidenceFileStoreException("正式証憑の保存先を作成できませんでした")
        }
    }

    private fun copyAndSync(source: File, destination: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun moveWithoutReplacement(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun quarantineInvalidStoredFile(file: File, evidenceId: String, cause: Throwable) {
        val quarantineDirectory = File(storedDirectory.parentFile, "quarantine")
        if (!quarantineDirectory.exists() && !quarantineDirectory.mkdirs()) {
            throw EvidenceFileStoreException("破損した正式証憑を隔離できませんでした", cause)
        }
        if (!quarantineDirectory.isDirectory) {
            throw EvidenceFileStoreException("正式証憑の隔離先がディレクトリではありません", cause)
        }
        val quarantinedFile = File(
            quarantineDirectory,
            "evidence_${evidenceId}_${UUID.randomUUID()}.bad"
        )
        try {
            Files.move(file.toPath(), quarantinedFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            try {
                Files.move(file.toPath(), quarantinedFile.toPath())
            } catch (error: Exception) {
                throw EvidenceFileStoreException("破損した正式証憑を隔離できませんでした", error)
            }
        } catch (error: Exception) {
            throw EvidenceFileStoreException("破損した正式証憑を隔離できませんでした", error)
        }
    }

    private fun referenceFor(file: File, evidenceId: String): EvidenceFileReference {
        validateReadableFile(file, "正式証憑")
        return EvidenceFileReference(
            evidenceId = evidenceId,
            localUri = file.toURI().toString(),
            byteSize = file.length(),
            sha256 = sha256(file),
            storedAt = file.lastModified()
        )
    }

    private fun validateReadableFile(file: File, label: String) {
        if (!file.isFile || !file.canRead()) {
            throw EvidenceFileStoreException("${label}が見つからないか読み込めません")
        }
        if (file.length() <= 0L) {
            throw EvidenceFileStoreException("${label}が0バイトです")
        }
        validateJpegStructure(file, label)
    }

    private fun validateJpegStructure(file: File, label: String) {
        if (file.length() < 4L) {
            throw EvidenceFileStoreException("${label}が有効なJPEGではありません")
        }
        val hasStartMarker = FileInputStream(file).use { input ->
            input.read() == JpegMarkerPrefix && input.read() == JpegStartOfImage
        }
        val hasEndMarker = RandomAccessFile(file, "r").use { input ->
            input.seek(input.length() - 2L)
            input.read() == JpegMarkerPrefix && input.read() == JpegEndOfImage
        }
        if (!hasStartMarker || !hasEndMarker) {
            throw EvidenceFileStoreException("${label}が破損しているかJPEG形式ではありません")
        }
    }

    private fun deletePendingOrThrow(file: File) {
        if (file.exists() && !file.delete()) {
            throw EvidenceFileStoreException("正式保存後のpending画像を削除できませんでした")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun validateId(evidenceId: String) {
        if (!evidenceId.matches(ValidId)) {
            throw EvidenceFileStoreException("不正な証憑IDです")
        }
    }

}

class EvidenceFileStoreException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)
