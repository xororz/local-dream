package io.github.xororz.localdream.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import io.github.xororz.localdream.data.DitResolution
import io.github.xororz.localdream.data.GenerationMode
import io.github.xororz.localdream.remote.RemoteApiClient
import io.github.xororz.localdream.remote.RemoteProtocol
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.service.BackgroundGenerationService
import io.github.xororz.localdream.utils.Http
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal fun checkStoragePermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    true // Android 10
} else {
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED
}

private val tokenizeClient: OkHttpClient by lazy {
    Http.client.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
}

private val healthClient: OkHttpClient by lazy {
    Http.client.newBuilder()
        .connectTimeout(100, TimeUnit.MILLISECONDS)
        .build()
}

internal data class TokenizeResult(val count: Int, val maxLength: Int, val overflowOffset: Int)

internal suspend fun tokenizePromptRequest(
    text: String,
    backendHost: String = BackgroundGenerationService.LOCAL_BACKEND_HOST,
): TokenizeResult? = withContext(Dispatchers.IO) {
    try {
        val body = JSONObject().apply { put("prompt", text) }
            .toString()
            .toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url("http://$backendHost/tokenize")
            .post(body)
            .build()
        tokenizeClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val payload = response.body?.string() ?: return@withContext null
            val json = JSONObject(payload)
            TokenizeResult(
                count = json.optInt("count", 0),
                maxLength = json.optInt("max_length", 77),
                overflowOffset = json.optInt("overflow_offset", -1),
            )
        }
    } catch (_: Exception) {
        null
    }
}

internal suspend fun checkBackendHealth(
    backendState: StateFlow<BackendService.BackendState>,
    servingModelId: StateFlow<String?>,
    expectedModelId: String,
    onHealthy: () -> Unit,
    onUnhealthy: () -> Unit,
) = withContext(Dispatchers.IO) {
    try {
        val startTime = System.currentTimeMillis()
        val timeoutDuration = 60000
        // Poll fast while the backend is likely just starting, then back off:
        // model loading takes seconds to minutes, so hammering every 100 ms
        // for the whole window is pointless.
        var pollDelayMs = 100L
        var ownErrorStreak = 0

        while (currentCoroutineContext().isActive) {
            val state = backendState.value
            // Only an error for this model (or a model-agnostic one) is ours; an
            // error left over from a different model's process still alive in the
            // stop grace window is not.
            val ownError = state is BackendService.BackendState.Error &&
                (state.modelId == null || state.modelId == expectedModelId)

            if (ownError) {
                // Require it to persist across a poll interval before failing: a
                // stale error from a previous run is superseded within ms by the
                // start this screen just issued (Starting/Running) and won't
                // survive to the next iteration; a genuine failure does.
                ownErrorStreak++
                if (ownErrorStreak >= 2) {
                    withContext(Dispatchers.Main) {
                        onUnhealthy()
                    }
                    break
                }
            } else {
                ownErrorStreak = 0

                if (System.currentTimeMillis() - startTime > timeoutDuration) {
                    withContext(Dispatchers.Main) {
                        onUnhealthy()
                    }
                    break
                }

                try {
                    val request = Request.Builder()
                        .url("http://localhost:8081/health")
                        .get()
                        .build()

                    val healthy = healthClient.newCall(request).execute().use { it.isSuccessful }
                    // A 200 only proves *some* backend answers on 8081. During a
                    // model switch (and the stop grace window) the previous model
                    // can still be alive, so also require the service to report it
                    // is serving the model this screen wants before declaring ready.
                    if (healthy && servingModelId.value == expectedModelId) {
                        withContext(Dispatchers.Main) {
                            onHealthy()
                        }
                        break
                    }
                } catch (e: Exception) {
                    // Backend not up yet; retry after the current delay.
                }
            }

            delay(pollDelayMs)
            pollDelayMs = (pollDelayMs * 2).coerceAtMost(500L)
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            onUnhealthy()
        }
    }
}

/**
 * Remote counterpart of [checkBackendHealth]: waits until the host device's
 * backend is serving [expectedModelId] at exactly [expectedWidth] x
 * [expectedHeight] and its generation port answers /health. The resolution
 * match matters: the host may still be serving the same model at an older
 * resolution when the check starts (the /select that switches it races this
 * poll), and declaring Ready then would send generations to the wrong patch.
 */
