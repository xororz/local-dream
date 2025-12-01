package io.github.xororz.localdream.data

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xororz.localdream.R
import io.github.xororz.localdream.service.ModelDownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import android.content.Intent
import android.util.Log

data class Resolution(
    val width: Int,
    val height: Int
) {
    val isSquare: Boolean get() = width == height

    override fun toString(): String =
        if (isSquare) "${width}×${width}"
        else "${width}×${height}"
}

object PatchScanner {
    private val squarePatchPattern = Regex("""^(\d+)\.patch$""")
    private val rectangularPatchPattern = Regex("""^(\d+)x(\d+)\.patch$""")

    fun scanAvailableResolutions(context: Context, modelId: String): List<Resolution> {
        val modelDir = File(Model.getModelsDir(context), modelId)
        if (!modelDir.exists() || !modelDir.isDirectory) {
            return emptyList()
        }

        val resolutions = mutableListOf<Resolution>()

        modelDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach

            squarePatchPattern.matchEntire(file.name)?.let { match ->
                val size = match.groupValues[1].toIntOrNull()
                if (size != null && size > 0) {
                    resolutions.add(Resolution(size, size))
                }
            }

            rectangularPatchPattern.matchEntire(file.name)?.let { match ->
                val width = match.groupValues[1].toIntOrNull()
                val height = match.groupValues[2].toIntOrNull()
                if (width != null && height != null && width > 0 && height > 0) {
                    resolutions.add(Resolution(width, height))
                }
            }
        }

        return resolutions.sortedBy { it.width * it.height }.distinct()
    }
}

private fun getDeviceSoc(): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL
    } else {
        "CPU"
    }
}

data class DownloadProgress(
    val progress: Float,
    val downloadedBytes: Long,
    val totalBytes: Long
)

val chipsetModelSuffixes = mapOf(
    "SM8475" to "8gen1",
    "SM8450" to "8gen1",
    "SM8550" to "8gen2",
    "SM8550P" to "8gen2",
    "QCS8550" to "8gen2",
    "QCM8550" to "8gen2",
    "SM8650" to "8gen2",
    "SM8650P" to "8gen2",
    "SM8750" to "8gen2",
    "SM8750P" to "8gen2",
    "SM8850" to "8gen2",
    "SM8850P" to "8gen2",
    "SM8735" to "8gen2",
    "SM8845" to "8gen2",
)

sealed class DownloadResult {
    data object Success : DownloadResult()
    data class Error(val message: String) : DownloadResult()
    data class Progress(val progress: DownloadProgress) : DownloadResult()
}

