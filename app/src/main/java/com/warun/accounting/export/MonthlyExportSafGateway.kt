package com.warun.accounting.export

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject

sealed interface SafExportCopyResult {
    data class Success(val byteSize: Long, val sha256: String) : SafExportCopyResult
    data class Failure(val exceptionType: String, val message: String?) : SafExportCopyResult
}

class MonthlyExportSafGateway @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun copy(source: File, destination: Uri): SafExportCopyResult = try {
        requireCompletedExport(source)
        ExportCacheContract.renewAccessLease(context.cacheDir, listOf(source))
        val expectedSize = source.length()
        val expectedHash = sha256(FileInputStream(source))
        val resolver = context.contentResolver
        resolver.openFileDescriptor(destination, "rwt")?.use { descriptor ->
            FileInputStream(source).use { input ->
                FileOutputStream(descriptor.fileDescriptor).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
        } ?: error("保存先を開けません")

        val (actualSize, actualHash) = resolver.openInputStream(destination)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                size = Math.addExact(size, count.toLong())
            }
            size to digest.hex()
        } ?: error("保存したファイルを読み戻せません")
        check(actualSize == expectedSize && actualHash == expectedHash) {
            "保存したファイルの検証に失敗しました"
        }
        SafExportCopyResult.Success(actualSize, actualHash)
    } catch (error: Throwable) {
        SafExportCopyResult.Failure(
            error::class.qualifiedName ?: error::class.simpleName.orEmpty(),
            error.message
        )
    }

    private fun requireCompletedExport(file: File) {
        ExportCacheContract.requireCompletedExport(context.cacheDir, file)
    }

    private fun sha256(input: FileInputStream): String = input.use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.hex()
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { byte ->
        "%02x".format(byte)
    }
}
