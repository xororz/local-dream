package io.github.xororz.localdream.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xororz.localdream.R
import io.github.xororz.localdream.service.ModelDownloadService
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Immutable
data class Resolution(val width: Int, val height: Int) {
    val isSquare: Boolean get() = width == height

    override fun toString(): String = if (isSquare) {
        "$width×$width"
    } else {
        "$width×$height"
    }
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

        return resolutions.distinct().sortedBy { it.width * it.height }
    }
}

private fun getDeviceSoc(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    Build.SOC_MODEL
} else {
    "CPU"
}

@Immutable
data class DownloadProgress(val progress: Float, val downloadedBytes: Long, val totalBytes: Long)

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

sealed class RenameResult {
    data object Success : RenameResult()

    // Caller decides messaging; BlankName/Reserved/Exists are recoverable input
    // errors, Io means the on-disk move failed.
    enum class Reason { BlankName, Reserved, Exists, Io }
    data class Error(val reason: Reason) : RenameResult()
}

@Immutable
data class Model(
    val id: String,
    val name: String,
    val description: String,
    val baseUrl: String,
    val fileUri: String = "",
    val generationSize: Int = 512,
    val approximateSize: String = "1GB",
    val isDownloaded: Boolean = false,
    val needsUpgrade: Boolean = false,
    // Defaults written in code for this model; only the fields it cares about.
    val codeDefaults: ModelConfig = ModelConfig(),
    // Defaults read from config.json in the model directory, if present.
    val configDefaults: ModelConfig = ModelConfig(),
    val runOnCpu: Boolean = false,
    val isCustom: Boolean = false,
    val isSdxl: Boolean = false,
    val isAnima: Boolean = false,

) {
    // Per-field priority: code defaults > config.json > global defaults.
    val defaults: GenerationDefaults
        get() = codeDefaults.withFallback(configDefaults).resolve()

    // SDXL and Anima both render on a fixed 1024 canvas and reach non-1:1
    // outputs via aspect-ratio inpaint padding; the run screen treats them
    // alike for default size and aspect-ratio handling (ultrafix stays
    // SDXL-only). SD1.5 NPU/CPU use their own sizes / resolution patches.
    val usesFixedCanvas: Boolean
        get() = isSdxl || isAnima

    // Backend --type value; each type implies the full model file layout.
    val backendType: String
        get() = when {
            isAnima -> "anima"
            isSdxl -> if (runOnCpu) "sdxlmnn" else "sdxl"
            runOnCpu -> "sd15cpu"
            else -> "sd15npu"
        }

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

    suspend fun deleteModel(context: Context, keepHistory: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelDir = File(getModelsDir(context), id)
            val generationPreferences = GenerationPreferences(context)

            if (!keepHistory) {
                HistoryManager(context).clearHistoryForModel(id)
            }
            generationPreferences.clearPreferencesForModel(id)
            PinnedModels.unpin(context, listOf(id))

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

    // Rename a custom model, migrating every artifact keyed by its id: the
    // model directory, history (files + DB rows), per-model preferences and the
    // pinned list. The model directory is moved first because scanCustomModels()
    // keys off it; if that move fails nothing else is touched.
    suspend fun rename(context: Context, newName: String): RenameResult = withContext(Dispatchers.IO) {
        val newId = newName.replace(" ", "")
        when {
            newId.isEmpty() -> return@withContext RenameResult.Error(RenameResult.Reason.BlankName)

            newId == id -> return@withContext RenameResult.Success

            ModelRepository.isReservedModelId(newId) ->
                return@withContext RenameResult.Error(RenameResult.Reason.Reserved)
        }

        val modelsDir = getModelsDir(context)
        val oldDir = File(modelsDir, id)
        val newDir = File(modelsDir, newId)
        if (!oldDir.exists()) return@withContext RenameResult.Error(RenameResult.Reason.Io)
        if (newDir.exists()) return@withContext RenameResult.Error(RenameResult.Reason.Exists)

        if (!oldDir.renameTo(newDir)) {
            return@withContext RenameResult.Error(RenameResult.Reason.Io)
        }

        // The directory move is the commit point: the model is now usable under
        // its new id. Migrate the remaining artifacts best-effort. A rare failure
        // here degrades gracefully (saved params/history may not carry over) but
        // must not be reported as a failed rename, since the model HAS been
        // renamed. Cancellation must still propagate.
        try {
            HistoryManager(context).renameModel(id, newId)
            GenerationPreferences(context).migratePreferencesForModel(id, newId)
            PinnedModels.rename(context, id, newId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Model", "rename post-move migration partly failed: ${e.message}")
        }
        RenameResult.Success
    }

    companion object {
        private const val MODELS_DIR = "models"

        fun isDeviceSupported(): Boolean {
            val soc = getDeviceSoc()
            return getChipsetSuffix(soc) != null
        }

        fun isQualcommDevice(): Boolean {
            val soc = getDeviceSoc().uppercase()
            val prefixes = listOf(
                "SM", "QCS", "QCM", "CQ", "IPQ", "SXR", "AIC", "SSG",
                "SC", "SA", "SDM", "MSM", "QRB", "X1E", "X1P",
            )
            return prefixes.any { soc.startsWith(it) }
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

        fun getModelsDir(context: Context): File = File(context.filesDir, MODELS_DIR).apply {
            if (!exists()) mkdirs()
        }

        fun isModelDownloaded(context: Context, modelId: String, isCustom: Boolean = false): Boolean {
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

        // Upscalers store a single raw weight file; existence must match what
        // performUpscale() actually loads, not just a non-empty directory.
        const val UPSCALER_FILE_NAME = "upscaler.bin"

        fun isUpscalerDownloaded(context: Context, upscalerId: String): Boolean {
            val file = File(File(getModelsDir(context), upscalerId), UPSCALER_FILE_NAME)
            return file.exists() && file.length() > 0
        }

        fun needsModelUpgrade(context: Context, modelId: String, isNpu: Boolean): Boolean {
            if (!isNpu) return false

            val modelDir = File(getModelsDir(context), modelId)
            if (!modelDir.exists()) return false

            val vFile = File(modelDir, "v3")
            return !vFile.exists()
        }
    }
}

@Immutable
data class UpscalerModel(
    val id: String,
    val name: String,
    val description: String,
    val baseUrl: String,
    val fileUri: String,
    val isDownloaded: Boolean = false,
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

class UpscalerRepository private constructor(private val context: Context) {
    private val generationPreferences = GenerationPreferences(context)
    private val refreshMutex = Mutex()

    var upscalers by mutableStateOf<List<UpscalerModel>>(emptyList())
        private set

    private var isLoaded = false

    suspend fun ensureLoaded() {
        if (isLoaded) return
        refreshMutex.withLock {
            if (isLoaded) return
            val baseUrl = generationPreferences.getBaseUrl()
            upscalers = withContext(Dispatchers.IO) { initializeUpscalers(baseUrl) }
            isLoaded = true
        }
    }

    private fun initializeUpscalers(baseUrl: String): List<UpscalerModel> {
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"

        return listOf(
            createAnimeUpscaler(baseUrl, suffix),
            createRealisticUpscaler(baseUrl, suffix),
        )
    }

    private fun createAnimeUpscaler(baseUrl: String, suffix: String): UpscalerModel {
        val id = "upscaler_anime"
        val fileUri =
            "xororz/upscaler/resolve/main/realesrgan_x4plus_anime_6b/upscaler_$suffix.bin"

        val isDownloaded = Model.isUpscalerDownloaded(context, id)

        return UpscalerModel(
            id = id,
            name = context.getString(R.string.upscaler_anime),
            description = context.getString(R.string.upscaler_anime_desc),
            baseUrl = baseUrl,
            fileUri = fileUri,
            isDownloaded = isDownloaded,
        )
    }

    private fun createRealisticUpscaler(baseUrl: String, suffix: String): UpscalerModel {
        val id = "upscaler_realistic"
        val fileUri = "xororz/upscaler/resolve/main/4x_UltraSharpV2_Lite/upscaler_$suffix.bin"

        val isDownloaded = Model.isUpscalerDownloaded(context, id)

        return UpscalerModel(
            id = id,
            name = context.getString(R.string.upscaler_realistic),
            description = context.getString(R.string.upscaler_realistic_desc),
            baseUrl = baseUrl,
            fileUri = fileUri,
            isDownloaded = isDownloaded,
        )
    }

    // Re-read the base URL and rebuild the upscaler list so a base-URL change
    // in settings takes effect without an app restart. Mirrors
    // ModelRepository.refreshAllModels(); the singleton otherwise caches the
    // URL captured at first ensureLoaded().
    suspend fun refreshBaseUrl() {
        refreshMutex.withLock {
            if (!isLoaded) return
            val baseUrl = generationPreferences.getBaseUrl()
            upscalers = withContext(Dispatchers.IO) { initializeUpscalers(baseUrl) }
        }
    }

    suspend fun refreshUpscalerState(upscalerId: String) {
        refreshMutex.withLock {
            val current = upscalers
            upscalers = withContext(Dispatchers.IO) {
                current.map { upscaler ->
                    if (upscaler.id == upscalerId) {
                        val isDownloaded = Model.isUpscalerDownloaded(context, upscaler.id)
                        upscaler.copy(isDownloaded = isDownloaded)
                    } else {
                        upscaler
                    }
                }
            }
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: UpscalerRepository? = null

        fun getInstance(context: Context): UpscalerRepository = instance ?: synchronized(this) {
            instance ?: UpscalerRepository(context.applicationContext).also { instance = it }
        }
    }
}

class ModelRepository private constructor(private val context: Context) {
    private val generationPreferences = GenerationPreferences(context)
    private val refreshMutex = Mutex()

    // Read by the create*Model() builders during a scan; refreshed from
    // preferences at the start of every refresh, always under refreshMutex.
    private var baseUrl = "https://huggingface.co/"

    var models by mutableStateOf<List<Model>>(emptyList())
        private set

    // False until the first disk scan completes; lets the UI tell "still
    // loading" apart from "genuinely no models".
    var isLoaded by mutableStateOf(false)
        private set

    suspend fun ensureLoaded() {
        if (isLoaded) return
        refreshAllModels()
    }

    private fun scanCustomModels(): List<Model> {
        val modelsDir = Model.getModelsDir(context)
        val customModels = mutableListOf<Model>()

        if (modelsDir.exists() && modelsDir.isDirectory) {
            modelsDir.listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach

                val modelId = dir.name
                if (modelId in RESERVED_MODEL_IDS) {
                    Log.w(
                        "ModelRepository",
                        "skip custom model '$modelId': id conflicts with a built-in model",
                    )
                    return@forEach
                }

                val finishedFile = File(dir, "finished")
                val npuCustomFile = File(dir, "npucustom")
                val sdxlFile = File(dir, "SDXL")
                val animaFile = File(dir, "ANIMA")

                when {
                    animaFile.exists() ->
                        customModels.add(createCustomModel(dir, isNpu = true, isAnima = true))

                    sdxlFile.exists() ->
                        customModels.add(createCustomModel(dir, isNpu = !File(dir, "unet.mnn").exists(), isSdxl = true))

                    finishedFile.exists() ->
                        customModels.add(createCustomModel(dir, isNpu = false))

                    npuCustomFile.exists() ->
                        customModels.add(createCustomModel(dir, isNpu = true))
                }
            }
        }

        return customModels.sortedBy { it.name.lowercase() }
    }

    private fun createCustomModel(modelDir: File, isNpu: Boolean = false, isSdxl: Boolean = false, isAnima: Boolean = false): Model {
        val modelId = modelDir.name
        // Imported models have no code-level defaults: config.json (if
        // bundled in the zip) wins, the generic placeholder prompts below
        // only fill what it leaves unset.
        val placeholders = ModelConfig(
            prompt = "masterpiece, best quality, a cat sat on a mat,",
            negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
        )
        val config = ModelConfig.read(modelDir) ?: ModelConfig()

        return Model(
            id = modelId,
            name = modelId,
            description = context.getString(R.string.custom_model),
            baseUrl = "",
            generationSize = if (isSdxl || isAnima) 1024 else 512,
            approximateSize = "Custom",
            isDownloaded = true,
            configDefaults = config.withFallback(placeholders),
            runOnCpu = !isNpu,
            isCustom = true,
            isSdxl = isSdxl,
            isAnima = isAnima,
        )
    }

    private fun initializeModels(): List<Model> {
        val customModels = scanCustomModels()

        val predefinedModels = mutableListOf<Model>().apply {
            if (isSdxlCapableSoc(getDeviceSoc())) {
                add(createIllustriousV16Model())
                add(createIllustriousV16Dmd2Model())
                add(createCyberRealisticV10Model())
                add(createCyberRealisticV10Dmd2Model())
            }
            add(createAnythingV5Model())
            add(createAnythingV5ModelCPU())
            add(createQteaMixModel())
            add(createQteaMixModelCPU())
            add(createAbsoluteRealityModel())
            add(createAbsoluteRealityModelCPU())
            add(createCuteYukiMixModel())
            add(createCuteYukiMixModelCPU())
            add(createChilloutMixModelCPU())
            add(createChilloutMixModel())
        }

        return customModels + predefinedModels.map { applyConfigDefaults(it) }
    }

    // Load config.json shipped inside the model's downloaded files, keeping
    // any values already merged into configDefaults (e.g. the custom model
    // placeholders) as fallback.
    private fun applyConfigDefaults(model: Model): Model {
        val config = ModelConfig.read(File(Model.getModelsDir(context), model.id)) ?: return model
        return model.copy(configDefaults = config.withFallback(model.configDefaults))
    }

    private fun isSdxlCapableSoc(soc: String): Boolean = soc in setOf("SM8750", "SM8750P", "SM8850", "SM8850P", "SM8845", "SM8650")

    private fun createCyberRealisticV10Model(): Model {
        val id = "cyber_realistic_v10"
        val fileUri = "xororz/sdxl-qnn/resolve/main/cyber_realistic_v10_qnn2.28_8gen3.zip"

        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return Model(
            id = id,
            name = "CyberRealistic v10",
            description = context.getString(R.string.cyberrealistic_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            generationSize = 1024,
            approximateSize = "4.2GB",
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "masterpiece, best quality, a majestic cat sitting on a windowsill at sunset,",
                negativePrompt = "lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, fewer digits, cropped, worst quality, low quality, normal quality, jpeg artifacts, signature, watermark, username, blurry,",
            ),
            runOnCpu = false,
            isSdxl = true,
        )
    }

    private fun createCyberRealisticV10Dmd2Model(): Model {
        val id = "cyber_realistic_v10_dmd2"
        val fileUri = "xororz/sdxl-qnn/resolve/main/cyber_realistic_v10_dmd2_qnn2.28_8gen3.zip"

        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return Model(
            id = id,
            name = "CyberRealistic v10 DMD2",
            description = context.getString(R.string.dmd2_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            generationSize = 1024,
            approximateSize = "4.2GB",
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "masterpiece, best quality, a majestic cat sitting on a windowsill at sunset,",
                negativePrompt = "lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, fewer digits, cropped, worst quality, low quality, normal quality, jpeg artifacts, signature, watermark, username, blurry,",
            ),
            // steps/cfg/scheduler intentionally unset: the distilled model
            // ships them in a config.json bundled inside the zip.
            runOnCpu = false,
            isSdxl = true,
        )
    }

    private fun createIllustriousV16Model(): Model {
        val id = "illustrious_v16"
        val fileUri = "xororz/sdxl-qnn/resolve/main/illustrious_v16_qnn2.28_8gen3.zip"

        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return Model(
            id = id,
            name = "Illustrious v16",
            description = context.getString(R.string.illustriousv16_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            generationSize = 1024,
            approximateSize = "4.2GB",
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "1girl, solo, blue twintails, very long hair, bangs, blue eyes, jewelry, necklace, hair bow, off-shoulder white frilled dress, bare shoulders, collarbone, underwater, floating hair, reaching towards viewer, air bubbles, blue theme, blurry foreground, masterpiece",
                negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            ),
            runOnCpu = false,
            isSdxl = true,
        )
    }

    private fun createIllustriousV16Dmd2Model(): Model {
        val id = "illustrious_v16_dmd2"
        val fileUri = "xororz/sdxl-qnn/resolve/main/illustrious_v16_dmd2_qnn2.28_8gen3.zip"

        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return Model(
            id = id,
            name = "Illustrious v16 DMD2",
            description = context.getString(R.string.dmd2_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            generationSize = 1024,
            approximateSize = "4.2GB",
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "1girl, solo, blue twintails, very long hair, bangs, blue eyes, jewelry, necklace, hair bow, off-shoulder white frilled dress, bare shoulders, collarbone, underwater, floating hair, reaching towards viewer, air bubbles, blue theme, blurry foreground, masterpiece",
                negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            ),
            runOnCpu = false,
            isSdxl = true,
        )
    }

    private fun createAnythingV5Model(): Model {
        val id = "anythingv5"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val fileUri = "xororz/sd-qnn/resolve/main/AnythingV5_qnn2.28_$suffix.zip"

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
            codeDefaults = ModelConfig(
                prompt = "masterpiece, best quality, 1girl, solo, cute, white hair,",
                negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            ),
            runOnCpu = false,
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
            codeDefaults = ModelConfig(
                prompt = "masterpiece, best quality, 1girl, solo, cute, white hair,",
                negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            ),
            runOnCpu = true,
        )
    }

    private fun createQteaMixModel(): Model {
        val id = "qteamix"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val fileUri = "xororz/sd-qnn/resolve/main/QteaMix_qnn2.28_$suffix.zip"
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
            codeDefaults = ModelConfig(
                prompt = "chibi, best quality, 1girl, solo, cute, pink hair,",
                negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            ),
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
            codeDefaults = ModelConfig(
                prompt = "chibi, best quality, 1girl, solo, cute, pink hair,",
                negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            ),
            runOnCpu = true,
        )
    }

    private fun createCuteYukiMixModel(): Model {
        val id = "cuteyukimix"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val fileUri = "xororz/sd-qnn/resolve/main/CuteYukiMix_qnn2.28_$suffix.zip"
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
            codeDefaults = ModelConfig(
                prompt = "masterpiece, best quality, 1girl, solo, cute, white hair,",
                negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            ),
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
            codeDefaults = ModelConfig(
                prompt = "masterpiece, best quality, 1girl, solo, cute, white hair,",
                negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            ),
            runOnCpu = true,
        )
    }

    private fun createAbsoluteRealityModel(): Model {
        val id = "absolutereality"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val fileUri = "xororz/sd-qnn/resolve/main/AbsoluteReality_qnn2.28_$suffix.zip"
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
            codeDefaults = ModelConfig(
                prompt = "masterpiece, best quality, ultra-detailed, realistic, 8k, a cat on grass,",
                negativePrompt = "worst quality, low quality, normal quality, poorly drawn, lowres, low resolution, signature, watermarks, ugly, out of focus, error, blurry, unclear photo, bad photo, unrealistic, semi realistic, pixelated, cartoon, anime, cgi, drawing, 2d, 3d, censored, duplicate,",
            ),
            runOnCpu = false,
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
            codeDefaults = ModelConfig(
                prompt = "masterpiece, best quality, ultra-detailed, realistic, 8k, a cat on grass,",
                negativePrompt = "worst quality, low quality, normal quality, poorly drawn, lowres, low resolution, signature, watermarks, ugly, out of focus, error, blurry, unclear photo, bad photo, unrealistic, semi realistic, pixelated, cartoon, anime, cgi, drawing, 2d, 3d, censored, duplicate,",
            ),
            runOnCpu = true,
        )
    }

    private fun createChilloutMixModel(): Model {
        val id = "chilloutmix"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val fileUri = "xororz/sd-qnn/resolve/main/ChilloutMix_qnn2.28_$suffix.zip"
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
            codeDefaults = ModelConfig(
                prompt = "RAW photo, best quality, realistic, photo-realistic, masterpiece, 1girl, upper body, facing front, portrait, white shirt",
                negativePrompt = "paintings, cartoon, anime, lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, cropped, worst quality, low quality, normal quality, jpeg artifacts, signature, watermark, username, skin spots, acnes, skin blemishes",
            ),
            runOnCpu = false,
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
            codeDefaults = ModelConfig(
                prompt = "RAW photo, best quality, realistic, photo-realistic, masterpiece, 1girl, upper body, facing front, portrait, white shirt",
                negativePrompt = "paintings, cartoon, anime, lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, cropped, worst quality, low quality, normal quality, jpeg artifacts, signature, watermark, username, skin spots, acnes, skin blemishes",
            ),
            runOnCpu = true,
        )
    }

    suspend fun refreshModelState(modelId: String) {
        refreshMutex.withLock {
            val current = models
            models = withContext(Dispatchers.IO) {
                current.map { model ->
                    if (model.id == modelId) {
                        val isDownloaded =
                            Model.isModelDownloaded(context, modelId, model.isCustom)
                        val needsUpgrade = if (!model.runOnCpu) {
                            Model.needsModelUpgrade(context, modelId, true)
                        } else {
                            false
                        }
                        applyConfigDefaults(
                            model.copy(
                                isDownloaded = isDownloaded,
                                needsUpgrade = needsUpgrade,
                            ),
                        )
                    } else {
                        model
                    }
                }
            }
        }
    }

    suspend fun refreshAllModels() {
        refreshMutex.withLock {
            baseUrl = generationPreferences.getBaseUrl()
            models = withContext(Dispatchers.IO) { initializeModels() }
            isLoaded = true
        }
    }

    companion object {
        // IDs reserved by built-in models and upscalers. Custom model
        // directories that match one of these would collide with the built-in
        // entry on disk and in the UI list, so they are skipped during scan.
        // Keep in sync with the create*Model() functions and UpscalerRepository.
        private val RESERVED_MODEL_IDS = setOf(
            // SDXL (NPU)
            "illustrious_v16", "illustrious_v16_dmd2",
            "cyber_realistic_v10", "cyber_realistic_v10_dmd2",
            // SD 1.5 NPU
            "anythingv5", "qteamix", "cuteyukimix", "absolutereality", "chilloutmix",
            // SD 1.5 CPU
            "anythingv5cpu", "qteamixcpu", "cuteyukimixcpu",
            "absoluterealitycpu", "chilloutmixcpu",
        )

        fun isReservedModelId(id: String): Boolean = id in RESERVED_MODEL_IDS

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: ModelRepository? = null

        fun getInstance(context: Context): ModelRepository = instance ?: synchronized(this) {
            instance ?: ModelRepository(context.applicationContext).also { instance = it }
        }
    }
}
