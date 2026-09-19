package io.github.xororz.localdream.data

import android.util.Log
import androidx.compose.runtime.Immutable
import java.io.File
import kotlin.math.roundToInt
import org.json.JSONObject

/**
 * A partial set of default generation parameters. Null means "not specified
 * here, fall through to the next priority level".
 *
 * Used both for code-level defaults of built-in models and for the optional
 * config.json file inside a model directory (which imported zip models can
 * bundle to ship their own defaults).
 */
@Immutable
data class ModelConfig(
    val prompt: String? = null,
    val negativePrompt: String? = null,
    val steps: Float? = null,
    val cfg: Float? = null,
    val scheduler: String? = null,
    // Code-level only (not read from config.json): models whose img2img
    // semantics differ, like Klein's reference edit, pick their own default.
    val denoiseStrength: Float? = null,
) {
    /** Field-by-field merge: values from this win, [other] fills the nulls. */
    fun withFallback(other: ModelConfig): ModelConfig = ModelConfig(
        prompt = prompt ?: other.prompt,
        negativePrompt = negativePrompt ?: other.negativePrompt,
        steps = steps ?: other.steps,
        cfg = cfg ?: other.cfg,
        scheduler = scheduler ?: other.scheduler,
        denoiseStrength = denoiseStrength ?: other.denoiseStrength,
    )

    /** Fill any remaining nulls from the global defaults. */
    fun resolve(global: GenerationDefaults = GenerationDefaults.GLOBAL): GenerationDefaults = global.copy(
        prompt = prompt ?: global.prompt,
        negativePrompt = negativePrompt ?: global.negativePrompt,
        steps = steps ?: global.steps,
        cfg = cfg ?: global.cfg,
        scheduler = scheduler ?: global.scheduler,
        denoiseStrength = denoiseStrength ?: global.denoiseStrength,
    )

    companion object {
        private const val TAG = "ModelConfig"
        private const val FILE_NAME = "config.json"

        // Keep in sync with the scheduler options in ModelRunScreen.
        private val VALID_SCHEDULERS = setOf(
            "dpm", "dpm_karras",
            "dpm_sde", "dpm_sde_karras",
            "euler", "euler_karras",
            "euler_a", "euler_a_karras",
            "lcm",
        )

        // Keep in sync with the steps/cfg slider ranges in ModelRunScreen.
        private val STEPS_RANGE = 1f..50f
        private val CFG_RANGE = 1f..30f

        fun read(modelDir: File): ModelConfig? {
            val file = File(modelDir, FILE_NAME)
            if (!file.isFile) return null

            return try {
                val json = JSONObject(file.readText())
                ModelConfig(
                    prompt = json.optStringOrNull("default_prompt"),
                    negativePrompt = json.optStringOrNull("default_negative_prompt"),
                    steps = json.optFloatOrNull("default_steps")
                        ?.roundToInt()?.toFloat()?.coerceIn(STEPS_RANGE),
                    cfg = json.optFloatOrNull("default_cfg")?.coerceIn(CFG_RANGE),
                    scheduler = json.optStringOrNull("default_scheduler")?.let { value ->
                        value.takeIf { it in VALID_SCHEDULERS }.also {
                            if (it == null) Log.w(TAG, "ignore unknown scheduler '$value' in ${file.path}")
                        }
                    },
                )
            } catch (e: Exception) {
                Log.w(TAG, "failed to parse ${file.path}: ${e.message}")
                null
            }
        }

        private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) {
            optString(key).takeIf { it.isNotEmpty() }
        } else {
            null
        }

        private fun JSONObject.optFloatOrNull(key: String): Float? {
            val value = optDouble(key)
            return if (value.isNaN()) null else value.toFloat()
        }
    }
}
