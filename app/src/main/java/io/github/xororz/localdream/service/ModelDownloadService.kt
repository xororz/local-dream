package io.github.xororz.localdream.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.utils.Http
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

class ModelDownloadService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var downloadJob: Job? = null

    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private val client = Http.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "ModelDownloadService"
        private const val NOTIFICATION_CHANNEL_ID = "model_download_channel"
        private const val NOTIFICATION_ID = 2001

        private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val downloadState: StateFlow<DownloadState> = _downloadState

        const val ACTION_START_DOWNLOAD = "action_start_download"
        const val ACTION_CANCEL_DOWNLOAD = "action_cancel_download"

        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_MODEL_NAME = "model_name"
        const val EXTRA_FILE_URL = "file_url"
        const val EXTRA_IS_ZIP = "is_zip"
        const val EXTRA_MODEL_TYPE = "model_type"
        const val TYPE_SD = "sd"
        const val TYPE_UPSCALER = "upscaler"

        // Package downloaded as individual files instead of one zip. A DiT
        // package is 7-9GB, and unzipping one needs the archive and its
        // contents on disk at the same time; fetching the files straight into
        // the model directory halves the space a download needs and lets an
        // interrupted one resume at file granularity.
        const val TYPE_MULTI_FILE = "multi_file"

        // TYPE_MULTI_FILE only: file names under EXTRA_FILE_URL, and an empty
        // marker file to create once they all arrived.
        const val EXTRA_FILE_NAMES = "file_names"
        const val EXTRA_MARKER_FILE = "marker_file"
    }

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(
            val modelId: String,
            val progress: Float,
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : DownloadState()

        data class Extracting(val modelId: String) : DownloadState()
        data class Success(val modelId: String) : DownloadState()
        data class Error(val modelId: String, val message: String) : DownloadState()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: return START_NOT_STICKY
                val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: modelId
                val fileUrl = intent.getStringExtra(EXTRA_FILE_URL) ?: return START_NOT_STICKY
                val isZip = intent.getBooleanExtra(EXTRA_IS_ZIP, false)
                val modelType = intent.getStringExtra(EXTRA_MODEL_TYPE) ?: TYPE_SD
                val fileNames = intent.getStringArrayListExtra(EXTRA_FILE_NAMES)
                val markerFile = intent.getStringExtra(EXTRA_MARKER_FILE)

                startForeground(NOTIFICATION_ID, createNotification(modelName, 0f))
                startDownload(
                    modelId = modelId,
                    modelName = modelName,
                    fileUrl = fileUrl,
                    isZip = isZip,
                    modelType = modelType,
                    fileNames = fileNames.orEmpty(),
                    markerFile = markerFile,
                )
            }

            ACTION_CANCEL_DOWNLOAD -> {
                cancelDownload()
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(
        modelId: String,
        modelName: String,
        fileUrl: String,
        isZip: Boolean,
        modelType: String,
        fileNames: List<String> = emptyList(),
        markerFile: String? = null,
    ) {
        downloadJob?.cancel()
        downloadJob = serviceScope.launch {
            var tempFile: File? = null
            var extractTempDir: File? = null
            try {
                _downloadState.value = DownloadState.Downloading(modelId, 0f, 0, 0)

                val tempDir = File(filesDir, "temp_downloads")

                if (tempDir.exists()) {
                    tempDir.deleteRecursively()
                }
                tempDir.mkdirs()

                if (modelType == TYPE_MULTI_FILE) {
                    downloadPackageFiles(modelId, modelName, fileUrl, fileNames, markerFile)
                    _downloadState.value = DownloadState.Success(modelId)
                    updateNotification(modelName, 100f, true)
                    withContext(Dispatchers.Main) {
                        kotlinx.coroutines.delay(2000)
                        _downloadState.value = DownloadState.Idle
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    return@launch
                }

                tempFile = File(tempDir, "${modelId}_${System.currentTimeMillis()}.tmp")

                downloadFile(fileUrl, tempFile, modelId, modelName)

                when (modelType) {
                    TYPE_SD -> {
                        if (isZip) {
                            val modelDir = File(getModelsDir(), modelId)

                            if (modelDir.exists()) {
                                modelDir.deleteRecursively()
                            }
                            modelDir.mkdirs()

                            extractTempDir = File(tempDir, "${modelId}_extract")
                            extractTempDir.mkdirs()

                            _downloadState.value = DownloadState.Extracting(modelId)
                            updateNotification(modelName, 0f, isExtracting = true)

                            unzipFile(tempFile, extractTempDir)

                            extractTempDir.listFiles()?.forEach { file ->
                                file.renameTo(File(modelDir, file.name))
                            }
                            extractTempDir.delete()
                            extractTempDir = null
                        }
                    }

                    TYPE_UPSCALER -> {
                        val upscalerDir = File(getModelsDir(), modelId).apply {
                            if (!exists()) mkdirs()
                        }
                        val targetFile = File(upscalerDir, Model.UPSCALER_FILE_NAME)

                        if (targetFile.exists()) {
                            targetFile.delete()
                        }

                        // Don't report success on a failed move: it would leave
                        // an empty model dir that the UI/loader can't use.
                        if (!tempFile.renameTo(targetFile)) {
                            tempFile.copyTo(targetFile, overwrite = true)
                        }
                    }
                }

                tempFile.delete()
                tempFile = null

                _downloadState.value = DownloadState.Success(modelId)
                updateNotification(modelName, 100f, true)

                withContext(Dispatchers.Main) {
                    kotlinx.coroutines.delay(2000)
                    _downloadState.value = DownloadState.Idle
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } catch (e: CancellationException) {
                discardPartialFiles(modelType, modelId)
                // Cancellation (service reclaimed, a new download started, or
                // explicit cancel) is not a download failure: re-throw so it is
                // not surfaced as an "Error" state. Emitting Error here is what
                // produced the spurious "Job was cancelled" snackbar that could
                // appear right after a successful download finished.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)

                tempFile?.delete()
                extractTempDir?.deleteRecursively()
                discardPartialFiles(modelType, modelId)

                _downloadState.value =
                    DownloadState.Error(modelId, e.message ?: getString(R.string.unknown_error))
                updateNotification(modelName, 0f, false, e.message)

                withContext(Dispatchers.Main) {
                    kotlinx.coroutines.delay(3000)
                    _downloadState.value = DownloadState.Idle
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    /**
     * Fetches each file of a package straight into the model directory.
     *
     * A file that is already there at its published size is kept, so a
     * download interrupted after 6 of 8GB resumes on the next attempt instead
     * of starting over. Progress is reported across the whole package, using
     * the sizes a HEAD request reports up front.
     */
    private suspend fun downloadPackageFiles(
        modelId: String,
        modelName: String,
        baseUrl: String,
        fileNames: List<String>,
        markerFile: String?,
    ) = withContext(Dispatchers.IO) {
        require(fileNames.isNotEmpty()) { "empty package file list" }
        val modelDir = File(getModelsDir(), modelId).apply { mkdirs() }
        val base = baseUrl.removeSuffix("/")

        // Entries are "<remote path>|<name on disk>": the parts of a package
        // can come from different repositories, and the names they are
        // published under are not the ones the backend looks for.
        val parts = fileNames.map { entry ->
            val remote = entry.substringBefore('|')
            val local = entry.substringAfter('|', remote.substringAfterLast('/'))
            remote to local
        }

        val sizes = parts.associate { (remote, _) -> remote to remoteSize("$base/$remote") }
        val totalBytes = sizes.values.sumOf { it.coerceAtLeast(0L) }
        var completedBytes = 0L

        for ((remote, local) in parts) {
            val dest = File(modelDir, local)
            val expected = sizes[remote] ?: -1L
            if (dest.exists() && expected > 0 && dest.length() == expected) {
                completedBytes += expected
                Log.i(TAG, "Package file already complete: $local")
                continue
            }
            val part = File(modelDir, "$local.part")
            downloadFile(
                url = "$base/$remote",
                destFile = part,
                modelId = modelId,
                modelName = modelName,
                packageOffset = completedBytes,
                packageTotal = totalBytes,
            )
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw IOException("Failed to install $local")
            completedBytes += if (expected > 0) expected else dest.length()
        }

        // Written last: it is what marks the package complete to the scanner,
        // so an interrupted download never looks like an installed model.
        if (!markerFile.isNullOrEmpty()) File(modelDir, markerFile).createNewFile()
    }

    /**
     * Drops the ".part" files a multi-file download leaves behind.
     *
     * Each part is written from the start rather than resumed, so a leftover
     * one is dead weight - and at DiT package sizes that is gigabytes the user
     * cannot see. Files that already finished keep their final name and stay,
     * which is what lets the next attempt skip them.
     */
    private fun discardPartialFiles(modelType: String, modelId: String) {
        if (modelType != TYPE_MULTI_FILE) return
        val modelDir = File(getModelsDir(), modelId)
        modelDir.listFiles { file -> file.isFile && file.name.endsWith(".part") }
            ?.forEach { part ->
                if (part.delete()) Log.i(TAG, "Removed partial file ${part.name}")
            }
    }

    /** Published size of a remote file, or -1 when the server does not say. */
    private fun remoteSize(url: String): Long {
        val request = Request.Builder().url(url).head().build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.header("Content-Length")?.toLongOrNull() ?: -1L
                } else {
                    -1L
                }
            }
        }.getOrDefault(-1L)
    }

    private suspend fun downloadFile(
        url: String,
        destFile: File,
        modelId: String,
        modelName: String,
        packageOffset: Long = 0L,
        packageTotal: Long = 0L,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception(getString(R.string.error_download_failed, response.code.toString()))
            }

            val body = response.body ?: throw Exception("Response body is null")
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L
            var lastUpdateTime = 0L

            java.io.BufferedOutputStream(FileOutputStream(destFile)).use { output ->
                body.byteStream().buffered().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var bytes: Int

                    while (input.read(buffer).also { bytes = it } != -1) {
                        output.write(buffer, 0, bytes)
                        downloadedBytes += bytes

                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpdateTime >= 500 || downloadedBytes == totalBytes) {
                            lastUpdateTime = currentTime
                            val reportedDone = packageOffset + downloadedBytes
                            val reportedTotal = if (packageTotal > 0) packageTotal else totalBytes
                            val progress = if (reportedTotal > 0) {
                                reportedDone.toFloat() / reportedTotal
                            } else {
                                0f
                            }

                            _downloadState.value = DownloadState.Downloading(
                                modelId,
                                progress,
                                reportedDone,
                                reportedTotal,
                            )

                            updateNotification(modelName, progress)
                        }
                    }
                }
            }

            // Guard against silently truncated downloads: a dropped connection
            // ends the read loop without throwing, leaving a partial file.
            if (totalBytes > 0 && downloadedBytes != totalBytes) {
                throw Exception(
                    getString(R.string.error_download_failed, "$downloadedBytes/$totalBytes"),
                )
            }
        }
    }

    private suspend fun unzipFile(zipFile: File, destDir: File) = withContext(Dispatchers.IO) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry

            while (entry != null) {
                if (!entry.isDirectory) {
                    val fileName = entry.name.substringAfterLast('/')
                    if (fileName.isNotEmpty() && !fileName.startsWith(".") && !fileName.startsWith("__MACOSX")) {
                        val file = File(destDir, fileName)

                        java.io.BufferedOutputStream(FileOutputStream(file)).use { output ->
                            zis.copyTo(output)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun cancelDownload() {
        downloadJob?.cancel()
        _downloadState.value = DownloadState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun getModelsDir(): File = File(filesDir, "models").apply {
        if (!exists()) mkdirs()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.model_download_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.model_download_channel_desc)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(
        modelName: String,
        progress: Float,
        isExtracting: Boolean = false,
    ): android.app.Notification {
        val title = if (isExtracting) {
            getString(R.string.extracting)
        } else {
            getString(R.string.downloading_model, modelName)
        }

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val appPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, (progress * 100).toInt(), isExtracting)
            .setOngoing(true)
            .setContentIntent(appPendingIntent)
            .build()
    }

    private fun updateNotification(
        modelName: String,
        progress: Float,
        success: Boolean = false,
        error: String? = null,
        isExtracting: Boolean = false,
    ) {
        val notification = when {
            success -> {
                NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(getString(R.string.download_complete))
                    .setContentText(modelName)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setOngoing(false)
                    .build()
            }

            error != null -> {
                NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(getString(R.string.download_failed))
                    .setContentText(error)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setOngoing(false)
                    .build()
            }

            else -> {
                createNotification(modelName, progress, isExtracting)
            }
        }

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        handleTimeout(0)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        handleTimeout(fgsType)
    }

    private fun handleTimeout(fgsType: Int) {
        Log.e(TAG, "Foreground service timeout (fgsType=$fgsType)")
        downloadJob?.cancel()
        _downloadState.value = DownloadState.Error("timeout", "Foreground service timeout")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
