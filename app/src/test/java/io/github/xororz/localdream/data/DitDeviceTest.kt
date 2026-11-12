package io.github.xororz.localdream.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DitDeviceTest {
    @Test
    fun v75ProfileIsLimitedToSnapdragon8Gen3OnAndroid12OrLater() {
        for (soc in listOf("SM8650", "sm8650p")) {
            assertTrue(soc, DitEngine.isSupportedSoc(soc, 31))
            assertTrue(soc, DitEngine.isSm8650Soc(soc, 31))
            assertFalse(soc, DitEngine.isSupportedSoc(soc, 30))
            assertFalse(soc, DitEngine.isSm8650Soc(soc, 30))
        }

        for (soc in listOf("SM8750", "SM8750P", "SM8850", "SM8950", "SM8975")) {
            assertTrue(soc, DitEngine.isSupportedSoc(soc, 31))
            assertFalse(soc, DitEngine.isSm8650Soc(soc, 31))
        }

        for (soc in listOf("SM8450", "SM8550", "SM8635", "SM8735", "SMfoo8650", "MT8650", "unknown")) {
            assertFalse(soc, DitEngine.isSupportedSoc(soc, 36))
            assertFalse(soc, DitEngine.isSm8650Soc(soc, 36))
        }
    }
}
