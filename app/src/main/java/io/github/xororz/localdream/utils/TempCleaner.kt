package io.github.xororz.localdream.utils

import android.content.Context
import android.util.Log
import io.github.xororz.localdream.data.ModelRepository
import io.github.xororz.localdream.data.ModelStorage
import io.github.xororz.localdream.service.ModelDownloadService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Removes scratch / orphaned files the app can leave behind, without touching
 * anything it actively manages (models, history images, embeddings, tag dicts,
 * the QNN runtime libs, prompt/latent caches). The targeted set:
 *
 *  - `temp_downloads/`        partial model `.tmp` files from an interrupted or
 *                             process-killed download (can be several GB).
 *  - tmp.txt / mask.txt / ultrafix.txt  base64 IPC buffers handed to the
 *                             generation service; safe to drop while idle.
 *  - .part files in history/  half-written images from a cancelled backup import.
 *  - models entries           entries under the models dir that aren't a usable
 *                             model: stray files, or half-extracted dirs with no
 *                             completion marker (e.g. a custom-model extraction
 *                             killed before it finished). Built-in models,
 *                             upscalers and finished custom models are kept.
 *
 * The download scratch dir and the models sweep are skipped while a
 * download/extract is in flight so cleaning can't pull the rug out from under
 * an active transfer (a model dir being populated by a rename).
 */
object TempCleaner {
    private const val TAG = "TempCleaner"

    /** Total size in bytes of everything [clean] would remove right now. */
    suspend fun scan(context: Context): Long = withContext(Dispatchers.IO) {
        collectTargets(context).sumOf { sizeOf(it) }
    }

    /** Deletes the targets and returns the number of bytes actually freed. */
    suspend fun clean(context: Context): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        for (target in collectTargets(context)) {
            val size = sizeOf(target)
            val deleted = if (target.isDirectory) target.deleteRecursively() else target.delete()
            if (deleted) {
                freed += size
            } else {
                Log.w(TAG, "failed to delete ${target.absolutePath}")
            }
        }
        freed
    }

    private fun collectTargets(context: Context): List<File> {
        val filesDir = context.filesDir
        val targets = mutableListOf<File>()

        // Download scratch dir: only when no transfer is using it.
        val downloadActive = ModelDownloadService.downloadState.value.let { state ->
            state is ModelDownloadService.DownloadState.Downloading ||
                state is ModelDownloadService.DownloadState.Extracting
        }
        if (!downloadActive) {
            ModelStorage.tempDownloadsDir(context).takeIf { it.exists() }?.let { targets += it }

            // Unrecognized leftovers under models/ (stray files, half-extracted
            // dirs). Built-in models, upscalers and finished custom models are
            // preserved. Skipped during a download since a model dir may be
            // mid-populate.
            File(ModelStorage.root(context), "models").takeIf { it.isDirectory }?.listFiles()?.forEach { entry ->
                if (!isRecognizedModelEntry(entry)) targets += entry
            }
        }

        // Transient base64 IPC buffers.
        for (name in listOf("tmp.txt", "mask.txt", "ultrafix.txt")) {
            File(filesDir, name).takeIf { it.isFile }?.let { targets += it }
        }

        // Half-written backup-import images.
        File(filesDir, "history").takeIf { it.isDirectory }?.walkTopDown()
            ?.filter { it.isFile && it.name.endsWith(".part") }
            ?.forEach { targets += it }

        return targets
    }

    // A models/ entry worth keeping. Conservative: anything that even looks
    // like a model, an upscaler or a built-in is preserved; only clearly
    // orphaned entries fall through to deletion.
    private fun isRecognizedModelEntry(entry: File): Boolean {
        // Stray files directly under models/ are never models.
        if (!entry.isDirectory) return false
        val name = entry.name
        // Built-in models (managed from the model list, downloaded or not).
        if (ModelRepository.isReservedModelId(name)) return true
        // Upscalers share the models dir; never touch them.
        if (name.startsWith("upscaler") || File(entry, "upscaler.bin").exists()) return true
        // Custom models are only listed once one of these markers is written,
        // so a marker-less dir is an unusable, half-finished import.
        return File(entry, "finished").exists() ||
            File(entry, "npucustom").exists() ||
            File(entry, "SDXL").exists()
    }

    private fun sizeOf(file: File): Long = if (file.isDirectory) {
        file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } else {
        file.length()
    }
}
