package com.warun.accounting.ui.evidence

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.warun.accounting.data.local.ExpenseEvidenceRecord
import com.warun.accounting.evidence.EvidenceFileStore
import com.warun.accounting.ui.image.ZoomableReceiptImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface EvidenceImageState {
    data object Loading : EvidenceImageState
    data class Ready(val bitmap: Bitmap) : EvidenceImageState
    data class Error(val message: String) : EvidenceImageState
}

@Composable
fun EvidenceImageDialog(
    evidence: ExpenseEvidenceRecord,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val imageState by produceState<EvidenceImageState>(
        initialValue = EvidenceImageState.Loading,
        key1 = evidence.evidenceId,
        key2 = evidence.sha256
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val store = EvidenceFileStore(
                    pendingDirectory = File(context.filesDir, "receipt-images/pending"),
                    storedDirectory = File(context.filesDir, "accounting-evidence/stored")
                )
                val reference = requireNotNull(store.resolve(evidence.evidenceId))
                check(reference.localUri == evidence.storedUri)
                check(reference.byteSize == evidence.byteSize)
                check(reference.sha256 == evidence.sha256)
                val file = store.fileFor(evidence.evidenceId)
                EvidenceImageState.Ready(decodePreview(file))
            }.getOrElse {
                EvidenceImageState.Error("保存済み画像を確認できませんでした")
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                    Column {
                        Text("保存済みレシート", style = MaterialTheme.typography.titleMedium)
                        Text("ピンチ操作で拡大できます", style = MaterialTheme.typography.bodySmall)
                    }
                }
                when (val state = imageState) {
                    EvidenceImageState.Loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { Text("画像を確認しています…") }

                    is EvidenceImageState.Error -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { Text(state.message, color = MaterialTheme.colorScheme.error) }

                    is EvidenceImageState.Ready -> ZoomableReceiptImage(
                        bitmap = state.bitmap.asImageBitmap(),
                        imageKey = "${evidence.evidenceId}:${evidence.sha256}",
                        contentDescription = "保存済みレシート画像",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

private fun decodePreview(file: File, maxDimension: Int = 2048): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    check(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    return requireNotNull(
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        )
    )
}