data class Model(
    val id: String,
    val name: String,
    val description: String,
    val baseUrl: String,
    val fileUri: String = "",
    val generationSize: Int = 512,
    val textEmbeddingSize: Int = 768,
    val approximateSize: String = "1GB",
    val isDownloaded: Boolean = false,
    val needsUpgrade: Boolean = false,
    val defaultPrompt: String = "",
    val defaultNegativePrompt: String = "",
    val runOnCpu: Boolean = false,
    val useCpuClip: Boolean = false,
    val isCustom: Boolean = false

) {

    fun startDownload(context: Context) {
        if (isCustom || fileUri.isEmpty()) return

        val intent = Intent(context, ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_START_DOWNLOAD
            putExtra(ModelDownloadService.EXTRA_MODEL_ID, id)
            putExtra(ModelDownloadService.EXTRA_MODEL_NAME, name)
            putExtra(ModelDownloadService.EXTRA_FILE_URL, "${baseUrl.removeSuffix("/")}/$fileUri")
            putExtra(ModelDownloadService.EXTRA_IS_ZIP, fileUri.endsWith(".zip"))
            putExtra(ModelDownloadService.EXTRA_IS_NPU, !runOnCpu)
            putExtra(ModelDownloadService.EXTRA_MODEL_TYPE, "sd")
        }

        context.startForegroundService(intent)
    }

    fun deleteModel(context: Context): Boolean {
        return try {
            val modelDir = File(getModelsDir(context), id)
            val historyManager = HistoryManager(context)
            val generationPreferences = GenerationPreferences(context)

            runBlocking {
                historyManager.clearHistoryForModel(id)
                generationPreferences.clearPreferencesForModel(id)
            }

            if (modelDir.exists() && modelDir.isDirectory) {
                val deleted = modelDir.deleteRecursively()
                Log.d("Model", "Delete model $id: $deleted")
                deleted
            } else {
                Log.d("Model", "Model does not exist: $id")
                false
            }
        } catch (e: Exception) {
            Log.e("Model", "error: ${e.message}")
            false
        }
    }

    companion object {
        private const val MODELS_DIR = "models"

        fun isDeviceSupported(): Boolean {
            val soc = getDeviceSoc()
            return getChipsetSuffix(soc) != null
        }

        fun isQualcommDevice(): Boolean {
            val soc = getDeviceSoc()
            return soc.startsWith("SM") || soc.startsWith("QCS") || soc.startsWith("QCM")
        }

        fun getChipsetSuffix(soc: String): String? {
            if (soc in chipsetModelSuffixes) {
                return chipsetModelSuffixes[soc]
            }
            if (soc.startsWith("SM")) {
                return "min"
            }
            return null
        }

        fun getModelsDir(context: Context): File {
            return File(context.filesDir, MODELS_DIR).apply {
                if (!exists()) mkdirs()
            }
        }

        fun isModelDownloaded(
            context: Context,
            modelId: String,
            isCustom: Boolean = false
        ): Boolean {
            if (isCustom) {
                return true
            }

            val modelDir = File(getModelsDir(context), modelId)
            if (!modelDir.exists() || !modelDir.isDirectory) {
                return false
            }

            val files = modelDir.listFiles()
            return files != null && files.isNotEmpty()
        }

        fun needsModelUpgrade(
            context: Context,
            modelId: String,
            isNpu: Boolean
        ): Boolean {
            if (!isNpu) return false

            val modelDir = File(getModelsDir(context), modelId)
            if (!modelDir.exists()) return false

            val vFile = File(modelDir, "v3")
            return !vFile.exists()
        }
    }
}

data class UpscalerModel(
    val id: String,
    val name: String,
    val description: String,
    val baseUrl: String,
    val fileUri: String,
    val isDownloaded: Boolean = false
) {
    fun startDownload(context: Context) {
        val intent = Intent(context, ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_START_DOWNLOAD
            putExtra(ModelDownloadService.EXTRA_MODEL_ID, id)
            putExtra(ModelDownloadService.EXTRA_MODEL_NAME, name)
            putExtra(ModelDownloadService.EXTRA_FILE_URL, "${baseUrl.removeSuffix("/")}/$fileUri")
            putExtra(ModelDownloadService.EXTRA_IS_ZIP, false)
            putExtra(ModelDownloadService.EXTRA_IS_NPU, false)
            putExtra(ModelDownloadService.EXTRA_MODEL_TYPE, "upscaler")
        }

        context.startForegroundService(intent)
    }
}

class UpscalerRepository(private val context: Context) {
    private val generationPreferences = GenerationPreferences(context)

    private var _baseUrl = mutableStateOf("https://huggingface.co/")
    var baseUrl: String
        get() = _baseUrl.value
        private set(value) {
            _baseUrl.value = value
        }

    var upscalers by mutableStateOf(initializeUpscalers())
        private set

    init {
        CoroutineScope(Dispatchers.Main).launch {
            baseUrl = generationPreferences.getBaseUrl()
            upscalers = initializeUpscalers()
        }
    }

    private fun initializeUpscalers(): List<UpscalerModel> {
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"

        return listOf(
            createAnimeUpscaler(suffix),
            createRealisticUpscaler(suffix)
        )
    }

