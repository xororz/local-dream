package io.github.xororz.localdream.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.service.BackgroundGenerationService
import io.github.xororz.localdream.ui.screens.GenerationParameters
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private val saveSequence = AtomicLong(0L)

private val reportClient: OkHttpClient by lazy {
    Http.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}

// A single upscale call covers model load + tiled inference, which can take
// minutes for large inputs.
private val upscaleClient: OkHttpClient by lazy {
    Http.client.newBuilder()
        .connectTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()
}

private fun nextSaveFilename(extension: String): String {
    val ts = System.currentTimeMillis()
    val seq = saveSequence.getAndIncrement()
    return "generated_image_${ts}_$seq.$extension"
}

// The upscaler models are fixed 4x; smaller target scales are produced by
// downscaling the native result on the client.
const val UPSCALER_NATIVE_SCALE = 4

private fun applyScaledAlpha(
    rgbBitmap: Bitmap,
    alphaBytes: ByteArray,
    sourceWidth: Int,
    sourceHeight: Int,
): Bitmap {
    val sourceAlpha = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ALPHA_8)
    val alphaBuffer = if (sourceAlpha.rowBytes == sourceWidth) {
        alphaBytes
    } else {
        ByteArray(sourceAlpha.rowBytes * sourceHeight).also { padded ->
            repeat(sourceHeight) { row ->
                alphaBytes.copyInto(
                    destination = padded,
                    destinationOffset = row * sourceAlpha.rowBytes,
                    startIndex = row * sourceWidth,
                    endIndex = (row + 1) * sourceWidth,
                )
            }
        }
    }
    sourceAlpha.copyPixelsFromBuffer(ByteBuffer.wrap(alphaBuffer))
    val scaledAlpha = if (
        sourceWidth == rgbBitmap.width && sourceHeight == rgbBitmap.height
    ) {
        sourceAlpha
    } else {
        Bitmap.createScaledBitmap(sourceAlpha, rgbBitmap.width, rgbBitmap.height, true)
    }

    val rgbaBitmap = rgbBitmap.copy(Bitmap.Config.ARGB_8888, true)
        ?: throw IllegalStateException("Failed to allocate RGBA upscale result")
    rgbaBitmap.setHasAlpha(true)
    val alphaPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    Canvas(rgbaBitmap).drawBitmap(scaledAlpha, 0f, 0f, alphaPaint)
    alphaPaint.xfermode = null

    if (scaledAlpha !== sourceAlpha) scaledAlpha.recycle()
    sourceAlpha.recycle()
    if (rgbaBitmap !== rgbBitmap) rgbBitmap.recycle()
    return rgbaBitmap
}

/**
 * Upscales [bitmap] with the model identified by [upscalerId].
 *
 * The backend always emits a [UPSCALER_NATIVE_SCALE]x result. When [targetScale]
 * is smaller, the result is downscaled to [targetScale]x of the source on the
 * client, since the model itself only runs at its native ratio.
 *
 * In connected-device mode, [backendHost] points at the host's generation
 * port and [remoteUpscalerPath] is the weight file's path on the HOST's
 * filesystem (from its catalog); the native /upscale endpoint loads whatever
 * X-Upscaler-Path names on the machine it runs on.
 */
