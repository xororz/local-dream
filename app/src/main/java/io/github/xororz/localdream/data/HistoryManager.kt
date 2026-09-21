package io.github.xororz.localdream.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import io.github.xororz.localdream.data.db.AppDatabase
import io.github.xororz.localdream.data.db.HistoryEntity
import io.github.xororz.localdream.ui.screens.GenerationParameters
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Immutable
data class HistoryItem(
    val id: Long,
    val modelId: String,
    val imageFile: File,
    val params: GenerationParameters,
    val timestamp: Long,
    val mode: GenerationMode,
    val upscalerId: String?,
    val favorite: Boolean = false,
) {
    companion object {
        fun fromEntity(filesDir: File, e: HistoryEntity): HistoryItem {
            val imageFile = File(filesDir, e.imagePath)
            val mode = GenerationMode.fromString(e.mode)
            return HistoryItem(
                id = e.id,
                modelId = e.modelId,
                imageFile = imageFile,
                timestamp = e.timestamp,
                mode = mode,
                upscalerId = e.upscalerId,
                favorite = e.favorite,
                params = GenerationParameters(
                    steps = e.steps,
                    cfg = e.cfg,
                    seed = e.seed,
                    prompt = e.prompt,
                    negativePrompt = e.negativePrompt,
                    generationTime = e.generationTime,
                    width = e.width,
                    height = e.height,
                    runOnCpu = e.runOnCpu,
                    denoiseStrength = e.denoiseStrength ?: 0.6f,
                    useOpenCL = e.useOpenCL,
                    scheduler = e.scheduler,
                    mode = mode,
                ),
            )
        }
    }
}

// Keep id batches under SQLite's host-parameter limit (999 on older API levels).
private const val SQLITE_IN_CHUNK = 900

class HistoryManager(private val context: Context) {

    private val db = AppDatabase.get(context)
    private val dao = db.historyDao()
    private val filesDir: File = context.filesDir

