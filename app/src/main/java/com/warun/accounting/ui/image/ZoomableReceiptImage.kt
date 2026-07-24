package com.warun.accounting.ui.image

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.material3.MaterialTheme
import kotlin.math.max
import kotlin.math.min

internal data class ZoomableImageTransform(
    val scale: Float = MinZoomScale,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
)

internal fun updateZoomableImageTransform(
    current: ZoomableImageTransform,
    zoomChange: Float,
    panX: Float,
    panY: Float,
    viewportWidth: Int,
    viewportHeight: Int,
    imageWidth: Int,
    imageHeight: Int
): ZoomableImageTransform {
    val nextScale = (current.scale * zoomChange).coerceIn(MinZoomScale, MaxZoomScale)
    if (nextScale <= MinZoomScale) return ZoomableImageTransform()

    val maxOffsets = maximumPanOffsets(
        scale = nextScale,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        imageWidth = imageWidth,
        imageHeight = imageHeight
    )
    return ZoomableImageTransform(
        scale = nextScale,
        offsetX = (current.offsetX + panX).coerceIn(-maxOffsets.first, maxOffsets.first),
        offsetY = (current.offsetY + panY).coerceIn(-maxOffsets.second, maxOffsets.second)
    )
}

internal fun constrainZoomableImageTransform(
    current: ZoomableImageTransform,
    viewportWidth: Int,
    viewportHeight: Int,
    imageWidth: Int,
    imageHeight: Int
): ZoomableImageTransform = updateZoomableImageTransform(
    current = current,
    zoomChange = 1f,
    panX = 0f,
    panY = 0f,
    viewportWidth = viewportWidth,
    viewportHeight = viewportHeight,
    imageWidth = imageWidth,
    imageHeight = imageHeight
)

private fun maximumPanOffsets(
    scale: Float,
    viewportWidth: Int,
    viewportHeight: Int,
    imageWidth: Int,
    imageHeight: Int
): Pair<Float, Float> {
    if (
        viewportWidth <= 0 ||
        viewportHeight <= 0 ||
        imageWidth <= 0 ||
        imageHeight <= 0
    ) {
        return 0f to 0f
    }
    val fitScale = min(
        viewportWidth.toFloat() / imageWidth.toFloat(),
        viewportHeight.toFloat() / imageHeight.toFloat()
    )
    val displayedWidth = imageWidth * fitScale
    val displayedHeight = imageHeight * fitScale
    return max(0f, (displayedWidth * scale - viewportWidth) / 2f) to
        max(0f, (displayedHeight * scale - viewportHeight) / 2f)
}

@Composable
internal fun ZoomableReceiptImage(
    bitmap: ImageBitmap,
    imageKey: String,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    var transform by remember(imageKey) { mutableStateOf(ZoomableImageTransform()) }
    var viewportSize by remember(imageKey) { mutableStateOf(IntSize.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        transform = updateZoomableImageTransform(
            current = transform,
            zoomChange = zoomChange,
            panX = panChange.x,
            panY = panChange.y,
            viewportWidth = viewportSize.width,
            viewportHeight = viewportSize.height,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height
        )
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { newSize ->
                viewportSize = newSize
                transform = constrainZoomableImageTransform(
                    current = transform,
                    viewportWidth = newSize.width,
                    viewportHeight = newSize.height,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height
                )
            }
            .transformable(transformState),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = transform.scale,
                    scaleY = transform.scale,
                    translationX = transform.offsetX,
                    translationY = transform.offsetY
                )
        )
    }
}

internal const val MinZoomScale = 1f
internal const val MaxZoomScale = 5f
