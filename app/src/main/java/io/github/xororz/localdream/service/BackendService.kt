package io.github.xororz.localdream.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.Model
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import io.github.xororz.localdream.data.ModelRepository
import io.github.xororz.localdream.BuildConfig

class BackendService : Service() {
    private var process: Process? = null
    private lateinit var runtimeDir: File

    companion object {
        private const val TAG = "BackendService"
        private const val EXECUTABLE_NAME = "libstable_diffusion_core.so"
        private const val RUNTIME_DIR = "runtime_libs"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "backend_service_channel"

        const val ACTION_STOP = "io.github.xororz.localdream.STOP_GENERATION"
        const val ACTION_RESTART = "io.github.xororz.localdream.RESTART_BACKEND"

        private object StateHolder {
            val _backendState = MutableStateFlow<BackendState>(BackendState.Idle)
        }

        val backendState: StateFlow<BackendState> = StateHolder._backendState

        private fun updateState(state: BackendState) {
            StateHolder._backendState.value = state
        }
    }

    sealed class BackendState {
        object Idle : BackendState()
        object Starting : BackendState()
        object Running : BackendState()
        data class Error(val message: String) : BackendState()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        prepareRuntimeDir()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "service started command: ${intent?.action}")
        startForeground(
            NOTIFICATION_ID,
            createNotification(this.getString(R.string.backend_notify))
        )

        when (intent?.action) {
            ACTION_STOP -> {
                Log.d("GenerationService", "stop")
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                Log.i(TAG, "restarting backend service")
                stopBackend()
            }
        }

