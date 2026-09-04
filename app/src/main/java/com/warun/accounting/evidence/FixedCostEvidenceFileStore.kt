package com.warun.accounting.evidence

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class FixedCostEvidenceSource(
    val uri: Uri,
    val mediaType: String,
    val sortOrder: Int
)

data class FixedCostEvidenceFileReference(
    val evidenceId: String,
    val mediaType: String,
    val byteSize: Long,
    val sha256: String,
    val pendingPath: String?,
    val finalPath: String,
    val storedAt: Long
)

fun interface FixedCostEvidenceInputOpener {
    fun open(resolver: ContentResolver, uri: Uri): InputStream?
}

class FixedCostEvidenceFileStore(
    private val pendingDirectory: File,
    private val storedDirectory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val inputOpener: FixedCostEvidenceInputOpener = FixedCostEvidenceInputOpener { resolver, uri ->
        resolver.openInputStream(uri)
    }
) {
    companion object {
        const val MaxBytes = 50L * 1024L * 1024L
        private const val TempSuffix = ".tmp"
        private val ValidId = Regex("[A-Za-z0-9-]{1,256}")
        private val Extensions = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "application/pdf" to "pdf"
        )
    }

    @Synchronized
    fun savePending(
        resolver: ContentResolver,
        source: FixedCostEvidenceSource,
        evidenceId: String
    ): FixedCostEvidenceFileReference {
        validateId(evidenceId)
        val mediaType = normalizedMediaType(source.mediaType)
        val extension = extensionFor(mediaType)
        require(source.sortOrder >= 0) { "sortOrder must be non-negative" }
        val resolverType = resolver.getType(source.uri)?.substringBefore(';')?.lowercase()
        require(resolverType == null || resolverType == mediaType) {
            "ContentResolver MIME does not match declared MIME"
        }
        ensureDirectory(pendingDirectory, "pending")
        val destination = File(pendingDirectory, pendingName(evidenceId, extension))
        if (destination.exists()) throw FixedCostEvidenceFileException("Evidence ID already exists")
        val temporary = File(pendingDirectory, ".${destination.name}.${UUID.randomUUID()}$TempSuffix")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            val first = ByteArray(8)
            var firstCount = 0
            val tail = ByteArray(1024)
            var tailCount = 0
            val input = inputOpener.open(resolver, source.uri)
                ?: throw FixedCostEvidenceFileException("Evidence source could not be opened")
            input.use {
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > MaxBytes) throw FixedCostEvidenceFileException("Evidence exceeds 50 MiB")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        repeat(count) { index ->
                            if (firstCount < first.size) first[firstCount++] = buffer[index]
                            if (tailCount < tail.size) tail[tailCount++] = buffer[index]
                            else {
                                tail.copyInto(tail, 0, 1, tail.size)
                                tail[tail.size - 1] = buffer[index]
                            }
                        }
                    }
                    output.fd.sync()
            }
            }
            validateSignature(mediaType, first, firstCount, tail, tailCount, size)
            moveNoReplace(temporary, destination)
            val hash = digest.digest().toHex()
            return FixedCostEvidenceFileReference(
                evidenceId = evidenceId,
                mediaType = mediaType,
                byteSize = size,
                sha256 = hash,
                pendingPath = destination.absolutePath,
                finalPath = storedFileForExtension(evidenceId, extension).absolutePath,
                storedAt = now()
            )
        } catch (error: FixedCostEvidenceFileException) {
            temporary.delete()
            throw error
        } catch (error: Exception) {
            temporary.delete()
            throw FixedCostEvidenceFileException("Fixed-cost Evidence pending save failed", error)
        }
    }

    @Synchronized
    fun promotePending(evidenceId: String, mediaType: String): FixedCostEvidenceFileReference {
        validateId(evidenceId)
        val normalizedMediaType = normalizedMediaType(mediaType)
        val extension = extensionFor(normalizedMediaType)
        val pending = File(pendingDirectory, pendingName(evidenceId, extension))
        val stored = storedFileForExtension(evidenceId, extension)
        val measured = measureFile(pending, normalizedMediaType, evidenceId)
        ensureDirectory(storedDirectory, "stored")
        if (stored.exists()) {
            val existing = measureFile(stored, normalizedMediaType, evidenceId)
            if (existing.sha256 != measured.sha256 || existing.byteSize != measured.byteSize) {
                throw FixedCostEvidenceFileException("Existing fixed-cost Evidence differs")
            }
            if (!pending.delete() && pending.exists()) {
                throw FixedCostEvidenceFileException("Pending Evidence could not be removed")
            }
            return existing.copy(pendingPath = null, finalPath = stored.absolutePath)
        }
        val temporary = File(storedDirectory, ".${stored.name}.${UUID.randomUUID()}$TempSuffix")
        try {
            copyAndSync(pending, temporary)
            val copied = measureFile(temporary, normalizedMediaType, evidenceId)
            check(copied.sha256 == measured.sha256 && copied.byteSize == measured.byteSize)
            moveNoReplace(temporary, stored)
            if (!pending.delete() && pending.exists()) {
                throw FixedCostEvidenceFileException("Pending Evidence could not be removed")
            }
            return copied.copy(pendingPath = null, finalPath = stored.absolutePath)
        } catch (error: FixedCostEvidenceFileException) {
            temporary.delete()
            throw error
        } catch (error: Exception) {
            temporary.delete()
            throw FixedCostEvidenceFileException("Fixed-cost Evidence promotion failed", error)
        }
    }

    fun pendingFileFor(evidenceId: String, mediaType: String): File =
        File(pendingDirectory, pendingName(validatedId(evidenceId), extensionFor(normalizedMediaType(mediaType))))

    fun storedFileFor(evidenceId: String, mediaType: String): File =
        storedFileForExtension(validatedId(evidenceId), extensionFor(normalizedMediaType(mediaType)))

    private fun storedFileForExtension(evidenceId: String, extension: String): File =
        File(storedDirectory, "evidence_${evidenceId}.$extension")

    private fun measureFile(
        file: File,
        mediaType: String,
        evidenceId: String
    ): FixedCostEvidenceFileReference {
        if (!file.isFile || !file.canRead()) throw FixedCostEvidenceFileException("Evidence file is unavailable")
        val digest = MessageDigest.getInstance("SHA-256")
        val first = ByteArray(8)
        var firstCount = 0
        val tail = ByteArray(1024)
        var tailCount = 0
        var size = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > MaxBytes) throw FixedCostEvidenceFileException("Evidence exceeds 50 MiB")
                digest.update(buffer, 0, count)
                repeat(count) { index ->
                    if (firstCount < first.size) first[firstCount++] = buffer[index]
                    if (tailCount < tail.size) tail[tailCount++] = buffer[index]
                    else {
                        tail.copyInto(tail, 0, 1, tail.size)
                        tail[tail.size - 1] = buffer[index]
                    }
                }
            }
        }
        validateSignature(mediaType, first, firstCount, tail, tailCount, size)
        return FixedCostEvidenceFileReference(
            evidenceId = evidenceId,
            mediaType = mediaType.lowercase(),
            byteSize = size,
            sha256 = digest.digest().toHex(),
            pendingPath = file.absolutePath,
            finalPath = file.absolutePath,
            storedAt = file.lastModified()
        )
    }

    private fun validateSignature(
        mediaType: String,
        first: ByteArray,
        firstCount: Int,
        tail: ByteArray,
        tailCount: Int,
        size: Long
    ) {
        if (size <= 0L) throw FixedCostEvidenceFileException("Evidence is empty")
        val type = normalizedMediaType(mediaType)
        val valid = when (type) {
            "image/jpeg" -> firstCount >= 3 && first[0] == 0xff.toByte() && first[1] == 0xd8.toByte() && first[2] == 0xff.toByte() && tailCount >= 2 && tail[tailCount - 2] == 0xff.toByte() && tail[tailCount - 1] == 0xd9.toByte()
            "image/png" -> firstCount >= 8 && first.copyOf(8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            "application/pdf" -> firstCount >= 5 && first.copyOf(5).contentEquals("%PDF-".toByteArray()) && tail.copyOf(tailCount).toString(Charsets.US_ASCII).contains("%%EOF")
            else -> false
        }
        if (!valid) throw FixedCostEvidenceFileException("MIME and file signature do not match")
    }

    private fun extensionFor(mediaType: String): String =
        Extensions[normalizedMediaType(mediaType)]
            ?: throw FixedCostEvidenceFileException("Unsupported Evidence MIME")

    private fun normalizedMediaType(mediaType: String): String =
        mediaType.substringBefore(';').trim().lowercase()

    private fun pendingName(evidenceId: String, extension: String) =
        "fixed_cost_pending_${evidenceId}.$extension"

    private fun moveNoReplace(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
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

    private fun ensureDirectory(directory: File, label: String) {
        if (directory.exists() && !directory.isDirectory) throw FixedCostEvidenceFileException("$label path is not a directory")
        if (!directory.exists() && !directory.mkdirs()) throw FixedCostEvidenceFileException("Cannot create $label directory")
    }

    private fun validatedId(value: String): String = value.also(::validateId)
    private fun validateId(value: String) { if (!ValidId.matches(value)) throw FixedCostEvidenceFileException("Invalid Evidence ID") }
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

class FixedCostEvidenceFileException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