internal suspend fun checkRemoteBackendHealth(
    client: RemoteApiClient,
    expectedModelId: String,
    expectedWidth: Int,
    expectedHeight: Int,
    onHealthy: () -> Unit,
    onUnhealthy: () -> Unit,
) = withContext(Dispatchers.IO) {
    val startTime = System.currentTimeMillis()
    val timeoutDuration = 120_000L
    var pollDelayMs = 300L

    while (currentCoroutineContext().isActive) {
        if (System.currentTimeMillis() - startTime > timeoutDuration) {
            withContext(Dispatchers.Main) { onUnhealthy() }
            break
        }

        val status = client.fetchStatus()
        if (status != null) {
            val ownError = status.state == RemoteProtocol.STATE_ERROR &&
                (status.errorModelId == null || status.errorModelId == expectedModelId)
            if (ownError) {
                withContext(Dispatchers.Main) { onUnhealthy() }
                break
            }
            if (status.servingModelId == expectedModelId &&
                status.state == RemoteProtocol.STATE_RUNNING &&
                status.width == expectedWidth &&
                status.height == expectedHeight &&
                client.checkGenerationHealth()
            ) {
                withContext(Dispatchers.Main) { onHealthy() }
                break
            }
        }

        delay(pollDelayMs)
        pollDelayMs = (pollDelayMs * 2).coerceAtMost(1000L)
    }
}

/**
 * For a fixed-1024-canvas model (SDXL / Anima) with a non-1:1 aspectRatio,
 * returns the centered (target_w, target_h) region inside the 1024x1024
 * generation canvas. The longest side is forced to canvasMax (1024), the
 * shortest side is scaled by the ratio and aligned down to a multiple of 8.
 * Returns null in all other cases (non-fixed-canvas, 1:1, malformed), meaning
 * "no padding, use canvas size directly."
 */
fun computeAspectTargetSize(usesFixedCanvas: Boolean, aspectRatio: String, canvasMax: Int = 1024): Pair<Int, Int>? {
    if (!usesFixedCanvas) return null
    val parts = aspectRatio.split(":")
    if (parts.size != 2) return null
    val rw = parts[0].toIntOrNull() ?: return null
    val rh = parts[1].toIntOrNull() ?: return null
    if (rw <= 0 || rh <= 0 || rw == rh) return null
    return if (rw >= rh) {
        val th = ((canvasMax.toDouble() * rh / rw).toInt() / 8 * 8).coerceAtLeast(8)
        Pair(canvasMax, th)
    } else {
        val tw = ((canvasMax.toDouble() * rw / rh).toInt() / 8 * 8).coerceAtLeast(8)
        Pair(tw, canvasMax)
    }
}

/**
 * Width and height a DiT model will actually run, snapped to
 * [DitResolution.SIZE_STEP].
 */
const val DIT_MIN_SIZE = DitResolution.MIN_SIZE
const val DIT_MAX_SIZE = DitResolution.MAX_SIZE
const val DIT_SIZE_STEP = DitResolution.SIZE_STEP

/** Slider positions between DIT_MIN_SIZE and DIT_MAX_SIZE, exclusive of both. */
const val DIT_SIZE_STEPS = DitResolution.SLIDER_STEPS

fun snapDitSize(value: Float): Int = DitResolution.snap(value)

/**
 * GCD-reduces (width, height) into a "W:H" aspect-ratio string.
 * Used by reproduce/import paths to recover an aspect from a recorded result size.
 */
fun inferAspectRatioString(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return "1:1"
    var a = width
    var b = height
    while (b != 0) {
        val t = b
        b = a % b
        a = t
    }
    return "${width / a}:${height / a}"
}

/**
 * Pads `src` (already at targetW x targetH) into a canvas of size canvasW x canvasH
 * with a centered placement and black borders. If src already matches canvas size,
 * returns the source unchanged.
 */
