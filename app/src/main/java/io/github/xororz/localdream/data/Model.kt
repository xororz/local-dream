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
    "QCS8550" to "8gen2",
    "QCM8550" to "8gen2",
    "SM8650" to "8gen3",
    "SM8750" to "8gen4",
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
    val defaultPrompt: String = "",
    val defaultNegativePrompt: String = "",
    val runOnCpu: Boolean = false,
    val useCpuClip: Boolean = false
) {
    fun download(context: Context): Flow<DownloadResult> = flow {
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
            modelDir.deleteRecursively()
            emit(DownloadResult.Error(e.message ?: "Download failed"))
        }
    }.flowOn(Dispatchers.IO)

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
            return getDeviceSoc() in chipsetModelSuffixes
        }

        fun getModelsDir(context: Context): File {
            return File(context.filesDir, MODELS_DIR).apply {
                if (!exists()) mkdirs()
            }
        }

        fun checkModelExists(context: Context, modelId: String, files: List<ModelFile>): Boolean {
            val modelDir = File(getModelsDir(context), modelId)
            val fileVerification = FileVerification(context)

            return runBlocking {
                files.all { modelFile ->
                    val file = File(modelDir, modelFile.name)
                    if (!file.exists()) return@all false

                    val savedSize = fileVerification.getFileSize(modelId, modelFile.name)
                    savedSize != null && file.length() == savedSize
                }
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

    private fun initializeModels(): List<Model> {
        if (BuildConfig.FLAVOR == "filter") {
            return listOf(
                createAnythingV5Model(),
                createAnythingV5ModelCPU(),
                createChilloutMixModelCPU(),
                createChilloutMixModel(),
                createAbsoluteRealityModel(),
                createAbsoluteRealityModelCPU(),
                createSD21Model(),
            )
        }
        return listOf(
            createAnythingV5Model(),
            createAnythingV5ModelCPU(),
            createChilloutMixModelCPU(),
            createChilloutMixModel(),
            createAbsoluteRealityModel(),
            createAbsoluteRealityModelCPU(),
            createPonyV55Model(),
            createSD21Model(),
        )
    }

    private fun createAnythingV5Model(): Model {
        val id = "anythingv5"
        val soc = getDeviceSoc()
        return Model(
            id = id,
            name = "Anything V5.0",
            description = context.getString(R.string.anythingv5_description),
            baseUrl = baseUrl,
            files = listOf(
                ModelFile(
                    "tokenizer.json",
                    "tokenizer",
                    "xororz/AnythingV5/resolve/main/tokenizer.json"
                ),
                ModelFile(
                    "clip.mnn",
                    "clip",
                    "xororz/AnythingV5/resolve/main/clip_fp16.mnn"
                ),
                ModelFile(
                    "vae_encoder.bin",
                    "vae_encoder",
                    "xororz/AnythingV5/resolve/main/vae_encoder_${chipsetModelSuffixes[soc]}.bin"
                ),
                ModelFile(
                    "vae_decoder.bin",
                    "vae_decoder",
                    "xororz/AnythingV5/resolve/main/vae_decoder_${chipsetModelSuffixes[soc]}.bin"
                ),
                ModelFile(
                    "unet.bin",
                    "unet",
                    "xororz/AnythingV5/resolve/main/unet_${chipsetModelSuffixes[soc]}.bin"
                )
            ),
            approximateSize = "1.1GB",
            isDownloaded = Model.checkModelExists(
                context,
                id,
                listOf(
                    ModelFile("tokenizer.json", "", ""),
                    ModelFile("clip.mnn", "", ""),
                    ModelFile("vae_decoder.bin", "", ""),
                    ModelFile("vae_encoder.bin", "", ""),
                    ModelFile("unet.bin", "", "")
                )
            ),
            defaultPrompt = "masterpiece, best quality, 1girl, solo, cute, white hair,",
            defaultNegativePrompt = "bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            useCpuClip = true
        )
    }

    private fun createAnythingV5ModelCPU(): Model {
        val id = "anythingv5cpu"
        return Model(
            id = id,
            name = "Anything V5.0",
            description = context.getString(R.string.anythingv5_description),
            baseUrl = baseUrl,
            files = listOf(
                ModelFile(
                    "tokenizer.json",
                    "tokenizer",
                    "xororz/AnythingV5/resolve/main/tokenizer.json"
                ),
                ModelFile("clip.mnn", "clip", "xororz/AnythingV5/resolve/main/clip_fp16.mnn"),
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
            ),
            approximateSize = "1.2GB",
            isDownloaded = Model.checkModelExists(
                context,
                id,
                listOf(
                    ModelFile("tokenizer.json", "", ""),
                    ModelFile("clip.mnn", "", ""),
                    ModelFile("vae_encoder.mnn", "", ""),
                    ModelFile("vae_decoder.mnn", "", ""),
                    ModelFile("unet.mnn", "", "")
                )
            ),
            defaultPrompt = "masterpiece, best quality, 1girl, solo, cute, white hair,",
            defaultNegativePrompt = "bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            runOnCpu = true
        )
    }

    private fun createAbsoluteRealityModel(): Model {
        val id = "absolutereality"
        val soc = getDeviceSoc()
        return Model(
            id = id,
            name = "Absolute Reality",
            description = context.getString(R.string.absolutereality_description),
            baseUrl = baseUrl,
            files = listOf(
                ModelFile(
                    "tokenizer.json",
                    "tokenizer",
                    "xororz/AbsoluteReality/resolve/main/tokenizer.json"
                ),
                ModelFile("clip.mnn", "clip", "xororz/AbsoluteReality/resolve/main/clip_fp16.mnn"),
                ModelFile(
                    "vae_encoder.bin",
                    "vae_encoder",
                    "xororz/AnythingV5/resolve/main/vae_encoder_${chipsetModelSuffixes[soc]}.bin"
                ),
                ModelFile(
                    "vae_decoder.bin",
                    "vae_decoder",
                    "xororz/AbsoluteReality/resolve/main/vae_decoder_${chipsetModelSuffixes[soc]}.bin"
                ),
                ModelFile(
                    "unet.bin",
                    "unet",
                    "xororz/AbsoluteReality/resolve/main/unet_${chipsetModelSuffixes[soc]}.bin"
                )
            ),
            approximateSize = "1.1GB",
            isDownloaded = Model.checkModelExists(
                context,
                id,
                listOf(
                    ModelFile("tokenizer.json", "", ""),
                    ModelFile("clip.mnn", "", ""),
                    ModelFile("vae_encoder.bin", "", ""),
                    ModelFile("vae_decoder.bin", "", ""),
                    ModelFile("unet.bin", "", "")
                )
            ),
            defaultPrompt = "masterpiece, best quality, ultra-detailed, realistic, 8k, a cat on grass,",
            defaultNegativePrompt = "Realisian-Neg, BadDream, negative_hand, NegfeetV2, worst quality, low quality, normal quality, poorly drawn, lowres, low resolution, signature, watermarks, ugly, out of focus, error, blurry, unclear photo, bad photo, unrealistic, semi realistic, pixelated, cartoon, anime, cgi, drawing, 2d, 3d, censored, duplicate,",
            runOnCpu = false,
            useCpuClip = true
        )
    }

    private fun createAbsoluteRealityModelCPU(): Model {
        val id = "absoluterealitycpu"
        return Model(
            id = id,
            name = "Absolute Reality",
            description = context.getString(R.string.absolutereality_description),
            baseUrl = baseUrl,
            files = listOf(
                ModelFile(
                    "tokenizer.json",
                    "tokenizer",
                    "xororz/AbsoluteReality/resolve/main/tokenizer.json"
                ),
                ModelFile("clip.mnn", "clip", "xororz/AbsoluteReality/resolve/main/clip_fp16.mnn"),
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
            ),
            approximateSize = "1.2GB",
            isDownloaded = Model.checkModelExists(
                context,
                id,
                listOf(
                    ModelFile("tokenizer.json", "", ""),
                    ModelFile("clip.mnn", "", ""),
                    ModelFile("vae_encoder.mnn", "", ""),
                    ModelFile("vae_decoder.mnn", "", ""),
                    ModelFile("unet.mnn", "", "")
                )
            ),
            defaultPrompt = "masterpiece, best quality, ultra-detailed, realistic, 8k, a cat on grass,",
            defaultNegativePrompt = "Realisian-Neg, BadDream, negative_hand, NegfeetV2, worst quality, low quality, normal quality, poorly drawn, lowres, low resolution, signature, watermarks, ugly, out of focus, error, blurry, unclear photo, bad photo, unrealistic, semi realistic, pixelated, cartoon, anime, cgi, drawing, 2d, 3d, censored, duplicate,",
            runOnCpu = true
        )
    }

    private fun createChilloutMixModel(): Model {
        val id = "chilloutmix"
        val soc = getDeviceSoc()
        return Model(
            id = id,
            name = "ChilloutMix",
            description = context.getString(R.string.chilloutmix_description),
            baseUrl = baseUrl,
            files = listOf(
                ModelFile(
                    "tokenizer.json",
                    "tokenizer",
                    "xororz/ChilloutMix/resolve/main/tokenizer.json"
                ),
                ModelFile("clip.mnn", "clip", "xororz/ChilloutMix/resolve/main/clip_fp16.mnn"),
                ModelFile(
                    "vae_encoder.bin",
                    "vae_encoder",
                    "xororz/AnythingV5/resolve/main/vae_encoder_${chipsetModelSuffixes[soc]}.bin"
                ),
                ModelFile(
                    "vae_decoder.bin",
                    "vae_decoder",
                    "xororz/ChilloutMix/resolve/main/vae_decoder_${chipsetModelSuffixes[soc]}.bin"
                ),
                ModelFile(
                    "unet.bin",
                    "unet",
                    "xororz/ChilloutMix/resolve/main/unet_${chipsetModelSuffixes[soc]}.bin"
                )
            ),
            approximateSize = "1.1GB",
            isDownloaded = Model.checkModelExists(
                context,
                id,
                listOf(
                    ModelFile("tokenizer.json", "", ""),
                    ModelFile("clip.mnn", "", ""),
                    ModelFile("vae_encoder.bin", "", ""),
                    ModelFile("vae_decoder.bin", "", ""),
                    ModelFile("unet.bin", "", "")
                )
            ),
            defaultPrompt = "RAW photo, best quality, realistic, photo-realistic, masterpiece, extremely detailed, CG, unity, 8k wallpaper, ultra-detailed, highres, absurdres, 1girl, silver hair,",
            defaultNegativePrompt = "paintings, sketches, worst quality, low quality, normal quality, lowres, monochrome, grayscale, skin spots, acnes, skin blemishes, age spot, bad anatomy, bad hands, bad body, bad proportions, gross proportions, extra fingers, fewer fingers, extra digit, missing fingers, fused fingers, extra arms, missing arms, extra legs, missing legs, extra limbs, mutated hands, poorly drawn hands, poorly drawn face, mutation, deformed, blurry, watermark, white letters, signature, text, error, jpeg artifacts, duplicate, morbid, mutilated, cross-eyed, long neck, ng_deepnegative_v1_75t, easynegative, bad-picture-chill-75v, bad-artist",
            runOnCpu = false,
            useCpuClip = true,
        )
    }

    private fun createChilloutMixModelCPU(): Model {
        val id = "chilloutmixcpu"
        return Model(
            id = id,
            name = "ChilloutMix",
            description = context.getString(R.string.chilloutmix_description),
            baseUrl = baseUrl,
            files = listOf(
                ModelFile(
                    "tokenizer.json",
                    "tokenizer",
                    "xororz/ChilloutMix/resolve/main/tokenizer.json"
                ),
                ModelFile("clip.mnn", "clip", "xororz/ChilloutMix/resolve/main/clip_fp16.mnn"),
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
            ),
            approximateSize = "1.2GB",
            isDownloaded = Model.checkModelExists(
                context,
                id,
                listOf(
                    ModelFile("tokenizer.json", "", ""),
                    ModelFile("clip.mnn", "", ""),
                    ModelFile("vae_encoder.mnn", "", ""),
                    ModelFile("vae_decoder.mnn", "", ""),
                    ModelFile("unet.mnn", "", "")
                )
            ),
            defaultPrompt = "RAW photo, best quality, realistic, photo-realistic, masterpiece, extremely detailed, CG, unity, 8k wallpaper, ultra-detailed, highres, absurdres, 1girl, silver hair,",
            defaultNegativePrompt = "paintings, sketches, worst quality, low quality, normal quality, lowres, monochrome, grayscale, skin spots, acnes, skin blemishes, age spot, bad anatomy, bad hands, bad body, bad proportions, gross proportions, extra fingers, fewer fingers, extra digit, missing fingers, fused fingers, extra arms, missing arms, extra legs, missing legs, extra limbs, mutated hands, poorly drawn hands, poorly drawn face, mutation, deformed, blurry, watermark, white letters, signature, text, error, jpeg artifacts, duplicate, morbid, mutilated, cross-eyed, long neck, ng_deepnegative_v1_75t, easynegative, bad-picture-chill-75v, bad-artist",
            runOnCpu = true
        )
    }

    private fun createSD21Model(): Model {
        val id = "sd21"
        val soc = getDeviceSoc()
        return Model(
            id = id,
            name = "Stable Diffusion 2.1",
            description = context.getString(R.string.sd21_description),
            baseUrl = baseUrl,
            files = listOf(
                ModelFile("tokenizer.json", "tokenizer", "xororz/SD21/resolve/main/tokenizer.json"),
                ModelFile(
                    "clip.bin",
                    "clip",
                    "xororz/SD21/resolve/main/clip_${chipsetModelSuffixes[soc]}.bin"
                ),
                ModelFile(
                    "vae_encoder.bin",
                    "vae_encoder",
                    "xororz/AnythingV5/resolve/main/vae_encoder_${chipsetModelSuffixes[soc]}.bin"
                ),
                ModelFile(
                    "vae_decoder.bin",
                    "vae_decoder",
                    "xororz/SD21/resolve/main/vae_decoder_${chipsetModelSuffixes[soc]}.bin"
                ),
                ModelFile(
                    "unet.bin",
                    "unet",
                    "xororz/SD21/resolve/main/unet_${chipsetModelSuffixes[soc]}.bin"
                )
            ),
            textEmbeddingSize = 1024,
            approximateSize = "1.3GB",
            isDownloaded = Model.checkModelExists(
                context,
                id,
                listOf(
                    ModelFile("tokenizer.json", "", ""),
                    ModelFile("clip.bin", "", ""),
                    ModelFile("vae_encoder.bin", "", ""),
                    ModelFile("vae_decoder.bin", "", ""),
                    ModelFile("unet.bin", "", "")
                )
            ),
            defaultPrompt = "a rabbit on grass,",
            defaultNegativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra digit, fewer fingers, cropped, worst quality, low quality, blur, simple background, mutation, deformed, ugly, duplicate, error, jpeg artifacts, watermark, username, blurry"
        )
    }

    private fun createPonyV55Model(): Model {
        val id = "ponyv55_640"
        val soc = getDeviceSoc()
        return Model(
            id = id,
            name = "Pony V5.5",
            description = context.getString(R.string.ponyv55_description),
            baseUrl = baseUrl,
            files = listOf(
                ModelFile(
                    "tokenizer.json",
                    "tokenizer",
                    "xororz/PonyV55/resolve/main/tokenizer.json"
                ),
                ModelFile(
                    "clip.bin",
                    "clip",
                    "xororz/PonyV55/resolve/main/clip_${chipsetModelSuffixes[soc]}.bin"
                ),
                ModelFile(
                    "vae_encoder.bin",
                    "vae_encoder",
                    "xororz/PonyV55/resolve/main/vae_encoder_${chipsetModelSuffixes[soc]}.bin"
                ),
                ModelFile(
                    "vae_decoder.bin",
                    "vae_decoder",
                    "xororz/PonyV55/resolve/main/vae_decoder_${chipsetModelSuffixes[soc]}.bin"
                ),
                ModelFile(
                    "unet.bin",
                    "unet",
                    "xororz/PonyV55/resolve/main/unet_${chipsetModelSuffixes[soc]}.bin"
                )
            ),
            generationSize = 640,
            textEmbeddingSize = 1024,
            approximateSize = "1.3GB",
            isDownloaded = Model.checkModelExists(
                context,
                id,
                listOf(
                    ModelFile("tokenizer.json", "", ""),
                    ModelFile("clip.bin", "", ""),
                    ModelFile("vae_encoder.bin", "", ""),
                    ModelFile("vae_decoder.bin", "", ""),
                    ModelFile("unet.bin", "", "")
                )
            ),
            defaultPrompt = "score_9, feral pony princess cadance swimming in a beautiful lake, reflections, night, moon and stars, solo",
            defaultNegativePrompt = "lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, fewer digits, cropped, worst quality, low quality, jpeg artifacts, signature, watermark, username, blurry"
        )
    }

    fun refreshModelState(modelId: String) {
        models = models.map { model ->
            if (model.id == modelId) {
                model.copy(
                    isDownloaded = Model.checkModelExists(context, modelId, model.files)
                )
            } else {
                model
            }
        }
    }

    fun refreshAllModels() {
        models = models.map { model ->
            model.copy(
                isDownloaded = Model.checkModelExists(context, model.id, model.files)
            )
        }
    }
}