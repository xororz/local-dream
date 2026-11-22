package io.github.xororz.localdream.data

import android.content.Context
import android.os.Build
import java.io.File

/**
 * The DiT engine (libdit_engine.so) that runs Z-Image Turbo, FLUX.2/Klein and
 * Qwen Image 2.1.
 *
 * It ships inside the APK alongside the backend executable, so it lands in
 * nativeLibraryDir and the core dlopens it from there. Its FastRPC skels are
 * APK assets copied to the shared runtime directory by BackendService.
 */
object DitEngine {
    const val ENGINE_LIB = "libdit_engine.so"
    const val CONVROT_DIT = "dit-convrot.safetensors"

    private const val FIRST_SUPPORTED_PART_NUMBER = 8550
    private const val FIRST_V79_PART_NUMBER = 8750

    private fun partNumber(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0
        val soc = Build.SOC_MODEL.uppercase()
        if (!soc.startsWith("SM")) return 0
        return soc.drop(2).takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }

    fun isSupportedDevice(): Boolean = partNumber() >= FIRST_SUPPORTED_PART_NUMBER

    fun usesConvRotWeights(): Boolean = partNumber() in FIRST_SUPPORTED_PART_NUMBER until FIRST_V79_PART_NUMBER

    /** Directory holding the engine, i.e. the app's native library directory. */
    fun dir(context: Context): File = File(context.applicationInfo.nativeLibraryDir)

    fun isInstalled(context: Context): Boolean = File(dir(context), ENGINE_LIB).exists()
}
