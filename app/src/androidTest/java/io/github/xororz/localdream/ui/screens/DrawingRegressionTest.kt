package io.github.xororz.localdream.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.set
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawingRegressionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun snappedCropsRoundTripWithoutDisplacingPixels() {
        val original = createBitmap(512, 512)
        val pixels = IntArray(512 * 512) { index ->
            android.graphics.Color.rgb(index % 251, (index / 512) % 251, index % 239)
        }
        original.setPixels(pixels, 0, 512, 0, 0, 512, 512)
        val selections = listOf(
            Rect(3, 2, 259, 258), // top/left
            Rect(253, 254, 509, 510), // bottom/right
            Rect(0, 0, 511, 511), // full image rounded down
            Rect(128, 1, 384, 511), // full height only
            Rect(1, 128, 511, 384), // full width only
            Rect(100, 100, 356, 356), // interior
        )
        selections.forEach { selection ->
            val crop = snapInpaintCropRect(selection, 512, 512, 6)
            val patch = Bitmap.createBitmap(original, crop.left, crop.top, crop.width(), crop.height())
            val result = original.copy(Bitmap.Config.ARGB_8888, true)
            drawInpaintPatch(result, patch, null, crop.left, crop.top)
            val actual = IntArray(pixels.size)
            result.getPixels(actual, 0, 512, 0, 0, 512, 512)
            assertArrayEquals("Pixel alignment for $selection", pixels, actual)
        }
        assertEquals(Rect(0, 0, 512, 512), snapInpaintCropRect(Rect(0, 0, 511, 511), 512, 512, 6))
        assertEquals(Rect(100, 100, 356, 356), snapInpaintCropRect(Rect(100, 100, 356, 356), 512, 512, 6))
    }

    @Test
    fun exportedStrokesMapThroughLetterboxingToOriginalPixels() {
        val stroke = StrokePath(
            Path().apply {
                moveTo(400f, 300f)
                lineTo(415f, 300f)
            },
            Color.Red,
            30f,
            1f,
        )
        val layer = renderDrawingLayer(listOf(stroke), 800, 600, 400, 400)
        assertEquals(android.graphics.Color.RED, layer[200, 200])
        assertEquals(0, android.graphics.Color.alpha(layer[100, 100]))
    }

    @Test
    fun exportPreservesBlurAndErasesOnlyTheDrawing() {
        val brush = StrokePath(
            Path().apply {
                moveTo(25f, 50f)
                lineTo(75f, 50f)
            },
            Color.Red,
            40f,
            1f,
            blurRadius = 10f,
        )
        val eraser = StrokePath(
            Path().apply {
                moveTo(50f, 30f)
                lineTo(50f, 70f)
            },
            Color.Transparent,
            10f,
            1f,
            isEraser = true,
        )
        val layer = renderDrawingLayer(listOf(brush, eraser), 100, 100, 100, 100)
        assertEquals(0, android.graphics.Color.alpha(layer[50, 50]))
        assertTrue(android.graphics.Color.alpha(layer[35, 25]) in 1..254)
        assertEquals(0, android.graphics.Color.alpha(layer[0, 0]))
    }

    @Test
    fun fullImageExportRetainsDrawingsOutsideTheInpaintMask() {
        val result = createBitmap(400, 400).apply { eraseColor(android.graphics.Color.BLUE) }
        val drawing = createBitmap(200, 200)
        Canvas(drawing).drawRect(10f, 10f, 30f, 30f, Paint().apply { color = android.graphics.Color.RED })
        val patch = createBitmap(200, 200).apply { eraseColor(android.graphics.Color.GREEN) }
        val mask = createBitmap(200, 200)
        Canvas(mask).drawRect(90f, 90f, 110f, 110f, Paint().apply { color = android.graphics.Color.WHITE })
        drawImageOverlay(result, drawing, Rect(100, 100, 300, 300))
        drawInpaintPatch(result, patch, mask, 100, 100)
        assertEquals(android.graphics.Color.RED, result[120, 120])
        assertEquals(android.graphics.Color.GREEN, result[200, 200])
        assertEquals(android.graphics.Color.BLUE, result[50, 50])
    }

    @Test
    fun laterDrawingSessionsPreserveEarlierEditsWithoutMutatingTheirSnapshot() {
        val first = createBitmap(100, 100).apply { set(10, 10, android.graphics.Color.RED) }
        val second = createBitmap(100, 100).apply { set(20, 20, android.graphics.Color.BLUE) }
        val combined = mergeDrawingLayers(first, second)
        assertEquals(android.graphics.Color.RED, combined[10, 10])
        assertEquals(android.graphics.Color.BLUE, combined[20, 20])
        assertEquals(0, first[20, 20])
    }

    @Test
    fun nonSquareDrawingAndMaskUseTheSameUploadCoordinates() {
        val drawing = createBitmap(768, 1024).apply { set(192, 64, android.graphics.Color.RED) }
        val mask = createBitmap(768, 1024).apply { set(192, 64, android.graphics.Color.WHITE) }
        val upload = padBitmapToCanvas(drawing, 1024, 1024)
        val maskUpload = padBitmapToCanvas(mask, 1024, 1024)
        assertEquals(1024, upload.width)
        assertEquals(android.graphics.Color.RED, upload[320, 64])
        assertEquals(android.graphics.Color.WHITE, maskUpload[320, 64])
    }

    @Test
    fun doneReturnsTheEditedImageAndTransparentDrawing() {
        val saved = AtomicReference<Pair<Bitmap, Bitmap>?>(null)
        val original = createBitmap(300, 200).apply { eraseColor(android.graphics.Color.WHITE) }
        compose.setContent {
            MaterialTheme {
                DrawScreen(original, { image, layer -> saved.set(image to layer) }, {})
            }
        }
        compose.onNodeWithTag("drawing_canvas").performTouchInput {
            swipe(center, center + Offset(50f, 0f))
        }
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(5_000) { saved.get() != null }
        val (image, layer) = saved.get()!!
        assertEquals(300, image.width)
        assertEquals(200, image.height)
        assertEquals(android.graphics.Color.WHITE, image[0, 0])
        assertEquals(0, android.graphics.Color.alpha(layer[0, 0]))
        assertTrue(android.graphics.Color.alpha(layer[150, 100]) > 0)
        assertTrue(image[150, 100] != android.graphics.Color.WHITE)
    }

    @Test
    fun systemBackDismissesTheDrawingScreen() {
        val dismissed = AtomicBoolean(false)
        compose.setContent {
            MaterialTheme {
                DrawScreen(createBitmap(100, 100), { _, _ -> }, { dismissed.set(true) })
            }
        }
        compose.waitForIdle()
        Espresso.pressBack()
        compose.runOnIdle { assertTrue(dismissed.get()) }
    }

    @Test
    fun zoomTransformsTheTouchpadIndicatorWithTheDrawing() {
        compose.setContent {
            MaterialTheme { DrawScreen(createBitmap(100, 100), { _, _ -> }, {}) }
        }
        val canvas = compose.onNodeWithTag("drawing_canvas")
        canvas.performTouchInput { swipe(center, center + Offset(50f, 0f)) }
        val center = canvas.fetchSemanticsNode().boundsInRoot.center
        val before = compose.onNodeWithTag("brush_indicator").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("Zoom").performClick()
        canvas.performTouchInput {
            down(0, this.center - Offset(100f, 0f))
            down(1, this.center + Offset(100f, 0f))
            updatePointerTo(0, this.center - Offset(150f, 0f))
            updatePointerTo(1, this.center + Offset(250f, 0f))
            move()
            up(0)
            up(1)
        }
        val after = compose.onNodeWithTag("brush_indicator").fetchSemanticsNode().boundsInRoot
        val scale = after.width / before.width
        assertTrue("Zoom was applied", scale > 1.5f)
        assertEquals(center.x + (before.center.x - center.x) * scale + 50f, after.center.x, 2f)
    }
}
