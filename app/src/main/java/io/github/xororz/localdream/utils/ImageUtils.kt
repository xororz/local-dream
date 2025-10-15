package io.github.xororz.localdream.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.ui.screens.GenerationParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.TimeUnit


suspend fun performUpscale(
    context: Context,
    bitmap: Bitmap,
    modelId: String,
    upscalerId: String
): Bitmap = withContext(Dispatchers.IO) {
    val totalStartTime = System.currentTimeMillis()

    // Get upscaler model path
    val upscalerModelsDir = File(Model.getModelsDir(context), upscalerId)
    val upscalerFile = File(upscalerModelsDir, "upscaler.bin")

    if (!upscalerFile.exists()) {
        throw Exception("Upscaler model file not found: ${upscalerFile.absolutePath}")
    }

    // Convert bitmap to RGB bytes
    val prepareStartTime = System.currentTimeMillis()
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val rgbBytes = ByteArray(width * height * 3)
    for (i in pixels.indices) {
        val pixel = pixels[i]
        rgbBytes[i * 3] = ((pixel shr 16) and 0xFF).toByte()
        rgbBytes[i * 3 + 1] = ((pixel shr 8) and 0xFF).toByte()
        rgbBytes[i * 3 + 2] = (pixel and 0xFF).toByte()
    }
    android.util.Log.d(
        "UpscaleBinary",
        "Prepare RGB data took: ${System.currentTimeMillis() - prepareStartTime}ms"
    )

    // Prepare binary request
    val url = URL("http://localhost:8081/upscale")
    val connection = url.openConnection() as HttpURLConnection

    try {
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.setRequestProperty("X-Image-Width", width.toString())
        connection.setRequestProperty("X-Image-Height", height.toString())
        connection.setRequestProperty("X-Upscaler-Path", upscalerFile.absolutePath)
        connection.doOutput = true
        connection.connectTimeout = 300000 // 5 minutes
        connection.readTimeout = 300000

        // Send RGB binary data directly
        val sendStartTime = System.currentTimeMillis()
        connection.outputStream.use { os ->
            os.write(rgbBytes)
        }
        android.util.Log.d(
            "UpscaleBinary",
            "Send data took: ${System.currentTimeMillis() - sendStartTime}ms"
        )

        // Read response
        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            // Read JPEG binary data
            val readStartTime = System.currentTimeMillis()
            val imageBytes = connection.inputStream.use { it.readBytes() }
            android.util.Log.d(
                "UpscaleBinary",
                "Receive JPEG data took: ${System.currentTimeMillis() - readStartTime}ms, size: ${imageBytes.size / 1024}KB"
            )

            // Decode JPEG to Bitmap
            val decodeStartTime = System.currentTimeMillis()
            val resultBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            android.util.Log.d(
                "UpscaleBinary",
                "Decode JPEG took: ${System.currentTimeMillis() - decodeStartTime}ms"
            )

            if (resultBitmap == null) {
                throw Exception("Failed to decode JPEG response")
            }

            // Read response headers
            val resultWidth =
                connection.getHeaderField("X-Output-Width")?.toIntOrNull() ?: resultBitmap.width
            val resultHeight =
                connection.getHeaderField("X-Output-Height")?.toIntOrNull() ?: resultBitmap.height
            val durationMs = connection.getHeaderField("X-Duration-Ms")?.toIntOrNull() ?: 0

            android.util.Log.d("UpscaleBinary", "=== Upscale complete ===")
            android.util.Log.d("UpscaleBinary", "Server processing took: ${durationMs}ms")
            android.util.Log.d(
                "UpscaleBinary",
                "Client total time: ${System.currentTimeMillis() - totalStartTime}ms"
            )
            android.util.Log.d("UpscaleBinary", "Output size: ${resultWidth}x${resultHeight}")

            resultBitmap
        } else {
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
            throw Exception("Upscale failed with response code: $responseCode, error: $errorBody")
        }
    } finally {
        connection.disconnect()
    }
}


suspend fun reportImage(
    context: Context,
    bitmap: Bitmap,
    modelName: String,
    params: GenerationParameters,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            val byteArrayOutputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, byteArrayOutputStream)
            val byteArray = byteArrayOutputStream.toByteArray()
            val base64Image = Base64.getEncoder().encodeToString(byteArray)

            val jsonObject = JSONObject().apply {
                put("model_name", modelName)
                put("generation_params", JSONObject().apply {
                    put("prompt", params.prompt)
                    put("negative_prompt", params.negativePrompt)
                    put("steps", params.steps)
                    put("cfg", params.cfg)
                    put("seed", params.seed ?: JSONObject.NULL)
                    put("size", params.size)
                    put("run_on_cpu", params.runOnCpu)
                    put("generation_time", params.generationTime ?: JSONObject.NULL)
                })
                put("image_data", base64Image)
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val requestBody = jsonObject.toString()
                .toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url("https://report.chino.icu/report")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

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

suspend fun saveImage(
    context: Context,
    bitmap: Bitmap,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            android.util.Log.d(
                "SaveImage",
                "Start saving image - size: ${bitmap.width}x${bitmap.height}"
            )

            // Save as JPEG if width or height is greater than 1024, otherwise save as PNG
            val isLargeImage = bitmap.width > 1024 || bitmap.height > 1024
            val format = if (isLargeImage) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
            val extension = if (isLargeImage) "jpg" else "png"
            val mimeType = if (isLargeImage) "image/jpeg" else "image/png"
            val quality = if (isLargeImage) 95 else 100

            android.util.Log.d("SaveImage", "Save format: ${if (isLargeImage) "JPEG" else "PNG"}")

            val timestamp = System.currentTimeMillis()
            val filename = "generated_image_$timestamp.$extension"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 MediaStore API
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }

                val resolver = context.contentResolver
                val createUriTime = System.currentTimeMillis()
                val uri =
                    resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                        ?: throw IOException("Failed to create MediaStore entry")
                android.util.Log.d(
                    "SaveImage",
                    "Create URI took: ${System.currentTimeMillis() - createUriTime}ms"
                )

                val compressStartTime = System.currentTimeMillis()
                resolver.openOutputStream(uri)?.use { outputStream ->
                    bitmap.compress(format, quality, outputStream)
                } ?: throw IOException("Failed to open output stream")
                android.util.Log.d(
                    "SaveImage",
                    "Compression and writing took: ${System.currentTimeMillis() - compressStartTime}ms"
                )
            } else {
                // Android 9
                val imagesDir = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES
                    ),
                    "LocalDream"
                )

                if (!imagesDir.exists()) {
                    imagesDir.mkdirs()
                }

                val file = File(imagesDir, filename)
                val compressStartTime = System.currentTimeMillis()
                FileOutputStream(file).use { out ->
                    bitmap.compress(format, quality, out)
                }
                android.util.Log.d(
                    "SaveImage",
                    "Compression and writing took: ${System.currentTimeMillis() - compressStartTime}ms"
                )

                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.toString()),
                    arrayOf(mimeType),
                    null
                )
            }

            val totalTime = System.currentTimeMillis() - startTime
            android.util.Log.d("SaveImage", "Save complete - total time: ${totalTime}ms")

            withContext(Dispatchers.Main) {
                onSuccess()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onError("Failed to save: ${e.localizedMessage}")
            }
        }
    }
}