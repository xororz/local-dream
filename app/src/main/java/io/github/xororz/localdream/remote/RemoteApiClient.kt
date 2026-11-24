package io.github.xororz.localdream.remote

import io.github.xororz.localdream.utils.Http
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Controller-side client for a host device's control API.
 *
 * Only covers the small JSON control endpoints; generation, tokenize and
 * health-check traffic goes straight to the host's native backend port via
 * [generationHost] using the same code paths as local generation.
 */
class RemoteApiClient(
    val host: String,
    val port: Int = RemoteProtocol.CONTROL_PORT,
) {
    private val baseUrl = "http://$host:$port"

    /** host:port of the native backend on the host device. */
    val generationHost: String = "$host:${RemoteProtocol.GENERATION_PORT}"

    private val client: OkHttpClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchInfo(): RemoteHostInfo? = withContext(Dispatchers.IO) {
        runCatching {
            get(RemoteProtocol.PATH_INFO)?.let { RemoteHostInfo.fromJson(it) }
        }.getOrNull()
    }

    suspend fun fetchCatalog(): RemoteCatalog? = withContext(Dispatchers.IO) {
        runCatching {
            get(RemoteProtocol.PATH_MODELS)?.let { RemoteCatalog.fromJson(it) }
        }.getOrNull()
    }

    suspend fun selectModel(modelId: String, width: Int, height: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("model_id", modelId)
                put("width", width)
                put("height", height)
            }
            post(RemoteProtocol.PATH_SELECT, body) != null
        }.getOrDefault(false)
    }

    suspend fun fetchStatus(): RemoteHostStatus? = withContext(Dispatchers.IO) {
        runCatching {
            get(RemoteProtocol.PATH_STATUS)?.let { RemoteHostStatus.fromJson(it) }
        }.getOrNull()
    }

    /**
     * Asks the host to stop its backend, but only if [modelId] is still the
     * host's current selection; a stop that arrives after a newer /select is
     * ignored host-side.
     */
    suspend fun stopBackend(modelId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            post(RemoteProtocol.PATH_STOP, JSONObject().put("model_id", modelId)) != null
        }.getOrDefault(false)
    }

    /**
     * Whether the host accepts this device's access code: true on success,
     * false when it answers 401, null when it cannot be reached.
     */
    suspend fun checkAccess(): Boolean? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(baseUrl + RemoteProtocol.PATH_STATUS)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> true
                    response.code == HTTP_UNAUTHORIZED -> false
                    else -> null
                }
            }
        }.getOrNull()
    }

    /** Direct health probe against the native backend on the host. */
    suspend fun checkGenerationHealth(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("http://$generationHost/health")
                .get()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun get(path: String): JSONObject? {
        val request = Request.Builder()
            .url(baseUrl + path)
            .get()
            .build()
        return execute(request)
    }

    private fun post(path: String, body: JSONObject): JSONObject? {
        val request = Request.Builder()
            .url(baseUrl + path)
            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        return execute(request)
    }

    private fun execute(request: Request): JSONObject? = client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return null
        val payload = response.body?.string() ?: return null
        runCatching { JSONObject(payload) }.getOrNull()
    }

    companion object {
        private const val CONNECT_TIMEOUT_S = 4L
        private const val READ_TIMEOUT_S = 15L
        private const val HTTP_UNAUTHORIZED = 401
    }
}
