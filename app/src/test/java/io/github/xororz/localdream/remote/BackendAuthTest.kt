package io.github.xororz.localdream.remote

import io.github.xororz.localdream.utils.BackendAuth
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendAuthTest {
    private val code = "ABCD2345EFGH"

    @Test
    fun controlApiRejectsMissingOrWrongCodes() {
        assertFalse(isAuthorized(null, code))
        assertFalse(isAuthorized("", code))
        assertFalse(isAuthorized("Bearer WRONGWRONGWR", code))
        assertFalse(isAuthorized("Bearer ABCD2345EFG", code))
        assertFalse(isAuthorized(code, code))
        assertTrue(isAuthorized("Bearer $code", code))
    }

    @Test
    fun noCodeMeansNoCheck() {
        assertTrue(isAuthorized(null, null))
        assertTrue(isAuthorized(null, ""))
    }

    @Test
    fun userInputIsNormalized() {
        assertEquals(code, BackendAuth.normalize(" abcd-2345 efgh "))
        assertTrue(BackendAuth.isValidCode(BackendAuth.normalize("abcd-2345-efgh")))
        // Look-alikes are not part of the alphabet, so such a code is invalid.
        assertFalse(BackendAuth.isValidCode("ABCD2345EFG0"))
        assertFalse(BackendAuth.isValidCode("ABCD2345EF"))
        assertEquals("ABCD-2345-EFGH", BackendAuth.format(code))
    }

    @Test
    fun codeIsOnlySentToThePairedHost() {
        BackendAuth.setRemoteCode("192.168.1.50", code)
        try {
            assertEquals(code, BackendAuth.codeFor("http://192.168.1.50:8081/generate".toHttpUrl()))
            assertEquals(code, BackendAuth.codeFor("http://192.168.1.50:8808/status".toHttpUrl()))
            assertNull(BackendAuth.codeFor("http://192.168.1.51:8081/generate".toHttpUrl()))
            assertNull(BackendAuth.codeFor("https://huggingface.co/xororz/model".toHttpUrl()))
        } finally {
            BackendAuth.setRemoteCode("192.168.1.50", null)
        }
        assertNull(BackendAuth.codeFor("http://192.168.1.50:8081/generate".toHttpUrl()))
    }
}
