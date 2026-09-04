package com.warun.accounting.ui.fixedcost

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.warun.accounting.evidence.FixedCostEvidenceFileStore

internal data class FixedCostEvidenceUriMetadata(
    val displayName: String,
    val mediaType: String,
    val byteSize: Long
)

internal sealed interface FixedCostEvidenceUriInspection {
    data class Ready(val metadata: FixedCostEvidenceUriMetadata) : FixedCostEvidenceUriInspection
    data object TooLarge : FixedCostEvidenceUriInspection
    data object Unavailable : FixedCostEvidenceUriInspection
}

/** Reads only metadata and, when necessary, at most MaxBytes + 1 bytes. */
internal fun inspectFixedCostEvidenceUri(
    resolver: ContentResolver,
    uri: Uri,
    mediaType: String,
    fallbackName: String = "Evidence"
): FixedCostEvidenceUriInspection {
    val normalizedMime = mediaType.substringBefore(';').trim().lowercase()
    val nameAndColumnSize = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                (if (nameIndex >= 0) cursor.getString(nameIndex) else null) to
                    (if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null)
            }
    }.getOrNull()
    val displayName = nameAndColumnSize?.first?.takeUnless { it.isNullOrBlank() } ?: fallbackName
    val size = nameAndColumnSize?.second
        ?: runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize.takeIf { size -> size >= 0L } } }.getOrNull()
        ?: runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { length -> length >= 0L } } }.getOrNull()
        ?: measureBounded(resolver, uri)
    return when {
        size == null -> FixedCostEvidenceUriInspection.Unavailable
        size > FixedCostEvidenceFileStore.MaxBytes -> FixedCostEvidenceUriInspection.TooLarge
        size <= 0L -> FixedCostEvidenceUriInspection.Unavailable
        else -> FixedCostEvidenceUriInspection.Ready(
            FixedCostEvidenceUriMetadata(displayName, normalizedMime, size)
        )
    }
}

private fun measureBounded(resolver: ContentResolver, uri: Uri): Long? = runCatching {
    var total = 0L
    resolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > FixedCostEvidenceFileStore.MaxBytes) return@use total
        }
    }?.let { if (total > FixedCostEvidenceFileStore.MaxBytes) total else total.takeIf { it > 0L } }
}.getOrNull()
