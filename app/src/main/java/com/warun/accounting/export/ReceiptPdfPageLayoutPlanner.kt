package com.warun.accounting.export

data class ExportPdfRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class ReceiptPdfPageLayout(
    val pageWidth: Int,
    val pageHeight: Int,
    val headerBaselines: List<Float>,
    val imageBounds: ExportPdfRect
)

/** Pure layout calculation kept independent from Android so extreme image ratios are JVM-tested. */
object ReceiptPdfPageLayoutPlanner {
    const val A4PortraitWidth = 595
    const val A4PortraitHeight = 842
    private const val Margin = 36f
    private const val HeaderFirstBaseline = 51f
    private const val HeaderLineHeight = 19f
    private const val HeaderToImageGap = 16f

    fun plan(
        imageWidth: Int,
        imageHeight: Int,
        headerLineCount: Int
    ): ReceiptPdfPageLayout {
        require(imageWidth > 0 && imageHeight > 0)
        require(headerLineCount in 1..3)

        val baselines = List(headerLineCount) { index ->
            HeaderFirstBaseline + HeaderLineHeight * index
        }
        val available = ExportPdfRect(
            left = Margin,
            top = baselines.last() + HeaderToImageGap,
            right = A4PortraitWidth - Margin,
            bottom = A4PortraitHeight - Margin
        )
        check(available.width > 0f && available.height > 0f)

        val scale = minOf(
            available.width / imageWidth.toFloat(),
            available.height / imageHeight.toFloat()
        )
        val drawnWidth = imageWidth * scale
        val drawnHeight = imageHeight * scale
        val left = available.left + (available.width - drawnWidth) / 2f
        val top = available.top + (available.height - drawnHeight) / 2f

        return ReceiptPdfPageLayout(
            pageWidth = A4PortraitWidth,
            pageHeight = A4PortraitHeight,
            headerBaselines = baselines,
            imageBounds = ExportPdfRect(left, top, left + drawnWidth, top + drawnHeight)
        )
    }
}