fun padBitmapToCanvas(src: Bitmap, canvasW: Int, canvasH: Int): Bitmap {
    if (src.width == canvasW && src.height == canvasH) return src
    val out = createBitmap(canvasW, canvasH)
    val canvas = Canvas(out)
    canvas.drawColor(android.graphics.Color.BLACK)
    val left = ((canvasW - src.width) / 2).toFloat()
    val top = ((canvasH - src.height) / 2).toFloat()
    canvas.drawBitmap(src, left, top, null)
    return out
}

/** Snap before decoding the crop so generation and stitching use the same pixels. */
internal fun snapInpaintCropRect(rect: Rect, imageWidth: Int, imageHeight: Int, tolerance: Int): Rect {
    val result = Rect(rect)
    if (imageWidth - result.width() <= tolerance) {
        result.left = 0
        result.right = imageWidth
    } else if (result.right >= imageWidth - tolerance) {
        result.offset(imageWidth - result.right, 0)
    } else if (result.left <= tolerance) {
        result.offset(-result.left, 0)
    }
    if (imageHeight - result.height() <= tolerance) {
        result.top = 0
        result.bottom = imageHeight
    } else if (result.bottom >= imageHeight - tolerance) {
        result.offset(0, imageHeight - result.bottom)
    } else if (result.top <= tolerance) {
        result.offset(0, -result.top)
    }
    return result
}

internal fun mergeDrawingLayers(previous: Bitmap?, drawing: Bitmap): Bitmap {
    if (previous == null) return drawing
    return previous.copy(Bitmap.Config.ARGB_8888, true).apply {
        Canvas(this).drawBitmap(drawing, 0f, 0f, null)
    }
}

internal fun drawImageOverlay(target: Bitmap, drawing: Bitmap, crop: Rect) {
    Canvas(target).drawBitmap(drawing, null, crop, Paint(Paint.FILTER_BITMAP_FLAG))
}

// Feathered inpaint stitching: the generated patch went through a
// downscale-to-model-resolution / upscale-back round trip, so even its unmasked
// pixels differ from the source image (lost high frequencies, resampling
// shifts). Pasting the whole rectangle therefore leaves a faint square seam.
// Instead the patch is alpha-blended: opaque over the painted mask, smoothstep
// falloff to transparent within a feather band just outside it, so everything
// beyond the band keeps the source image's exact pixels.

// Alpha map is computed at reduced resolution and upscaled with bilinear
// filtering; the feather is smooth so this loses nothing visible.
private const val ALPHA_MAP_MAX_DIM = 1024

// Mask alpha at or above this counts as painted (mask strokes are opaque
// white; lower values only appear on anti-aliased stroke edges).
private const val MASK_PAINTED_MIN_ALPHA = 128

// 3-4 chamfer weights: distances are in units of 3 per pixel, approximating
// Euclidean distance.
private const val CHAMFER_STRAIGHT = 3
private const val CHAMFER_DIAGONAL = 4

// Feather band width in full-resolution pixels: patchMaxDim / FEATHER_DIVISOR,
// at least MIN_FEATHER_PX.
private const val FEATHER_DIVISOR = 32
private const val MIN_FEATHER_PX = 16

/**
 * Draws an inpaint result [patch] onto [target] at ([left], [top]).
 *
 * When [mask] has painted pixels the patch is feather-blended (see above) so
 * no rectangular seam appears; otherwise falls back to an opaque paste.
 * [mask] may be any size; it is scaled to the patch's aspect space.
 */
internal fun drawInpaintPatch(target: Bitmap, patch: Bitmap, mask: Bitmap?, left: Int, top: Int) {
    val canvas = Canvas(target)
    // Force alpha to 0 along patch edges that sit inside the target image, so
    // a mask painted close to the crop boundary can't leave a straight seam
    // there. Edges flush with the image border keep full strength instead.
    val fade = FadeEdges(
        left = left > 0,
        top = top > 0,
        right = left + patch.width < target.width,
        bottom = top + patch.height < target.height,
    )
    val alphaMap = mask?.let { buildFeatheredAlphaMap(it, patch.width, patch.height, fade) }
    if (alphaMap == null) {
        canvas.drawBitmap(patch, left.toFloat(), top.toFloat(), null)
        return
    }
    val maskedPatch = patch.copy(Bitmap.Config.ARGB_8888, true)
    // Decoded images are opaque, and copy() keeps hasAlpha=false; without this
    // the DST_IN result is drawn as opaque black instead of transparent.
    maskedPatch.setHasAlpha(true)
    Canvas(maskedPatch).drawBitmap(
        alphaMap,
        null,
        Rect(0, 0, maskedPatch.width, maskedPatch.height),
        Paint(Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        },
    )
    canvas.drawBitmap(maskedPatch, left.toFloat(), top.toFloat(), null)
}

