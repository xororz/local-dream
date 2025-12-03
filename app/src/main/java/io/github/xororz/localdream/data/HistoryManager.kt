package io.github.xororz.localdream.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import io.github.xororz.localdream.ui.screens.GenerationParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class HistoryItem(
    val imageFile: File,
    val params: GenerationParameters?,
    val timestamp: Long
)

class HistoryManager(private val context: Context) {

    private fun getHistoryDir(modelId: String): File {
        val dir = File(context.filesDir, "history/$modelId")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    suspend fun saveGeneratedImage(
        modelId: String,
        bitmap: Bitmap,
        params: GenerationParameters
    ): HistoryItem? = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val historyDir = getHistoryDir(modelId)

            val imageFile = File(historyDir, "$timestamp.png")
            FileOutputStream(imageFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val jsonFile = File(historyDir, "$timestamp.json")
            val jsonObject = JSONObject().apply {
                put("steps", params.steps)
                put("cfg", params.cfg)
                put("seed", params.seed)
                put("prompt", params.prompt)
                put("negativePrompt", params.negativePrompt)
                put("generationTime", params.generationTime)
                put("size", "${params.width}x${params.height}")
                put("runOnCpu", params.runOnCpu)
                put("denoiseStrength", params.denoiseStrength)
                put("useOpenCL", params.useOpenCL)
                put("scheduler", params.scheduler)
                put("timestamp", timestamp)
            }
            jsonFile.writeText(jsonObject.toString())

            HistoryItem(
                imageFile = imageFile,
                params = params,
                timestamp = timestamp
            )
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to save image", e)
            null
        }
    }

    suspend fun loadHistoryForModel(modelId: String): List<HistoryItem> =
        withContext(Dispatchers.IO) {
            try {
                val historyDir = getHistoryDir(modelId)
                val imageFiles = historyDir.listFiles { file ->
                    file.extension == "png" || file.extension == "jpg"
                } ?: return@withContext emptyList()

                imageFiles.mapNotNull { imageFile ->
                    val timestamp =
                        imageFile.nameWithoutExtension.toLongOrNull() ?: return@mapNotNull null
                    HistoryItem(
                        imageFile = imageFile,
                        params = null,
                        timestamp = timestamp
                    )
                }.sortedByDescending { it.timestamp }
                    .distinctBy { it.timestamp }
            } catch (e: Exception) {
                Log.e("HistoryManager", "Failed to load history", e)
                emptyList()
            }
        }

    suspend fun loadHistoryItemParams(item: HistoryItem): GenerationParameters? =
        withContext(Dispatchers.IO) {
            try {
                val historyDir = item.imageFile.parentFile ?: return@withContext null
                val timestamp = item.timestamp
                val jsonFile = File(historyDir, "$timestamp.json")

                if (!jsonFile.exists()) {
                    return@withContext null
                }

                val jsonString = jsonFile.readText()
                val json = JSONObject(jsonString)

                val (width, height) = try {
                    when (val sizeValue = json.get("size")) {
                        is String -> {
                            val parts = sizeValue.split("x")
                            if (parts.size == 2) {
                                Pair(parts[0].toInt(), parts[1].toInt())
                            } else {
                                Pair(512, 512)
                            }
                        }

                        is Int -> {
                            Pair(sizeValue, sizeValue)
                        }

                        else -> Pair(512, 512)
                    }
                } catch (_: Exception) {
                    Pair(512, 512)
                }

                GenerationParameters(
                    steps = json.getInt("steps"),
                    cfg = json.getDouble("cfg").toFloat(),
                    seed = if (json.isNull("seed")) null else json.getLong("seed"),
                    prompt = json.getString("prompt"),
                    negativePrompt = json.getString("negativePrompt"),
                    generationTime = json.optString("generationTime", ""),
                    width = width,
                    height = height,
                    runOnCpu = json.getBoolean("runOnCpu"),
                    denoiseStrength = json.optDouble("denoiseStrength", 0.6).toFloat(),
                    useOpenCL = json.optBoolean("useOpenCL", false),
                    scheduler = json.optString("scheduler", "dpm")
                )
            } catch (e: Exception) {
                Log.e("HistoryManager", "Failed to load history item params", e)
                null
            }
        }

    suspend fun deleteHistoryItem(
        modelId: String,
        historyItem: HistoryItem
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val historyDir = getHistoryDir(modelId)
            val timestamp = historyItem.timestamp

            val jpgFile = File(historyDir, "$timestamp.jpg")
            val pngFile = File(historyDir, "$timestamp.png")
            val jsonFile = File(historyDir, "$timestamp.json")

            var success = true
            if (jpgFile.exists()) {
                success = success && jpgFile.delete()
            }
            if (pngFile.exists()) {
                success = success && pngFile.delete()
            }
            if (jsonFile.exists()) {
                success = success && jsonFile.delete()
            }

            success
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to delete history item", e)
            false
        }
    }

    suspend fun clearHistoryForModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val historyDir = getHistoryDir(modelId)
            historyDir.deleteRecursively()
            true
        } catch (e: Exception) {
            Log.e("HistoryManager", "Failed to clear history", e)
            false
        }
    }
}