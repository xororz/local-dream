package io.github.xororz.localdream.data

import kotlin.math.roundToInt

/** Resolutions verified to be stable with the optimized DiT HTP/VAE path. */
object DitResolution {
    const val MIN_SIZE = 512
    const val MAX_SIZE = 2048
    const val SIZE_STEP = 256
    const val SLIDER_STEPS = (MAX_SIZE - MIN_SIZE) / SIZE_STEP - 1

    fun snap(value: Float): Int = ((value / SIZE_STEP).roundToInt() * SIZE_STEP)
        .coerceIn(MIN_SIZE, MAX_SIZE)

    fun snap(value: Int): Int = snap(value.toFloat())

    fun isSupported(value: Int): Boolean = value in MIN_SIZE..MAX_SIZE &&
        (value - MIN_SIZE) % SIZE_STEP == 0
}
