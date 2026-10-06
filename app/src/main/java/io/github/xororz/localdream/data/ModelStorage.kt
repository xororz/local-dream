package io.github.xororz.localdream.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Where models live. Every model, embedding and download path is built from
 * [root], so a place that computes its own path from `filesDir` would put its
 * files where nothing else looks.
 *
 * - [Location.INTERNAL] (default): `filesDir`, private to the app. Exactly the
 *   paths used before this setting existed, so nothing moves for anyone who
 *   never opens it.
 * - [Location.DOWNLOADS]: `Download/LocalDream`, visible in file managers and
 *   kept after uninstall. Other apps holding All files access can use the same
 *   models without downloading them again. Needs All files access
 *   (Android 11+), asked for only when someone picks it.
 *
 * The layout under either root is the same, and other apps may read it:
 * - `models/<model id>/` - one folder per model, with its marker files
 *   (`finished`, `npucustom`, `SDXL`, `ZIMAGE`, ...)
 * - `embeddings/` - textual-inversion `.safetensors`
 * - `temp_downloads/` - download scratch, not a model
 *
 * Only data lives here. The backend and QNN libraries stay in internal storage:
 * shared storage is mounted noexec.
 */
object ModelStorage {
    private const val TAG = "ModelStorage"
    private const val PREFS = "app_prefs"
    private const val KEY_LOCATION = "model_storage_location"
    private const val KEY_MOVING_TO = "model_storage_moving_to"

    /** The folder under Download/ used by [Location.DOWNLOADS]. */
    const val PUBLIC_FOLDER = "LocalDream"

    // models/ and embeddings/ must stay siblings: the backend looks for
    // embeddings two levels above --model_dir.
    private val MOVED_DIRS = listOf("models", "embeddings")
    private const val TEMP_DIR = "temp_downloads"

    // Free space kept on top of each file being copied.
    private const val SPACE_MARGIN = 256L shl 20

    enum class Location { INTERNAL, DOWNLOADS }

    sealed class MoveState {
        object Idle : MoveState()
        data class Moving(val doneBytes: Long, val totalBytes: Long) : MoveState()
        data class Done(val keptFiles: Int) : MoveState()
        data class Failed(val message: String) : MoveState()
    }

    private val _moveState = MutableStateFlow<MoveState>(MoveState.Idle)
    val moveState: StateFlow<MoveState> = _moveState

    // Not tied to a screen: leaving Settings must not cancel a move halfway.
    private val moveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var cached: Location? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun location(context: Context): Location = cached ?: run {
        val raw = prefs(context).getString(KEY_LOCATION, null)
        (Location.entries.firstOrNull { it.name == raw } ?: Location.INTERNAL).also { cached = it }
    }

    private fun setLocation(context: Context, value: Location) {
        prefs(context).edit(commit = true) { putString(KEY_LOCATION, value.name) }
        cached = value
    }

