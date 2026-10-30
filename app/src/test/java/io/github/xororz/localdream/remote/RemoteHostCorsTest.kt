package io.github.xororz.localdream.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The control port is meant to be driven from a browser page (#345), which only
 * works if the preflight succeeds and every response carries the CORS headers.
 * Both are invisible to a JVM test through the socket, so the header block is
 * built by [responseHead] and asserted here.
 */
class RemoteHostCorsTest {
    private val corsHeaders = listOf(
        "Access-Control-Allow-Origin: *",
        "Access-Control-Allow-Methods: GET, POST, OPTIONS",
        "Access-Control-Allow-Headers: Content-Type, Authorization",
        "Access-Control-Max-Age: 86400",
        "Access-Control-Allow-Private-Network: true",
    )

    @Test
    fun preflightIsAnswerableWithoutABody() {
        val head = responseHead(204, 0)

        assertTrue(head.startsWith("HTTP/1.1 204 No Content\r\n"))
        // No body is sent for a preflight, so no Content-Type may be advertised.
        // Match the header name at the start of a line: "Content-Type" also
        // appears as a value inside Access-Control-Allow-Headers.
        assertFalse(head.contains("\r\nContent-Type:"))
        assertTrue(head.contains("Content-Length: 0\r\n"))
        corsHeaders.forEach { assertTrue("missing $it", head.contains("$it\r\n")) }
        assertTrue(head.endsWith("\r\n\r\n"))
    }

    @Test
    fun normalResponsesCarryCorsHeadersToo() {
        val head = responseHead(200, 42)

        assertTrue(head.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(head.contains("Content-Type: application/json\r\n"))
        assertTrue(head.contains("Content-Length: 42\r\n"))
        corsHeaders.forEach { assertTrue("missing $it", head.contains("$it\r\n")) }
    }

    @Test
    fun errorResponsesAreStillReadableCrossOrigin() {
        // A browser cannot read the body of a cross-origin error response unless
        // the CORS headers are present, which would turn /select failures into
        // opaque network errors on the page.
        val head = responseHead(404, 24)

        assertTrue(head.startsWith("HTTP/1.1 404 Not Found\r\n"))
        corsHeaders.forEach { assertTrue("missing $it", head.contains("$it\r\n")) }
    }

    @Test
    fun everyStatusLineIsTerminatedByABlankLine() {
        listOf(200, 204, 400, 401, 404).forEach { code ->
            val head = responseHead(code, 0)
            assertEquals("\r\n\r\n", head.takeLast(4))
        }
    }
}
