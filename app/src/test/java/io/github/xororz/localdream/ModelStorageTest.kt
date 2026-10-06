package io.github.xororz.localdream

import io.github.xororz.localdream.data.ModelStorage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelStorageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(file: File, text: String) = file.apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    @Test
    fun movesTreeAndRemovesSource() {
        val src = tmp.newFolder("src", "models")
        val dst = File(tmp.root, "dst/models")
        write(File(src, "a/unet.bin"), "unet")
        write(File(src, "a/cache/x.bin"), "cache")
        write(File(src, "b/finished"), "")

        var bytes = 0L
        val kept = ModelStorage.moveTree(src, dst) { bytes += it }

        assertEquals(0, kept)
        assertEquals("unet", File(dst, "a/unet.bin").readText())
        assertEquals("cache", File(dst, "a/cache/x.bin").readText())
        assertTrue(File(dst, "b/finished").isFile)
        assertFalse(src.exists())
        assertEquals(9L, bytes)
    }

    @Test
    fun finishedCopyIsNotCopiedAgain() {
        // An interrupted move: the copy completed, the source was not deleted.
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        write(File(src, "m/unet.bin"), "same")
        write(File(dst, "m/unet.bin"), "same")

        val kept = ModelStorage.moveTree(src, dst) { }

        assertEquals(0, kept)
        assertFalse(File(src, "m/unet.bin").exists())
        assertEquals("same", File(dst, "m/unet.bin").readText())
    }

    @Test
    fun differentFileAtDestinationIsKept() {
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        write(File(src, "m/unet.bin"), "mine")
        write(File(dst, "m/unet.bin"), "theirs!")

        val kept = ModelStorage.moveTree(src, dst) { }

        assertEquals(1, kept)
        assertEquals("mine", File(src, "m/unet.bin").readText())
        assertEquals("theirs!", File(dst, "m/unet.bin").readText())
    }

    @Test
    fun leftoverPartialCopyIsDropped() {
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        write(File(src, "m/unet.bin.moving"), "half")
        write(File(src, "m/unet.bin"), "full")

        ModelStorage.moveTree(src, dst) { }

        assertFalse(File(dst, "m/unet.bin.moving").exists())
        assertEquals("full", File(dst, "m/unet.bin").readText())
    }
}
