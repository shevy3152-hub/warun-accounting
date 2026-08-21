package com.warun.accounting.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptPdfPageLayoutPlannerTest {
    @Test
    fun portraitImageUsesAvailableHeightWithoutCropping() {
        val layout = ReceiptPdfPageLayoutPlanner.plan(1000, 2000, 3)

        assertA4AndInsidePage(layout)
        assertRatio(0.5f, layout.imageBounds.width / layout.imageBounds.height)
        assertEquals(3, layout.headerBaselines.size)
    }

    @Test
    fun landscapeImageUsesAvailableWidthAndIsCentered() {
        val layout = ReceiptPdfPageLayoutPlanner.plan(2000, 1000, 2)

        assertA4AndInsidePage(layout)
        assertRatio(2f, layout.imageBounds.width / layout.imageBounds.height)
        assertEquals(36f, layout.imageBounds.left, 0.01f)
        assertEquals(
            ReceiptPdfPageLayoutPlanner.A4PortraitWidth - 36f,
            layout.imageBounds.right,
            0.01f
        )
    }

    @Test
    fun extremeRatiosRemainCenteredAndKeepAspectRatio() {
        listOf(100_000 to 10, 10 to 100_000).forEach { (width, height) ->
            val layout = ReceiptPdfPageLayoutPlanner.plan(width, height, 3)

            assertA4AndInsidePage(layout)
            assertRatio(width.toFloat() / height, layout.imageBounds.width / layout.imageBounds.height)
            val centerX = (layout.imageBounds.left + layout.imageBounds.right) / 2f
            assertEquals(ReceiptPdfPageLayoutPlanner.A4PortraitWidth / 2f, centerX, 0.02f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidImageDimensionsAreRejected() {
        ReceiptPdfPageLayoutPlanner.plan(0, 100, 2)
    }

    private fun assertA4AndInsidePage(layout: ReceiptPdfPageLayout) {
        assertEquals(ReceiptPdfPageLayoutPlanner.A4PortraitWidth, layout.pageWidth)
        assertEquals(ReceiptPdfPageLayoutPlanner.A4PortraitHeight, layout.pageHeight)
        assertTrue(layout.imageBounds.left >= 36f)
        assertTrue(layout.imageBounds.top > layout.headerBaselines.last())
        assertTrue(layout.imageBounds.right <= layout.pageWidth - 36f + 0.01f)
        assertTrue(layout.imageBounds.bottom <= layout.pageHeight - 36f + 0.01f)
        assertTrue(layout.imageBounds.width > 0f)
        assertTrue(layout.imageBounds.height > 0f)
    }

    private fun assertRatio(expected: Float, actual: Float) {
        val tolerance = maxOf(0.000001f, expected * 0.01f)
        assertEquals(expected, actual, tolerance)
    }
}
