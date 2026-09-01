package com.warun.accounting.ui.fixedcost

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.warun.accounting.data.local.EvidenceRecord
import com.warun.accounting.evidence.FixedCostEvidenceFileStore
import com.warun.accounting.ui.image.ZoomableReceiptImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface FixedCostViewerState {
    data object Loading : FixedCostViewerState
    data class Ready(val bitmap: Bitmap) : FixedCostViewerState
    data object Unavailable : FixedCostViewerState
}

@Composable
fun FixedCostEvidenceViewer(
    evidence: List<EvidenceRecord>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    if (evidence.isEmpty()) return
    var index by remember(evidence, initialIndex) {
        mutableIntStateOf(initialIndex.coerceIn(evidence.indices))
    }
    val current = evidence[index]
    val state by fixedCostViewerState(LocalContext.current, current)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(12.dp).testTag("fixed-cost-viewer"),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("fixed-cost-viewer-close")) { Text("閉じる") }
                    Column {
                        Text("保存済み固定費証憑", style = MaterialTheme.typography.titleMedium)
                        Text("${index + 1} / ${evidence.size}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column(modifier = Modifier.padding(horizontal = 16.dp).testTag("fixed-cost-viewer-metadata")) {
                    Text(fileName(current))
                    Text("MIME: ${current.mediaType}")
                    Text("サイズ: ${current.byteSize} bytes")
                    Text("位置: ${index + 1} / ${evidence.size}")
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (val loaded = state) {
                        FixedCostViewerState.Loading -> Text("表示を準備しています…")
                        FixedCostViewerState.Unavailable -> Text(
                            "表示できません",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("fixed-cost-viewer-unavailable")
                        )
                        is FixedCostViewerState.Ready -> ZoomableReceiptImage(
                            bitmap = loaded.bitmap.asImageBitmap(),
                            imageKey = "${current.id}:${current.sha256}",
                            contentDescription = "保存済み固定費証憑",
                            modifier = Modifier.fillMaxSize().testTag("fixed-cost-viewer-content")
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { index-- }, enabled = index > 0, modifier = Modifier.testTag("fixed-cost-viewer-previous")) { Text("前へ") }
                    Button(onClick = onDismiss, modifier = Modifier.testTag("fixed-cost-viewer-close-bottom")) { Text("閉じる") }
                    TextButton(onClick = { index++ }, enabled = index < evidence.lastIndex, modifier = Modifier.testTag("fixed-cost-viewer-next")) { Text("次へ") }
                }
            }
        }
    }
}

@Composable
private fun fixedCostViewerState(context: android.content.Context, evidence: EvidenceRecord) = produceState<FixedCostViewerState>(
    initialValue = FixedCostViewerState.Loading,
    key1 = evidence.id,
    key2 = evidence.sha256
) {
    value = withContext(Dispatchers.IO) {
        runCatching {
            val store = FixedCostEvidenceFileStore(
                pendingDirectory = File(context.filesDir, "fixed-cost-evidence/pending"),
                storedDirectory = File(context.filesDir, "fixed-cost-evidence/stored")
            )
            val file = store.storedFileFor(evidence.id, evidence.mediaType)
            check(file.isFile && file.length() == evidence.byteSize)
            if (evidence.mediaType.startsWith("image/")) {
                decodeImage(file)
            } else {
                decodePdf(file)
            }
        }.fold(
            onSuccess = { FixedCostViewerState.Ready(it) },
            onFailure = { FixedCostViewerState.Unavailable }
        )
    }
}

private fun decodeImage(file: File): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    check(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    return requireNotNull(BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
        inSampleSize = sample
    }))
}

private fun decodePdf(file: File): Bitmap {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            check(renderer.pageCount > 0)
            renderer.openPage(0).use { page ->
                val scale = minOf(1f, 2048f / maxOf(page.width, page.height).toFloat())
                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        }
    }
}

private fun fileName(evidence: EvidenceRecord): String =
    "evidence_${evidence.id}.${when (evidence.mediaType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "application/pdf" -> "pdf"
        else -> "bin"
    }}"