/** Which patch edges must fade to zero alpha (edges interior to the target image). */
private data class FadeEdges(
    val left: Boolean,
    val top: Boolean,
    val right: Boolean,
    val bottom: Boolean,
)

private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

/**
 * Builds the blend alpha map for a patch of size [patchW] x [patchH]: opaque
 * over the painted mask, smoothstep falloff to transparent across the feather
 * band outside it, and additionally ramped to zero toward the patch edges
 * selected by [fade]. Returned at reduced resolution (the caller scales it up
 * with bilinear filtering). Null when nothing is painted on the mask.
 */
private fun buildFeatheredAlphaMap(mask: Bitmap, patchW: Int, patchH: Int, fade: FadeEdges): Bitmap? {
    val downscale = minOf(1f, ALPHA_MAP_MAX_DIM.toFloat() / maxOf(patchW, patchH))
    val w = (patchW * downscale).roundToInt().coerceAtLeast(1)
    val h = (patchH * downscale).roundToInt().coerceAtLeast(1)
    val scaledMask = if (mask.width != w || mask.height != h) mask.scale(w, h) else mask
    val pixels = IntArray(w * h)
    scaledMask.getPixels(pixels, 0, w, 0, 0, w, h)

    val dist = chamferDistanceFromMask(pixels, w, h) ?: return null

    val featherPx = maxOf(MIN_FEATHER_PX, maxOf(patchW, patchH) / FEATHER_DIVISOR)
    // Feather width in low-res pixels and in chamfer units.
    val featherLow = (featherPx * downscale).coerceAtLeast(1f)
    val feather = featherLow * CHAMFER_STRAIGHT
    for (y in 0 until h) {
        for (x in 0 until w) {
            val i = y * w + x
            val maskOpacity = 1f - smoothstep((dist[i] / feather).coerceAtMost(1f))
            var edgePx = Int.MAX_VALUE
            if (fade.left) edgePx = minOf(edgePx, x)
            if (fade.top) edgePx = minOf(edgePx, y)
            if (fade.right) edgePx = minOf(edgePx, w - 1 - x)
            if (fade.bottom) edgePx = minOf(edgePx, h - 1 - y)
            val edgeOpacity = if (edgePx == Int.MAX_VALUE) {
                1f
            } else {
                smoothstep((edgePx / featherLow).coerceAtMost(1f))
            }
            val opacity = maskOpacity * edgeOpacity
            pixels[i] = ((opacity * 0xFF).roundToInt() shl 24) or 0xFFFFFF
        }
    }
    return createBitmap(w, h).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
}

/**
 * Two-pass 3-4 chamfer distance transform over the mask's alpha channel:
 * distance from each pixel to the nearest painted pixel, in CHAMFER_STRAIGHT
 * units per pixel. Null when no pixel is painted.
 */
