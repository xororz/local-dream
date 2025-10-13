package io.github.xororz.localdream.data

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xororz.localdream.BuildConfig
import io.github.xororz.localdream.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.io.FileInputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private fun getDeviceSoc(): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL
    } else {
        "CPU"
    }
}

data class ModelFile(
    val name: String,
    val displayName: String,
    val uri: String
)

data class HighresInfo(
    val size: Int,
    val patchFileName: String,
    val isDownloaded: Boolean = false,
    val isDownloading: Boolean = false
)

data class DownloadProgress(
    val displayName: String,
    val currentFileIndex: Int,
    val totalFiles: Int,
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
    "SM8650" to "8gen3",
    "SM8650P" to "8gen3",
    "SM8750" to "8gen4",
    "SM8750P" to "8gen4",
    "SM8850" to "8gen2", // assuming no performance loss
    "SM8850P" to "8gen2",
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
    val files: List<ModelFile> = emptyList(),
    val generationSize: Int = 512,
    val textEmbeddingSize: Int = 768,
    val approximateSize: String = "1GB",
    val isDownloaded: Boolean = false,
    val isPartiallyDownloaded: Boolean = false,
    val defaultPrompt: String = "",
    val defaultNegativePrompt: String = "",
    val runOnCpu: Boolean = false,
    val useCpuClip: Boolean = false,
    val supportedHighres: List<Int> = emptyList(),
    val highresInfo: Map<Int, HighresInfo> = emptyMap(),
    val isCustom: Boolean = false

) {
    private fun calculateFileMD5(file: File): String? {
        return try {
            val md = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    md.update(buffer, 0, bytesRead)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            android.util.Log.e("Model", "Failed to calculate MD5 for ${file.name}", e)
            null
        }
    }

    private fun getUnetMD5Prefix(context: Context): String? {
        val modelDir = File(getModelsDir(context), id)
        val unetFile = File(modelDir, "unet.bin")

        if (!unetFile.exists()) {
            android.util.Log.e("Model", "unet.bin not found for model $id")
            return null
        }

        val fullMD5 = calculateFileMD5(unetFile)
        return fullMD5?.take(6)
    }

    fun download(context: Context): Flow<DownloadResult> = flow {
        if (isCustom) {
            emit(DownloadResult.Success)
            return@flow
        }

        val modelsDir = getModelsDir(context)
        val modelDir = File(modelsDir, id).apply {
            if (!exists()) mkdirs()
        }

        val downloadManager = DownloadManager(context)
        val fileVerification = FileVerification(context)

        try {
            downloadManager.downloadWithResume(
                modelId = id,
                files = files,
                baseUrl = baseUrl,
                modelDir = modelDir
            ).collect { result ->
                emit(result)
            }
        } catch (e: Exception) {
            fileVerification.clearVerification(id)
            emit(DownloadResult.Error(e.message ?: "Download failed"))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun checkUrlExists(url: String): Int {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .head()
                .build()

            val response = client.newCall(request).execute()
            response.use { it.code }
        } catch (e: Exception) {
            android.util.Log.e("Model", "Failed to check URL: $url", e)
            500
        }
    }

    fun downloadHighresPatch(context: Context, resolution: Int): Flow<DownloadResult> = flow {
        val modelsDir = getModelsDir(context)
        val modelDir = File(modelsDir, id).apply {
            if (!exists()) mkdirs()
        }

        val md5Prefix = getUnetMD5Prefix(context)
        if (md5Prefix == null) {
            emit(DownloadResult.Error("Cannot calculate MD5 of unet.bin, please ensure base model is fully downloaded"))
            return@flow
        }

        val patchFileNameWithMD5 = "${resolution}.patch.${md5Prefix}"

        val repoPath = when (id) {
            "anythingv5" -> "xororz/AnythingV5"
            "qteamix" -> "xororz/QteaMix"
            "cuteyukimix" -> "xororz/CuteYukiMix"
            "absolutereality" -> "xororz/AbsoluteReality"
            "chilloutmix" -> "xororz/ChilloutMix"
            else -> {
                emit(DownloadResult.Error("Unsupported model type"))
                return@flow
            }
        }

        val patchFileUri = "${repoPath}/resolve/main/patch/${patchFileNameWithMD5}"
        val fullUrl = "${baseUrl.removeSuffix("/")}/${patchFileUri}"

        val statusCode = checkUrlExists(fullUrl)
        if (statusCode == 404) {
            emit(DownloadResult.Error("PATCH_NOT_FOUND|Cannot find high resolution patch file matching current base model.\n\nThis usually means your base model version is outdated.\n\nPlease delete current model and download the latest version to get highres support.\n\nError code: MD5-${md5Prefix}"))
            return@flow
        } else if (statusCode != 200) {
            emit(DownloadResult.Error("Network error: HTTP $statusCode"))
            return@flow
        }

        val patchFile = ModelFile(
            name = "${resolution}.patch",
            displayName = "${resolution}.patch",
            uri = patchFileUri
        )

        val downloadManager = DownloadManager(context)

        try {
            downloadManager.downloadWithResume(
                modelId = id,
                files = listOf(patchFile),
                baseUrl = baseUrl,
                modelDir = modelDir
            ).collect { result ->
                emit(result)
            }
        } catch (e: Exception) {
            emit(DownloadResult.Error(e.message ?: "High resolution patch download failed"))
        }
    }.flowOn(Dispatchers.IO)

    fun isHighresDownloaded(context: Context, resolution: Int): Boolean {
        val modelDir = File(getModelsDir(context), id)
        val patchFile = File(modelDir, "${resolution}.patch")

        if (!patchFile.exists()) {
            return false
        }

        if (isCustom) {
            return true
        }

        val fileVerification = FileVerification(context)
        return runBlocking {
            val savedSize = fileVerification.getFileSize(id, "${resolution}.patch")
            savedSize != null && patchFile.length() == savedSize
        }
    }

    fun deleteModel(context: Context): Boolean {
        return try {
            val modelDir = File(getModelsDir(context), id)
            val fileVerification = FileVerification(context)

            runBlocking {
                fileVerification.clearVerification(id)
            }

            if (modelDir.exists() && modelDir.isDirectory) {
                val deleted = modelDir.deleteRecursively()
                android.util.Log.d("Model", "Delete model $id: $deleted")
                deleted
            } else {
                android.util.Log.d("Model", "Model does not exist: $id")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("Model", "error: ${e.message}")
            false
        }
    }

    companion object {
        private const val MODELS_DIR = "models"

        fun isDeviceSupported(): Boolean {
            val soc = getDeviceSoc()
            return getChipsetSuffix(soc) != null
        }

        fun isMinimalDevice(): Boolean {
            val soc = getDeviceSoc()
            return getChipsetSuffix(soc) == "min"
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

        fun checkModelDownloadStatus(
            context: Context,
            modelId: String,
            files: List<ModelFile>,
            isCustom: Boolean = false
        ): Pair<Boolean, Boolean> {
            if (isCustom) {
                return Pair(true, false)
            }

            val modelDir = File(getModelsDir(context), modelId)
            val fileVerification = FileVerification(context)

            var existingFilesCount = 0
            val totalFilesCount = files.size

            val fullyDownloaded = runBlocking {
                files.all { modelFile ->
                    val file = File(modelDir, modelFile.name)
                    if (file.exists()) {
                        existingFilesCount++
                        val savedSize = fileVerification.getFileSize(modelId, modelFile.name)
                        savedSize != null && file.length() == savedSize
                    } else {
                        false
                    }
                }
            }

            val partiallyDownloaded = existingFilesCount > 0 && existingFilesCount < totalFilesCount

            return Pair(fullyDownloaded, partiallyDownloaded)
        }

        fun checkModelExists(
            context: Context,
            modelId: String,
            files: List<ModelFile>,
            isCustom: Boolean = false
        ): Boolean {
            val (fullyDownloaded, _) = checkModelDownloadStatus(context, modelId, files, isCustom)
            return fullyDownloaded
        }
    }
}

data class UpscalerModel(
    val id: String,
    val name: String,
    val description: String,
    val baseUrl: String,
    val file: ModelFile,
    val isDownloaded: Boolean = false
)

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
            generationPreferences.getBaseUrl().collect { url ->
                baseUrl = url
                upscalers = initializeUpscalers()
            }
        }
    }

    fun updateBaseUrl(newUrl: String) {
        baseUrl = newUrl
        upscalers = initializeUpscalers()
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
        val file = ModelFile(
            "upscaler.bin",
            "upscaler",
            "xororz/upscaler/resolve/main/realesrgan_x4plus_anime_6b/upscaler_${suffix}.bin"
        )

        val modelsDir = File(Model.getModelsDir(context), id)
        val upscalerFile = File(modelsDir, "upscaler.bin")

        val isDownloaded = if (upscalerFile.exists()) {
            val fileVerification = FileVerification(context)
            runBlocking {
                val savedSize = fileVerification.getFileSize(id, "upscaler.bin")
                savedSize != null && upscalerFile.length() == savedSize
            }
        } else {
            false
        }

        return UpscalerModel(
            id = id,
            name = context.getString(R.string.upscaler_anime),
            description = context.getString(R.string.upscaler_anime_desc),
            baseUrl = baseUrl,
            file = file,
            isDownloaded = isDownloaded
        )
    }

    private fun createRealisticUpscaler(suffix: String): UpscalerModel {
        val id = "upscaler_realistic"
        val file = ModelFile(
            "upscaler.bin",
            "upscaler",
            "xororz/upscaler/resolve/main/4x_UltraSharpV2_Lite/upscaler_${suffix}.bin"
        )

        val modelsDir = File(Model.getModelsDir(context), id)
        val upscalerFile = File(modelsDir, "upscaler.bin")

        val isDownloaded = if (upscalerFile.exists()) {
            val fileVerification = FileVerification(context)
            runBlocking {
                val savedSize = fileVerification.getFileSize(id, "upscaler.bin")
                savedSize != null && upscalerFile.length() == savedSize
            }
        } else {
            false
        }

        return UpscalerModel(
            id = id,
            name = context.getString(R.string.upscaler_realistic),
            description = context.getString(R.string.upscaler_realistic_desc),
            baseUrl = baseUrl,
            file = file,
            isDownloaded = isDownloaded
        )
    }

    fun downloadUpscaler(upscaler: UpscalerModel): Flow<DownloadResult> = flow {
        val modelsDir = File(Model.getModelsDir(context), upscaler.id).apply {
            if (!exists()) mkdirs()
        }

        val downloadManager = DownloadManager(context)

        try {
            downloadManager.downloadWithResume(
                modelId = upscaler.id,
                files = listOf(upscaler.file),
                baseUrl = upscaler.baseUrl,
                modelDir = modelsDir
            ).collect { result ->
                emit(result)
            }
        } catch (e: Exception) {
            emit(DownloadResult.Error(e.message ?: "Download failed"))
        }
    }.flowOn(Dispatchers.IO)

    fun refreshUpscalerState(upscalerId: String) {
        upscalers = upscalers.map { upscaler ->
            if (upscaler.id == upscalerId) {
                val modelsDir = File(Model.getModelsDir(context), upscaler.id)
                val upscalerFile = File(modelsDir, "upscaler.bin")

                val isDownloaded = if (upscalerFile.exists()) {
                    val fileVerification = FileVerification(context)
                    runBlocking {
                        val savedSize = fileVerification.getFileSize(upscaler.id, "upscaler.bin")
                        savedSize != null && upscalerFile.length() == savedSize
                    }
                } else {
                    false
                }

                upscaler.copy(isDownloaded = isDownloaded)
            } else {
                upscaler
            }
        }
    }

    fun refreshAll() {
        upscalers = initializeUpscalers()
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
            generationPreferences.getBaseUrl().collect { url ->
                baseUrl = url
                models = initializeModels()
            }
        }
    }

    fun updateBaseUrl(newUrl: String) {
        baseUrl = newUrl
        models = initializeModels()
    }

    private fun checkHighresPatchExists(modelId: String, resolution: Int): Boolean {
        val modelDir = File(Model.getModelsDir(context), modelId)
        val patchFile = File(modelDir, "${resolution}.patch")

        if (!patchFile.exists()) {
            return false
        }

        val fileVerification = FileVerification(context)
        return runBlocking {
            val savedSize = fileVerification.getFileSize(modelId, "${resolution}.patch")
            savedSize != null && patchFile.length() == savedSize
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

        return customModels
    }

    private fun createCustomModel(modelDir: File, isNpu: Boolean = false): Model {
        val modelId = modelDir.name
        val files = mutableListOf<ModelFile>()

        if (isNpu) {
            modelDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name != "npucustom") {
                    files.add(
                        ModelFile(
                            name = file.name,
                            displayName = file.nameWithoutExtension,
                            uri = ""
                        )
                    )
                }
            }
        } else {
            val commonFiles = listOf(
                "tokenizer.json" to "tokenizer",
                "clip.mnn" to "clip",
                "unet.mnn" to "unet",
                "vae_decoder.mnn" to "vae_decoder",
                "vae_encoder.mnn" to "vae_encoder"
            )

            commonFiles.forEach { (fileName, displayName) ->
                val file = File(modelDir, fileName)
                if (file.exists()) {
                    files.add(
                        ModelFile(
                            name = fileName,
                            displayName = displayName,
                            uri = ""
                        )
                    )
                }
            }
        }

        // Check for high resolution patches for NPU models
        val supportedHighres = mutableListOf<Int>()
        val highresInfo = mutableMapOf<Int, HighresInfo>()

        if (isNpu) {
            val patchResolutions = listOf(768, 1024)
            patchResolutions.forEach { resolution ->
                val patchFile = File(modelDir, "${resolution}.patch")
                if (patchFile.exists()) {
                    supportedHighres.add(resolution)
                    highresInfo[resolution] = HighresInfo(
                        size = resolution,
                        patchFileName = "${resolution}.patch",
                        isDownloaded = true
                    )
                }
            }
        }

        return Model(
            id = modelId,
            name = modelId,
            description = context.getString(R.string.custom_model),
            baseUrl = "",
            files = files,
            approximateSize = "Custom",
            isDownloaded = true,
            isPartiallyDownloaded = false,
            defaultPrompt = "masterpiece, best quality, flowers,",
            defaultNegativePrompt = "bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            runOnCpu = !isNpu,
            useCpuClip = true,
            supportedHighres = supportedHighres,
            highresInfo = highresInfo,
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

        if (!Model.isMinimalDevice()) {
            predefinedModels.add(createSD21Model())
        }

        return customModels + predefinedModels
    }

    private fun createAnythingV5Model(): Model {
        val id = "anythingv5"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val files = listOf(
            ModelFile(
                "tokenizer.json",
                "tokenizer",
                "xororz/AnythingV5/resolve/main/tokenizer.json"
            ),
            ModelFile(
                "clip_v2.mnn",
                "clip",
                "xororz/AnythingV5/resolve/main/clip_v2.mnn"
            ),
            ModelFile(
                "pos_emb.bin",
                "pos_emb",
                "xororz/AnythingV5/resolve/main/pos_emb.bin"
            ),
            ModelFile(
                "token_emb.bin",
                "token_emb",
                "xororz/AnythingV5/resolve/main/token_emb.bin"
            ),
            ModelFile(
                "vae_encoder.bin",
                "vae_encoder",
                "xororz/AnythingV5/resolve/main/vae_encoder_${suffix}.bin"
            ),
            ModelFile(
                "vae_decoder.bin",
                "vae_decoder",
                "xororz/AnythingV5/resolve/main/vae_decoder_${suffix}.bin"
            ),
            ModelFile(
                "unet.bin",
                "unet",
                "xororz/AnythingV5/resolve/main/unet_${suffix}.bin"
            )
        )

        val (fullyDownloaded, partiallyDownloaded) = Model.checkModelDownloadStatus(
            context,
            id,
            files,
            false
        )
        val supportedHighres = listOf(768, 1024)
        val highresInfo = supportedHighres.associateWith { resolution ->
            HighresInfo(
                size = resolution,
                patchFileName = "${resolution}.patch",
                isDownloaded = checkHighresPatchExists(id, resolution)
            )
        }

        return Model(
            id = id,
            name = "Anything V5.0",
            description = context.getString(R.string.anythingv5_description),
            baseUrl = baseUrl,
            files = files,
            approximateSize = "1.1GB",
            isDownloaded = fullyDownloaded,
            isPartiallyDownloaded = partiallyDownloaded,
            defaultPrompt = "masterpiece, best quality, 1girl, solo, cute, white hair,",
            defaultNegativePrompt = "bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            useCpuClip = true,
            supportedHighres = supportedHighres,
            highresInfo = highresInfo
        )
    }

    private fun createAnythingV5ModelCPU(): Model {
        val id = "anythingv5cpu"
        val files = listOf(
            ModelFile(
                "tokenizer.json",
                "tokenizer",
                "xororz/AnythingV5/resolve/main/tokenizer.json"
            ),

            ModelFile(
                "clip_v2.mnn",
                "clip",
                "xororz/AnythingV5/resolve/main/clip_v2.mnn"
            ),
            ModelFile(
                "pos_emb.bin",
                "pos_emb",
                "xororz/AnythingV5/resolve/main/pos_emb.bin"
            ),
            ModelFile(
                "token_emb.bin",
                "token_emb",
                "xororz/AnythingV5/resolve/main/token_emb.bin"
            ),
            ModelFile(
                "vae_encoder.mnn",
                "vae_encoder",
                "xororz/AnythingV5/resolve/main/vae_encoder_fp16.mnn"
            ),
            ModelFile(
                "vae_decoder.mnn",
                "vae_decoder",
                "xororz/AnythingV5/resolve/main/vae_decoder_fp16.mnn"
            ),
            ModelFile(
                "unet.mnn",
                "unet",
                "xororz/AnythingV5/resolve/main/unet_asym_block32.mnn"
            )
        )

        val (fullyDownloaded, partiallyDownloaded) = Model.checkModelDownloadStatus(
            context,
            id,
            files,
            false
        )

        return Model(
            id = id,
            name = "Anything V5.0",
            description = context.getString(R.string.anythingv5_description),
            baseUrl = baseUrl,
            files = files,
            approximateSize = "1.2GB",
            isDownloaded = fullyDownloaded,
            isPartiallyDownloaded = partiallyDownloaded,
            defaultPrompt = "masterpiece, best quality, 1girl, solo, cute, white hair,",
            defaultNegativePrompt = "bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            runOnCpu = true
        )
    }

    private fun createQteaMixModel(): Model {
        val id = "qteamix"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val files = listOf(
            ModelFile(
                "tokenizer.json",
                "tokenizer",
                "xororz/QteaMix/resolve/main/tokenizer.json"
            ),
            ModelFile(
                "clip_v2.mnn",
                "clip",
                "xororz/QteaMix/resolve/main/clip_v2.mnn"
            ),
            ModelFile(
                "pos_emb.bin",
                "pos_emb",
                "xororz/QteaMix/resolve/main/pos_emb.bin"
            ),
            ModelFile(
                "token_emb.bin",
                "token_emb",
                "xororz/QteaMix/resolve/main/token_emb.bin"
            ),
            ModelFile(
                "vae_encoder.bin",
                "vae_encoder",
                "xororz/AnythingV5/resolve/main/vae_encoder_${suffix}.bin"
            ),
            ModelFile(
                "vae_decoder.bin",
                "vae_decoder",
                "xororz/QteaMix/resolve/main/vae_decoder_${suffix}.bin"
            ),
            ModelFile(
                "unet.bin",
                "unet",
                "xororz/QteaMix/resolve/main/unet_${suffix}.bin"
            )
        )

        val (fullyDownloaded, partiallyDownloaded) = Model.checkModelDownloadStatus(
            context,
            id,
            files,
            false
        )
        val supportedHighres = listOf(768, 1024)
        val highresInfo = supportedHighres.associateWith { resolution ->
            HighresInfo(
                size = resolution,
                patchFileName = "${resolution}.patch",
                isDownloaded = checkHighresPatchExists(id, resolution)
            )
        }

        return Model(
            id = id,
            name = "QteaMix",
            description = context.getString(R.string.qteamix_description),
            baseUrl = baseUrl,
            files = files,
            approximateSize = "1.1GB",
            isDownloaded = fullyDownloaded,
            isPartiallyDownloaded = partiallyDownloaded,
            defaultPrompt = "chibi, best quality, 1girl, solo, cute, pink hair,",
            defaultNegativePrompt = "bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            useCpuClip = true,
            supportedHighres = supportedHighres,
            highresInfo = highresInfo
        )
    }

    private fun createQteaMixModelCPU(): Model {
        val id = "qteamixcpu"
        val files = listOf(
            ModelFile(
                "tokenizer.json",
                "tokenizer",
                "xororz/AnythingV5/resolve/main/tokenizer.json"
            ),
            ModelFile(
                "clip_v2.mnn",
                "clip",
                "xororz/QteaMix/resolve/main/clip_v2.mnn"
            ),
            ModelFile(
                "pos_emb.bin",
                "pos_emb",
                "xororz/QteaMix/resolve/main/pos_emb.bin"
            ),
            ModelFile(
                "token_emb.bin",
                "token_emb",
                "xororz/QteaMix/resolve/main/token_emb.bin"
            ),
            ModelFile(
                "vae_encoder.mnn",
                "vae_encoder",
                "xororz/AnythingV5/resolve/main/vae_encoder_fp16.mnn"
            ),
            ModelFile(
                "vae_decoder.mnn",
                "vae_decoder",
                "xororz/QteaMix/resolve/main/vae_decoder_fp16.mnn"
            ),
            ModelFile(
                "unet.mnn",
                "unet",
                "xororz/QteaMix/resolve/main/unet_asym_block32.mnn"
            )
        )

        val (fullyDownloaded, partiallyDownloaded) = Model.checkModelDownloadStatus(
            context,
            id,
            files,
            false
        )

        return Model(
            id = id,
            name = "QteaMix",
            description = context.getString(R.string.qteamix_description),
            baseUrl = baseUrl,
            files = files,
            approximateSize = "1.2GB",
            isDownloaded = fullyDownloaded,
            isPartiallyDownloaded = partiallyDownloaded,
            defaultPrompt = "chibi, best quality, 1girl, solo, cute, pink hair,",
            defaultNegativePrompt = "bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            runOnCpu = true
        )
    }

    private fun createCuteYukiMixModel(): Model {
        val id = "cuteyukimix"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val files = listOf(
            ModelFile(
                "tokenizer.json",
                "tokenizer",
                "xororz/CuteYukiMix/resolve/main/tokenizer.json"
            ),
            ModelFile(
                "clip_v2.mnn",
                "clip",
                "xororz/CuteYukiMix/resolve/main/clip_v2.mnn"
            ),
            ModelFile(
                "pos_emb.bin",
                "pos_emb",
                "xororz/CuteYukiMix/resolve/main/pos_emb.bin"
            ),
            ModelFile(
                "token_emb.bin",
                "token_emb",
                "xororz/CuteYukiMix/resolve/main/token_emb.bin"
            ),
            ModelFile(
                "vae_encoder.bin",
                "vae_encoder",
                "xororz/AnythingV5/resolve/main/vae_encoder_${suffix}.bin"
            ),
            ModelFile(
                "vae_decoder.bin",
                "vae_decoder",
                "xororz/CuteYukiMix/resolve/main/vae_decoder_${suffix}.bin"
            ),
            ModelFile(
                "unet.bin",
                "unet",
                "xororz/CuteYukiMix/resolve/main/unet_${suffix}.bin"
            )
        )

        val (fullyDownloaded, partiallyDownloaded) = Model.checkModelDownloadStatus(
            context,
            id,
            files,
            false
        )
        val supportedHighres = listOf(768, 1024)
        val highresInfo = supportedHighres.associateWith { resolution ->
            HighresInfo(
                size = resolution,
                patchFileName = "${resolution}.patch",
                isDownloaded = checkHighresPatchExists(id, resolution)
            )
        }

        return Model(
            id = id,
            name = "CuteYukiMix",
            description = context.getString(R.string.cuteyukimix_description),
            baseUrl = baseUrl,
            files = files,
            approximateSize = "1.1GB",
            isDownloaded = fullyDownloaded,
            isPartiallyDownloaded = partiallyDownloaded,
            defaultPrompt = "masterpiece, best quality, 1girl, solo, cute, white hair,",
            defaultNegativePrompt = "bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            useCpuClip = true,
            supportedHighres = supportedHighres,
            highresInfo = highresInfo
        )
    }

    private fun createCuteYukiMixModelCPU(): Model {
        val id = "cuteyukimixcpu"
        val files = listOf(
            ModelFile(
                "tokenizer.json",
                "tokenizer",
                "xororz/CuteYukiMix/resolve/main/tokenizer.json"
            ),
            ModelFile(
                "clip_v2.mnn",
                "clip",
                "xororz/CuteYukiMix/resolve/main/clip_v2.mnn"
            ),
            ModelFile(
                "pos_emb.bin",
                "pos_emb",
                "xororz/CuteYukiMix/resolve/main/pos_emb.bin"
            ),
            ModelFile(
                "token_emb.bin",
                "token_emb",
                "xororz/CuteYukiMix/resolve/main/token_emb.bin"
            ),
            ModelFile(
                "vae_encoder.mnn",
                "vae_encoder",
                "xororz/AnythingV5/resolve/main/vae_encoder_fp16.mnn"
            ),
            ModelFile(
                "vae_decoder.mnn",
                "vae_decoder",
                "xororz/CuteYukiMix/resolve/main/vae_decoder_fp16.mnn"
            ),
            ModelFile(
                "unet.mnn",
                "unet",
                "xororz/CuteYukiMix/resolve/main/unet_asym_block32.mnn"
            )
        )

        val (fullyDownloaded, partiallyDownloaded) = Model.checkModelDownloadStatus(
            context,
            id,
            files,
            false
        )

        return Model(
            id = id,
            name = "CuteYukiMix",
            description = context.getString(R.string.cuteyukimix_description),
            baseUrl = baseUrl,
            files = files,
            approximateSize = "1.2GB",
            isDownloaded = fullyDownloaded,
            isPartiallyDownloaded = partiallyDownloaded,
            defaultPrompt = "masterpiece, best quality, 1girl, solo, cute, white hair,",
            defaultNegativePrompt = "bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            runOnCpu = true
        )
    }

    private fun createAbsoluteRealityModel(): Model {
        val id = "absolutereality"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val files = listOf(
            ModelFile(
                "tokenizer.json",
                "tokenizer",
                "xororz/AbsoluteReality/resolve/main/tokenizer.json"
            ),
            ModelFile(
                "clip_v2.mnn",
                "clip",
                "xororz/AbsoluteReality/resolve/main/clip_v2.mnn"
            ),
            ModelFile(
                "pos_emb.bin",
                "pos_emb",
                "xororz/AbsoluteReality/resolve/main/pos_emb.bin"
            ),
            ModelFile(
                "token_emb.bin",
                "token_emb",
                "xororz/AbsoluteReality/resolve/main/token_emb.bin"
            ),
            ModelFile(
                "vae_encoder.bin",
                "vae_encoder",
                "xororz/AnythingV5/resolve/main/vae_encoder_${suffix}.bin"
            ),
            ModelFile(
                "vae_decoder.bin",
                "vae_decoder",
                "xororz/AbsoluteReality/resolve/main/vae_decoder_${suffix}.bin"
            ),
            ModelFile(
                "unet.bin",
                "unet",
                "xororz/AbsoluteReality/resolve/main/unet_${suffix}.bin"
            )
        )

        val (fullyDownloaded, partiallyDownloaded) = Model.checkModelDownloadStatus(
            context,
            id,
            files,
            false
        )
        val supportedHighres = listOf(768, 1024)
        val highresInfo = supportedHighres.associateWith { resolution ->
            HighresInfo(
                size = resolution,
                patchFileName = "${resolution}.patch",
                isDownloaded = checkHighresPatchExists(id, resolution)
            )
        }

        return Model(
            id = id,
            name = "Absolute Reality",
            description = context.getString(R.string.absolutereality_description),
            baseUrl = baseUrl,
            files = files,
            approximateSize = "1.1GB",
            isDownloaded = fullyDownloaded,
            isPartiallyDownloaded = partiallyDownloaded,
            defaultPrompt = "masterpiece, best quality, ultra-detailed, realistic, 8k, a cat on grass,",
            defaultNegativePrompt = "worst quality, low quality, normal quality, poorly drawn, lowres, low resolution, signature, watermarks, ugly, out of focus, error, blurry, unclear photo, bad photo, unrealistic, semi realistic, pixelated, cartoon, anime, cgi, drawing, 2d, 3d, censored, duplicate,",
            runOnCpu = false,
            useCpuClip = true,
            supportedHighres = supportedHighres,
            highresInfo = highresInfo
        )
    }

    private fun createAbsoluteRealityModelCPU(): Model {
        val id = "absoluterealitycpu"
        val files = listOf(
            ModelFile(
                "tokenizer.json",
                "tokenizer",
                "xororz/AbsoluteReality/resolve/main/tokenizer.json"
            ),
            ModelFile(
                "clip_v2.mnn",
                "clip",
                "xororz/AbsoluteReality/resolve/main/clip_v2.mnn"
            ),
            ModelFile(
                "pos_emb.bin",
                "pos_emb",
                "xororz/AbsoluteReality/resolve/main/pos_emb.bin"
            ),
            ModelFile(
                "token_emb.bin",
                "token_emb",
                "xororz/AbsoluteReality/resolve/main/token_emb.bin"
            ),
            ModelFile(
                "vae_encoder.mnn",
                "vae_encoder",
                "xororz/AnythingV5/resolve/main/vae_encoder_fp16.mnn"
            ),
            ModelFile(
                "vae_decoder.mnn",
                "vae_decoder",
                "xororz/AbsoluteReality/resolve/main/vae_decoder_fp16.mnn"
            ),
            ModelFile(
                "unet.mnn",
                "unet",
                "xororz/AbsoluteReality/resolve/main/unet_asym_block32.mnn"
            )
        )

        val (fullyDownloaded, partiallyDownloaded) = Model.checkModelDownloadStatus(
            context,
            id,
            files,
            false
        )

        return Model(
            id = id,
            name = "Absolute Reality",
            description = context.getString(R.string.absolutereality_description),
            baseUrl = baseUrl,
            files = files,
            approximateSize = "1.2GB",
            isDownloaded = fullyDownloaded,
            isPartiallyDownloaded = partiallyDownloaded,
            defaultPrompt = "masterpiece, best quality, ultra-detailed, realistic, 8k, a cat on grass,",
            defaultNegativePrompt = "worst quality, low quality, normal quality, poorly drawn, lowres, low resolution, signature, watermarks, ugly, out of focus, error, blurry, unclear photo, bad photo, unrealistic, semi realistic, pixelated, cartoon, anime, cgi, drawing, 2d, 3d, censored, duplicate,",
            runOnCpu = true
        )
    }

    private fun createChilloutMixModel(): Model {
        val id = "chilloutmix"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val files = listOf(
            ModelFile(
                "tokenizer.json",
                "tokenizer",
                "xororz/ChilloutMix/resolve/main/tokenizer.json"
            ),
            ModelFile(
                "clip_v2.mnn",
                "clip",
                "xororz/ChilloutMix/resolve/main/clip_v2.mnn"
            ),
            ModelFile(
                "pos_emb.bin",
                "pos_emb",
                "xororz/ChilloutMix/resolve/main/pos_emb.bin"
            ),
            ModelFile(
                "token_emb.bin",
                "token_emb",
                "xororz/ChilloutMix/resolve/main/token_emb.bin"
            ),
            ModelFile(
                "vae_encoder.bin",
                "vae_encoder",
                "xororz/AnythingV5/resolve/main/vae_encoder_${suffix}.bin"
            ),
            ModelFile(
                "vae_decoder.bin",
                "vae_decoder",
                "xororz/ChilloutMix/resolve/main/vae_decoder_${suffix}.bin"
            ),
            ModelFile(
                "unet.bin",
                "unet",
                "xororz/ChilloutMix/resolve/main/unet_${suffix}.bin"
            )
        )

        val (fullyDownloaded, partiallyDownloaded) = Model.checkModelDownloadStatus(
            context,
            id,
            files,
            false
        )
        val supportedHighres = listOf(768, 1024)
        val highresInfo = supportedHighres.associateWith { resolution ->
            HighresInfo(
                size = resolution,
                patchFileName = "${resolution}.patch",
                isDownloaded = checkHighresPatchExists(id, resolution)
            )
        }

        return Model(
            id = id,
            name = "ChilloutMix",
            description = context.getString(R.string.chilloutmix_description),
            baseUrl = baseUrl,
            files = files,
            approximateSize = "1.1GB",
            isDownloaded = fullyDownloaded,
            isPartiallyDownloaded = partiallyDownloaded,
            defaultPrompt = "RAW photo, best quality, realistic, photo-realistic, masterpiece, 1girl, upper body, facing front, portrait,",
            defaultNegativePrompt = "paintings, sketches, worst quality, low quality, normal quality, lowres, monochrome, grayscale, skin spots, acnes, skin blemishes, age spot, bad anatomy, bad hands, bad body, bad proportions, gross proportions, extra fingers, fewer fingers, extra digit, missing fingers, fused fingers, extra arms, missing arms, extra legs, missing legs, extra limbs, mutated hands, poorly drawn hands, poorly drawn face, mutation, deformed, blurry, watermark, white letters, signature, text, error, jpeg artifacts, duplicate, morbid, mutilated, cross-eyed, long neck, ng_deepnegative_v1_75t, easynegative, bad-picture-chill-75v, bad-artist",
            runOnCpu = false,
            useCpuClip = true,
            supportedHighres = supportedHighres,
            highresInfo = highresInfo
        )
    }

    private fun createChilloutMixModelCPU(): Model {
        val id = "chilloutmixcpu"
        val files = listOf(
            ModelFile(
                "tokenizer.json",
                "tokenizer",
                "xororz/ChilloutMix/resolve/main/tokenizer.json"
            ),
            ModelFile(
                "clip_v2.mnn",
                "clip",
                "xororz/ChilloutMix/resolve/main/clip_v2.mnn"
            ),
            ModelFile(
                "pos_emb.bin",
                "pos_emb",
                "xororz/ChilloutMix/resolve/main/pos_emb.bin"
            ),
            ModelFile(
                "token_emb.bin",
                "token_emb",
                "xororz/ChilloutMix/resolve/main/token_emb.bin"
            ),
            ModelFile(
                "vae_encoder.mnn",
                "vae_encoder",
                "xororz/AnythingV5/resolve/main/vae_encoder_fp16.mnn"
            ),
            ModelFile(
                "vae_decoder.mnn",
                "vae_decoder",
                "xororz/ChilloutMix/resolve/main/vae_decoder_fp16.mnn"
            ),
            ModelFile(
                "unet.mnn",
                "unet",
                "xororz/ChilloutMix/resolve/main/unet_asym_block32.mnn"
            )
        )

        val (fullyDownloaded, partiallyDownloaded) = Model.checkModelDownloadStatus(
            context,
            id,
            files,
            false
        )

        return Model(
            id = id,
            name = "ChilloutMix",
            description = context.getString(R.string.chilloutmix_description),
            baseUrl = baseUrl,
            files = files,
            approximateSize = "1.2GB",
            isDownloaded = fullyDownloaded,
            isPartiallyDownloaded = partiallyDownloaded,
            defaultPrompt = "RAW photo, best quality, realistic, photo-realistic, masterpiece, 1girl, upper body, facing front, portrait,",
            defaultNegativePrompt = "paintings, sketches, worst quality, low quality, normal quality, lowres, monochrome, grayscale, skin spots, acnes, skin blemishes, age spot, bad anatomy, bad hands, bad body, bad proportions, gross proportions, extra fingers, fewer fingers, extra digit, missing fingers, fused fingers, extra arms, missing arms, extra legs, missing legs, extra limbs, mutated hands, poorly drawn hands, poorly drawn face, mutation, deformed, blurry, watermark, white letters, signature, text, error, jpeg artifacts, duplicate, morbid, mutilated, cross-eyed, long neck, ng_deepnegative_v1_75t, easynegative, bad-picture-chill-75v, bad-artist",
            runOnCpu = true
        )
    }

    private fun createSD21Model(): Model {
        val id = "sd21"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val files = listOf(
            ModelFile("tokenizer.json", "tokenizer", "xororz/SD21/resolve/main/tokenizer.json"),
            ModelFile(
                "clip.bin",
                "clip",
                "xororz/SD21/resolve/main/clip_${suffix}.bin"
            ),
            ModelFile(
                "vae_encoder.bin",
                "vae_encoder",
                "xororz/AnythingV5/resolve/main/vae_encoder_${suffix}.bin"
            ),
            ModelFile(
                "vae_decoder.bin",
                "vae_decoder",
                "xororz/SD21/resolve/main/vae_decoder_${suffix}.bin"
            ),
            ModelFile(
                "unet.bin",
                "unet",
                "xororz/SD21/resolve/main/unet_${suffix}.bin"
            )
        )

        val (fullyDownloaded, partiallyDownloaded) = Model.checkModelDownloadStatus(
            context,
            id,
            files,
            false
        )

        return Model(
            id = id,
            name = "Stable Diffusion 2.1",
            description = context.getString(R.string.sd21_description),
            baseUrl = baseUrl,
            files = files,
            textEmbeddingSize = 1024,
            approximateSize = "1.3GB",
            isDownloaded = fullyDownloaded,
            isPartiallyDownloaded = partiallyDownloaded,
            defaultPrompt = "a rabbit on grass,",
            defaultNegativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra digit, fewer fingers, cropped, worst quality, low quality, blur, simple background, mutation, deformed, ugly, duplicate, error, jpeg artifacts, watermark, username, blurry"
        )
    }

    fun refreshModelState(modelId: String) {
        models = models.map { model ->
            if (model.id == modelId) {
                val (fullyDownloaded, partiallyDownloaded) = Model.checkModelDownloadStatus(
                    context,
                    modelId,
                    model.files,
                    model.isCustom
                )
                model.copy(
                    isDownloaded = fullyDownloaded,
                    isPartiallyDownloaded = partiallyDownloaded
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