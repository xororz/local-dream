package io.github.xororz.localdream.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.xororz.localdream.BuildConfig
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.Model
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BackendService : Service() {
    @Volatile
    private var process: Process? = null

    // Set true around an intentional teardown (and reset just before a new
    // start) so the monitor thread doesn't surface the resulting process exit
    // as a backend Error. backendState is a process-wide StateFlow shared
    // across Service instances, so a stale Error would otherwise be read by
    // the next model's health check and shown as "backend start failed".
    @Volatile
    private var stopping = false

    // Desired-state reconciliation. `desired` is the config the screen wants
    // running (null = nothing); `serving` is what the live process was actually
    // started for. Both are touched only on the single backend thread via
    // reconcile(), so no extra locking is needed. idleStopJob is the pending
    // grace-period teardown scheduled by a stop request.
    private var desired: BackendConfig? = null
    private var serving: BackendConfig? = null
    private var idleStopJob: Job? = null
    private lateinit var runtimeDir: File

    @Volatile
    private var runtimeDirReady = false

    // All backend process management (asset copies, exec, destroy/waitFor)
    // runs on this single thread: jobs stay ordered relative to each other
    // and the main thread never blocks on waitFor() or large file copies.
    private val backendDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "backend-control") }
            .asCoroutineDispatcher()
    private val serviceScope = CoroutineScope(SupervisorJob() + backendDispatcher)

    companion object {
        private const val TAG = "BackendService"
        private const val EXECUTABLE_NAME = "libstable_diffusion_core.so"
        private const val RUNTIME_DIR = "runtime_libs"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "backend_service_channel"

        // Grace window before a stop request actually tears the backend down.
        // A re-entry within this window (same or different model) cancels the
        // teardown and reconciles in-place, so quick back-then-reopen reuses
        // the live process and model switches stay on one Service instance
        // (single-threaded, no cross-instance start/stop race). Affects only
        // reuse/latency, never correctness: a slower re-entry just starts fresh.
        private const val IDLE_GRACE_MS = 1500L

        const val ACTION_STOP = "io.github.xororz.localdream.STOP_GENERATION"
        const val ACTION_RESTART = "io.github.xororz.localdream.RESTART_BACKEND"

        private object StateHolder {
            val _backendState = MutableStateFlow<BackendState>(BackendState.Idle)

            // modelId the live process is serving (null when none). Process-wide
            // so a screen can tell whether 8081 is already serving *its* model
            // vs. a previous model still alive in the stop grace window.
            val _servingModelId = MutableStateFlow<String?>(null)
        }

        val backendState: StateFlow<BackendState> = StateHolder._backendState

        val servingModelId: StateFlow<String?> = StateHolder._servingModelId

        private fun updateState(state: BackendState) {
            StateHolder._backendState.value = state
        }

        private fun updateServingModelId(modelId: String?) {
            StateHolder._servingModelId.value = modelId
        }
    }

    sealed class BackendState {
        object Idle : BackendState()
        object Starting : BackendState()
        object Running : BackendState()

        // modelId is the model this failure pertains to, or null for a failure
        // that affects any model (e.g. runtime preparation). Lets a screen
        // ignore an error left over from a *different* model's process (a crash
        // in the stop grace window) instead of mistaking it for its own.
        data class Error(val message: String, val modelId: String? = null) : BackendState()
    }

    // What a backend process is (or should be) running for. Equality drives
    // reconcile()'s "already serving this exact config" decision.
    private data class BackendConfig(
        val modelId: String,
        val backendType: String,
        val width: Int,
        val height: Int,
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope.launch { prepareRuntimeDir() }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "service command: ${intent?.action}")
        startForeground(
            NOTIFICATION_ID,
            createNotification(this.getString(R.string.backend_notify)),
        )

        // Commands only declare intent; the single backend thread converges the
        // actual process to it via reconcile(). This keeps every start/stop
        // ordered and race-free regardless of how fast the screen comes and goes.
        when (intent?.action) {
            ACTION_STOP -> serviceScope.launch { requestStop(startId) }

            else -> {
                val forceRestart = intent?.action == ACTION_RESTART
                val config = parseConfig(intent)
                serviceScope.launch { requestStart(config, forceRestart) }
            }
        }

        return START_NOT_STICKY
    }

    private fun parseConfig(intent: Intent?): BackendConfig? {
        val modelId = intent?.getStringExtra("modelId") ?: return null
        // Backend type is decided by the caller (it already has the Model);
        // re-deriving it here would require a full model-directory scan.
        val backendType = intent.getStringExtra("backendType") ?: return null
        val width = intent.getIntExtra("width", 512)
        val height = intent.getIntExtra("height", 512)
        return BackendConfig(modelId, backendType, width, height)
    }

    // Declares the desired backend and converges to it. Cancels any pending
    // idle teardown first so a quick re-entry keeps the live process.
    private fun requestStart(config: BackendConfig?, forceRestart: Boolean) {
        if (config == null) {
            updateState(BackendState.Error("Model not found"))
            return
        }
        idleStopJob?.cancel()
        idleStopJob = null
        desired = config
        reconcile(forceRestart)
    }

    // Declares that nothing should run, but only tears down after a grace
    // window. A re-entry within the window cancels this job and reconciles in
    // place; otherwise the process is stopped and the Service stops itself.
    private fun requestStop(startId: Int) {
        desired = null
        idleStopJob?.cancel()
        idleStopJob = serviceScope.launch {
            delay(IDLE_GRACE_MS)
            // A re-entry during the delay normally cancels us. The startId guard
            // closes the remaining edge where a new command's onStartCommand
            // raced in just as the grace fired: stopSelfResult() refuses to stop
            // when a newer start exists, leaving the (re)started service alive
            // with its foreground notification intact.
            if (desired == null) {
                stopBackend()
                if (stopSelfResult(startId)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }
    }

    // Converges the actual process to `desired`. Runs only on the single
    // backend thread, so reading current state and any start/stop are atomic
    // with respect to other commands.
    private fun reconcile(forceRestart: Boolean) {
        val want = desired ?: return
        // prepareRuntimeDir runs earlier on this same thread and publishes its
        // own error on failure; if it didn't finish ready, leave that state.
        if (!runtimeDirReady) {
            return
        }
        val alreadyServing = process?.isAlive == true && serving == want
        if (alreadyServing && !forceRestart) {
            Log.i(TAG, "backend already serving ${want.modelId} ${want.width}x${want.height}")
            updateServingModelId(want.modelId)
            updateState(BackendState.Running)
            return
        }
        stopBackend()
        if (startBackend(want)) {
            serving = want
            updateServingModelId(want.modelId)
            updateState(BackendState.Running)
        } else {
            serving = null
            updateServingModelId(null)
            updateState(BackendState.Error("Backend start failed", want.modelId))
        }
    }

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        handleTimeout(0)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        handleTimeout(fgsType)
    }

    private fun handleTimeout(fgsType: Int) {
        Log.e(TAG, "Foreground service timeout (fgsType=$fgsType)")
        updateState(BackendState.Error("Service timeout", servingModelId.value))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        serviceScope.launch {
            desired = null
            idleStopJob?.cancel()
            try {
                stopBackend()
            } catch (e: Exception) {
                Log.e(TAG, "stopBackend on timeout failed", e)
            }
        }
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
            PendingIntent.FLAG_IMMUTABLE,
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

                    val needsCopy = !targetLib.exists() ||
                        run {
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
                    val safetyCheckerTarget = File(filesDir, "safety_checker.mnn")
                    val assetSize = assets.open("safety_checker.mnn")
                        .use { it.available().toLong() }

                    if (!safetyCheckerTarget.exists() ||
                        safetyCheckerTarget.length() != assetSize
                    ) {
                        assets.open("safety_checker.mnn").use { input ->
                            safetyCheckerTarget.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.i(
                            TAG,
                            "Safety checker model copied to: ${safetyCheckerTarget.absolutePath}",
                        )
                    }

                    safetyCheckerTarget.setReadable(true, true)
                } catch (e: IOException) {
                    Log.e(TAG, "copy safety_checker.mnn failed", e)
                    throw RuntimeException("Failed to copy safety checker model", e)
                }
            }

            runtimeDir.setReadable(true, true)
            runtimeDir.setExecutable(true, true)
            runtimeDirReady = true

            Log.i(TAG, "Runtime directory prepared: ${runtimeDir.absolutePath}")
            Log.i(TAG, "Runtime files: ${runtimeDir.list()?.joinToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Prepare runtime dir failed", e)
            updateState(BackendState.Error("Prepare runtime dir failed: ${e.message}"))
        }
    }

    private fun startBackend(config: BackendConfig): Boolean {
        val modelId = config.modelId
        val backendType = config.backendType
        val width = config.width
        val height = config.height
        Log.i(TAG, "backend start, model: $modelId, resolution: $width×$height")

        // reconcile() has already stopped any previous process; just re-arm
        // crash reporting for the process we are about to start.
        stopping = false
        updateState(BackendState.Starting)

        try {
            val nativeDir = applicationInfo.nativeLibraryDir
            val modelsDir = File(Model.getModelsDir(this), modelId)

            val executableFile = File(nativeDir, EXECUTABLE_NAME)

            if (!executableFile.exists()) {
                Log.e(TAG, "error: executable does not exist: ${executableFile.absolutePath}")
                return false
            }

            val preferences = this.getSharedPreferences("app_prefs", MODE_PRIVATE)
            val useImg2img = preferences.getBoolean("use_img2img", true)
            val listenOnAll = preferences.getBoolean("listen_on_all_addresses", false)
            val openAiApiEnabled = preferences.getBoolean("enable_openai_api", true)
            val openAiApiKey = preferences.getString("openai_api_key", "")?.trim().orEmpty()

            val command = mutableListOf(
                executableFile.absolutePath,
                "--type",
                backendType,
                "--model_dir",
                modelsDir.absolutePath,
                "--port",
                "8081",
            )
            if (backendType != "sd15cpu") {
                command += listOf("--lib_dir", runtimeDir.absolutePath)
            }
            if (!useImg2img) {
                command += "--no_img2img"
            }
            if (backendType == "sd15npu" && (width != 512 || height != 512)) {
                val patchFile = if (width == height) {
                    val squarePatch = File(modelsDir, "$width.patch")
                    if (squarePatch.exists()) {
                        squarePatch
                    } else {
                        File(modelsDir, "${width}x$height.patch")
                    }
                } else {
                    File(modelsDir, "${width}x$height.patch")
                }

                if (patchFile.exists()) {
                    command += listOf("--patch", patchFile.absolutePath)
                    Log.i(TAG, "Using patch file: ${patchFile.name}")
                } else {
                    Log.w(
                        TAG,
                        "Patch file not found: ${patchFile.absolutePath}, falling back to 512×512",
                    )
                }
            }
            if (File(modelsDir, "V_PRED").exists()) {
                command += "--use_v_pred"
            }
            if (BuildConfig.FLAVOR == "filter") {
                command += listOf(
                    "--safety_checker",
                    File(filesDir, "safety_checker.mnn").absolutePath,
                )
            }
            // SDXL and Anima are the large NPU formats that benefit from
            // per-stage load/release. They share the same backend --lowram flag
            // but keep separate UI toggles so each can opt in independently.
            if (backendType == "sdxl" && preferences.getBoolean("sdxl_lowram", true)) {
                command += "--lowram"
            }
            if (backendType == "anima" && preferences.getBoolean("anima_lowram", true)) {
                command += "--lowram"
                // Aggressive variant: never hold both DiT halves resident at
                // once, so 12GB devices can run Anima low-RAM. Slower per step.
                if (preferences.getBoolean("anima_seq_dit", false)) {
                    command += "--anima_seq_dit"
                }
            }
            if (listenOnAll) {
                command += "--listen_all"
            }
            if (!openAiApiEnabled) {
                command += "--no_openai_api"
            }
            if (openAiApiKey.isNotEmpty()) {
                command += listOf("--api_key", openAiApiKey)
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
                            "/vendor/lib64/egl/$soc",
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
            Log.d(TAG, "DIR: $runtimeDir")
            Log.d(TAG, "LD_LIBRARY_PATH=${env["LD_LIBRARY_PATH"]}")
            Log.d(TAG, "DSP_LIBRARY_PATH=${env["DSP_LIBRARY_PATH"]}")

            val processBuilder = ProcessBuilder(command).apply {
                directory(File(nativeDir))
                redirectErrorStream(true)
                environment().putAll(env)
            }

            val proc = processBuilder.start()
            process = proc

            startMonitorThread(proc)

            return true
        } catch (e: Exception) {
            Log.e(TAG, "backend start failed", e)
            updateState(BackendState.Error("backend start failed: ${e.message}", config.modelId))
            return false
        }
    }

    private fun startMonitorThread(proc: Process) {
        Thread {
            val exitCode = try {
                proc.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.i(TAG, "Backend: $line")
                    }
                }
                proc.waitFor()
            } catch (e: Exception) {
                Log.e(TAG, "monitor error", e)
                if (isLiveCrash(proc)) {
                    updateState(BackendState.Error("monitor error: ${e.message}", servingModelId.value))
                }
                return@Thread
            }
            Log.i(TAG, "Backend process exited with code: $exitCode")
            // Only surface as an error when this is still the active process and
            // we didn't intentionally stop it; a torn-down or superseded process
            // exiting is expected and must not poison the shared backendState.
            if (isLiveCrash(proc)) {
                updateState(
                    BackendState.Error(
                        "Backend process exited with code: $exitCode",
                        servingModelId.value,
                    ),
                )
            } else {
                Log.i(TAG, "backend exit ($exitCode) was intentional/stale, not reporting")
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    // True when proc is still the tracked process and no intentional stop is in
    // progress, i.e. its exit really is an unexpected crash worth reporting.
    private fun isLiveCrash(proc: Process): Boolean = !stopping && process === proc

    override fun onDestroy() {
        super.onDestroy()
        // The scope is never cancelled, so this job still runs after
        // onDestroy returns; closing the dispatcher afterwards lets its
        // thread wind down once the backend process has exited.
        serviceScope.launch {
            idleStopJob?.cancel()
            stopBackend()
            backendDispatcher.close()
        }
    }

    private fun stopBackend() {
        Log.i(TAG, "to stop backend")
        // Mark the upcoming exit as intentional before destroy() so the monitor
        // thread (which wakes the instant the process dies) won't race ahead and
        // report it as a crash.
        stopping = true
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
        serving = null
        updateServingModelId(null)
    }
}
