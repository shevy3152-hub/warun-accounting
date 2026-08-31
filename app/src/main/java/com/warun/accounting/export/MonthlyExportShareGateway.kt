package com.warun.accounting.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class MonthlyExportShareGateway @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Creates a generic share intent; it never records a submission. */
    fun createShareIntent(
        files: List<File>,
        targetMonth: String? = null,
        storeName: String? = null
    ): Intent {
        require(files.isNotEmpty())
        files.forEach(::requireCompletedExport)
        ExportCacheContract.renewAccessLease(context.cacheDir, files)
        val uris = ArrayList(files.map(::contentUri))
        val mimeTypes = files.map(::mimeType).distinct()
        val mimeType = mimeTypes.singleOrNull() ?: "*/*"
        return if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putShareMetadata(mimeTypes, targetMonth, storeName)
                putExtra(Intent.EXTRA_STREAM, uris.single())
                clipData = ClipData.newUri(context.contentResolver, files.single().name, uris.single())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putShareMetadata(mimeTypes, targetMonth, storeName)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = ClipData.newUri(context.contentResolver, files.first().name, uris.first())
                    .also { clip ->
                        uris.drop(1).forEach { uri -> clip.addItem(ClipData.Item(uri)) }
                    }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /**
     * Targets the public MyKomon package only when it declares a compatible share handler.
     * Returns null when the app is absent or does not expose SEND support.
     */
    fun createMyKomonShareIntent(
        files: List<File>,
        targetMonth: String,
        storeName: String?
    ): Intent? {
        val targeted = createShareIntent(files, targetMonth, storeName).apply {
            setPackage(OfficialMyKomonPackage)
        }
        val handles = context.packageManager.queryIntentActivities(
            targeted,
            PackageManager.MATCH_DEFAULT_ONLY
        ).any { it.activityInfo.packageName == OfficialMyKomonPackage }
        return targeted.takeIf { handles }
    }

    private fun Intent.putShareMetadata(
        mimeTypes: List<String>,
        targetMonth: String?,
        storeName: String?
    ) {
        putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
        val cleanMonth = targetMonth?.trim().orEmpty()
        val cleanStore = storeName?.trim().orEmpty()
        if (cleanMonth.isNotEmpty() || cleanStore.isNotEmpty()) {
            val titleParts = listOfNotNull(
                cleanStore.takeIf { it.isNotEmpty() },
                cleanMonth.takeIf { it.isNotEmpty() }
            )
            putExtra(Intent.EXTRA_SUBJECT, "わるん会計 提出資料 ${titleParts.joinToString(" ")}")
            putExtra(
                Intent.EXTRA_TEXT,
                titleParts.joinToString("\n") { part ->
                    if (part == cleanMonth) "対象月：$part" else "店舗：$part"
                }
            )
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

    companion object {
        /** Public package id of the MyKomon app listed in Google Play. */
        const val OfficialMyKomonPackage = "com.mykomon.mykomon"

        /** Public login page documented by MyKomon; no credentials are handled by this app. */
        const val MyKomonLoginUrl = "https://www.mykomon.com/MyKomon/"
    }
}
