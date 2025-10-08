package io.github.xororz.localdream.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Base64
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import io.github.xororz.localdream.R
import java.io.File

class BackgroundGenerationService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }

    companion object {
        private const val CHANNEL_ID = "image_generation_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "stop_generation"

        private val _generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
        val generationState: StateFlow<GenerationState> = _generationState

        fun resetState() {
            _generationState.value = GenerationState.Idle
        }

        fun clearCompleteState() {
            if (_generationState.value is GenerationState.Complete) {
                _generationState.value = GenerationState.Idle
            }
        }
    }

    sealed class GenerationState {
        object Idle : GenerationState()
        data class Progress(val progress: Float) : GenerationState()
        data class Complete(val bitmap: Bitmap, val seed: Long?) : GenerationState()
        data class Error(val message: String) : GenerationState()
    }

    private fun updateState(newState: GenerationState) {
        _generationState.value = newState
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("GenerationService", "service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("GenerationService", "service execute: ${intent?.extras}")

        when (intent?.action) {
            ACTION_STOP -> {
                android.util.Log.d("GenerationService", "service stopped")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val prompt = intent?.getStringExtra("prompt")
        android.util.Log.d("GenerationService", "prompt: $prompt")

        if (prompt == null) {
            android.util.Log.e("GenerationService", "empty prompt")
            return START_NOT_STICKY
        }

        val negativePrompt = intent.getStringExtra("negative_prompt") ?: ""
        val steps = intent.getIntExtra("steps", 28)
        val cfg = intent.getFloatExtra("cfg", 7f)
        val seed = if (intent.hasExtra("seed")) intent.getLongExtra("seed", 0) else null
        val size = intent.getIntExtra("size", 512)
        val denoiseStrength = intent.getFloatExtra("denoise_strength", 0.6f)
        val useOpenCL = intent.getBooleanExtra("use_opencl", false)

        val image = if (intent.getBooleanExtra("has_image", false)) {
            try {
                val tmpFile = File(applicationContext.filesDir, "tmp.txt")
                if (tmpFile.exists()) {
                    tmpFile.readText()
                } else {
                    null
                }
            } catch (e: Exception) {
                android.util.Log.e("GenerationService", "Failed to read image data", e)
                null
            }
        } else {
            null
        }
        val mask = if (intent.getBooleanExtra("has_mask", false)) {
            try {
                val maskFile = File(applicationContext.filesDir, "mask.txt")
                if (maskFile.exists()) {
                    maskFile.readText()
                } else {
                    android.util.Log.w(
                        "GenerationService",
                        "has_mask is true but mask.txt not found"
                    )
                    null
                }
            } catch (e: Exception) {
                android.util.Log.e("GenerationService", "Failed to read mask data", e)
                null
            }
        } else {
            null
        }

        android.util.Log.d("GenerationService", "params: steps=$steps, cfg=$cfg, seed=$seed")

        startForeground(NOTIFICATION_ID, createNotification(0f))

        if (_generationState.value is GenerationState.Complete) {
            updateState(GenerationState.Idle)
        }

        serviceScope.launch {
            android.util.Log.d("GenerationService", "start generation")
            runGeneration(
                prompt,
                negativePrompt,
                steps,
                cfg,
                seed,
                size,
                image,
                mask,
                denoiseStrength,
                useOpenCL
            )
        }

        return START_NOT_STICKY
    }

    private suspend fun runGeneration(
        prompt: String,
        negativePrompt: String,
        steps: Int,
        cfg: Float,
        seed: Long?,
        size: Int,
        image: String?,
        mask: String?,
        denoiseStrength: Float,
        useOpenCL: Boolean
    ) = withContext(Dispatchers.IO) {
        try {
            updateState(GenerationState.Progress(0f))

            val jsonObject = JSONObject().apply {
                put("prompt", prompt)
                put("negative_prompt", negativePrompt)
                put("steps", steps)
                put("cfg", cfg)
                put("use_cfg", true)
                put("size", size)
                put("denoise_strength", denoiseStrength)
                put("use_opencl", useOpenCL)
                seed?.let { put("seed", it) }
                image?.let { put("image", it) }
                mask?.let { put("mask", it) }
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(3600, TimeUnit.SECONDS)
                .readTimeout(3600, TimeUnit.SECONDS)
                .writeTimeout(3600, TimeUnit.SECONDS)
                .callTimeout(3600, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            val request = Request.Builder()
                .url("http://localhost:8081/generate")
                .post(jsonObject.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(
                        this@BackgroundGenerationService.getString(
                            R.string.error_request_failed,
                            response.code.toString()
                        )
                    )
                }

                response.body?.let { responseBody ->
                    val reader = BufferedReader(InputStreamReader(responseBody.byteStream()))
                    var buffer = StringBuilder()

                    while (isActive) {
                        val char = reader.read()
                        if (char == -1) break

                        when (char.toChar()) {
                            '\n' -> {
                                val line = buffer.toString()
                                if (line.startsWith("data: ")) {
                                    val data = line.substring(6).trim()
                                    if (data == "[DONE]") break

                                    val message = JSONObject(data)
                                    when (message.optString("type")) {
                                        "progress" -> {
                                            val step = message.optInt("step")
                                            val totalSteps = message.optInt("total_steps")
                                            val progress = step.toFloat() / totalSteps
                                            updateState(GenerationState.Progress(progress))
                                            updateNotification(progress)
                                        }

                                        "complete" -> {
                                            val base64Image = message.optString("image")
                                            val returnedSeed =
                                                message.optLong("seed", -1).takeIf { it != -1L }
                                            val size = message.optInt("width", 256)

                                            if (base64Image.isNullOrEmpty()) {
                                                throw IOException("no image data")
                                            }

                                            val imageBytes = Base64.getDecoder().decode(base64Image)
                                            val bitmap = Bitmap.createBitmap(
                                                size,
                                                size,
                                                Bitmap.Config.ARGB_8888
                                            )
                                            val pixels = IntArray(size * size)

                                            for (i in 0 until size * size) {
                                                val index = i * 3
                                                val r = imageBytes[index].toInt() and 0xFF
                                                val g = imageBytes[index + 1].toInt() and 0xFF
                                                val b = imageBytes[index + 2].toInt() and 0xFF
                                                pixels[i] =
                                                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                                            }
                                            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)

                                            updateState(
                                                GenerationState.Complete(
                                                    bitmap,
                                                    returnedSeed
                                                )
                                            )

                                            delay(500)
                                            stopSelf()
                                        }

                                        "error" -> {
                                            val errorMsg =
                                                message.optString("message", "unknown error")
                                            throw IOException(errorMsg)
                                        }
                                    }
                                }
                                buffer = StringBuilder()
                            }

                            else -> buffer.append(char.toChar())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("GenerationService", "generation error", e)
            updateState(
                GenerationState.Error(
                    e.message ?: this@BackgroundGenerationService.getString(R.string.unknown_error)
                )
            )
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Image Generation"
            val descriptionText = "Background image generation"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(progress: Float): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, BackgroundGenerationService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(this.getString(R.string.generating_notify))
            .setContentText("Progress: ${(progress * 100).toInt()}%")
            .setProgress(100, (progress * 100).toInt(), false)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(progress: Float) {
        notificationManager.notify(NOTIFICATION_ID, createNotification(progress))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()

        if (_generationState.value is GenerationState.Error) {
            resetState()
        }
    }
}