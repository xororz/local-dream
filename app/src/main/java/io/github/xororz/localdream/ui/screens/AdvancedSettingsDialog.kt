package io.github.xororz.localdream.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.Resolution
import kotlin.math.roundToInt

/**
 * The generation-parameter dialog opened from the prompt page. Pure UI: every
 * state mutation is routed back through callbacks so the screen keeps ownership
 * of parameter state and persistence.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AdvancedSettingsDialog(
    isSdxl: Boolean,
    isDit: Boolean = false,
    runOnCpu: Boolean,
    useImg2img: Boolean,
    isRunning: Boolean,
    aspectRatio: String,
    availableResolutions: List<Resolution>,
    currentWidth: Int,
    currentHeight: Int,
    scheduler: String,
    steps: Float,
    cfg: Float,
    useOpenCL: Boolean,
    batchCounts: Int,
    denoiseStrength: Float,
    seed: String,
    returnedSeed: Long?,
    onAspectRatioSelected: (String) -> Unit,
    onCustomAspectRatioClick: () -> Unit,
    onResolutionSelected: (Resolution) -> Unit,
    onDitWidthChange: (Float) -> Unit = {},
    onDitHeightChange: (Float) -> Unit = {},
    onSchedulerChange: (String) -> Unit,
    onStepsChange: (Float) -> Unit,
    onCfgChange: (Float) -> Unit,
    onSizeChange: (Float) -> Unit,
    onCpuSelected: () -> Unit,
    onGpuSelected: () -> Unit,
    onBatchCountsChange: (Float) -> Unit,
    onDenoiseStrengthChange: (Float) -> Unit,
    onSeedChange: (String) -> Unit,
    onUseLastSeed: () -> Unit,
    onImportFromClipboard: () -> Unit,
    onShare: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.advanced_settings_title),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onImportFromClipboard) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = stringResource(R.string.import_from_clipboard),
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = stringResource(R.string.share),
                    )
                }
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                // On SDXL the aspect ratio is inpaint-based padding, so it
                // needs the VAE encoder that --no_img2img skips. DiT models get
                // width and height directly instead.
                if (isSdxl && useImg2img) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.aspect_ratio),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val presets = listOf("1:1", "3:4", "4:3")
                        val isCustom = aspectRatio !in presets
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(
                                ButtonGroupDefaults.ConnectedSpaceBetween,
                            ),
                        ) {
                            presets.forEachIndexed { index, ratio ->
                                val shapes = if (index == 0) {
                                    ButtonGroupDefaults.connectedLeadingButtonShapes()
                                } else {
                                    ButtonGroupDefaults.connectedMiddleButtonShapes()
                                }
                                ToggleButton(
                                    checked = aspectRatio == ratio,
                                    onCheckedChange = { checked ->
                                        if (checked) onAspectRatioSelected(ratio)
                                    },
                                    shapes = shapes,
                                    enabled = !isRunning,
                                ) {
                                    Text(ratio)
                                }
                            }
                            ToggleButton(
                                checked = isCustom,
                                onCheckedChange = { onCustomAspectRatioClick() },
                                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                                enabled = !isRunning,
                            ) {
                                Text(
                                    if (isCustom) {
                                        aspectRatio
                                    } else {
                                        stringResource(R.string.aspect_ratio_custom)
                                    },
                                )
                            }
                        }
                    }
                }
                if (isDit) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.image_size, currentWidth, currentHeight),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.width),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = currentWidth.toFloat(),
                            onValueChange = onDitWidthChange,
                            valueRange = DIT_MIN_SIZE.toFloat()..DIT_MAX_SIZE.toFloat(),
                            steps = DIT_SIZE_STEPS,
                            enabled = !isRunning,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.height),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = currentHeight.toFloat(),
                            onValueChange = onDitHeightChange,
                            valueRange = DIT_MIN_SIZE.toFloat()..DIT_MAX_SIZE.toFloat(),
                            steps = DIT_SIZE_STEPS,
                            enabled = !isRunning,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (!runOnCpu && !isSdxl && !isDit && availableResolutions.isNotEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.resolution),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(
                                ButtonGroupDefaults.ConnectedSpaceBetween,
                            ),
                        ) {
                            availableResolutions.forEachIndexed { index, resolution ->
                                val shapes = when (index) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()

                                    availableResolutions.lastIndex ->
                                        ButtonGroupDefaults.connectedTrailingButtonShapes()

                                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                }
                                ToggleButton(
                                    checked = currentWidth == resolution.width &&
                                        currentHeight == resolution.height,
                                    onCheckedChange = { checked ->
                                        if (checked) onResolutionSelected(resolution)
                                    },
                                    shapes = shapes,
                                    enabled = !isRunning,
                                ) {
                                    Text(resolution.toString())
                                }
                            }
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    // Split scheduler id into base + Karras flag so the UI
                    // can offer one base chip per family plus a single
                    // Karras switch, instead of listing every combination.
                    val baseId = scheduler.removeSuffix("_karras")
                    val karras = scheduler.endsWith("_karras")
                    val karrasSupported = baseId != "lcm"
                    val baseOptions = listOf(
                        "dpm" to "DPM++ 2M",
                        "dpm_sde" to "DPM++ 2M SDE",
                        "euler_a" to "Euler A",
                        "euler" to "Euler",
                        "lcm" to "LCM",
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.scheduler),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "Karras",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .alpha(if (karrasSupported) 1f else 0.4f),
                        )
                        CompositionLocalProvider(
                            LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
                        ) {
                            Switch(
                                checked = karras && karrasSupported,
                                enabled = karrasSupported,
                                onCheckedChange = { enable ->
                                    onSchedulerChange(
                                        if (enable) "${baseId}_karras" else baseId,
                                    )
                                },
                                modifier = Modifier.scale(0.8f),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(
                            ButtonGroupDefaults.ConnectedSpaceBetween,
                        ),
                    ) {
                        baseOptions.forEachIndexed { index, (id, label) ->
                            val shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()

                                baseOptions.lastIndex ->
                                    ButtonGroupDefaults.connectedTrailingButtonShapes()

                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            }
                            ToggleButton(
                                checked = baseId == id,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        val nextKarras = karras && id != "lcm"
                                        onSchedulerChange(
                                            if (nextKarras) "${id}_karras" else id,
                                        )
                                    }
                                },
                                shapes = shapes,
                            ) {
                                Text(label)
                            }
                        }
                    }
                }

                Column {
                    Text(
                        stringResource(R.string.steps, steps.roundToInt()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = steps,
                        onValueChange = onStepsChange,
                        valueRange = 1f..50f,
                        steps = 48,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Column {
                    Text(
                        "CFG Scale: %.1f".format(cfg),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = cfg,
                        onValueChange = onCfgChange,
                        valueRange = 1f..30f,
                        steps = 57,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (runOnCpu) {
                    Column {
                        Text(
                            stringResource(
                                R.string.image_size,
                                currentWidth,
                                currentHeight,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = currentWidth.toFloat(),
                            onValueChange = onSizeChange,
                            valueRange = 128f..512f,
                            steps = 5,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(
                            ButtonGroupDefaults.ConnectedSpaceBetween,
                        ),
                    ) {
                        Text(
                            "Runtime",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        ToggleButton(
                            checked = !useOpenCL,
                            onCheckedChange = { checked ->
                                if (checked) onCpuSelected()
                            },
                            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("CPU")
                        }
                        ToggleButton(
                            checked = useOpenCL,
                            onCheckedChange = { checked ->
                                if (checked) onGpuSelected()
                            },
                            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("GPU")
                        }
                    }
                }
                Column {
                    Text(
                        stringResource(R.string.batch_count, batchCounts),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = batchCounts.toFloat(),
                        onValueChange = onBatchCountsChange,
                        valueRange = 1f..10f,
                        steps = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (useImg2img) {
                    Column {
                        Text(
                            "[img2img]Denoise Strength: %.2f".format(denoiseStrength),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = denoiseStrength,
                            onValueChange = onDenoiseStrengthChange,
                            valueRange = 0f..1f,
                            steps = 99,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = seed,
                        onValueChange = onSeedChange,
                        label = { Text(stringResource(R.string.random_seed)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        trailingIcon = {
                            if (seed.isNotEmpty()) {
                                IconButton(onClick = { onSeedChange("") }) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "clear",
                                    )
                                }
                            }
                        },
                    )

                    if (returnedSeed != null) {
                        FilledTonalButton(
                            onClick = onUseLastSeed,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.use_last_seed),
                                modifier = Modifier
                                    .size(20.dp)
                                    .padding(end = 4.dp),
                            )
                            Text(
                                stringResource(
                                    R.string.use_last_seed,
                                    returnedSeed.toString(),
                                ),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = onReset,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.reset),
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 4.dp),
                    )
                    Text(stringResource(R.string.reset))
                }

                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
    )
}
