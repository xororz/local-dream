package io.github.xororz.localdream.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import io.github.xororz.localdream.R
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.service.ModelDownloadService
import io.github.xororz.localdream.service.ModelMoveService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
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
 *   (Android 11+), asked for only when someone picks it. Shared storage
 *   ignores letter case, unlike app storage ([ignoresCase]).
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

    // A file of its own so backups can leave it out (res/xml/backup_rules.xml,
    // data_extraction_rules.xml): where the models are is a fact about this
    // device, and restoring it elsewhere would point the app at a folder it
    // cannot read, or start a stale move.
    private const val PREFS = "model_storage"
    private const val KEY_LOCATION = "location"
    private const val KEY_MOVING_TO = "moving_to"

    // In noBackupFilesDir, see MoveJournal.
    private const val JOURNAL = "model_move_journal"

    /** The folder under Download/ used by [Location.DOWNLOADS]. */
    const val PUBLIC_FOLDER = "LocalDream"

    // models/ and embeddings/ must stay siblings: the backend looks for
    // embeddings two levels above --model_dir.
    private const val MODELS_DIR = "models"
    private val MOVED_DIRS = listOf(MODELS_DIR, "embeddings")
    private const val TEMP_DIR = "temp_downloads"

    // Suffix of a copy in progress at the destination.
    private const val PARTIAL_SUFFIX = ".moving"

    // Free space kept on top of each file being copied.
    private const val SPACE_MARGIN = 256L shl 20

    enum class Location { INTERNAL, DOWNLOADS }

    sealed class MoveState {
        object Idle : MoveState()
        data class Moving(val doneBytes: Long, val totalBytes: Long) : MoveState()
        object Done : MoveState()
        data class Failed(val message: String) : MoveState()
    }

    private val _moveState = MutableStateFlow<MoveState>(MoveState.Idle)
    val moveState: StateFlow<MoveState> = _moveState

    // Not tied to a screen: leaving Settings must not cancel a move halfway.
    private val moveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A move and anything that reads or writes model files exclude each other
    // through this lock: startMove() checks [isBusy] and sets [moving] under it,
    // and BackendService publishes its Starting state through [unlessMoving].
    private val gate = Any()
    private var moving = false

    @Volatile private var cached: Location? = null

    @Volatile private var publicAvailable: Boolean? = null

    // Set once any move starts in this process: only a move an earlier
    // process was killed in is resumed on its own.
    @Volatile private var moveStarted = false

    @Volatile private var seenAccess: Boolean? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun location(context: Context): Location = cached ?: run {
        val raw = prefs(context).getString(KEY_LOCATION, null)
        (Location.entries.firstOrNull { it.name == raw } ?: Location.INTERNAL).also { cached = it }
    }

    private fun setLocation(context: Context, value: Location) {
        prefs(context).edit(commit = true) { putString(KEY_LOCATION, value.name) }
        cached = value
    }

    fun other(location: Location): Location = when (location) {
        Location.INTERNAL -> Location.DOWNLOADS
        Location.DOWNLOADS -> Location.INTERNAL
    }

    fun rootFor(context: Context, location: Location): File = when (location) {
        Location.INTERNAL -> context.filesDir

        Location.DOWNLOADS -> File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            PUBLIC_FOLDER,
        )
    }

    fun root(context: Context): File = rootFor(context, location(context))

    fun modelsDir(context: Context): File = File(root(context), MODELS_DIR).apply {
        if (!exists()) mkdirs()
    }

    fun embeddingsDir(context: Context): File = File(root(context), "embeddings")

    // Beside models/ so an extracted download is renamed into place, not
    // copied: a rename between internal and shared storage fails.
    fun tempDownloadsDir(context: Context): File = File(root(context), TEMP_DIR)

    /**
     * Whether names that differ only in letter case are the same file at the
     * current location. Shared storage ignores case, so a model id checked
     * case-sensitively there can still land on another model's folder.
     */
    fun ignoresCase(context: Context): Boolean = location(context) == Location.DOWNLOADS

    /**
     * Whether this build can offer [Location.DOWNLOADS] at all: only the
     * GitHub build declares All files access (`src/basic/AndroidManifest.xml`),
     * because Google Play restricts it. Fixed per build, so asked once.
     */
    fun isPublicStorageAvailable(context: Context): Boolean = publicAvailable ?: runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
            .contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
    }.getOrDefault(false).also { publicAvailable = it }

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

    /**
     * All files access when it changed since the last call while models live in
     * Download/LocalDream, else null. It is switched in system settings, outside
     * the app, so the model list has to be rescanned when it flips. The first
     * call reports only missing access.
     */
    fun pollAccessChange(context: Context): Boolean? {
        if (location(context) != Location.DOWNLOADS) {
            seenAccess = null
            return null
        }
        val now = hasAllFilesAccess()
        val before = seenAccess
        seenAccess = now
        val changed = if (before == null) !now else before != now
        return now.takeIf { changed }
    }

    /**
     * Settings pages that grant All files access, best first. Some devices
     * have no per-app page; the list of all apps is the fallback.
     */
    fun allFilesAccessIntents(context: Context): List<Intent> = listOf(
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:${context.packageName}".toUri(),
        ),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
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

    /** Whether a download or a backend is using the model files right now. */
    fun isBusy(): Boolean {
        val download = ModelDownloadService.downloadState.value
        val backend = BackendService.backendState.value
        return download is ModelDownloadService.DownloadState.Downloading ||
            download is ModelDownloadService.DownloadState.Extracting ||
            backend is BackendService.BackendState.Starting ||
            backend is BackendService.BackendState.Running
    }

    /**
     * Runs [block] unless models are being moved, and returns null if they
     * are. [block] must publish the state [isBusy] looks at, so a move that
     * starts afterwards sees it.
     */
    fun <T> unlessMoving(block: () -> T): T? = synchronized(gate) { if (moving) null else block() }

    /**
     * Moves every model and embedding to [to] in the background, then switches
     * to it. Progress and the outcome are published on [moveState]. Returns
     * false, and does nothing, while a move runs or the models are in use.
     *
     * Resumable: the target is recorded before the first file moves, and the
     * one file that may sit on both sides after an interruption is named in a
     * journal, so running it again finishes the job ([pendingMove]). Moving
     * to the location an unfinished move started from brings the moved files
     * back.
     */
    fun startMove(context: Context, to: Location): Boolean {
        val app = context.applicationContext
        synchronized(gate) {
            if (moving || isBusy()) return false
            moving = true
            moveStarted = true
            _moveState.value = MoveState.Moving(0, 0)
        }
        // A multi-gigabyte copy outlives the screen; without a foreground
        // service the process may be frozen or killed once the app is left.
        runCatching { ContextCompat.startForegroundService(app, Intent(app, ModelMoveService::class.java)) }
            .onFailure { Log.w(TAG, "moving without a foreground service", it) }
        moveScope.launch {
            val result = try {
                move(app, to)
                MoveState.Done
            } catch (e: Exception) {
                Log.e(TAG, "move to $to failed", e)
                MoveState.Failed(e.message ?: e.javaClass.simpleName)
            }
            synchronized(gate) {
                moving = false
                _moveState.value = result
            }
        }
        return true
    }

    /**
     * Finishes a move the app was killed in, once nothing uses the models.
     * Only one try per process: a move that fails again is left to Settings,
     * where it can be retried or turned around.
     */
    fun resumePendingMove(context: Context) {
        if (moveStarted) return
        val to = pendingMove(context) ?: return
        startMove(context, to)
    }

    fun clearMoveState() {
        if (_moveState.value !is MoveState.Moving) _moveState.value = MoveState.Idle
    }

    private fun move(context: Context, to: Location) {
        // Either direction reads or writes Download/LocalDream.
        if (!hasAllFilesAccess()) throw IOException(context.getString(R.string.model_storage_no_access))

        val from = other(to)
        val src = rootFor(context, from)
        val dst = rootFor(context, to)
        val journal = MoveJournal(File(context.noBackupFilesDir, JOURNAL))
        val resuming = pendingMove(context) != null
        checkNames(context, src, dst, to, resuming)
        if (!resuming) journal.clear()
        prefs(context).edit(commit = true) { putString(KEY_MOVING_TO, to.name) }

        dst.mkdirs()
        if (to == Location.DOWNLOADS) {
            // Nothing here is a picture; keep the gallery from scanning gigabytes.
            File(dst, ".nomedia").takeIf { !it.exists() }?.createNewFile()
        }

        // Download scratch is not worth moving.
        File(src, TEMP_DIR).deleteRecursively()

        val total = sizeAt(context, from)
        var done = 0L
        for (sub in MOVED_DIRS) {
            val dir = File(src, sub)
            if (!dir.exists()) continue
            moveTree(dir, File(dst, sub), journal) { n ->
                done += n
                _moveState.value = MoveState.Moving(done, total)
            }
        }

        setLocation(context, to)
        prefs(context).edit(commit = true) { remove(KEY_MOVING_TO) }
        Log.i(TAG, "moved ${done shr 20} MB $src -> $dst")
    }

    // Turns a move down before anything moves, rather than failing halfway.
    private fun checkNames(context: Context, src: File, dst: File, to: Location, resuming: Boolean) {
        val clashes = mutableListOf<String>()
        val taken = mutableListOf<String>()
        for (sub in MOVED_DIRS) {
            val dir = File(src, sub)
            if (!dir.exists()) continue
            val names = dir.list() ?: throw IOException("cannot read ${dir.path}")
            val there = File(dst, sub).list().orEmpty()
            if (to == Location.DOWNLOADS) {
                // Download/ ignores letter case: these would land in one folder.
                names.groupBy { it.lowercase(Locale.ROOT) }.values
                    .filter { it.size > 1 }
                    .forEach { clashes += it.joinToString(" / ") }
                for (name in names) {
                    val twin = there.firstOrNull { it != name && it.equals(name, ignoreCase = true) }
                    if (twin != null) {
                        clashes += "$name / $twin"
                    } else if (sub == MODELS_DIR &&
                        ModelRepository.isReservedModelId(name, ignoreCase = true) &&
                        !ModelRepository.isReservedModelId(name)
                    ) {
                        // A custom model named like a built-in one in another case.
                        clashes += name
                    }
                }
            }
            // An interrupted move leaves a model on both sides by design;
            // moveTree tells its own committed copy apart from anything else.
            if (!resuming) taken += names.filter { File(File(dst, sub), it).exists() }
        }
        if (clashes.isNotEmpty()) {
            throw IOException(context.getString(R.string.model_storage_name_clash, clashes.joinToString()))
        }
        if (taken.isNotEmpty()) {
            val label = when (to) {
                Location.INTERNAL -> context.getString(R.string.model_storage_internal)
                Location.DOWNLOADS -> context.getString(R.string.model_storage_public, PUBLIC_FOLDER)
            }
            throw IOException(context.getString(R.string.model_storage_conflict, label, taken.joinToString()))
        }
    }

    /**
     * Moves [src] to [dst] one file at a time. A file already at the
     * destination stops the move, except the one [journal] names: its copy was
     * committed, and the app died before its source was deleted.
     */
    internal fun moveTree(
        src: File,
        dst: File,
        journal: MoveJournal,
        rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
        onBytes: (Long) -> Unit,
    ) {
        if (src.isDirectory) {
            if (!dst.isDirectory && !dst.mkdirs()) throw IOException("cannot create ${dst.path}")
            val children = src.listFiles() ?: throw IOException("cannot read ${src.path}")
            for (child in children) {
                moveTree(child, File(dst, child.name), journal, rename, onBytes)
            }
            if (!src.delete()) throw IOException("cannot delete ${src.path}")
            return
        }
        if (src.name.endsWith(PARTIAL_SUFFIX)) {
            // A partial copy left by a move the other way.
            src.delete()
            return
        }
        val len = src.length()
        if (dst.exists()) {
            if (!journal.names(src, dst) || dst.length() != len) {
                throw IOException("${dst.path} already exists")
            }
            deleteOrThrow(src)
            journal.clear()
            onBytes(len)
            return
        }
        if (rename(src, dst)) {
            onBytes(len)
            return
        }

        // Internal and shared storage are different mounts, so this is the
        // usual path: copy under a temporary name, then rename, so a
        // half-copied file never appears under its real name.
        val tmp = File(dst.parentFile, dst.name + PARTIAL_SUFFIX)
        // Left by an interrupted copy; it would count against the space below.
        tmp.delete()
        val free = generateSequence(dst) { it.parentFile }.first { it.exists() }.usableSpace
        if (free < len + SPACE_MARGIN) {
            throw IOException("not enough space for ${src.name}: needs ${len shr 20} MB, ${free shr 20} MB free")
        }
        try {
            src.inputStream().use { input ->
                FileOutputStream(tmp).use { output ->
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
                    // On disk before the source, the only other copy, is deleted.
                    output.fd.sync()
                }
            }
            if (tmp.length() != len) throw IOException("could not copy ${src.name}")
            journal.record(src, dst)
            if (!tmp.renameTo(dst)) throw IOException("could not move ${src.name}")
        } finally {
            // Gone after a successful rename; otherwise a partial copy.
            tmp.delete()
        }
        deleteOrThrow(src)
        journal.clear()
    }

    private fun deleteOrThrow(file: File) {
        if (!file.delete()) throw IOException("cannot delete ${file.path}")
    }

    /**
     * Names the one file whose copy is committed at the destination while its
     * source may still exist: the only file found on both sides after an
     * interruption that is safe to treat as moved. Holds both paths, so it
     * also matches when the move is turned around.
     */
    internal class MoveJournal(private val file: File) {
        fun record(a: File, b: File) {
            FileOutputStream(file).use { out ->
                out.write("${a.absolutePath}\n${b.absolutePath}".toByteArray())
                out.fd.sync()
            }
        }

        fun names(a: File, b: File): Boolean = runCatching { file.readLines().toSet() }.getOrNull() ==
            setOf(a.absolutePath, b.absolutePath)

        fun clear() {
            file.delete()
        }
    }
}
