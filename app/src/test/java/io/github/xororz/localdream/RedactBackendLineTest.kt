package io.github.xororz.localdream

import io.github.xororz.localdream.service.redactBackendLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactBackendLineTest {
    @Test
    fun oldRequestLogLosesThePromptTexts() {
        val line = "Req Rcvd: P:a secret prompt NP:blurry, bad S:20 CFG:7 Seed:1 Size:512x512"
        val redacted = redactBackendLine(line)
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("blurry"))
        assertEquals("Req Rcvd: P:<redacted> S:20 CFG:7 Seed:1 Size:512x512", redacted)
    }

    @Test
    fun promptsContainingTheSeparatorDoNotLeak() {
        val line = "Req Rcvd: P:secret S: part NP:more S:5 hidden S:20 CFG:7 Seed:1"
        assertEquals("Req Rcvd: P:<redacted> S:20 CFG:7 Seed:1", redactBackendLine(line))
    }

    @Test
    fun otherLinesAreUntouched() {
        val line = "Server listening on 127.0.0.1:8081"
        assertEquals(line, redactBackendLine(line))
    }
}