    fun rootFor(context: Context, location: Location): File = when (location) {
        Location.INTERNAL -> context.filesDir

        Location.DOWNLOADS -> File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            PUBLIC_FOLDER,
        )
    }

    fun root(context: Context): File = rootFor(context, location(context))

    fun modelsDir(context: Context): File = File(root(context), "models").apply {
        if (!exists()) mkdirs()
    }

    fun embeddingsDir(context: Context): File = File(root(context), "embeddings")

    // Beside models/ so an extracted download is renamed into place, not
    // copied: a rename between internal and shared storage fails.
    fun tempDownloadsDir(context: Context): File = File(root(context), TEMP_DIR)

    /**
     * Whether this build can offer [Location.DOWNLOADS] at all: only the
     * GitHub build declares All files access (`src/basic/AndroidManifest.xml`),
     * because Google Play restricts it.
     */
    fun isPublicStorageAvailable(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
            .contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
    }.getOrDefault(false)

    // A build without the permission still shows the choice if models are
    // already in Download/, so they can be moved back.
    fun isChoiceShown(context: Context): Boolean = isPublicStorageAvailable(context) ||
        location(context) == Location.DOWNLOADS

    fun isPublicStorageSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasAllFilesAccess(): Boolean = isPublicStorageSupported() &&
        // Throws when no primary volume is mounted; no volume is no access.
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /** Download/LocalDream is selected but All files access has been turned off since. */
    fun isAccessLost(context: Context): Boolean = location(context) == Location.DOWNLOADS && !hasAllFilesAccess()

    fun allFilesAccessIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        "package:${context.packageName}".toUri(),
    )

    /** The target of a move that started and never finished, or null. */
    fun pendingMove(context: Context): Location? = prefs(context).getString(KEY_MOVING_TO, null)
        ?.let { raw -> Location.entries.firstOrNull { it.name == raw } }

    /** Bytes of models and embeddings at [location]. */
    fun sizeAt(context: Context, location: Location): Long {
        val root = rootFor(context, location)
        return MOVED_DIRS.sumOf { sub ->
            File(root, sub).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }

    /**
     * Moves every model and embedding to [to] in the background, then switches
     * to it. Progress and the outcome are published on [moveState].
     *
     * Resumable: the target is recorded before the first file moves, and a file
     * already complete at the destination is not copied again, so running it
     * after an interruption finishes the job ([pendingMove]).
     */
    fun startMove(context: Context, to: Location) {
        if (_moveState.value is MoveState.Moving) return
        val app = context.applicationContext
        _moveState.value = MoveState.Moving(0, 0)
        moveScope.launch {
            _moveState.value = try {
                MoveState.Done(move(app, to))
            } catch (e: Exception) {
                Log.e(TAG, "move to $to failed", e)
                MoveState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun clearMoveState() {
        if (_moveState.value !is MoveState.Moving) _moveState.value = MoveState.Idle
    }

    private fun move(context: Context, to: Location): Int {
        val from = Location.entries.first { it != to }
        prefs(context).edit(commit = true) { putString(KEY_MOVING_TO, to.name) }

        val src = rootFor(context, from)
        val dst = rootFor(context, to)
        dst.mkdirs()
        if (to == Location.DOWNLOADS) {
            // Nothing here is a picture; keep the gallery from scanning gigabytes.
            File(dst, ".nomedia").takeIf { !it.exists() }?.createNewFile()
        }

        // Download scratch is not worth moving.
        File(src, TEMP_DIR).deleteRecursively()

        val total = sizeAt(context, from)
        var done = 0L
        var kept = 0
        for (sub in MOVED_DIRS) {
            val dir = File(src, sub)
            if (!dir.exists()) continue
            kept += moveTree(dir, File(dst, sub)) { n ->
                done += n
                _moveState.value = MoveState.Moving(done, total)
            }
        }

        setLocation(context, to)
        prefs(context).edit(commit = true) { remove(KEY_MOVING_TO) }
        Log.i(TAG, "moved ${done shr 20} MB $src -> $dst, $kept files left in place")
        return kept
    }

    /** @return files left at [src] because a different file already sat at the destination. */
    internal fun moveTree(src: File, dst: File, onBytes: (Long) -> Unit): Int {
        if (src.isDirectory) {
            dst.mkdirs()
            var kept = 0
            for (child in src.listFiles().orEmpty()) {
                kept += moveTree(child, File(dst, child.name), onBytes)
            }
            // Only once empty: a file left in place keeps its directory.
            if (src.listFiles().isNullOrEmpty()) src.delete()
            return kept
        }
        if (src.name.endsWith(".moving")) {
            src.delete()
            return 0
        }
        val len = src.length()
        if (dst.exists()) {
            // Same size: a copy that finished before the source was deleted.
            if (dst.length() == len) {
                src.delete()
                onBytes(len)
                return 0
            }
            // A different file is already there: keep both, report it.
            return 1
        }
        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) {
            onBytes(len)
            return 0
        }

        // Internal and shared storage are different mounts, so this is the
        // usual path: copy under a temporary name, then rename, so a
        // half-copied file never appears under its real name.
        val free = generateSequence(dst) { it.parentFile }.first { it.exists() }.usableSpace
        if (free < len + SPACE_MARGIN) {
            throw IOException("not enough space for ${src.name}: needs ${len shr 20} MB, ${free shr 20} MB free")
        }
        val tmp = File(dst.parentFile, dst.name + ".moving")
        src.inputStream().use { input ->
            tmp.outputStream().use { output ->
                val buffer = ByteArray(1 shl 20)
                var since = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    since += n
                    if (since >= 64L shl 20) {
                        onBytes(since)
                        since = 0
                    }
                }
                onBytes(since)
            }
        }
        if (tmp.length() != len || !tmp.renameTo(dst)) {
            tmp.delete()
            throw IOException("could not move ${src.name}")
        }
        src.delete()
        return 0
    }
}
