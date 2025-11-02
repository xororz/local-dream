package io.github.xororz.localdream.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "generation_prefs")

class GenerationPreferences(private val context: Context) {
    private fun getPromptKey(modelId: String) = stringPreferencesKey("${modelId}_prompt")
    private fun getNegativePromptKey(modelId: String) =
        stringPreferencesKey("${modelId}_negative_prompt")

    private fun getStepsKey(modelId: String) = floatPreferencesKey("${modelId}_steps")
    private fun getCfgKey(modelId: String) = floatPreferencesKey("${modelId}_cfg")
    private fun getSeedKey(modelId: String) = stringPreferencesKey("${modelId}_seed")
    private fun getSizeKey(modelId: String) = intPreferencesKey("${modelId}_size")
    private fun getDenoiseStrengthKey(modelId: String) =
        floatPreferencesKey("${modelId}_denoise_strength")

    private fun getUseOpenCLKey(modelId: String) = booleanPreferencesKey("${modelId}_use_opencl")

    private fun getBatchCountsKey(modelId: String) = intPreferencesKey("${modelId}_batch_counts")

    private val BASE_URL_KEY = stringPreferencesKey("base_url")
    private val SELECTED_SOURCE_KEY = stringPreferencesKey("selected_source")

    suspend fun saveBaseUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[BASE_URL_KEY] = url
        }
    }

    suspend fun getBaseUrl(): String {
        return context.dataStore.data
            .map { preferences ->
                preferences[BASE_URL_KEY] ?: "https://huggingface.co/"
            }.first()
    }

    suspend fun saveSelectedSource(source: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_SOURCE_KEY] = source
        }
    }

    suspend fun getSelectedSource(): String {
        return context.dataStore.data
            .map { preferences ->
                preferences[SELECTED_SOURCE_KEY] ?: "huggingface"
            }.first()
    }

    suspend fun saveAllFields(
        modelId: String,
        prompt: String,
        negativePrompt: String,
        steps: Float,
        cfg: Float,
        seed: String,
        size: Int,
        denoiseStrength: Float,
        useOpenCL: Boolean,
        batchCounts: Int
    ) {
        context.dataStore.edit { preferences ->
            preferences[getPromptKey(modelId)] = prompt
            preferences[getNegativePromptKey(modelId)] = negativePrompt
            preferences[getStepsKey(modelId)] = steps
            preferences[getCfgKey(modelId)] = cfg
            preferences[getSeedKey(modelId)] = seed
            preferences[getSizeKey(modelId)] = size
            preferences[getDenoiseStrengthKey(modelId)] = denoiseStrength
            preferences[getUseOpenCLKey(modelId)] = useOpenCL
            preferences[getBatchCountsKey(modelId)] = batchCounts
        }
    }

    suspend fun savePrompt(modelId: String, prompt: String) {
        context.dataStore.edit { preferences ->
            preferences[getPromptKey(modelId)] = prompt
        }
    }

    suspend fun saveNegativePrompt(modelId: String, negativePrompt: String) {
        context.dataStore.edit { preferences ->
            preferences[getNegativePromptKey(modelId)] = negativePrompt
        }
    }

    suspend fun saveSteps(modelId: String, steps: Float) {
        context.dataStore.edit { preferences ->
            preferences[getStepsKey(modelId)] = steps
        }
    }

    suspend fun saveCfg(modelId: String, cfg: Float) {
        context.dataStore.edit { preferences ->
            preferences[getCfgKey(modelId)] = cfg
        }
    }

    suspend fun saveSeed(modelId: String, seed: String) {
        context.dataStore.edit { preferences ->
            preferences[getSeedKey(modelId)] = seed
        }
    }

    suspend fun saveSize(modelId: String, size: Int) {
        context.dataStore.edit { preferences ->
            preferences[getSizeKey(modelId)] = size
        }
    }

    suspend fun saveDenoiseStrength(modelId: String, denoiseStrength: Float) {
        context.dataStore.edit { preferences ->
            preferences[getDenoiseStrengthKey(modelId)] = denoiseStrength
        }
    }

    suspend fun saveUseOpenCL(modelId: String, useOpenCL: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[getUseOpenCLKey(modelId)] = useOpenCL
        }
    }

    suspend fun saveBatchCounts(modelId: String, batchCounts: Int) {
        context.dataStore.edit { preferences ->
            preferences[getBatchCountsKey(modelId)] = batchCounts
        }
    }

    fun getPreferences(modelId: String): Flow<GenerationPrefs> {
        return context.dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                GenerationPrefs(
                    prompt = preferences[getPromptKey(modelId)] ?: "",
                    negativePrompt = preferences[getNegativePromptKey(modelId)] ?: "",
                    steps = preferences[getStepsKey(modelId)] ?: 20f,
                    cfg = preferences[getCfgKey(modelId)] ?: 7f,
                    seed = preferences[getSeedKey(modelId)] ?: "",
                    size = preferences[getSizeKey(modelId)] ?: 256,
                    denoiseStrength = preferences[getDenoiseStrengthKey(modelId)] ?: 0.6f,
                    useOpenCL = preferences[getUseOpenCLKey(modelId)] ?: false,
                    batchCounts = preferences[getBatchCountsKey(modelId)] ?: 1
                )
            }
    }

    suspend fun clearPreferencesForModel(modelId: String) {
        context.dataStore.edit { preferences ->
            preferences.remove(getPromptKey(modelId))
            preferences.remove(getNegativePromptKey(modelId))
            preferences.remove(getStepsKey(modelId))
            preferences.remove(getCfgKey(modelId))
            preferences.remove(getSeedKey(modelId))
            preferences.remove(getSizeKey(modelId))
            preferences.remove(getDenoiseStrengthKey(modelId))
            preferences.remove(getUseOpenCLKey(modelId))
            preferences.remove(getBatchCountsKey(modelId))
        }
    }
}

data class GenerationPrefs(
    val prompt: String = "",
    val negativePrompt: String = "",
    val steps: Float = 20f,
    val cfg: Float = 7f,
    val seed: String = "",
    val size: Int = 512,
    val denoiseStrength: Float = 0.6f,
    val useOpenCL: Boolean = false,
    val batchCounts: Int = 1
)