suspend fun performUpscale(
    context: Context,
    bitmap: Bitmap,
    upscalerId: String,
    targetScale: Int = UPSCALER_NATIVE_SCALE,
    backendHost: String = BackgroundGenerationService.LOCAL_BACKEND_HOST,
    remoteUpscalerPath: String? = null,
): Bitmap = withContext(Dispatchers.IO) {
    val totalStartTime = System.currentTimeMillis()

    val upscalerPath = remoteUpscalerPath ?: run {
        val upscalerFile =
            File(File(Model.getModelsDir(context), upscalerId), Model.UPSCALER_FILE_NAME)
        if (!upscalerFile.exists()) {
            throw Exception("Upscaler model file not found: ${upscalerFile.absolutePath}")
        }
        upscalerFile.absolutePath
    }

    // Convert bitmap to RGB bytes
    val prepareStartTime = System.currentTimeMillis()
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val rgbBytes = ByteArray(width * height * 3)
    val alphaBytes = if (bitmap.hasAlpha()) ByteArray(width * height) else null
    var hasTransparency = false
    for (i in pixels.indices) {
        val pixel = pixels[i]
        rgbBytes[i * 3] = ((pixel shr 16) and 0xFF).toByte()
        rgbBytes[i * 3 + 1] = ((pixel shr 8) and 0xFF).toByte()
        rgbBytes[i * 3 + 2] = (pixel and 0xFF).toByte()
        alphaBytes?.let {
            val alpha = (pixel ushr 24) and 0xFF
            it[i] = alpha.toByte()
            if (alpha != 0xFF) hasTransparency = true
        }
    }
    Log.d(
        "UpscaleBinary",
        "Prepare RGB data took: ${System.currentTimeMillis() - prepareStartTime}ms",
    )

    // Binary protocol: raw RGB in, JPEG out, metadata in headers.
    val request = Request.Builder()
        .url("http://$backendHost/upscale")
        .header("X-Image-Width", width.toString())
        .header("X-Image-Height", height.toString())
        .header("X-Upscaler-Path", upscalerPath)
        .post(rgbBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
        .build()

    upscaleClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            val errorBody = response.body?.string()
            throw Exception("Upscale failed with response code: ${response.code}, error: $errorBody")
        }

        // Read JPEG binary data
        val readStartTime = System.currentTimeMillis()
        val imageBytes = response.body?.bytes() ?: throw Exception("Empty upscale response")
        Log.d(
            "UpscaleBinary",
            "Receive JPEG data took: ${System.currentTimeMillis() - readStartTime}ms, size: ${imageBytes.size / 1024}KB",
        )

        // Decode JPEG to Bitmap
        val decodeStartTime = System.currentTimeMillis()
        var resultBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw Exception("Failed to decode JPEG response")
        Log.d(
            "UpscaleBinary",
            "Decode JPEG took: ${System.currentTimeMillis() - decodeStartTime}ms",
        )

        val resultWidth =
            response.header("X-Output-Width")?.toIntOrNull() ?: resultBitmap.width
        val resultHeight =
            response.header("X-Output-Height")?.toIntOrNull() ?: resultBitmap.height
        val durationMs = response.header("X-Duration-Ms")?.toIntOrNull() ?: 0

        Log.d("UpscaleBinary", "=== Upscale complete ===")
        Log.d("UpscaleBinary", "Server processing took: ${durationMs}ms")
        Log.d(
            "UpscaleBinary",
            "Client total time: ${System.currentTimeMillis() - totalStartTime}ms",
        )
        Log.d("UpscaleBinary", "Output size: ${resultWidth}x$resultHeight")

        // The model only runs at its native ratio; emulate a smaller scale by
        // downscaling the native result to targetScale x the source dimensions.
        val clampedScale = targetScale.coerceIn(1, UPSCALER_NATIVE_SCALE)
        if (clampedScale != UPSCALER_NATIVE_SCALE) {
            val targetWidth = width * clampedScale
            val targetHeight = height * clampedScale
            if (resultBitmap.width != targetWidth || resultBitmap.height != targetHeight) {
                val scaled = Bitmap.createScaledBitmap(
                    resultBitmap,
                    targetWidth,
                    targetHeight,
                    true,
                )
                if (scaled != resultBitmap) {
                    resultBitmap.recycle()
                }
                Log.d("UpscaleBinary", "Resized to ${targetWidth}x$targetHeight (${clampedScale}x)")
                resultBitmap = scaled
            }
        }

        // The neural upscalers are RGB-only. Preserve a Qwen RGBA result by
        // scaling its alpha plane separately and applying it after RGB
        // upscaling; opaque inputs keep the existing JPEG-sized memory path.
        if (hasTransparency && alphaBytes != null) {
            applyScaledAlpha(resultBitmap, alphaBytes, width, height)
        } else {
            resultBitmap
        }
    }
}

