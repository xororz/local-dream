package io.github.xororz.localdream.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.xororz.localdream.BuildConfig
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.DownloadProgress
import io.github.xororz.localdream.data.RemoteRepository
import io.github.xororz.localdream.data.UpscalerRepository
import io.github.xororz.localdream.navigation.popBackStackIfResumed
import io.github.xororz.localdream.remote.RemoteProtocol
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.service.BackgroundGenerationService
import io.github.xororz.localdream.service.ModelDownloadService
import io.github.xororz.localdream.ui.components.BlockingProgressOverlay
import io.github.xororz.localdream.ui.components.SmoothCircularWavyProgressIndicator
import io.github.xororz.localdream.ui.theme.Motion
import io.github.xororz.localdream.utils.UPSCALER_NATIVE_SCALE
import io.github.xororz.localdream.utils.performUpscale
import io.github.xororz.localdream.utils.saveImage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpscaleScreen(navController: NavController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelId = "upscaler_standalone"

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var upscaledImageUri by remember { mutableStateOf<Uri?>(null) }
    var upscaledBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isUpscaling by remember { mutableStateOf(false) }
    var backendProcess by remember { mutableStateOf<Process?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var backendLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentLog by remember { mutableStateOf("") }
    var tileProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val tileRegex = remember { Regex("""Processed tile (\d+)/(\d+)""") }

    var sharedScale by remember { mutableFloatStateOf(1f) }
    var sharedOffsetX by remember { mutableFloatStateOf(0f) }
    var sharedOffsetY by remember { mutableFloatStateOf(0f) }

    var showUpscalerDialog by remember { mutableStateOf(false) }
    val upscalerRepository = remember { UpscalerRepository.getInstance(context) }
    val remoteRepository = remember { RemoteRepository.getInstance(context) }
    // Connected-device mode snapshot at entry: the host runs its native
    // backend in upscaler mode and does the actual work; this device never
    // spawns a local process. restore() first so a process-recreated entry
    // doesn't misread a saved connection as local mode.
    val isRemote = remember {
        remoteRepository.restore()
        remoteRepository.isActive
    }
    val remoteClient = remember { if (isRemote) remoteRepository.client() else null }
    val backendHost = remoteClient?.generationHost
        ?: BackgroundGenerationService.LOCAL_BACKEND_HOST
    val availableUpscalers =
        if (isRemote) remoteRepository.remoteUpscalers() else upscalerRepository.upscalers
    LaunchedEffect(Unit) {
        if (!isRemote) upscalerRepository.ensureLoaded()
    }
    val upscalerPreferences =
        remember { context.getSharedPreferences("upscaler_prefs", Context.MODE_PRIVATE) }

    // String resources hoisted to composable scope (lint: LocalContextGetResourceValueCall).
    val msgImageResolutionTooLarge = stringResource(R.string.image_resolution_too_large)
    val msgFailedToLoadImage = stringResource(R.string.failed_to_load_image)
    val msgImageSaved = stringResource(R.string.image_saved)
    val msgDownloadDone = stringResource(R.string.download_done)
    val msgErrorDownloadFailed = stringResource(R.string.error_download_failed)
    val msgUpscaleFailed = stringResource(R.string.upscale_failed)
    val msgDownloadModelFirst = stringResource(R.string.download_model_first)
    val msgExecutableNotFound = stringResource(R.string.executable_not_found)
    val msgFailedToStartBackend = stringResource(R.string.failed_to_start_backend)
    val msgUnknownError = stringResource(R.string.unknown_error)

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val bitmap = context.contentResolver.openInputStream(it)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }

                    if (bitmap != null) {
                        val totalPixels = bitmap.width.toLong() * bitmap.height.toLong()
                        val maxPixels = 2048L * 2048L
                        val enforceMaxPixels = BuildConfig.FLAVOR == "filter"

                        if (enforceMaxPixels && totalPixels > maxPixels) {
                            withContext(Dispatchers.Main) {
                                errorMessage = msgImageResolutionTooLarge.format(
                                    bitmap.width,
                                    bitmap.height,
                                )
                            }
                        } else {
                            selectedImageUri = it
                            selectedBitmap = bitmap
                            withContext(Dispatchers.Main) {
                                sharedScale = 1f
                                sharedOffsetX = 0f
                                sharedOffsetY = 0f
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("UpscaleScreen", "Failed to load image", e)
                    withContext(Dispatchers.Main) {
                        errorMessage = msgFailedToLoadImage.format(e.message ?: "")
                    }
                }
            }
        }
    }

    fun startUpscalerBackend() {
        if (backendProcess?.isAlive == true) {
            Log.d("UpscaleScreen", "Backend already running")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val runtimeDir = prepareRuntimeDir(context)
                val nativeDir = context.applicationInfo.nativeLibraryDir
                val executableFile = File(nativeDir, "libstable_diffusion_core.so")

                if (!executableFile.exists()) {
                    withContext(Dispatchers.Main) {
                        errorMessage = msgExecutableNotFound.format(executableFile.absolutePath)
                    }
                    return@launch
                }

                val listenOnAll = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    .getBoolean("listen_on_all_addresses", false)
                var command = listOf(
                    executableFile.absolutePath,
                    "--upscaler_mode",
                    "--lib_dir",
                    runtimeDir.absolutePath,
                    "--port",
                    "8081",
                )
                if (listenOnAll) {
                    command = command + "--listen_all"
                }

                val env = mutableMapOf<String, String>()
                val systemLibPaths = mutableListOf(
                    runtimeDir.absolutePath,
                    "/system/lib64",
                    "/vendor/lib64",
                    "/vendor/lib64/egl",
                )

                try {
                    val maliSymlink = File("/system/vendor/lib64/egl/libGLES_mali.so")
                    if (maliSymlink.exists()) {
                        val realPath = maliSymlink.canonicalPath
                        val soc = realPath.split("/").getOrNull(realPath.split("/").size - 2)
                        if (soc != null) {
                            val socPaths = listOf(
                                "/vendor/lib64/$soc",
                                "/vendor/lib64/egl/$soc",
                            )
                            socPaths.forEach { path ->
                                if (!systemLibPaths.contains(path)) {
                                    systemLibPaths.add(path)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("UpscaleScreen", "Failed to resolve Mali paths: ${e.message}")
                }

                env["LD_LIBRARY_PATH"] = systemLibPaths.joinToString(":")
                env["DSP_LIBRARY_PATH"] = runtimeDir.absolutePath

                Log.d("UpscaleScreen", "COMMAND: ${command.joinToString(" ")}")
                Log.d("UpscaleScreen", "LD_LIBRARY_PATH=${env["LD_LIBRARY_PATH"]}")

                val processBuilder = ProcessBuilder(command).apply {
                    directory(File(nativeDir))
                    redirectErrorStream(true)
                    environment().putAll(env)
                }

                val proc = processBuilder.start()
                backendProcess = proc

                Thread {
                    try {
                        proc.inputStream.bufferedReader().use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val logLine = line!!
                                Log.i("UpscaleBackend", "Backend: $logLine")
                                scope.launch(Dispatchers.Main) {
                                    backendLogs = (backendLogs + logLine).takeLast(50)
                                    if (isUpscaling && logLine.startsWith("Process")) {
                                        currentLog = logLine
                                        tileRegex.find(logLine)?.let { match ->
                                            val current = match.groupValues[1].toIntOrNull()
                                            val total = match.groupValues[2].toIntOrNull()
                                            if (current != null && total != null && total > 0) {
                                                tileProgress = current to total
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val exitCode = proc.waitFor()
                        Log.i("UpscaleBackend", "Backend process exited with code: $exitCode")
                    } catch (e: InterruptedIOException) {
                        // Expected when stopUpscalerBackend() destroys the process.
                        Log.i("UpscaleBackend", "Monitor stopped: ${e.message}")
                    } catch (e: Exception) {
                        Log.e("UpscaleBackend", "Monitor error", e)
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            } catch (e: Exception) {
                Log.e("UpscaleScreen", "Failed to start backend", e)
                withContext(Dispatchers.Main) {
                    errorMessage = msgFailedToStartBackend.format(e.message ?: msgUnknownError)
                }
            }
        }
    }

    fun stopUpscalerBackend() {
        val proc = backendProcess ?: return
        backendProcess = null
        // Called from onDispose on the main thread; waitFor must not block it.
        Thread {
            try {
                proc.destroy()
                if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
                Log.i("UpscaleScreen", "Backend stopped")
            } catch (e: Exception) {
                Log.e("UpscaleScreen", "Failed to stop backend", e)
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    LaunchedEffect(Unit) {
        launch(Dispatchers.IO) {
            try {
                context.cacheDir.listFiles { file ->
                    file.name.startsWith("upscaled_temp_") && file.name.endsWith(".jpg")
                }?.forEach { file ->
                    if (file.delete()) {
                        Log.d("UpscaleScreen", "Deleted temp file: ${file.name}")
                    }
                }
            } catch (e: Exception) {
                Log.e("UpscaleScreen", "Failed to clean temp files", e)
            }
        }
        if (isRemote) {
            // Process recreation lands here before the list screen's catalog
            // refresh; without it the upscaler list (and paths) stay empty.
            if (remoteRepository.upscalerPaths.isEmpty()) {
                remoteRepository.refresh()
            }
            remoteClient?.let { client ->
                client.selectModel(RemoteProtocol.UPSCALER_MODEL_ID, 512, 512)
                // Wait until the host's upscaler process answers /health so an
                // immediate upscale doesn't hit a connection refused. Bounded;
                // on timeout the upscale itself will surface the error.
                repeat(30) {
                    if (client.checkGenerationHealth()) return@let
                    delay(1000)
                }
            }
        } else {
            startUpscalerBackend()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (isRemote) {
                remoteRepository.stopHostBackendAsync(RemoteProtocol.UPSCALER_MODEL_ID)
            } else {
                stopUpscalerBackend()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.image_upscale)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStackIfResumed() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (selectedImageUri == null) {
                                    Modifier.clickable { imagePickerLauncher.launch("image/*") }
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selectedImageUri == null) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(32.dp),
                            ) {
                                val iconAlpha = remember { Animatable(0.4f) }
                                LaunchedEffect(Unit) {
                                    iconAlpha.animateTo(
                                        targetValue = 0.8f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(1200),
                                            repeatMode = RepeatMode.Reverse,
                                        ),
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = stringResource(R.string.add_image),
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = iconAlpha.value),
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    stringResource(R.string.click_to_add_image),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            ZoomableImage(
                                imageUri = selectedImageUri,
                                contentDescription = stringResource(R.string.selected_image),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                                scale = sharedScale,
                                offsetX = sharedOffsetX,
                                offsetY = sharedOffsetY,
                                onTransform = { newScale, newOffsetX, newOffsetY ->
                                    sharedScale = newScale
                                    sharedOffsetX = newOffsetX
                                    sharedOffsetY = newOffsetY
                                },
                                useOriginalSize = true,
                            )
                        }

                        if (selectedImageUri != null) {
                            FilledTonalIconButton(
                                onClick = {
                                    selectedImageUri = null
                                    selectedBitmap = null
                                    sharedScale = 1f
                                    sharedOffsetX = 0f
                                    sharedOffsetY = 0f
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.clear_image),
                                )
                            }
                        }

                        if (selectedBitmap != null) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    text = "${selectedBitmap!!.width} × ${selectedBitmap!!.height}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                val fabEnabled = selectedBitmap != null && !isUpscaling
                val fabContainerColor by animateColorAsState(
                    targetValue = if (fabEnabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    animationSpec = tween(Motion.DurationMedium),
                    label = "FabContainerColor",
                )
                val fabContentColor by animateColorAsState(
                    targetValue = if (fabEnabled) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                    animationSpec = tween(Motion.DurationMedium),
                    label = "FabContentColor",
                )
                FloatingActionButton(
                    onClick = {
                        if (fabEnabled) {
                            showUpscalerDialog = true
                        }
                    },
                    containerColor = fabContainerColor,
                    contentColor = fabContentColor,
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = stringResource(R.string.upscale),
                    )
                }

                AnimatedVisibility(
                    visible = upscaledImageUri != null,
                    enter = fadeIn(animationSpec = Motion.Fade) +
                        expandVertically(expandFrom = Alignment.Top, animationSpec = Motion.Expand),
                    exit = fadeOut(animationSpec = Motion.FadeOut) +
                        shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = Motion.Shrink),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxSize(),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            ZoomableImage(
                                imageUri = upscaledImageUri,
                                contentDescription = stringResource(R.string.upscaled_image),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                                scale = sharedScale,
                                offsetX = sharedOffsetX,
                                offsetY = sharedOffsetY,
                                onTransform = { newScale, newOffsetX, newOffsetY ->
                                    sharedScale = newScale
                                    sharedOffsetX = newOffsetX
                                    sharedOffsetY = newOffsetY
                                },
                                useOriginalSize = true,
                            )

                            FilledTonalIconButton(
                                onClick = {
                                    upscaledBitmap?.let { bitmap ->
                                        scope.launch {
                                            saveImage(
                                                context = context,
                                                bitmap = bitmap,
                                                onSuccess = {
                                                    Toast.makeText(
                                                        context,
                                                        msgImageSaved,
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                },
                                                onError = { error ->
                                                    errorMessage = error
                                                },
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = stringResource(R.string.save_image),
                                )
                            }

                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    text = "${upscaledBitmap!!.width} × ${upscaledBitmap!!.height}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
                if (upscaledImageUri == null) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            // Floating Error Message
            AnimatedVisibility(
                visible = errorMessage != null,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                errorMessage?.let { msg ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        onClick = { errorMessage = null },
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = msg,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }

        BlockingProgressOverlay(visible = isUpscaling) {
            val progress = tileProgress
            if (progress != null) {
                val (current, total) = progress
                val fraction = current.toFloat() / total
                SmoothCircularWavyProgressIndicator(
                    progress = fraction,
                    modifier = Modifier.size(72.dp),
                )
                Text(
                    text = "${(fraction * 100).toInt()}%  $current/$total",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                ContainedLoadingIndicator()
                if (currentLog.isNotEmpty()) {
                    Text(
                        text = currentLog,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }

    if (showUpscalerDialog) {
        var tempSelectedUpscalerId by remember {
            mutableStateOf(upscalerPreferences.getString("${modelId}_selected_upscaler", null))
        }
        var tempSelectedScale by remember {
            mutableStateOf(
                upscalerPreferences.getInt("${modelId}_upscale_scale", UPSCALER_NATIVE_SCALE),
            )
        }
        var downloadingUpscalerId by remember { mutableStateOf<String?>(null) }
        var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }

        LaunchedEffect(Unit) {
            ModelDownloadService.downloadState.collect { state ->
                when (state) {
                    is ModelDownloadService.DownloadState.Downloading -> {
                        val upscaler =
                            upscalerRepository.upscalers.find { it.id == state.modelId }
                        if (upscaler != null) {
                            downloadingUpscalerId = upscaler.id
                            downloadProgress = DownloadProgress(
                                progress = state.progress,
                                downloadedBytes = state.downloadedBytes,
                                totalBytes = state.totalBytes,
                            )
                        }
                    }

                    is ModelDownloadService.DownloadState.Success -> {
                        upscalerRepository.refreshUpscalerState(state.modelId)
                        downloadingUpscalerId = null
                        downloadProgress = null
                        Toast.makeText(
                            context,
                            msgDownloadDone,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }

                    is ModelDownloadService.DownloadState.Error -> {
                        downloadingUpscalerId = null
                        downloadProgress = null
                        Toast.makeText(
                            context,
                            msgErrorDownloadFailed.format(state.message),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }

                    is ModelDownloadService.DownloadState.Extracting -> {
                        val upscaler =
                            upscalerRepository.upscalers.find { it.id == state.modelId }
                        if (upscaler != null) {
                            downloadingUpscalerId = upscaler.id
                            downloadProgress = null // Indeterminate progress during extraction
                        }
                    }

                    is ModelDownloadService.DownloadState.Idle -> {
                        if (downloadingUpscalerId != null && downloadProgress == null) {
                            downloadingUpscalerId = null
                        }
                    }
                }
            }
        }

        UpscalerSelectDialog(
            upscalers = availableUpscalers,
            selectedUpscalerId = tempSelectedUpscalerId,
            selectedScale = tempSelectedScale,
            downloadingUpscalerId = downloadingUpscalerId,
            downloadProgress = downloadProgress,
            onDismiss = { showUpscalerDialog = false },
            onSelectUpscaler = { upscalerId ->
                tempSelectedUpscalerId = upscalerId
            },
            onSelectScale = { scale ->
                tempSelectedScale = scale
            },
            onConfirm = {
                val selectedUpscaler =
                    availableUpscalers.find { it.id == tempSelectedUpscalerId }
                if (selectedUpscaler != null && selectedUpscaler.isDownloaded) {
                    upscalerPreferences.edit {
                        putString("${modelId}_selected_upscaler", selectedUpscaler.id)
                        putInt("${modelId}_upscale_scale", tempSelectedScale)
                    }
                    showUpscalerDialog = false

                    val targetScale = tempSelectedScale
                    selectedBitmap?.let { bitmap ->
                        tileProgress = null
                        currentLog = ""
                        isUpscaling = true
                        scope.launch {
                            try {
                                val resultBitmap = performUpscale(
                                    context = context,
                                    bitmap = bitmap,
                                    upscalerId = selectedUpscaler.id,
                                    targetScale = targetScale,
                                    backendHost = backendHost,
                                    remoteUpscalerPath = if (isRemote) {
                                        remoteRepository.upscalerPaths[selectedUpscaler.id]
                                    } else {
                                        null
                                    },
                                )
                                upscaledBitmap = resultBitmap

                                resultBitmap.let { bmp ->
                                    withContext(Dispatchers.IO) {
                                        try {
                                            val usePng = bmp.hasAlpha()
                                            val tempFile = File(
                                                context.cacheDir,
                                                "upscaled_temp_${System.currentTimeMillis()}." +
                                                    if (usePng) "png" else "jpg",
                                            )
                                            FileOutputStream(tempFile).use { out ->
                                                bmp.compress(
                                                    if (usePng) {
                                                        Bitmap.CompressFormat.PNG
                                                    } else {
                                                        Bitmap.CompressFormat.JPEG
                                                    },
                                                    if (usePng) 100 else 95,
                                                    out,
                                                )
                                            }
                                            upscaledImageUri = Uri.fromFile(tempFile)
                                        } catch (e: Exception) {
                                            Log.e("UpscaleScreen", "Failed to save temp file", e)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    msgUpscaleFailed.format(e.message ?: "Unknown error"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } finally {
                                isUpscaling = false
                            }
                        }
                    }
                } else if (selectedUpscaler != null) {
                    Toast.makeText(
                        context,
                        msgDownloadModelFirst,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onDownload = { upscaler ->
                downloadingUpscalerId = upscaler.id
                downloadProgress = null
                upscaler.startDownload(context)
            },
        )
    }
}

sealed class BackendState {
    object Idle : BackendState()
    object Starting : BackendState()
    object Running : BackendState()
    data class Error(val message: String) : BackendState()
}

fun prepareRuntimeDir(context: Context): File {
    val runtimeDir = BackendService.prepareRuntimeDirRoot(context.filesDir)

    try {
        val qnnlibsAssets = context.assets.list("qnnlibs")
        qnnlibsAssets?.forEach { fileName ->
            val targetLib = File(runtimeDir, fileName)

            val needsCopy = !targetLib.exists() ||
                run {
                    val assetInputStream = context.assets.open("qnnlibs/$fileName")
                    val assetSize = assetInputStream.use { it.available().toLong() }
                    targetLib.length() != assetSize
                }

            if (needsCopy) {
                val assetInputStream = context.assets.open("qnnlibs/$fileName")
                assetInputStream.use { input ->
                    targetLib.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d("UpscaleScreen", "Copied $fileName from assets to runtime directory")
            }

            targetLib.setReadable(true, true)
            targetLib.setExecutable(true, true)
        }
    } catch (e: IOException) {
        Log.e("UpscaleScreen", "Failed to prepare QNN libraries from assets", e)
        throw RuntimeException("Failed to prepare QNN libraries from assets", e)
    }

    runtimeDir.setReadable(true, true)
    runtimeDir.setExecutable(true, true)

    return runtimeDir
}

// Longest-side cap for previewing high-res images. 4096 is the universal max GPU texture
// size, and 4096^2 * 4 = 67MB stays under the hardware Canvas ~100MB per-bitmap limit.
private const val MAX_DISPLAY_DIMENSION = 4096

@Composable
fun ZoomableImage(
    imageUri: Uri?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    onTransform: (scale: Float, offsetX: Float, offsetY: Float) -> Unit,
    useOriginalSize: Boolean = false,
) {
    val context = LocalContext.current

    var currentScale by remember { mutableFloatStateOf(1f) }
    var currentOffsetX by remember { mutableFloatStateOf(0f) }
    var currentOffsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(scale, offsetX, offsetY) {
        currentScale = scale
        currentOffsetX = offsetX
        currentOffsetY = offsetY
    }

    val imageRequest = remember(imageUri, useOriginalSize) {
        ImageRequest.Builder(context)
            .data(imageUri)
            .apply {
                if (useOriginalSize) {
                    // Cap the decoded preview to MAX_DISPLAY_DIMENSION on the long side.
                    // A hardware Canvas refuses to draw bitmaps over ~100MB
                    // (RecordingCanvas: "trying to draw too large bitmap"), and an upscaled
                    // result can easily exceed that (e.g. 5760x5760 = 132MB). The cap keeps
                    // the preview under the Canvas/GPU-texture limit while staying sharp under
                    // zoom; saving still uses the full-resolution bitmap.
                    size(MAX_DISPLAY_DIMENSION, MAX_DISPLAY_DIMENSION)
                    memoryCacheKey(imageUri.toString() + "_display")
                }
            }
            .build()
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, rotation ->
                    val newScale = (currentScale * zoom).coerceIn(1f, 5f)

                    val newOffsetX = currentOffsetX + pan.x
                    val newOffsetY = currentOffsetY + pan.y

                    currentScale = newScale
                    currentOffsetX = newOffsetX
                    currentOffsetY = newOffsetY

                    onTransform(newScale, newOffsetX, newOffsetY)
                }
            },
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = currentScale,
                    scaleY = currentScale,
                    translationX = currentOffsetX,
                    translationY = currentOffsetY,
                ),
            contentScale = ContentScale.Fit,
        )
    }
}
