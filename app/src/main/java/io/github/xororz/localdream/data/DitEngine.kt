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

    // SM8750 and newer use native FP8. SM8650 uses the v75 compatibility path.
    private const val FIRST_SUPPORTED_PART_NUMBER = 8750

    internal fun isSm8650Soc(socModel: String, apiLevel: Int): Boolean {
        if (apiLevel < Build.VERSION_CODES.S) return false
        val soc = socModel.trim().uppercase()
        return soc == "SM8650" || soc == "SM8650P"
    }

    internal fun isSupportedSoc(socModel: String, apiLevel: Int): Boolean {
        if (apiLevel < Build.VERSION_CODES.S) return false
        if (isSm8650Soc(socModel, apiLevel)) return true
        val soc = socModel.uppercase()
        if (!soc.startsWith("SM")) return false
        val partNumber = soc.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }.toIntOrNull()
        return partNumber != null && partNumber >= FIRST_SUPPORTED_PART_NUMBER
    }

    fun isSupportedDevice(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        isSupportedSoc(Build.SOC_MODEL, Build.VERSION.SDK_INT)

    internal fun isSm8650Device(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        isSm8650Soc(Build.SOC_MODEL, Build.VERSION.SDK_INT)

    /** Directory holding the engine, i.e. the app's native library directory. */
    fun dir(context: Context): File = File(context.applicationInfo.nativeLibraryDir)

    fun isInstalled(context: Context): Boolean = File(dir(context), ENGINE_LIB).exists()
}