suspend fun reportImage(
    bitmap: Bitmap,
    modelName: String,
    params: GenerationParameters,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    withContext(Dispatchers.IO) {
        try {
            val byteArrayOutputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, byteArrayOutputStream)
            val byteArray = byteArrayOutputStream.toByteArray()
            val base64Image = Base64.getEncoder().encodeToString(byteArray)

            val jsonObject = JSONObject().apply {
                put("model_name", modelName)
                put(
                    "generation_params",
                    JSONObject().apply {
                        put("prompt", params.prompt)
                        put("negative_prompt", params.negativePrompt)
                        put("steps", params.steps)
                        put("cfg", params.cfg)
                        put("seed", params.seed ?: JSONObject.NULL)
                        put("size", "${params.width}x${params.height}")
                        put("run_on_cpu", params.runOnCpu)
                        put("generation_time", params.generationTime ?: JSONObject.NULL)
                    },
                )
                put("image_data", base64Image)
            }

            val requestBody = jsonObject.toString()
                .toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url("https://report.chino.icu/report")
                .post(requestBody)
                .build()

            val response = reportClient.newCall(request).execute()

            withContext(Dispatchers.Main) {
                if (response.isSuccessful) {
                    onSuccess()
                } else {
                    onError("Report failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
//                onError("Failed to report: ${e.localizedMessage}")
                onError("Network Error")
            }
        }
    }
}

suspend fun saveImage(context: Context, bitmap: Bitmap, onSuccess: () -> Unit, onError: (String) -> Unit) {
    withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            Log.d(
                "SaveImage",
                "Start saving image - size: ${bitmap.width}x${bitmap.height}",
            )

            // Transparent output (notably Qwen Image 2.1) must stay PNG. Opaque
            // large images retain the existing JPEG space optimization.
            val isLargeImage = bitmap.width > 1024 || bitmap.height > 1024
            val usePng = bitmap.hasAlpha() || !isLargeImage
            val format = if (usePng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val extension = if (usePng) "png" else "jpg"
            val mimeType = if (usePng) "image/png" else "image/jpeg"
            val quality = if (usePng) 100 else 95

            Log.d("SaveImage", "Save format: ${if (usePng) "PNG" else "JPEG"}")

            val filename = nextSaveFilename(extension)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 MediaStore API
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/LocalDream",
                    )
                }

                val resolver = context.contentResolver
                val createUriTime = System.currentTimeMillis()
                val uri =
                    resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                        ?: throw IOException("Failed to create MediaStore entry")
                Log.d(
                    "SaveImage",
                    "Create URI took: ${System.currentTimeMillis() - createUriTime}ms",
                )

                val compressStartTime = System.currentTimeMillis()
                resolver.openOutputStream(uri)?.use { outputStream ->
                    bitmap.compress(format, quality, outputStream)
                } ?: throw IOException("Failed to open output stream")
                Log.d(
                    "SaveImage",
                    "Compression and writing took: ${System.currentTimeMillis() - compressStartTime}ms",
                )
            } else {
                // Android 9
                val imagesDir = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES,
                    ),
                    "LocalDream",
                )

                if (!imagesDir.exists()) {
                    imagesDir.mkdirs()
                }

                val file = File(imagesDir, filename)
                val compressStartTime = System.currentTimeMillis()
                FileOutputStream(file).use { out ->
                    bitmap.compress(format, quality, out)
                }
                Log.d(
                    "SaveImage",
                    "Compression and writing took: ${System.currentTimeMillis() - compressStartTime}ms",
                )

                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.toString()),
                    arrayOf(mimeType),
                    null,
                )
            }

            val totalTime = System.currentTimeMillis() - startTime
            Log.d("SaveImage", "Save complete - total time: ${totalTime}ms")

            withContext(Dispatchers.Main) {
                onSuccess()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onError(
                    context.getString(
                        R.string.save_failed_detail,
                        e.localizedMessage ?: context.getString(R.string.unknown_error),
                    ),
                )
            }
        }
    }
}

/**
 * Copies a pre-encoded image file (PNG/JPEG) into the Pictures/LocalDream gallery
 * folder without decoding + re-encoding. Used for batch-saving history items
 * where the source file is already in the format we want to export.
 */
suspend fun saveImageFromFile(context: Context, sourceFile: File, onSuccess: () -> Unit, onError: (String) -> Unit) {
    withContext(Dispatchers.IO) {
        try {
            val extension = sourceFile.extension.lowercase().ifEmpty { "png" }
            val mimeType = when (extension) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/*"
            }
            val filename = nextSaveFilename(extension)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/LocalDream",
                    )
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues,
                ) ?: throw IOException("Failed to create MediaStore entry")

                resolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { input -> input.copyTo(out) }
                } ?: throw IOException("Failed to open output stream")
            } else {
                val imagesDir = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES,
                    ),
                    "LocalDream",
                )
                if (!imagesDir.exists()) imagesDir.mkdirs()
                val outFile = File(imagesDir, filename)
                sourceFile.inputStream().use { input ->
                    FileOutputStream(outFile).use { out -> input.copyTo(out) }
                }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(outFile.toString()),
                    arrayOf(mimeType),
                    null,
                )
            }

            withContext(Dispatchers.Main) { onSuccess() }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onError(
                    context.getString(
                        R.string.save_failed_detail,
                        e.localizedMessage ?: context.getString(R.string.unknown_error),
                    ),
                )
            }
        }
    }
}