    private fun getHistoryDir(modelId: String): File {
        val dir = File(filesDir, "history/$modelId")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    suspend fun saveGeneratedImage(
        modelId: String,
        bitmap: Bitmap,
        params: GenerationParameters,
        mode: GenerationMode,
        upscalerId: String? = null,
    ): HistoryItem? = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val historyDir = getHistoryDir(modelId)

            // Upscaled and ultrafixed opaque images are 4x-class resolutions;
            // store them as JPEG. Transparent Qwen results must remain PNG.
            val isUpscaled = upscalerId != null || mode == GenerationMode.ULTRAFIX
            val useJpeg = isUpscaled && !bitmap.hasAlpha()
            val ext = if (useJpeg) "jpg" else "png"
            val imageFile = File(historyDir, "$timestamp.$ext")
            FileOutputStream(imageFile).use { out ->
                if (useJpeg) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }

            val relativePath = "history/$modelId/$timestamp.$ext"
            val entity = HistoryEntity(
                modelId = modelId,
                timestamp = timestamp,
                imagePath = relativePath,
                width = params.width,
                height = params.height,
                mode = mode.name,
                denoiseStrength = if (mode == GenerationMode.IMG2IMG ||
                    mode == GenerationMode.INPAINT ||
                    mode == GenerationMode.ULTRAFIX
                ) {
                    params.denoiseStrength
                } else {
                    null
                },
                upscalerId = upscalerId,
                steps = params.steps,
                cfg = params.cfg,
                seed = params.seed,
                prompt = params.prompt,
                negativePrompt = params.negativePrompt,
                generationTime = params.generationTime,
                scheduler = params.scheduler,
                runOnCpu = params.runOnCpu,
                useOpenCL = params.useOpenCL,
            )
            val id = dao.insert(entity)
            HistoryItem.fromEntity(filesDir, entity.copy(id = id))
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to save image", e)
            null
        }
    }

    suspend fun setFavorite(id: Long, favorite: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            dao.setFavorite(id, favorite) > 0
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to update favorite", e)
            false
        }
    }

    suspend fun loadHistoryForModel(modelId: String): List<HistoryItem> = withContext(Dispatchers.IO) {
        try {
            val filter = HistoryFilter(modelIds = setOf(modelId))
            dao.queryOnce(filter.toSqlQuery())
                .map { HistoryItem.fromEntity(filesDir, it) }
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to load history", e)
            emptyList()
        }
    }

    fun observe(filter: HistoryFilter): Flow<List<HistoryItem>> = dao.query(filter.toSqlQuery()).map { entities ->
        entities.map { HistoryItem.fromEntity(filesDir, it) }
    }

    // Paged grid feed. pageSize 60 keeps roughly three screens of 2-column
    // thumbnails resident; placeholders are off so the grid never renders empty
    // slots (the list simply grows as pages load).
    fun pager(filter: HistoryFilter): Flow<PagingData<HistoryItem>> = Pager(
        config = PagingConfig(pageSize = 60, enablePlaceholders = false),
        pagingSourceFactory = { dao.queryPaged(filter.toSqlQuery()) },
    ).flow.map { data -> data.map { HistoryItem.fromEntity(filesDir, it) } }

    fun observeCount(filter: HistoryFilter): Flow<Int> = dao.queryCount(filter.toCountQuery())

    // Newest matches first, capped. Backs the result-page thumbnail strip.
    fun observeRecent(filter: HistoryFilter, limit: Int): Flow<List<HistoryItem>> = dao.query(filter.toRecentQuery(limit)).map { entities ->
        entities.map { HistoryItem.fromEntity(filesDir, it) }
    }

    fun observeFavorite(id: Long): Flow<Boolean?> = dao.observeFavorite(id)

    // Every id matching the filter, in display order. Used by select-all.
    suspend fun queryIds(filter: HistoryFilter): List<Long> = withContext(Dispatchers.IO) {
        try {
            dao.queryIds(filter.toIdQuery())
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to query ids", e)
            emptyList()
        }
    }

    // Resolves a selection (ids) back to items for batch save/delete. Returned
    // in the requested id order so callers can rely on it.
    suspend fun getItems(ids: Collection<Long>): List<HistoryItem> = withContext(Dispatchers.IO) {
        try {
            // Chunk the IN clause so large select-all sets stay under SQLite's
            // host-parameter limit (999 on older API levels).
            val byId = ids.toList()
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { dao.getByIds(it) }
                .associateBy { it.id }
            ids.mapNotNull { byId[it]?.let { e -> HistoryItem.fromEntity(filesDir, e) } }
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to load items", e)
            emptyList()
        }
    }

    fun observeKnownModelIds(): Flow<List<String>> = dao.observeKnownModelIds()
    fun observeKnownSchedulers(): Flow<List<String>> = dao.observeKnownSchedulers()
    fun observeKnownSizes(): Flow<List<String>> = dao.observeKnownSizes()

    suspend fun deleteHistoryItem(item: HistoryItem): Boolean = withContext(Dispatchers.IO) {
        try {
            dao.deleteById(item.id)
            if (item.imageFile.exists()) item.imageFile.delete()
            true
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to delete history item", e)
            false
        }
    }

    // Batch delete. All DB rows are removed inside a single transaction so the
    // paging source is invalidated exactly once, on commit - deleting row by row
    // races the Paging refresh and leaves just-deleted thumbnails rendered until
    // some later, unrelated change refreshes the grid. Image files are removed
    // best-effort afterwards; a file that won't delete doesn't fail the row.
    // Returns the number of rows successfully deleted.
    suspend fun deleteHistoryItems(items: List<HistoryItem>): Int = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext 0
        try {
            db.withTransaction {
                items.map { it.id }
                    .chunked(SQLITE_IN_CHUNK)
                    .forEach { dao.deleteByIds(it) }
            }
            items.forEach { item ->
                if (item.imageFile.exists()) item.imageFile.delete()
            }
            items.size
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to delete history items", e)
            0
        }
    }

    // Move a model's history (image files + DB rows) to a new id. The DB path
    // rewrite mirrors the directory move so saved thumbnails keep resolving.
    suspend fun renameModel(oldId: String, newId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val oldDir = File(filesDir, "history/$oldId")
            if (oldDir.exists()) {
                val newDir = File(filesDir, "history/$newId")
                newDir.parentFile?.mkdirs()
                if (newDir.exists()) {
                    oldDir.listFiles()?.forEach { file ->
                        file.renameTo(File(newDir, file.name))
                    }
                    oldDir.delete()
                } else {
                    oldDir.renameTo(newDir)
                }
            }
            dao.renameModelId(oldId, newId)
            true
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to rename model history", e)
            false
        }
    }

    suspend fun clearHistoryForModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            dao.deleteAllForModel(modelId)
            File(filesDir, "history/$modelId").deleteRecursively()
            true
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to clear history", e)
            false
        }
    }
}
