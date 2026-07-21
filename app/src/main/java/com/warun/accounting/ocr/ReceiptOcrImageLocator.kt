package com.warun.accounting.ocr

import com.warun.accounting.camera.ReceiptImageStore
import com.warun.accounting.future.ReceiptOcrRequest
import java.io.File
import java.net.URI

class ReceiptOcrImageLocator(
    private val imageStore: ReceiptImageStore
) {
    fun locate(request: ReceiptOcrRequest): String {
        val managedFile = runCatching { imageStore.fileFor(request.imageId) }.getOrNull()
        if (managedFile?.isFile == true) return managedFile.toURI().toString()

        if (request.localUri.isBlank()) throw ReceiptOcrImageNotFoundException()
        val uri = runCatching { URI(request.localUri) }
            .getOrElse { throw ReceiptOcrImageNotFoundException() }
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val file = runCatching { File(uri) }
                .getOrElse { throw ReceiptOcrImageNotFoundException() }
            if (!file.isFile) throw ReceiptOcrImageNotFoundException()
        }
        return request.localUri
    }
}
