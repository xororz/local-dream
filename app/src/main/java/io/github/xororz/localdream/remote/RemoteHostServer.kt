package io.github.xororz.localdream.remote

import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Minimal blocking HTTP/1.1 server backing host mode's control API.
 *
 * Scope is deliberately tiny: small JSON requests and responses on a handful
 * of fixed routes, one response per connection (Connection: close). Anything
 * heavy (generation SSE, tokenize, upscale) is served by the native backend
 * on its own port, never through here.
 *
 * No authentication, matching the trust model of the existing "allow LAN
 * access" feature: the generation port itself is unauthenticated, so gating
 * only the control API would add friction without adding security. Host mode
 * is explicit and user-visible while active.
 *
 * CORS headers mirror the native backend's (main.cpp, set_default_headers) so a
 * browser page can drive these routes the same way it already drives /generate.
 * `Access-Control-Allow-Origin: *` matches what the generation port already
 * sends; it widens nothing that was not already reachable from a page.
 */
class RemoteHostServer(
    private val port: Int,
    private val handler: Handler,
) {
    interface Handler {
        fun info(): JSONObject
        fun models(): JSONObject
        fun select(body: JSONObject): Response
        fun status(): JSONObject
        fun stop(body: JSONObject): JSONObject
    }

    data class Response(val code: Int, val body: JSONObject)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false
    private var acceptThread: Thread? = null
    private var workers: ExecutorService? = null

    @Throws(IOException::class)
    fun start() {
        val socket = ServerSocket(port)
        serverSocket = socket
        running = true
        workers = Executors.newFixedThreadPool(WORKER_COUNT) { r ->
            Thread(r, "remote-host-worker")
        }
        acceptThread = Thread({
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "accept failed: ${e.message}")
                    break
                }
                workers?.execute { serve(client) }
            }
        }, "remote-host-accept").apply { start() }
        Log.i(TAG, "control server listening on $port")
    }

    fun shutdown() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        workers?.shutdownNow()
        workers = null
        acceptThread = null
        Log.i(TAG, "control server stopped")
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            client.use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val request = parseRequest(input)
                val response = if (request == null) {
                    Response(400, errorBody("bad request"))
                } else {
                    route(request)
                }
                writeResponse(socket, response)
            }
        } catch (e: Exception) {
            Log.w(TAG, "request handling failed: ${e.message}")
        }
    }

    private data class Request(
        val method: String,
        val path: String,
        val body: JSONObject,
    )

    private fun parseRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0]
        // Strip any query string; routes don't use one.
        val path = parts[1].substringBefore('?')

        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val name = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':').trim()
            if (name == "content-length") {
                contentLength = value.toIntOrNull() ?: 0
            }
        }
        if (contentLength > MAX_BODY_BYTES) return null

        val body = if (contentLength > 0) {
            val bytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(bytes, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            try {
                JSONObject(String(bytes, 0, read, StandardCharsets.UTF_8))
            } catch (_: Exception) {
                JSONObject()
            }
        } else {
            JSONObject()
        }
        return Request(method, path, body)
    }

    // Reads one CRLF/LF-terminated header line as ISO-8859-1 (headers are
    // ASCII here; bodies are decoded separately as UTF-8).
    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) buffer.append(c.toChar())
            if (buffer.length > MAX_LINE_LENGTH) return null
        }
        return buffer.toString()
    }

    private fun route(request: Request): Response {
        // Preflight is answered before any path-based routing: a browser sends
        // OPTIONS without the method it intends to use, so deciding by path
        // would 404 the preflight itself on every route except /info.
        if (request.method == "OPTIONS") {
            return Response(204, JSONObject())
        }
        if (request.path == RemoteProtocol.PATH_INFO) {
            return Response(200, handler.info())
        }
        return when {
            request.method == "GET" && request.path == RemoteProtocol.PATH_MODELS ->
                Response(200, handler.models())

            request.method == "POST" && request.path == RemoteProtocol.PATH_SELECT ->
                handler.select(request.body)

            request.method == "GET" && request.path == RemoteProtocol.PATH_STATUS ->
                Response(200, handler.status())

            request.method == "POST" && request.path == RemoteProtocol.PATH_STOP ->
                Response(200, handler.stop(request.body))

            else -> Response(404, errorBody("not found"))
        }
    }

    private fun writeResponse(socket: Socket, response: Response) {
        // A 204 must not carry a body, and Content-Type on it is meaningless.
        val noContent = response.code == 204
        val body = if (noContent) {
            ByteArray(0)
        } else {
            response.body.toString().toByteArray(StandardCharsets.UTF_8)
        }
        val output = socket.getOutputStream()
        output.write(
            responseHead(response.code, body.size).toByteArray(StandardCharsets.ISO_8859_1),
        )
        if (!noContent) {
            output.write(body)
        }
        output.flush()
    }

    private fun errorBody(message: String): JSONObject = JSONObject().put("error", message)

    companion object {
        private const val TAG = "RemoteHostServer"

        // Single worker on purpose: select/stop must be processed in arrival
        // order (a reordered late stop could kill a newer selection), and
        // every route is a fast, small JSON exchange.
        private const val WORKER_COUNT = 1
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val MAX_LINE_LENGTH = 8 * 1024
    }
}

// The CORS headers mirror the native backend's default headers (set_default_headers
// in main.cpp) so a browser page can drive the control API exactly the way it
// already drives /generate on the generation port. Allow-Private-Network is the
// one addition: Chrome sends a private-network preflight when a public page
// reaches a LAN address, and that preflight fails without this header.
//
// Kept outside the class so it holds no socket state: the wire format is then
// assertable from a plain JVM test, which is otherwise only observable by
// opening a browser against a running device.
internal fun responseHead(code: Int, bodySize: Int): String {
    val reason = when (code) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        else -> "Error"
    }
    return buildString {
        append("HTTP/1.1 $code $reason\r\n")
        append("Access-Control-Allow-Origin: *\r\n")
        append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
        append("Access-Control-Max-Age: 86400\r\n")
        append("Access-Control-Allow-Private-Network: true\r\n")
        if (code == 204) {
            append("Content-Length: 0\r\n")
        } else {
            append("Content-Type: application/json\r\n")
            append("Content-Length: $bodySize\r\n")
        }
        append("Connection: close\r\n")
        append("\r\n")
    }
}
