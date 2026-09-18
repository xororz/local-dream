package io.github.xororz.localdream.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.xororz.localdream.BuildConfig
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.DitResolution
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.data.ModelRepository
import io.github.xororz.localdream.data.PatchScanner
import io.github.xororz.localdream.remote.RemoteCatalog
import io.github.xororz.localdream.remote.RemoteHostInfo
import io.github.xororz.localdream.remote.RemoteHostServer
import io.github.xororz.localdream.remote.RemoteHostStatus
import io.github.xororz.localdream.remote.RemoteModelDefaults
import io.github.xororz.localdream.remote.RemoteModelInfo
import io.github.xororz.localdream.remote.RemoteProtocol
import io.github.xororz.localdream.remote.RemoteUpscalerInfo
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Host-mode (controlled device, "A") foreground service.
 *
 * Runs the small authenticated control API ([RemoteHostServer]) that lets a
 * controller device list this device's installed models, activate one and
 * watch backend state. While active it also flips the
 * [KEY_HOST_MODE_ACTIVE] preference so [BackendService] starts the native
 * backend with --listen_all, letting the controller talk to the generation
 * port directly.
 */
class RemoteHostService : Service() {
    private var server: RemoteHostServer? = null

    // Model id of the most recent /select. /stop requests carry the id they
    // intend to stop; a late-arriving stop for a superseded selection is
    // ignored instead of killing the newly selected model. Handlers run on a
    // single server worker thread, so this needs no further synchronization.
    @Volatile
    private var lastSelectedModelId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification())

        if (server == null) {
            val newServer = RemoteHostServer(
                port = RemoteProtocol.CONTROL_PORT,
                handler = ApiHandler(),
            )
            try {
                newServer.start()
            } catch (e: IOException) {
                Log.e(TAG, "failed to start control server", e)
                updateState(running = false)
                stopSelf()
                return START_NOT_STICKY
            }
            server = newServer
            updateState(running = true)
            // Bring BackendService up as a foreground service now, while the
            // app is still visible (the user just tapped the button). Later
            // remote /select commands then reach a live FGS via plain
            // startService(), which is allowed from the background; starting a
            // NEW foreground service at that point would not be. A stale
            // localhost-only process needs no special handling: listenOnAll is
            // part of the backend config, so the first select restarts it.
            try {
                startForegroundService(
                    Intent(this, BackendService::class.java)
                        .setAction(BackendService.ACTION_STANDBY),
                )
            } catch (e: Exception) {
                Log.e(TAG, "failed to bring backend service to standby", e)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.shutdown()
        server = null
        // Flip the flag before stopping the backend so its stop-grace logic
        // no longer keeps the service alive for host mode.
        updateState(running = false)
        // The backend was reachable from the LAN; don't leave it running
        // (and exposed) after host mode ends.
        try {
            startService(
                Intent(this, BackendService::class.java)
                    .setAction(BackendService.ACTION_STOP),
            )
        } catch (e: Exception) {
            Log.e(TAG, "failed to stop backend on host mode exit", e)
        }
    }

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        handleTimeout()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        handleTimeout()
    }

    private fun handleTimeout() {
        Log.e(TAG, "foreground service timeout")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private inner class ApiHandler : RemoteHostServer.Handler {
        override fun info(): JSONObject = RemoteHostInfo(
            protocol = RemoteProtocol.PROTOCOL_VERSION,
            version = BuildConfig.VERSION_NAME,
            deviceName = Build.MODEL,
        ).toJson()

        override fun models(): JSONObject {
            val context = applicationContext
            val repository = ModelRepository.getInstance(context)
            // Full re-scan so models downloaded/imported after host mode
            // started are visible; /models is called rarely.
            runBlocking { repository.refreshAllModels() }
            val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val catalog = RemoteCatalog(
                useImg2img = preferences.getBoolean("use_img2img", true),
                models = repository.models
                    .filter { it.isDownloaded }
                    .map { model -> toRemoteInfo(context, model) },
                upscalers = installedUpscalers(context),
            )
            return catalog.toJson()
        }

        override fun select(body: JSONObject): RemoteHostServer.Response {
            val modelId = body.optString("model_id")
            val width = body.optInt("width", 512)
            val height = body.optInt("height", 512)
            val backendType: String
            if (modelId == RemoteProtocol.UPSCALER_MODEL_ID) {
                backendType = BackendService.BACKEND_TYPE_UPSCALER
            } else {
                val repository = ModelRepository.getInstance(applicationContext)
                runBlocking { repository.ensureLoaded() }
                val model = repository.models.find { it.id == modelId && it.isDownloaded }
                    ?: return RemoteHostServer.Response(
                        404,
                        JSONObject().put("error", "model not found"),
                    )
                backendType = model.backendType
                if (model.isDit &&
                    (!DitResolution.isSupported(width) || !DitResolution.isSupported(height))
                ) {
                    return RemoteHostServer.Response(
                        400,
                        JSONObject().put(
                            "error",
                            "DiT resolution must be 512..2048 in 256-pixel steps",
                        ),
                    )
                }
            }
            val intent = Intent(this@RemoteHostService, BackendService::class.java).apply {
                putExtra("modelId", modelId)
                putExtra("backendType", backendType)
                putExtra("width", width)
                putExtra("height", height)
            }
            // Plain startService: BackendService is already a live foreground
            // service (standby since host mode started), and this app holds an
            // FGS so background startService is permitted. Starting a NEW FGS
            // here would be blocked on Android 12+ when no activity is visible.
            return try {
                startService(intent)
                lastSelectedModelId = modelId
                RemoteHostServer.Response(200, JSONObject().put("ok", true))
            } catch (e: Exception) {
                Log.e(TAG, "failed to start backend for remote select", e)
                RemoteHostServer.Response(
                    500,
                    JSONObject().put("error", "backend start rejected"),
                )
            }
        }

        override fun status(): JSONObject {
            val state = BackendService.backendState.value
            val resolution = BackendService.servingResolution.value
            val status = when (state) {
                is BackendService.BackendState.Idle -> RemoteHostStatus(
                    servingModelId = null,
                    state = RemoteProtocol.STATE_IDLE,
                    message = null,
                    errorModelId = null,
                    width = null,
                    height = null,
                )

                is BackendService.BackendState.Starting -> RemoteHostStatus(
                    servingModelId = BackendService.servingModelId.value,
                    state = RemoteProtocol.STATE_STARTING,
                    message = null,
                    errorModelId = null,
                    width = resolution?.first,
                    height = resolution?.second,
                )

                is BackendService.BackendState.Running -> RemoteHostStatus(
                    servingModelId = BackendService.servingModelId.value,
                    state = RemoteProtocol.STATE_RUNNING,
                    message = null,
                    errorModelId = null,
                    width = resolution?.first,
                    height = resolution?.second,
                )

                is BackendService.BackendState.Error -> RemoteHostStatus(
                    servingModelId = BackendService.servingModelId.value,
                    state = RemoteProtocol.STATE_ERROR,
                    message = state.message,
                    errorModelId = state.modelId,
                    width = resolution?.first,
                    height = resolution?.second,
                )
            }
            return status.toJson()
        }

        override fun stop(body: JSONObject): JSONObject {
            // Only honor a stop that targets the current selection: a delayed
            // stop from a screen the controller already left must not kill the
            // model it selected next. An id-less stop is legacy/unconditional.
            val targetModelId = body.optString("model_id").takeIf { it.isNotEmpty() }
            if (targetModelId != null && targetModelId != lastSelectedModelId) {
                Log.i(TAG, "ignore stale stop for $targetModelId (now $lastSelectedModelId)")
                return JSONObject().put("ok", true).put("ignored", true)
            }
            lastSelectedModelId = null
            try {
                startService(
                    Intent(this@RemoteHostService, BackendService::class.java)
                        .setAction(BackendService.ACTION_STOP),
                )
            } catch (e: Exception) {
                Log.e(TAG, "failed to stop backend for remote stop", e)
            }
            return JSONObject().put("ok", true)
        }
    }

    private fun toRemoteInfo(context: Context, model: Model): RemoteModelInfo {
        val defaults = model.defaults
        val resolutions = if (!model.runOnCpu && !model.usesFixedCanvas && !model.isDit) {
            val patches = PatchScanner.scanAvailableResolutions(context, model.id)
            (listOf(Pair(512, 512)) + patches.map { Pair(it.width, it.height) })
                .distinct()
        } else {
            emptyList()
        }
        return RemoteModelInfo(
            id = model.id,
            name = model.name,
            description = model.description,
            runOnCpu = model.runOnCpu,
            isSdxl = model.isSdxl,
            isAnima = model.isAnima,
            ditKind = model.ditKind,
            isCustom = model.isCustom,
            generationSize = model.generationSize,
            defaults = RemoteModelDefaults(
                prompt = defaults.prompt,
                negativePrompt = defaults.negativePrompt,
                steps = defaults.steps,
                cfg = defaults.cfg,
                scheduler = defaults.scheduler,
            ),
            resolutions = resolutions,
        )
    }

    // Upscalers installed on this device, with the absolute weight paths the
    // native /upscale endpoint expects in X-Upscaler-Path. The controller
    // echoes the path back when it requests a remote upscale.
    private fun installedUpscalers(context: Context): List<RemoteUpscalerInfo> = UPSCALER_IDS.mapNotNull { id ->
        if (!Model.isUpscalerDownloaded(context, id)) return@mapNotNull null
        val file = File(File(Model.getModelsDir(context), id), Model.UPSCALER_FILE_NAME)
        RemoteUpscalerInfo(id = id, path = file.absolutePath)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Remote Host",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Host mode for remote control"
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
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
            .setContentTitle(getString(R.string.remote_host_notify_title))
            .setContentText(getString(R.string.remote_host_notify))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "RemoteHostService"
        private const val CHANNEL_ID = "remote_host_channel"
        private const val NOTIFICATION_ID = 4
        private const val PREFS_NAME = "app_prefs"

        // Keep in sync with UpscalerRepository's fixed upscaler set.
        private val UPSCALER_IDS = listOf("upscaler_anime", "upscaler_realistic")

        const val ACTION_STOP = "io.github.xororz.localdream.STOP_REMOTE_HOST"

        private object StateHolder {
            val _isRunning = MutableStateFlow(false)
        }

        val isRunning: StateFlow<Boolean> = StateHolder._isRunning

        private fun updateState(running: Boolean) {
            StateHolder._isRunning.value = running
        }

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, RemoteHostService::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RemoteHostService::class.java).setAction(ACTION_STOP),
            )
        }

        /** Non-loopback IPv4 addresses of this device, for display. */
        fun localIpAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif -> nif.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
