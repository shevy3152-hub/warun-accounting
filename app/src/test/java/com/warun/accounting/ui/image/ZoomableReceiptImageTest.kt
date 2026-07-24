package com.warun.accounting.ui.image

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomableReceiptImageTest {
    @Test
    fun initialTransformStartsAtOneWithoutOffset() {
        val transform = ZoomableImageTransform()

        assertEquals(1f, transform.scale)
        assertEquals(0f, transform.offsetX)
        assertEquals(0f, transform.offsetY)
    }

    @Test
    fun zoomIsLimitedToFiveTimes() {
        val transform = updateZoomableImageTransform(
            current = ZoomableImageTransform(),
            zoomChange = 10f,
            panX = 0f,
            panY = 0f,
            viewportWidth = 1_000,
            viewportHeight = 1_000,
            imageWidth = 1_000,
            imageHeight = 1_000
        )

        assertEquals(5f, transform.scale)
    }

    @Test
    fun zoomCannotGoBelowOneAndOffsetReturnsToCenter() {
        val transform = updateZoomableImageTransform(
            current = ZoomableImageTransform(scale = 2f, offsetX = 240f, offsetY = -180f),
            zoomChange = 0.1f,
            panX = 40f,
            panY = 40f,
            viewportWidth = 1_000,
            viewportHeight = 1_000,
            imageWidth = 1_000,
            imageHeight = 1_000
        )

        assertEquals(ZoomableImageTransform(), transform)
    }

    @Test
    fun expandedImageCanBePannedWithinVisibleBounds() {
        val transform = updateZoomableImageTransform(
            current = ZoomableImageTransform(),
            zoomChange = 2f,
            panX = 900f,
            panY = 200f,
            viewportWidth = 1_000,
            viewportHeight = 1_000,
            imageWidth = 1_000,
            imageHeight = 500
        )

        assertEquals(2f, transform.scale)
        assertEquals(500f, transform.offsetX)
        assertEquals(0f, transform.offsetY)
    }

    @Test
    fun portraitImageCannotBePannedIntoLetterboxedHorizontalArea() {
        val transform = updateZoomableImageTransform(
            current = ZoomableImageTransform(),
            zoomChange = 2f,
            panX = 400f,
            panY = -400f,
            viewportWidth = 1_000,
            viewportHeight = 1_000,
            imageWidth = 500,
            imageHeight = 1_000
        )

        assertEquals(0f, transform.offsetX)
        assertEquals(-400f, transform.offsetY)
    }

    @Test
    fun viewportChangeReclampsExistingOffset() {
        val transform = constrainZoomableImageTransform(
            current = ZoomableImageTransform(scale = 2f, offsetX = 500f, offsetY = 500f),
            viewportWidth = 600,
            viewportHeight = 1_000,
            imageWidth = 1_000,
            imageHeight = 500
        )

        assertEquals(300f, transform.offsetX)
        assertEquals(0f, transform.offsetY)
    }

    @Test
    fun aNewImageStartsFromASeparateInitialTransform() {
        val firstImageTransform =
            ZoomableImageTransform(scale = 4f, offsetX = 300f, offsetY = -200f)

        val secondImageTransform = ZoomableImageTransform()

        assertEquals(4f, firstImageTransform.scale)
        assertEquals(ZoomableImageTransform(), secondImageTransform)
    }
}
