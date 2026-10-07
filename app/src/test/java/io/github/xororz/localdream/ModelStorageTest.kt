package io.github.xororz.localdream

import io.github.xororz.localdream.data.ModelStorage
import io.github.xororz.localdream.data.ModelStorage.MoveJournal
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelStorageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val journal by lazy { MoveJournal(File(tmp.root, "journal")) }

    // Between app and shared storage a rename always fails; on one JVM
    // filesystem it never does.
    private val acrossMounts: (File, File) -> Boolean = { _, _ -> false }

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
        ModelStorage.moveTree(src, dst, journal) { bytes += it }

        assertEquals("unet", File(dst, "a/unet.bin").readText())
        assertEquals("cache", File(dst, "a/cache/x.bin").readText())
        assertTrue(File(dst, "b/finished").isFile)
        assertFalse(src.exists())
        assertEquals(9L, bytes)
    }

    @Test
    fun copiesAcrossMountsAndRemovesSource() {
        val src = tmp.newFolder("src", "models")
        val dst = File(tmp.root, "dst/models")
        write(File(src, "a/unet.bin"), "unet")
        write(File(src, "a/cache/x.bin"), "cache")

        var bytes = 0L
        ModelStorage.moveTree(src, dst, journal, acrossMounts) { bytes += it }

        assertEquals("unet", File(dst, "a/unet.bin").readText())
        assertEquals("cache", File(dst, "a/cache/x.bin").readText())
        assertFalse(File(dst, "a/unet.bin.moving").exists())
        assertFalse(src.exists())
        assertFalse(File(tmp.root, "journal").exists())
        assertEquals(9L, bytes)
    }

    @Test
    fun committedCopyIsFinishedOnResume() {
        // The app died after the copy was renamed into place, before the
        // source was deleted.
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        val from = write(File(src, "m/unet.bin"), "same")
        val to = write(File(dst, "m/unet.bin"), "same")
        journal.record(from, to)

        ModelStorage.moveTree(src, dst, journal, acrossMounts) { }

        assertFalse(from.exists())
        assertEquals("same", to.readText())
    }

    @Test
    fun committedCopyIsFinishedWhenMovedBack() {
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        val there = write(File(src, "m/unet.bin"), "same")
        val back = write(File(dst, "m/unet.bin"), "same")
        // Recorded by the move the other way.
        journal.record(back, there)

        ModelStorage.moveTree(src, dst, journal, acrossMounts) { }

        assertFalse(there.exists())
        assertEquals("same", back.readText())
    }

    @Test
    fun sameSizeFileAtDestinationStopsTheMove() {
        // Same size is no proof of a copy this move made: an older version
        // left in Download/ would replace the current one.
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        write(File(src, "m/unet.bin"), "mine")
        write(File(dst, "m/unet.bin"), "old!")

        assertThrows(IOException::class.java) {
            ModelStorage.moveTree(src, dst, journal, acrossMounts) { }
        }

        assertEquals("mine", File(src, "m/unet.bin").readText())
        assertEquals("old!", File(dst, "m/unet.bin").readText())
    }

    @Test
    fun leftoverPartialCopyAtDestinationIsReplaced() {
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        write(File(src, "m/unet.bin"), "full")
        write(File(dst, "m/unet.bin.moving"), "ha")

        ModelStorage.moveTree(src, dst, journal, acrossMounts) { }

        assertFalse(File(dst, "m/unet.bin.moving").exists())
        assertEquals("full", File(dst, "m/unet.bin").readText())
        assertFalse(File(src, "m/unet.bin").exists())
    }

    @Test
    fun leftoverPartialCopyAtSourceIsDropped() {
        // Left by an interrupted move the other way.
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        write(File(src, "m/unet.bin.moving"), "half")
        write(File(src, "m/unet.bin"), "full")

        ModelStorage.moveTree(src, dst, journal, acrossMounts) { }

        assertFalse(File(dst, "m/unet.bin.moving").exists())
        assertEquals("full", File(dst, "m/unet.bin").readText())
        assertFalse(src.exists())
    }
}