        val modelId = intent?.getStringExtra("modelId")
        val width = intent?.getIntExtra("width", 512) ?: 512
        val height = intent?.getIntExtra("height", 512) ?: 512
        if (modelId != null) {
            val modelRepository = ModelRepository(this)
            val model = modelRepository.models.find { it.id == modelId }

            if (model != null) {
                if (startBackend(model, width, height)) {
                    updateState(BackendState.Running)
                } else {
                    updateState(BackendState.Error("Backend start failed"))
                }
            } else {
                updateState(BackendState.Error("Model not found"))
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        Log.e(TAG, "Foreground service timeout")
        updateState(BackendState.Error("Service timeout"))
        stopBackend()
        stopSelf()
    }

    private fun createNotificationChannel() {
        val name = "Backend Service"
        val descriptionText = "Backend service for image generation"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(contentText: String): Notification {
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
            .setContentTitle(this.getString(R.string.backend_notify_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun prepareRuntimeDir() {
        try {
            runtimeDir = File(filesDir, RUNTIME_DIR).apply {
                if (!exists()) {
                    mkdirs()
                }
            }

            try {
                val qnnlibsAssets = assets.list("qnnlibs")
                qnnlibsAssets?.forEach { fileName ->
                    val targetLib = File(runtimeDir, fileName)

                    val needsCopy = !targetLib.exists() || run {
                        val assetInputStream = assets.open("qnnlibs/$fileName")
                        val assetSize = assetInputStream.use { it.available().toLong() }
                        targetLib.length() != assetSize
                    }

                    if (needsCopy) {
                        val assetInputStream = assets.open("qnnlibs/$fileName")
                        assetInputStream.use { input ->
                            targetLib.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.d(TAG, "Copied $fileName from assets to runtime directory")
                    }

                    targetLib.setReadable(true, true)
                    targetLib.setExecutable(true, true)
                }
                Log.i(TAG, "QNN libraries prepared in runtime directory")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to prepare QNN libraries from assets", e)
                throw RuntimeException("Failed to prepare QNN libraries from assets", e)
            }

            if (BuildConfig.FLAVOR == "filter") {
                try {
                    val safetyCheckerSource = assets.open("safety_checker.mnn")
                    val safetyCheckerTarget = File(filesDir, "safety_checker.mnn")

                    safetyCheckerSource.use { input ->
                        safetyCheckerTarget.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    safetyCheckerTarget.setReadable(true, true)
                    Log.i(
                        TAG,
                        "Safety checker model copied to: ${safetyCheckerTarget.absolutePath}"
                    )
                } catch (e: IOException) {
                    Log.e(TAG, "copy safety_checker.mnn failed", e)
                    throw RuntimeException("Failed to copy safety checker model", e)
                }
            }

            runtimeDir.setReadable(true, true)
            runtimeDir.setExecutable(true, true)

            Log.i(TAG, "Runtime directory prepared: ${runtimeDir.absolutePath}")
            Log.i(TAG, "Runtime files: ${runtimeDir.list()?.joinToString()}")

        } catch (e: Exception) {
            Log.e(TAG, "Prepare runtime dir failed", e)
            updateState(BackendState.Error("Prepare runtime dir failed: ${e.message}"))
            throw RuntimeException("Failed to prepare runtime directory", e)
        }
    }

    private fun startBackend(model: Model, width: Int, height: Int): Boolean {
        Log.i(TAG, "backend start, model: ${model.name}, resolution: ${width}×${height}")
        updateState(BackendState.Starting)

        try {
            val nativeDir = applicationInfo.nativeLibraryDir
            val modelsDir = File(Model.getModelsDir(this), model.id)

            val executableFile = File(nativeDir, EXECUTABLE_NAME)

            if (!executableFile.exists()) {
                Log.e(TAG, "error: executable does not exist: ${executableFile.absolutePath}")
                return false
            }

            val preferences = this.getSharedPreferences("app_prefs", MODE_PRIVATE)
            val useImg2img = preferences.getBoolean("use_img2img", true)

            var clipfilename = "clip.bin"
            if (model.useCpuClip) {
                clipfilename = "clip.mnn"
            }
            var command = listOf(
                executableFile.absolutePath,
                "--clip", File(modelsDir, clipfilename).absolutePath,
                "--unet", File(modelsDir, "unet.bin").absolutePath,
                "--vae_decoder", File(modelsDir, "vae_decoder.bin").absolutePath,
                "--tokenizer", File(modelsDir, "tokenizer.json").absolutePath,
                "--backend", File(runtimeDir, "libQnnHtp.so").absolutePath,
                "--system_library", File(runtimeDir, "libQnnSystem.so").absolutePath,
                "--port", "8081",
                "--text_embedding_size", model.textEmbeddingSize.toString()
            )
            if (width != 512 || height != 512) {
                val patchFile = if (width == height) {
                    val squarePatch = File(modelsDir, "${width}.patch")
                    if (squarePatch.exists()) {
                        squarePatch
                    } else {
                        File(modelsDir, "${width}x${height}.patch")
                    }
                } else {
                    File(modelsDir, "${width}x${height}.patch")
                }

                if (patchFile.exists()) {
                    command = command + listOf(
                        "--patch", patchFile.absolutePath,
                    )
                    Log.i(TAG, "Using patch file: ${patchFile.name}")
                } else {
                    Log.w(
                        TAG,
                        "Patch file not found: ${patchFile.absolutePath}, falling back to 512×512"
                    )
                }
            }
            if (useImg2img) {
                command = command + listOf(
                    "--vae_encoder", File(modelsDir, "vae_encoder.bin").absolutePath,
                )
            }
            if (model.id.startsWith("pony")) {
                command += "--ponyv55"
            }
            if (model.useCpuClip) {
                command += "--use_cpu_clip"
            }
            if (model.runOnCpu) {
                command = listOf(
                    executableFile.absolutePath,
                    "--clip", File(modelsDir, "clip.mnn").absolutePath,
                    "--unet", File(modelsDir, "unet.mnn").absolutePath,
                    "--vae_decoder", File(modelsDir, "vae_decoder.mnn").absolutePath,
                    "--tokenizer", File(modelsDir, "tokenizer.json").absolutePath,
                    "--port", "8081",
                    "--text_embedding_size", if (model.id != "sd21") "768" else "1024",
                    "--cpu"
                )
                if (useImg2img) {
                    command = command + listOf(
                        "--vae_encoder", File(modelsDir, "vae_encoder.mnn").absolutePath,
                    )
                }
            }
            if (BuildConfig.FLAVOR == "filter") {
                command = command + listOf(
                    "--safety_checker",
                    File(filesDir, "safety_checker.mnn").absolutePath
                )
            }
            val env = mutableMapOf<String, String>()

            val systemLibPaths = mutableListOf(
                runtimeDir.absolutePath,
                "/system/lib64",
                "/vendor/lib64",
                "/vendor/lib64/egl",
            )
            try {
                val maliSymlink = File("/system/vendor/lib64/egl/libGLES_mali.so")
                if (maliSymlink.exists()) {
                    val realPath = maliSymlink.canonicalPath
                    val soc = realPath.split("/").getOrNull(realPath.split("/").size - 2)

                    if (soc != null) {
                        val socPaths = listOf(
                            "/vendor/lib64/$soc",
                            "/vendor/lib64/egl/$soc"
                        )

                        socPaths.forEach { path ->
                            if (!systemLibPaths.contains(path)) {
                                systemLibPaths.add(path)
                                Log.d("LibPath", "Added SoC path: $path")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("LibPath", "Failed to resolve Mali paths: ${e.message}")
            }
            val systemLibPathsStr = systemLibPaths.joinToString(":")
            env["LD_LIBRARY_PATH"] = systemLibPathsStr
            env["DSP_LIBRARY_PATH"] = runtimeDir.absolutePath

            Log.d(TAG, "COMMAND: ${command.joinToString(" ")}")
            Log.d(TAG, "DIR: ${runtimeDir}")
            Log.d(TAG, "LD_LIBRARY_PATH=${env["LD_LIBRARY_PATH"]}")
            Log.d(TAG, "DSP_LIBRARY_PATH=${env["DSP_LIBRARY_PATH"]}")

            val processBuilder = ProcessBuilder(command).apply {
                directory(File(nativeDir))
                redirectErrorStream(true)
                environment().putAll(env)
            }

            process = processBuilder.start()

            startMonitorThread()

            return true

        } catch (e: Exception) {
            Log.e(TAG, "backend start failed", e)
            updateState(BackendState.Error("backend start failed: ${e.message}"))
            e.printStackTrace()
            return false
        }
    }

    private fun startMonitorThread() {
        Thread {
            try {
                process?.let { proc ->
                    proc.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            Log.i(TAG, "Backend: $line")
                        }
                    }

                    val exitCode = proc.waitFor()
                    Log.i(TAG, "Backend process exited with code: $exitCode")
                    updateState(BackendState.Error("Backend process exited with code: $exitCode"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "monitor error", e)
                updateState(BackendState.Error("monitor error: ${e.message}"))
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBackend()
    }

    fun stopBackend() {
        Log.i(TAG, "to stop backend")
        process?.let { proc ->
            try {
                proc.destroy()

                if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }

                Log.i(TAG, "process end, code: ${proc.exitValue()}")
                updateState(BackendState.Idle)
            } catch (e: Exception) {
                Log.e(TAG, "error", e)
                updateState(BackendState.Error("error: ${e.message}"))
            } finally {
                process = null
            }
        }
    }
}