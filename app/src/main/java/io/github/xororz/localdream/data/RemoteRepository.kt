package io.github.xororz.localdream.data

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import io.github.xororz.localdream.R
import io.github.xororz.localdream.remote.RemoteApiClient
import io.github.xororz.localdream.remote.RemoteCatalog
import io.github.xororz.localdream.remote.RemoteProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Saved link to a host device (A). */
data class RemoteConnection(
    val host: String,
    val port: Int,
    val deviceName: String,
)

sealed class RemoteConnectResult {
    data class Success(val deviceName: String) : RemoteConnectResult()
    data object Unreachable : RemoteConnectResult()
}

/**
 * Controller-side (B) state for the connected-device mode: the saved host
 * link plus the host's model catalog mapped into regular [Model] values.
 *
 * Remote models reuse the local Model type (isDownloaded = true, defaults
 * from the host's resolved values) so the list and run screens work on them
 * unchanged; the remote flag lives in the navigation route, not the model.
 * Local file operations (download/delete/rename) are never offered while
 * remote mode is active.
 */
class RemoteRepository private constructor(private val context: Context) {
    var connection by mutableStateOf<RemoteConnection?>(null)
        private set

    var models by mutableStateOf<List<Model>>(emptyList())
        private set

    var useImg2img by mutableStateOf(true)
        private set

    // True while the last catalog refresh succeeded; false shows the offline
    // banner with a retry affordance.
    var online by mutableStateOf(false)
        private set

    var refreshing by mutableStateOf(false)
        private set

    private var resolutionsById: Map<String, List<Resolution>> = emptyMap()

    // Upscalers installed on the host: id -> weight-file path on the HOST's
    // filesystem (echoed back in X-Upscaler-Path when upscaling remotely).
    // Compose state so the result page's upscale-button gating reacts to it.
    var upscalerPaths by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    // For fire-and-forget control calls issued from non-suspend lifecycle
    // callbacks (e.g. stopping the host backend when the run screen closes).
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isActive: Boolean get() = connection != null

    fun client(): RemoteApiClient? = connection?.let {
        RemoteApiClient(it.host, it.port)
    }

    fun resolutionsFor(modelId: String): List<Resolution> = resolutionsById[modelId].orEmpty()

    /**
     * Upscaler entries installed on the host, presented with this device's
     * localized names for the known ids. isDownloaded is always true: these
     * files live on the host, there is nothing to download here.
     */
    fun remoteUpscalers(): List<UpscalerModel> = upscalerPaths.keys.sorted().map { id ->
        val nameRes = when (id) {
            "upscaler_anime" -> R.string.upscaler_anime
            "upscaler_realistic" -> R.string.upscaler_realistic
            else -> null
        }
        UpscalerModel(
            id = id,
            name = nameRes?.let { context.getString(it) } ?: id,
            description = connection?.deviceName.orEmpty(),
            baseUrl = "",
            fileUri = "",
            isDownloaded = true,
        )
    }

    /** Loads the saved connection (if any) from preferences. Idempotent. */
    fun restore() {
        if (connection != null) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, null) ?: return
        connection = RemoteConnection(
            host = host,
            port = prefs.getInt(KEY_PORT, RemoteProtocol.CONTROL_PORT),
            deviceName = prefs.getString(KEY_NAME, host) ?: host,
        )
    }

    /**
     * Verifies the host at [hostInput] ("ip" or "ip:port"), fetches its
     * catalog and persists the link on success.
     */
    suspend fun connect(hostInput: String): RemoteConnectResult {
        val host = hostInput.substringBefore(':').trim()
        val port = hostInput.substringAfter(':', "").toIntOrNull()
            ?: RemoteProtocol.CONTROL_PORT
        if (host.isEmpty()) return RemoteConnectResult.Unreachable

        val client = RemoteApiClient(host, port)
        val info = client.fetchInfo() ?: return RemoteConnectResult.Unreachable
        val catalog = client.fetchCatalog() ?: return RemoteConnectResult.Unreachable

        val name = info.deviceName.ifEmpty { host }
        connection = RemoteConnection(host, port, name)
        applyCatalog(catalog)
        online = true
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_HOST, host)
            putInt(KEY_PORT, port)
            putString(KEY_NAME, name)
        }
        return RemoteConnectResult.Success(name)
    }

    /** Re-fetches the catalog from the saved host. */
    suspend fun refresh(): Boolean {
        val client = client() ?: return false
        refreshing = true
        try {
            val catalog = client.fetchCatalog()
            if (catalog == null) {
                online = false
                return false
            }
            applyCatalog(catalog)
            online = true
            return true
        } finally {
            refreshing = false
        }
    }

    /** Drops the saved link and returns the list screens to local mode. */
    fun disconnect() {
        connection = null
        models = emptyList()
        resolutionsById = emptyMap()
        upscalerPaths = emptyMap()
        online = false
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            remove(KEY_HOST)
            remove(KEY_PORT)
            remove(KEY_CODE)
            remove(KEY_NAME)
        }
    }

    /**
     * Fire-and-forget stop of the host backend (screen teardown path).
     * [modelId] scopes the stop: the host ignores it if it has since been
     * asked to serve something else.
     */
    fun stopHostBackendAsync(modelId: String) {
        val client = client() ?: return
        backgroundScope.launch { client.stopBackend(modelId) }
    }

    private fun applyCatalog(catalog: RemoteCatalog) {
        useImg2img = catalog.useImg2img
        resolutionsById = catalog.models.associate { info ->
            info.id to info.resolutions.map { (w, h) -> Resolution(w, h) }
        }
        upscalerPaths = catalog.upscalers.associate { it.id to it.path }
        models = catalog.models.map { info ->
            Model(
                id = info.id,
                name = info.name,
                description = info.description,
                baseUrl = "",
                generationSize = info.generationSize,
                approximateSize = "Remote",
                isDownloaded = true,
                codeDefaults = ModelConfig(
                    prompt = info.defaults.prompt.takeIf { it.isNotEmpty() },
                    negativePrompt = info.defaults.negativePrompt.takeIf { it.isNotEmpty() },
                    steps = info.defaults.steps,
                    cfg = info.defaults.cfg,
                    scheduler = info.defaults.scheduler.takeIf { it.isNotEmpty() },
                ),
                runOnCpu = info.runOnCpu,
                isCustom = info.isCustom,
                isSdxl = info.isSdxl,
                isAnima = info.isAnima,
                ditKind = info.ditKind,
            )
        }
    }

    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_HOST = "remote_host"
        private const val KEY_PORT = "remote_port"
        private const val KEY_CODE = "remote_code"
        private const val KEY_NAME = "remote_device_name"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: RemoteRepository? = null

        fun getInstance(context: Context): RemoteRepository = instance ?: synchronized(this) {
            instance ?: RemoteRepository(context.applicationContext).also { instance = it }
        }
    }
}