    private fun createAnimeUpscaler(suffix: String): UpscalerModel {
        val id = "upscaler_anime"
        val fileUri =
            "xororz/upscaler/resolve/main/realesrgan_x4plus_anime_6b/upscaler_${suffix}.bin"

        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return UpscalerModel(
            id = id,
            name = context.getString(R.string.upscaler_anime),
            description = context.getString(R.string.upscaler_anime_desc),
            baseUrl = baseUrl,
            fileUri = fileUri,
            isDownloaded = isDownloaded
        )
    }

    private fun createRealisticUpscaler(suffix: String): UpscalerModel {
        val id = "upscaler_realistic"
        val fileUri = "xororz/upscaler/resolve/main/4x_UltraSharpV2_Lite/upscaler_${suffix}.bin"

        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return UpscalerModel(
            id = id,
            name = context.getString(R.string.upscaler_realistic),
            description = context.getString(R.string.upscaler_realistic_desc),
            baseUrl = baseUrl,
            fileUri = fileUri,
            isDownloaded = isDownloaded
        )
    }

    fun refreshUpscalerState(upscalerId: String) {
        upscalers = upscalers.map { upscaler ->
            if (upscaler.id == upscalerId) {
                val isDownloaded = Model.isModelDownloaded(context, upscaler.id, false)
                upscaler.copy(isDownloaded = isDownloaded)
            } else {
                upscaler
            }
        }
    }
}

class ModelRepository(private val context: Context) {
    private val generationPreferences = GenerationPreferences(context)

    private var _baseUrl = mutableStateOf("https://huggingface.co/")
    var baseUrl: String
        get() = _baseUrl.value
        private set(value) {
            _baseUrl.value = value
        }

    var models by mutableStateOf(initializeModels())
        private set

    init {
        CoroutineScope(Dispatchers.Main).launch {
            baseUrl = generationPreferences.getBaseUrl()
            models = initializeModels()
        }
    }

