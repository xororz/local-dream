package io.github.xororz.localdream.navigation

sealed class Screen(val route: String) {
    object ModelList : Screen("model_list")
    object ModelRun : Screen("model_run/{modelId}?resolution={resolution}") {
        fun createRoute(modelId: String, resolution: Int? = null) =
            if (resolution != null) {
                "model_run/$modelId?resolution=$resolution"
            } else {
                "model_run/$modelId"
            }
    }
    object Upscale : Screen("upscale")
}