@Suppress("CyclomaticComplexMethod")
private fun chamferDistanceFromMask(maskPixels: IntArray, w: Int, h: Int): IntArray? {
    val far = Int.MAX_VALUE / 2
    val dist = IntArray(maskPixels.size)
    var anyPainted = false
    for (i in dist.indices) {
        if ((maskPixels[i] ushr 24) >= MASK_PAINTED_MIN_ALPHA) {
            anyPainted = true
        } else {
            dist[i] = far
        }
    }
    if (!anyPainted) return null

    for (y in 0 until h) {
        for (x in 0 until w) {
            val i = y * w + x
            var d = dist[i]
            if (d == 0) continue
            if (x > 0) d = minOf(d, dist[i - 1] + CHAMFER_STRAIGHT)
            if (y > 0) {
                d = minOf(d, dist[i - w] + CHAMFER_STRAIGHT)
                if (x > 0) d = minOf(d, dist[i - w - 1] + CHAMFER_DIAGONAL)
                if (x < w - 1) d = minOf(d, dist[i - w + 1] + CHAMFER_DIAGONAL)
            }
            dist[i] = d
        }
    }
    for (y in h - 1 downTo 0) {
        for (x in w - 1 downTo 0) {
            val i = y * w + x
            var d = dist[i]
            if (d == 0) continue
            if (x < w - 1) d = minOf(d, dist[i + 1] + CHAMFER_STRAIGHT)
            if (y < h - 1) {
                d = minOf(d, dist[i + w] + CHAMFER_STRAIGHT)
                if (x < w - 1) d = minOf(d, dist[i + w + 1] + CHAMFER_DIAGONAL)
                if (x > 0) d = minOf(d, dist[i + w - 1] + CHAMFER_DIAGONAL)
            }
            dist[i] = d
        }
    }
    return dist
}

/**
 * PNG-compresses the bitmap (lossless; the quality argument is ignored by the
 * PNG encoder) and returns it as a base64 string for backend upload.
 *
 * Uploads must stay lossless: inpaint pastes the unmasked region of the
 * uploaded base image verbatim into the final result (laplacian blend), so
 * any compression artifacts would survive into the output. Masks need exact
 * pixel values anyway.
 */
internal fun bitmapToBase64Png(bitmap: Bitmap): String {
    val baos = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
    return Base64.getEncoder().encodeToString(baos.toByteArray())
}

/**
 * JPEG-compresses the bitmap for backend upload. Unlike inpaint uploads
 * (which must stay lossless because unmasked pixels are pasted back into the
 * result verbatim), an ultrafix base image has every pixel re-encoded through
 * the VAE and renoised, so JPEG artifacts at this quality are immaterial,
 * while PNG-encoding a 4x-class image would take several seconds and tens
 * of MB.
 */
internal fun bitmapToBase64Jpeg(bitmap: Bitmap, quality: Int = 95): String {
    val baos = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
    return Base64.getEncoder().encodeToString(baos.toByteArray())
}

/** Default generation canvas side length for a model class. */
internal fun defaultGenerationSize(
    usesFixedCanvas: Boolean,
    runOnCpu: Boolean,
    isDit: Boolean = false,
): Int = when {
    // 1024 in 4-8 steps is where these turbo models are quick enough to feel
    // interactive; 1536 and 2048 stay available in the resolution picker.
    isDit -> 1024

    usesFixedCanvas -> 1024

    runOnCpu -> 256

    else -> 512
}

/**
 * Maps a target UltraFix denoise-step count to the denoise_strength the backend
 * needs to run exactly that many steps.
 *
 * The backend derives its start step as floor(steps * (1 - strength)) and then
 * runs (steps - start) denoising steps. Solving for a result of [denoiseSteps]
 * and aiming at the midpoint of the target integer interval gives
 * strength = (denoiseSteps - 0.5) / totalSteps: the half-step offset keeps the
 * backend's float product safely inside the right interval, so rounding error
 * (e.g. a 0.40001 that a naive ceil() would push to the next step) can never tip
 * it across an integer boundary. [denoiseSteps] is clamped to [0, totalSteps];
 * 0 collapses to the backend's minimum since it never runs zero steps.
 */
internal fun ultrafixDenoiseStrength(denoiseSteps: Int, totalSteps: Int): Float {
    if (totalSteps <= 0) return 0f
    val clamped = denoiseSteps.coerceIn(0, totalSteps)
    return ((clamped - 0.5f) / totalSteps).coerceIn(0f, 1f)
}

@Immutable
data class GenerationParameters(
    val steps: Int,
    val cfg: Float,
    val seed: Long?,
    val prompt: String,
    val negativePrompt: String,
    val generationTime: String?,
    val width: Int,
    val height: Int,
    val runOnCpu: Boolean,
    val denoiseStrength: Float = 0.6f,
    val useOpenCL: Boolean = false,
    val scheduler: String = "dpm",
    val mode: GenerationMode = GenerationMode.UNKNOWN,
)
