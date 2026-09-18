package io.github.xororz.localdream.data

import android.content.Context
import android.os.Build
import java.io.File

/**
 * The DiT engine (libdit_engine.so) that runs Z-Image Turbo and FLUX.2/Klein 4B.
 *
 * It ships inside the APK alongside the backend executable, so it lands in
 * nativeLibraryDir and the core dlopens it from there. Its FastRPC skels are
 * APK assets copied to the shared runtime directory by BackendService.
 */
object DitEngine {
    const val ENGINE_LIB = "libdit_engine.so"

    // The optimized FP8 path has been validated from SM8750 onward. Comparing
    // the numeric part also admits newer SM-series chips without maintaining a
    // hard-coded allowlist; suffixes such as SM8750P naturally map to 8750.
    private const val FIRST_SUPPORTED_PART_NUMBER = 8750

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
