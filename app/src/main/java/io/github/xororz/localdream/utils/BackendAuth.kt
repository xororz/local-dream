package io.github.xororz.localdream.utils

import android.content.Context
import android.util.Log
import io.github.xororz.localdream.remote.RemoteProtocol
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl
import okhttp3.Interceptor

/**
 * Access code for the native backend (generation port) and the host-mode
 * control API.
 *
 * Both servers used to accept any request, so while a model was loaded any
 * other app on the device - or any web page, through the permissive CORS
 * headers - could use the backend on 127.0.0.1, and with "Allow LAN access" or
 * host mode anyone on the network. Now the backend is started with
 * [ENV_TOKEN] and only accepts requests carrying "Authorization: Bearer
 * <code>".
 *
 * - This device's code is generated once per install and kept in
 *   noBackupFilesDir, so it never ends up in a backup. The app attaches it to
 *   its own loopback requests automatically ([interceptor] on [Http.client]).
 * - A controller paired with a host stores the host's code (shown on the host's
 *   Remote screen) and attaches it to every request to that host.
 *
 * Codes are 12 characters from an alphabet without look-alikes (no 0/O, 1/I),
 * about 60 bits, displayed as XXXX-XXXX-XXXX.
 */
object BackendAuth {
    private const val TAG = "BackendAuth"

    /** Environment variable the native backend reads its access code from. */
    const val ENV_TOKEN = "LD_BACKEND_TOKEN"

    const val CODE_LENGTH = 12
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val FILE_NAME = "backend_access_code"

    private val random = SecureRandom()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedCode: String? = null

    // Paired hosts: host name/address -> that host's access code.
    private val remoteCodes = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** This device's access code, created on first use. */
    fun localCode(context: Context): String = cachedCode ?: synchronized(this) {
        cachedCode ?: loadOrCreate(context.applicationContext).also { cachedCode = it }
    }

    /**
     * Replaces this device's code, which unpairs every controller. Takes effect
     * for the backend the next time it is started.
     */
    fun regenerateLocalCode(context: Context): String = synchronized(this) {
        val code = newCode()
        writeCode(context.applicationContext, code)
        cachedCode = code
        code
    }

    /** XXXX-XXXX-XXXX for display. */
    fun format(code: String): String = code.chunked(4).joinToString("-")

    /** Upper-cases user input and drops separators and anything not in the code alphabet. */
    fun normalize(input: String): String = input.uppercase().filter { it in ALPHABET }

    fun isValidCode(code: String): Boolean = code.length == CODE_LENGTH && code.all { it in ALPHABET }

    /** Remembers (or with null, forgets) the access code of a paired host. */
    fun setRemoteCode(host: String, code: String?) {
        if (code.isNullOrEmpty()) remoteCodes.remove(host) else remoteCodes[host] = code
    }

    /** Constant-time check of an Authorization header against [code]. */
    fun matches(authorizationHeader: String?, code: String): Boolean {
        val expected = "Bearer $code".toByteArray(Charsets.UTF_8)
        val got = (authorizationHeader ?: "").trim().toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(expected, got)
    }

    internal fun codeFor(url: HttpUrl): String? {
        val host = url.host
        if (host == "127.0.0.1" || host == "localhost" || host == "::1") {
            // Only the native backend listens on loopback; never send the code
            // to anything else that happens to run there.
            if (url.port != RemoteProtocol.GENERATION_PORT) return null
            return appContext?.let { localCode(it) }
        }
        return remoteCodes[host]
    }

    /** Adds the Authorization header to requests for the backend and paired hosts. */
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val code = if (request.header("Authorization") == null) codeFor(request.url) else null
        if (code == null) {
            chain.proceed(request)
        } else {
            chain.proceed(request.newBuilder().header("Authorization", "Bearer $code").build())
        }
    }

    private fun loadOrCreate(context: Context): String {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        val stored = runCatching { file.readText().trim() }.getOrNull()
        if (stored != null && isValidCode(stored)) return stored
        val code = newCode()
        writeCode(context, code)
        return code
    }

    private fun writeCode(context: Context, code: String) {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        val tmp = File(context.noBackupFilesDir, "$FILE_NAME.tmp")
        try {
            tmp.writeText(code)
            if (!tmp.renameTo(file)) {
                file.writeText(code)
                tmp.delete()
            }
        } catch (e: java.io.IOException) {
            Log.e(TAG, "Failed to store the access code", e)
        }
    }

    private fun newCode(): String = buildString(CODE_LENGTH) {
        repeat(CODE_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
}
