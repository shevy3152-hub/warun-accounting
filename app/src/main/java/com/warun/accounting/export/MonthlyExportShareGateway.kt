package com.warun.accounting.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class MonthlyExportShareGateway @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun createShareIntent(files: List<File>): Intent {
        require(files.isNotEmpty())
        files.forEach(::requireCompletedExport)
        ExportCacheContract.renewAccessLease(context.cacheDir, files)
        val uris = ArrayList(files.map(::contentUri))
        val mimeTypes = files.map(::mimeType).distinct()
        val mimeType = mimeTypes.singleOrNull() ?: "*/*"
        return if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris.single())
                clipData = ClipData.newUri(context.contentResolver, files.single().name, uris.single())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = ClipData.newUri(context.contentResolver, files.first().name, uris.first())
                    .also { clip ->
                        uris.drop(1).forEach { uri -> clip.addItem(ClipData.Item(uri)) }
                    }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun contentUri(file: File): Uri = FileProvider.getUriForFile(
        context,
        context.packageName + ExportCacheContract.FileProviderAuthoritySuffix,
        file
    )

    private fun requireCompletedExport(file: File) {
        ExportCacheContract.requireCompletedExport(context.cacheDir, file)
    }

    private fun mimeType(file: File): String = when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "application/octet-stream"
    }
}