    private fun scanCustomModels(): List<Model> {
        val modelsDir = Model.getModelsDir(context)
        val customModels = mutableListOf<Model>()

        if (modelsDir.exists() && modelsDir.isDirectory) {
            modelsDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory) {
                    val finishedFile = File(dir, "finished")
                    val npuCustomFile = File(dir, "npucustom")

                    if (finishedFile.exists()) {
                        val customModel = createCustomModel(dir, isNpu = false)
                        customModels.add(customModel)
                    } else if (npuCustomFile.exists()) {
                        val customModel = createCustomModel(dir, isNpu = true)
                        customModels.add(customModel)
                    }
                }
            }
        }

        return customModels.sortedBy { it.name.lowercase() }
    }

    private fun createCustomModel(modelDir: File, isNpu: Boolean = false): Model {
        val modelId = modelDir.name

        return Model(
            id = modelId,
            name = modelId,
            description = context.getString(R.string.custom_model),
            baseUrl = "",
            approximateSize = "Custom",
            isDownloaded = true,
            defaultPrompt = "masterpiece, best quality, a cat sat on a mat,",
            defaultNegativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            runOnCpu = !isNpu,
            useCpuClip = true,
            isCustom = true
        )
    }

    private fun initializeModels(): List<Model> {
        val customModels = scanCustomModels()

        val predefinedModels = mutableListOf(
            createAnythingV5Model(),
            createAnythingV5ModelCPU(),
            createQteaMixModel(),
            createQteaMixModelCPU(),
            createAbsoluteRealityModel(),
            createAbsoluteRealityModelCPU(),
            createCuteYukiMixModel(),
            createCuteYukiMixModelCPU(),
            createChilloutMixModelCPU(),
            createChilloutMixModel(),
        )

        return customModels + predefinedModels
    }

    private fun createAnythingV5Model(): Model {
        val id = "anythingv5"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val fileUri = "xororz/sd-qnn/resolve/main/AnythingV5_qnn2.28_${suffix}.zip"

        val isDownloaded = Model.isModelDownloaded(context, id, false)
        val needsUpgrade = Model.needsModelUpgrade(context, id, true)

        return Model(
            id = id,
            name = "Anything V5.0",
            description = context.getString(R.string.anythingv5_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.1GB",
            isDownloaded = isDownloaded,
            needsUpgrade = needsUpgrade,
            defaultPrompt = "masterpiece, best quality, 1girl, solo, cute, white hair,",
            defaultNegativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            runOnCpu = false,
            useCpuClip = true
        )
    }

    private fun createAnythingV5ModelCPU(): Model {
        val id = "anythingv5cpu"
        val fileUri = "xororz/sd-mnn/resolve/main/AnythingV5.zip"

        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return Model(
            id = id,
            name = "Anything V5.0",
            description = context.getString(R.string.anythingv5_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.2GB",
            isDownloaded = isDownloaded,
            defaultPrompt = "masterpiece, best quality, 1girl, solo, cute, white hair,",
            defaultNegativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            runOnCpu = true
        )
    }

    private fun createQteaMixModel(): Model {
        val id = "qteamix"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val fileUri = "xororz/sd-qnn/resolve/main/QteaMix_qnn2.28_${suffix}.zip"
        val isDownloaded = Model.isModelDownloaded(context, id, false)
        val needsUpgrade = Model.needsModelUpgrade(context, id, true)

        return Model(
            id = id,
            name = "QteaMix",
            description = context.getString(R.string.qteamix_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.1GB",
            isDownloaded = isDownloaded,
            needsUpgrade = needsUpgrade,
            defaultPrompt = "chibi, best quality, 1girl, solo, cute, pink hair,",
            defaultNegativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            useCpuClip = true
        )
    }

    private fun createQteaMixModelCPU(): Model {
        val id = "qteamixcpu"
        val fileUri = "xororz/sd-mnn/resolve/main/QteaMix.zip"
        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return Model(
            id = id,
            name = "QteaMix",
            description = context.getString(R.string.qteamix_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.2GB",
            isDownloaded = isDownloaded,
            defaultPrompt = "chibi, best quality, 1girl, solo, cute, pink hair,",
            defaultNegativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            runOnCpu = true
        )
    }

    private fun createCuteYukiMixModel(): Model {
        val id = "cuteyukimix"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val fileUri = "xororz/sd-qnn/resolve/main/CuteYukiMix_qnn2.28_${suffix}.zip"
        val isDownloaded = Model.isModelDownloaded(context, id, false)
        val needsUpgrade = Model.needsModelUpgrade(context, id, true)

        return Model(
            id = id,
            name = "CuteYukiMix",
            description = context.getString(R.string.cuteyukimix_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.1GB",
            isDownloaded = isDownloaded,
            needsUpgrade = needsUpgrade,
            defaultPrompt = "masterpiece, best quality, 1girl, solo, cute, white hair,",
            defaultNegativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            useCpuClip = true
        )
    }

    private fun createCuteYukiMixModelCPU(): Model {
        val id = "cuteyukimixcpu"
        val fileUri = "xororz/sd-mnn/resolve/main/CuteYukiMix.zip"
        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return Model(
            id = id,
            name = "CuteYukiMix",
            description = context.getString(R.string.cuteyukimix_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.2GB",
            isDownloaded = isDownloaded,
            defaultPrompt = "masterpiece, best quality, 1girl, solo, cute, white hair,",
            defaultNegativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            runOnCpu = true
        )
    }

    private fun createAbsoluteRealityModel(): Model {
        val id = "absolutereality"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val fileUri = "xororz/sd-qnn/resolve/main/AbsoluteReality_qnn2.28_${suffix}.zip"
        val isDownloaded = Model.isModelDownloaded(context, id, false)
        val needsUpgrade = Model.needsModelUpgrade(context, id, true)

        return Model(
            id = id,
            name = "Absolute Reality",
            description = context.getString(R.string.absolutereality_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.1GB",
            isDownloaded = isDownloaded,
            needsUpgrade = needsUpgrade,
            defaultPrompt = "masterpiece, best quality, ultra-detailed, realistic, 8k, a cat on grass,",
            defaultNegativePrompt = "worst quality, low quality, normal quality, poorly drawn, lowres, low resolution, signature, watermarks, ugly, out of focus, error, blurry, unclear photo, bad photo, unrealistic, semi realistic, pixelated, cartoon, anime, cgi, drawing, 2d, 3d, censored, duplicate,",
            runOnCpu = false,
            useCpuClip = true
        )
    }

    private fun createAbsoluteRealityModelCPU(): Model {
        val id = "absoluterealitycpu"
        val fileUri = "xororz/sd-mnn/resolve/main/AbsoluteReality.zip"
        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return Model(
            id = id,
            name = "Absolute Reality",
            description = context.getString(R.string.absolutereality_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.2GB",
            isDownloaded = isDownloaded,
            defaultPrompt = "masterpiece, best quality, ultra-detailed, realistic, 8k, a cat on grass,",
            defaultNegativePrompt = "worst quality, low quality, normal quality, poorly drawn, lowres, low resolution, signature, watermarks, ugly, out of focus, error, blurry, unclear photo, bad photo, unrealistic, semi realistic, pixelated, cartoon, anime, cgi, drawing, 2d, 3d, censored, duplicate,",
            runOnCpu = true
        )
    }

    private fun createChilloutMixModel(): Model {
        val id = "chilloutmix"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val fileUri = "xororz/sd-qnn/resolve/main/ChilloutMix_qnn2.28_${suffix}.zip"
        val isDownloaded = Model.isModelDownloaded(context, id, false)
        val needsUpgrade = Model.needsModelUpgrade(context, id, true)

        return Model(
            id = id,
            name = "ChilloutMix",
            description = context.getString(R.string.chilloutmix_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.1GB",
            isDownloaded = isDownloaded,
            needsUpgrade = needsUpgrade,
            defaultPrompt = "RAW photo, best quality, realistic, photo-realistic, masterpiece, 1girl, upper body, facing front, portrait, white shirt",
            defaultNegativePrompt = "paintings, cartoon, anime, lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, cropped, worst quality, low quality, normal quality, jpeg artifacts, signature, watermark, username, skin spots, acnes, skin blemishes",
            runOnCpu = false,
            useCpuClip = true
        )
    }

    private fun createChilloutMixModelCPU(): Model {
        val id = "chilloutmixcpu"
        val fileUri = "xororz/sd-mnn/resolve/main/ChilloutMix.zip"
        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return Model(
            id = id,
            name = "ChilloutMix",
            description = context.getString(R.string.chilloutmix_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.2GB",
            isDownloaded = isDownloaded,
            defaultPrompt = "RAW photo, best quality, realistic, photo-realistic, masterpiece, 1girl, upper body, facing front, portrait, white shirt",
            defaultNegativePrompt = "paintings, cartoon, anime, lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, cropped, worst quality, low quality, normal quality, jpeg artifacts, signature, watermark, username, skin spots, acnes, skin blemishes",
            runOnCpu = true
        )
    }

    fun refreshModelState(modelId: String) {
        models = models.map { model ->
            if (model.id == modelId) {
                val isDownloaded = Model.isModelDownloaded(context, modelId, model.isCustom)
                val needsUpgrade = if (!model.runOnCpu) {
                    Model.needsModelUpgrade(context, modelId, true)
                } else {
                    false
                }
                model.copy(
                    isDownloaded = isDownloaded,
                    needsUpgrade = needsUpgrade
                )
            } else {
                model
            }
        }
    }

    fun refreshAllModels() {
        models = initializeModels()
    }
}