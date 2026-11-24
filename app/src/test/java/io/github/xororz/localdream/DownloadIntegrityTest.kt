package io.github.xororz.localdream

import io.github.xororz.localdream.utils.DownloadIntegrity
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadIntegrityTest {
    private val sha = "2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae"

    private fun response(code: Int, url: String, etag: String? = null, prior: Response? = null): Response = Response.Builder()
        .request(Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("x")
        .apply { if (etag != null) header("X-Linked-Etag", etag) }
        .priorResponse(prior)
        .build()

    @Test
    fun parsesQuotedAndWeakEtags() {
        assertEquals(sha, DownloadIntegrity.parseSha256Etag("\"$sha\""))
        assertEquals(sha, DownloadIntegrity.parseSha256Etag("W/\"${sha.uppercase()}\""))
        // A git blob SHA-1 (non-LFS file) is not a SHA-256.
        assertNull(DownloadIntegrity.parseSha256Etag("\"a94a8fe5ccb19ba61c4c0873d391e987982fbbd3\""))
        assertNull(DownloadIntegrity.parseSha256Etag(null))
    }

    @Test
    fun findsTheHashOnTheHubRedirect() {
        val hub = response(302, "https://huggingface.co/a/b/resolve/main/unet.bin", "\"$sha\"")
        val cdn = response(200, "https://cdn-lfs.hf.co/x", prior = hub)
        assertEquals(sha, DownloadIntegrity.expectedSha256(cdn))
        assertNull(DownloadIntegrity.expectedSha256(response(200, "https://example.com/x")))
    }

    @Test
    fun hexOfDigest() {
        val digest = DownloadIntegrity.newDigest()
        digest.update("foo".toByteArray())
        assertEquals(sha, DownloadIntegrity.hex(digest.digest()))
    }

    @Test
    fun plainHttpIsRefused() {
        assertThrows(DownloadIntegrity.InsecureUrlException::class.java) {
            DownloadIntegrity.requireHttps("http://huggingface.co/x")
        }
        DownloadIntegrity.requireHttps("https://huggingface.co/x")
    }
}
