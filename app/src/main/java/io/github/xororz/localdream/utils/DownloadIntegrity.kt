package io.github.xororz.localdream.utils

import java.io.IOException
import java.security.MessageDigest
import okhttp3.Response

/**
 * Integrity checks for model downloads.
 *
 * Model files are parsed by native code, so a tampered or corrupted file is
 * more than a broken download. Downloads are therefore HTTPS-only (no
 * redirects to plain HTTP), and files are verified against the SHA-256 that
 * Hugging Face publishes for every LFS file in the X-Linked-Etag header of the
 * resolve response. That response comes from the hub itself, while the bytes
 * usually come from a CDN it redirects to, so the check also covers the CDN
 * leg. Mirrors that pass the header on are verified the same way; files
 * without a published SHA-256 (small non-LFS files) are only size-checked.
 */
object DownloadIntegrity {
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    class InsecureUrlException(url: String) : IOException("Refusing non-HTTPS download: $url")

    class ChecksumMismatchException(expected: String, actual: String) : IOException("SHA-256 mismatch: expected $expected, got $actual")

    fun requireHttps(url: String) {
        if (!url.startsWith("https://", ignoreCase = true)) throw InsecureUrlException(url)
    }

    /** The SHA-256 announced anywhere along the redirect chain, or null. */
    fun expectedSha256(response: Response): String? {
        var current: Response? = response
        while (current != null) {
            parseSha256Etag(current.header("X-Linked-Etag"))?.let { return it }
            current = current.priorResponse
        }
        return null
    }

    /** `"abc…"` / `W/"abc…"` -> lower-case hex, if it is a SHA-256. */
    fun parseSha256Etag(value: String?): String? {
        val cleaned = value?.trim()?.removePrefix("W/")?.trim('"')?.lowercase() ?: return null
        return cleaned.takeIf { SHA256_HEX.matches(it) }
    }

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
