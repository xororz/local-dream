package io.github.xororz.localdream.service

import android.app.*
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import io.github.xororz.localdream.R
import io.github.xororz.localdream.utils.Http
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class BackgroundGenerationService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private var lastProgressNotifyAt = 0L

    // In-flight /generate call; cancelled by ACTION_STOP. Cancelling closes the
    // socket, which the backend detects at the next progress event and aborts
    // the generation instead of computing a result nobody will read.
    @Volatile
    private var activeCall: okhttp3.Call? = null

    @Volatile
    private var cancelRequested = false

    companion object {
        private const val CHANNEL_ID = "image_generation_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "stop_generation"
        const val LOCAL_BACKEND_HOST = "localhost:8081"

        // Shared across generations; the long timeouts cover a single SDXL
        // request that can stream for many minutes.
        private val generationClient: OkHttpClient by lazy {
            Http.client.newBuilder()
                .connectTimeout(3600, TimeUnit.SECONDS)
                .readTimeout(3600, TimeUnit.SECONDS)
                .writeTimeout(3600, TimeUnit.SECONDS)
                .callTimeout(3600, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        private val _generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
        val generationState: StateFlow<GenerationState> = _generationState

        private val _bitmapConsumed = MutableStateFlow(false)

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning

        fun resetState() {
            _generationState.value = GenerationState.Idle
            _bitmapConsumed.value = false
        }

        fun clearCompleteState() {
            if (_generationState.value is GenerationState.Complete) {
                _generationState.value = GenerationState.Idle
            }
        }

        fun markBitmapConsumed() {
            _bitmapConsumed.value = true
        }

        /** Interrupts the in-flight generation (if any) and stops the service. */
        fun stop(context: Context) {
            context.startService(
                Intent(context, BackgroundGenerationService::class.java)
                    .setAction(ACTION_STOP),
            )
        }
    }

    sealed class GenerationState {
        object Idle : GenerationState()
        data class Progress(val progress: Float, val intermediateImage: Bitmap? = null) : GenerationState()

        data class Complete(val bitmap: Bitmap, val seed: Long?) : GenerationState()
        data class Error(val message: String) : GenerationState()
    }

    private fun updateState(newState: GenerationState) {
        _generationState.value = newState
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("GenerationService", "service created")
        _isServiceRunning.value = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("GenerationService", "service execute: ${intent?.extras}")

        startForeground(NOTIFICATION_ID, createNotification(0f))

        when (intent?.action) {
            ACTION_STOP -> {
                Log.d("GenerationService", "generation interrupted by user")
                cancelRequested = true
                activeCall?.cancel()
                updateState(GenerationState.Idle)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val prompt = intent?.getStringExtra("prompt")
        Log.d("GenerationService", "prompt: $prompt")

        if (prompt == null) {
            Log.e("GenerationService", "empty prompt")
            stopSelf()
            return START_NOT_STICKY
        }

        val negativePrompt = intent.getStringExtra("negative_prompt") ?: ""
        val steps = intent.getIntExtra("steps", 28)
        val cfg = intent.getFloatExtra("cfg", 7f)
        val seed = if (intent.hasExtra("seed")) intent.getLongExtra("seed", 0) else null
        val width = intent.getIntExtra("width", 512)
        val height = intent.getIntExtra("height", 512)
        // Effective dimensions = target crop size for SDXL aspect-pad mode,
        // or equal to width/height otherwise. Used for decoding progress
        // previews which the backend already crops to the visible region.
        val effectiveWidth = intent.getIntExtra("effective_width", width)
        val effectiveHeight = intent.getIntExtra("effective_height", height)
        val denoiseStrength = intent.getFloatExtra("denoise_strength", 0.6f)
        val useOpenCL = intent.getBooleanExtra("use_opencl", false)
        val scheduler = intent.getStringExtra("scheduler") ?: "dpm"
        val aspectRatio = intent.getStringExtra("aspect_ratio") ?: "1:1"
        // Ultrafix: tiled img2img repair over an upscaled image. Uses its own
        // base-image file so a pending img2img selection in tmp.txt survives.
        val ultrafix = intent.getBooleanExtra("ultrafix", false)
        val ultrafixTileSize = intent.getIntExtra("ultrafix_tile_size", 512)
        // Backend to talk to: the local backend by default, or a remote host's
        // generation port when running in connected-device mode.
        val backendHost = intent.getStringExtra("backend_host") ?: LOCAL_BACKEND_HOST

        val image = if (ultrafix) {
            try {
                val ultrafixFile = File(applicationContext.filesDir, "ultrafix.txt")
                if (ultrafixFile.exists()) {
                    ultrafixFile.readText()
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("GenerationService", "Failed to read ultrafix image data", e)
                null
            }
        } else if (intent.getBooleanExtra("has_image", false)) {
            try {
                val tmpFile = File(applicationContext.filesDir, "tmp.txt")
                if (tmpFile.exists()) {
                    tmpFile.readText()
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("GenerationService", "Failed to read image data", e)
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
                    Log.w(
                        "GenerationService",
                        "has_mask is true but mask.txt not found",
                    )
                    null
                }
            } catch (e: Exception) {
                Log.e("GenerationService", "Failed to read mask data", e)
                null
            }
        } else {
            null
        }
        val referenceImages = if (intent.getBooleanExtra("has_reference_images", false)) {
            try {
                val refsFile = File(applicationContext.filesDir, "flux_references.json")
                if (refsFile.exists()) JSONArray(refsFile.readText()) else null
            } catch (e: Exception) {
                Log.e("GenerationService", "Failed to read FLUX reference images", e)
                null
            }
        } else {
            null
        }

        Log.d("GenerationService", "params: steps=$steps, cfg=$cfg, seed=$seed")

        if (_generationState.value is GenerationState.Complete) {
            updateState(GenerationState.Idle)
        }
        _bitmapConsumed.value = false
        cancelRequested = false

        serviceScope.launch {
            Log.d("GenerationService", "start generation")
            runGeneration(
                prompt,
                negativePrompt,
                steps,
                cfg,
                seed,
                width,
                height,
                effectiveWidth,
                effectiveHeight,
                image,
                mask,
                referenceImages,
                denoiseStrength,
                useOpenCL,
                scheduler,
                aspectRatio,
                ultrafix,
                ultrafixTileSize,
                backendHost,
            )
        }

        return START_NOT_STICKY
    }

    @Suppress("LongParameterList")
    private suspend fun runGeneration(
        prompt: String,
        negativePrompt: String,
        steps: Int,
        cfg: Float,
        seed: Long?,
        width: Int,
        height: Int,
        effectiveWidth: Int,
        effectiveHeight: Int,
        image: String?,
        mask: String?,
        referenceImages: JSONArray?,
        denoiseStrength: Float,
        useOpenCL: Boolean,
        scheduler: String,
        aspectRatio: String,
        ultrafix: Boolean,
        ultrafixTileSize: Int,
        backendHost: String,
    ) = withContext(Dispatchers.IO) {
        // Set once the complete event is fully handled; a socket teardown
        // racing the service shutdown after that point is not an error.
        var completed = false
        try {
            updateState(GenerationState.Progress(0f))

            val preferences =
                applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val showProcess = preferences.getBoolean("show_diffusion_process", false)
            val showStride = preferences.getInt("show_diffusion_stride", 1)

            val jsonObject = JSONObject().apply {
                put("prompt", prompt)
                put("negative_prompt", negativePrompt)
                put("steps", steps)
                put("cfg", cfg)
                // Per-step previews come back as base64 JPEG (tiny) instead of
                // raw RGB; the final image stays raw (lossless, loopback-cheap).
                put("preview_format", "jpeg")
                put("width", width)
                put("height", height)
                put("denoise_strength", denoiseStrength)
                put("use_opencl", useOpenCL)
                put("scheduler", scheduler)
                // Ultrafix never streams previews: each one would tile-decode
                // the full image (the backend rejects it as well).
                put("show_diffusion_process", if (ultrafix) false else showProcess)
                put("show_diffusion_stride", showStride)
                if (ultrafix) {
                    put("ultrafix", true)
                    put("tile_size", ultrafixTileSize)
                    // The result is 4x-class resolution; raw RGB would be a
                    // ~67 MB base64 payload at 4096x4096. It is persisted as
                    // JPEG by the history manager anyway.
                    put("output_format", "jpeg")
                } else {
                    put("aspect_ratio", aspectRatio)
                }
                seed?.let { put("seed", it) }
                image?.let { put("image", it) }
                mask?.let { put("mask", it) }
                referenceImages?.let { put("reference_images", it) }
            }

            val request = Request.Builder()
                .url("http://$backendHost/generate")
                .post(jsonObject.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            val call = generationClient.newCall(request)
            activeCall = call
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(
                        this@BackgroundGenerationService.getString(
                            R.string.error_request_failed,
                            response.code.toString(),
                        ),
                    )
                }

                response.body?.let { responseBody ->
                    Log.d("BgGenService", "Reading streaming response")

                    val reader = BufferedReader(InputStreamReader(responseBody.byteStream()))
                    var messageCount = 0
                    // Reused across progress previews: with the diffusion
                    // process shown every step would otherwise allocate a
                    // fresh width*height IntArray (4 MB at 1024x1024).
                    var previewPixels: IntArray? = null

                    // Read line by line for efficiency
                    readLoop@ while (isActive) {
                        val readLineStart = System.currentTimeMillis()
                        val line = reader.readLine() ?: break
                        val readLineTime = System.currentTimeMillis() - readLineStart

                        if (line.startsWith("data: ")) {
                            val data = line.substring(6).trim()
                            if (data == "[DONE]") break

                            val jsonParseStart = System.currentTimeMillis()
                            val message = JSONObject(data)
                            val jsonParseTime = System.currentTimeMillis() - jsonParseStart
                            messageCount++

                            when (message.optString("type")) {
                                "progress" -> {
                                    val step = message.optInt("step")
                                    val totalSteps = message.optInt("total_steps")
                                    val progress = step.toFloat() / totalSteps

                                    val b64Img = message.optString("image")
                                    var bitmap: Bitmap? = null
                                    if (b64Img.isNotEmpty()) {
                                        try {
                                            val imageBytes = Base64.getDecoder().decode(b64Img)
                                            bitmap = if (message.optString("format", "raw") == "raw") {
                                                // Progress previews are cropped to (effectiveWidth,
                                                // effectiveHeight) by the backend so the SDXL aspect-pad
                                                // path doesn't ship the 1024 canvas every step.
                                                val pw = effectiveWidth
                                                val ph = effectiveHeight
                                                val pixels = previewPixels
                                                    ?.takeIf { it.size == pw * ph }
                                                    ?: IntArray(pw * ph).also {
                                                        previewPixels = it
                                                    }
                                                rgbBytesToPixels(imageBytes, pixels)
                                                createBitmap(pw, ph).also {
                                                    it.setPixels(pixels, 0, pw, 0, 0, pw, ph)
                                                }
                                            } else {
                                                // jpeg/png: native decode.
                                                BitmapFactory.decodeByteArray(
                                                    imageBytes,
                                                    0,
                                                    imageBytes.size,
                                                )
                                            }
                                        } catch (e: Exception) {
                                            Log.e(
                                                "BgGenService",
                                                "Failed to decode intermediate image",
                                                e,
                                            )
                                        }
                                    }

                                    updateState(GenerationState.Progress(progress, bitmap))
                                    updateNotification(progress)
                                }

                                "complete" -> {
                                    Log.d(
                                        "BgGenService",
                                        "=== Received complete message, parsing... ===",
                                    )
                                    Log.d(
                                        "BgGenService",
                                        "readLine took: ${readLineTime}ms, line length: ${line.length}",
                                    )
                                    Log.d(
                                        "BgGenService",
                                        "JSONObject parsing took: ${jsonParseTime}ms, data length: ${data.length}",
                                    )
                                    val completeStartTime = System.currentTimeMillis()

                                    // 1. Extract fields from JSON
                                    val extractStart = System.currentTimeMillis()
                                    val base64Image = message.optString("image")
                                    val returnedSeed =
                                        message.optLong("seed", -1).takeIf { it != -1L }
                                    val resultWidth = message.optInt("width", 512)
                                    val resultHeight = message.optInt("height", 512)
                                    Log.d(
                                        "BgGenService",
                                        "JSON extraction took: ${System.currentTimeMillis() - extractStart}ms, Base64 length: ${base64Image.length}",
                                    )

                                    if (base64Image.isNullOrEmpty()) {
                                        throw IOException("no image data")
                                    }

                                    // 2. Base64 decode
                                    val decodeStartTime = System.currentTimeMillis()
                                    val imageBytes = Base64.getDecoder().decode(base64Image)
                                    Log.d(
                                        "BgGenService",
                                        "Base64 decoding took: ${System.currentTimeMillis() - decodeStartTime}ms, decoded size: ${imageBytes.size} bytes",
                                    )

                                    // 3. RGB conversion + Bitmap creation
                                    val bitmapStartTime = System.currentTimeMillis()
                                    val bitmap = if (message.optString("format", "raw") == "raw") {
                                        val pixels = IntArray(resultWidth * resultHeight)
                                        rgbBytesToPixels(imageBytes, pixels)
                                        createBitmap(resultWidth, resultHeight).also {
                                            it.setPixels(
                                                pixels,
                                                0,
                                                resultWidth,
                                                0,
                                                0,
                                                resultWidth,
                                                resultHeight,
                                            )
                                        }
                                    } else {
                                        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                                            ?: throw IOException("Failed to decode result image")
                                    }
                                    Log.d(
                                        "BgGenService",
                                        "RGB conversion + Bitmap creation took: ${System.currentTimeMillis() - bitmapStartTime}ms",
                                    )

                                    Log.d(
                                        "BgGenService",
                                        "=== Total processing time for complete message: ${System.currentTimeMillis() - completeStartTime}ms, size: ${resultWidth}x$resultHeight ===",
                                    )

                                    updateState(
                                        GenerationState.Complete(
                                            bitmap,
                                            returnedSeed,
                                        ),
                                    )

                                    Log.d(
                                        "BgGenService",
                                        "Generation completed, waiting for UI to consume bitmap",
                                    )

                                    // Wait for UI to consume the bitmap with timeout
                                    val waitStartTime = System.currentTimeMillis()
                                    val consumed = withTimeoutOrNull(5000L) {
                                        _bitmapConsumed.first { it }
                                    }
                                    if (consumed == null) {
                                        Log.w(
                                            "BgGenService",
                                            "Timeout waiting for bitmap consumption",
                                        )
                                    }

                                    Log.d(
                                        "BgGenService",
                                        "Bitmap consumed, stopping service. Wait time: ${System.currentTimeMillis() - waitStartTime}ms",
                                    )
                                    completed = true
                                    stopSelf()
                                    // The stream carries nothing after complete; leaving
                                    // the loop here avoids a blocked readLine() racing the
                                    // service shutdown (stopSelf -> onDestroy cancels the
                                    // call, which would surface as "Socket closed").
                                    break@readLoop
                                }

                                "error" -> {
                                    val errorMsg =
                                        message.optString("message", "unknown error")
                                    Log.e(
                                        "BgGenService",
                                        "Received error message: $errorMsg",
                                    )
                                    throw IOException(errorMsg)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (completed) {
                // Result already delivered; a teardown exception from the
                // closing socket must not overwrite the Complete state.
                Log.d("GenerationService", "post-completion teardown: ${e.message}")
            } else if (cancelRequested) {
                // User interrupted: the cancelled call throws on its blocked
                // read; this is the expected exit, not an error.
                Log.d("GenerationService", "generation cancelled")
                updateState(GenerationState.Idle)
            } else {
                Log.e("GenerationService", "generation error", e)
                updateState(
                    GenerationState.Error(
                        e.message ?: this@BackgroundGenerationService.getString(R.string.unknown_error),
                    ),
                )
            }
            stopSelf()
        } finally {
            activeCall = null
        }
    }

    // Expands packed RGB bytes into ARGB ints; stops at whichever buffer ends
    // first so a short payload can never index out of bounds.
    private fun rgbBytesToPixels(rgb: ByteArray, pixels: IntArray) {
        val count = minOf(pixels.size, rgb.size / 3)
        for (i in 0 until count) {
            val index = i * 3
            val r = rgb[index].toInt() and 0xFF
            val g = rgb[index + 1].toInt() and 0xFF
            val b = rgb[index + 2].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun createNotificationChannel() {
        val name = "Image Generation"
        val descriptionText = "Background image generation"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(progress: Float): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(this.getString(R.string.generating_notify))
            .setContentText("Progress: ${(progress * 100).toInt()}%")
            .setProgress(100, (progress * 100).toInt(), false)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(progress: Float) {
        // The system rate-limits notification updates; posting one per
        // diffusion step just gets dropped, so throttle to ~2 per second.
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressNotifyAt < 500) return
        lastProgressNotifyAt = now
        notificationManager.notify(NOTIFICATION_ID, createNotification(progress))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        handleTimeout(0)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        handleTimeout(fgsType)
    }

    private fun handleTimeout(fgsType: Int) {
        Log.e("GenerationService", "Foreground service timeout (fgsType=$fgsType)")
        updateState(GenerationState.Error("Service timeout"))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        activeCall?.cancel()
        serviceScope.cancel()

        if (_generationState.value is GenerationState.Error) {
            resetState()
        }

        _isServiceRunning.value = false
        Log.d("GenerationService", "service destroyed, isServiceRunning set to false")
    }
}
