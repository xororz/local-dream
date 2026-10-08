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

    // 8Gen3 (SM8650) test override: the optimized FP8 path was validated
    // from SM8750 onward; lowering to 8650 unlocks the DiT listing/scan/skel
    // copy on 8Gen3 so the DSP error (missing v75 skel) can be captured.
    // Revert after the v75 skel is proven, or keep if P0 passes.
    private const val FIRST_SUPPORTED_PART_NUMBER = 8650

    fun isSupportedDevice(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val soc = Build.SOC_MODEL.uppercase()
        if (!soc.startsWith("SM")) return false
        val partNumber = soc.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }.toIntOrNull()
        return partNumber != null && partNumber >= FIRST_SUPPORTED_PART_NUMBER
    }

    /** Directory holding the engine, i.e. the app's native library directory. */
    fun dir(context: Context): File = File(context.applicationInfo.nativeLibraryDir)

    fun isInstalled(context: Context): Boolean = File(dir(context), ENGINE_LIB).exists()
}
