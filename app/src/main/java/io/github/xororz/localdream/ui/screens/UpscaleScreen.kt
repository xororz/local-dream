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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.DownloadProgress
import io.github.xororz.localdream.data.UpscalerRepository
import io.github.xororz.localdream.service.ModelDownloadService
import io.github.xororz.localdream.utils.performUpscale
import io.github.xororz.localdream.utils.saveImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import androidx.core.content.edit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpscaleScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
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

    var sharedScale by remember { mutableFloatStateOf(1f) }
    var sharedOffsetX by remember { mutableFloatStateOf(0f) }
    var sharedOffsetY by remember { mutableFloatStateOf(0f) }

    var showUpscalerDialog by remember { mutableStateOf(false) }
    val upscalerRepository = remember { UpscalerRepository(context) }
    val upscalerPreferences =
        remember { context.getSharedPreferences("upscaler_prefs", Context.MODE_PRIVATE) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
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

                        if (totalPixels > maxPixels) {
                            withContext(Dispatchers.Main) {
                                errorMessage = context.getString(
                                    R.string.image_resolution_too_large,
                                    bitmap.width,
                                    bitmap.height
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
                        errorMessage =
                            context.getString(R.string.failed_to_load_image, e.message ?: "")
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
                        errorMessage = "Executable file not found: ${executableFile.absolutePath}"
                    }
                    return@launch
                }

                val command = listOf(
                    executableFile.absolutePath,
                    "--upscaler_mode",
                    "--backend", File(runtimeDir, "libQnnHtp.so").absolutePath,
                    "--system_library", File(runtimeDir, "libQnnSystem.so").absolutePath,
                    "--port", "8081"
                )

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
                                "/vendor/lib64/egl/$soc"
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

                backendProcess = processBuilder.start()

                Thread {
                    try {
                        backendProcess?.inputStream?.bufferedReader()?.use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val logLine = line!!
                                Log.i("UpscaleBackend", "Backend: $logLine")
                                scope.launch(Dispatchers.Main) {
                                    backendLogs = (backendLogs + logLine).takeLast(50)
                                    if (isUpscaling && logLine.startsWith("Process")) {
                                        currentLog = logLine
                                    }
                                }
                            }
                        }
                        val exitCode = backendProcess?.waitFor()
                        Log.i("UpscaleBackend", "Backend process exited with code: $exitCode")
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
                    errorMessage = "Failed to start backend: ${e.message}"
                }
            }
        }
    }

    fun stopUpscalerBackend() {
        backendProcess?.let { proc ->
            try {
                proc.destroy()
                if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
                Log.i("UpscaleScreen", "Backend stopped")
            } catch (e: Exception) {
                Log.e("UpscaleScreen", "Failed to stop backend", e)
            } finally {
                backendProcess = null
            }
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
        startUpscalerBackend()
    }

    DisposableEffect(Unit) {
        onDispose {
            stopUpscalerBackend()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.image_upscale)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (selectedImageUri == null) {
                                    Modifier.clickable { imagePickerLauncher.launch("image/*") }
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedImageUri == null) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = stringResource(R.string.add_image),
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    stringResource(R.string.click_to_add_image),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
                                useOriginalSize = true
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
                                    .padding(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.clear_image)
                                )
                            }
                        }

                        if (selectedBitmap != null) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "${selectedBitmap!!.width} × ${selectedBitmap!!.height}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                FloatingActionButton(
                    onClick = {
                        if (selectedBitmap != null && !isUpscaling) {
                            showUpscalerDialog = true
                        }
                    },
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoFixHigh,
                        contentDescription = stringResource(R.string.upscale),
                        modifier = Modifier.size(24.dp)
                    )
                }

                if (upscaledImageUri != null) {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = RoundedCornerShape(12.dp)
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
                                useOriginalSize = true
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
                                                        context.getString(R.string.image_saved),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                },
                                                onError = { error ->
                                                    errorMessage = error
                                                }
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = stringResource(R.string.save_image)
                                )
                            }

                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "${upscaledBitmap!!.width} × ${upscaledBitmap!!.height}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                } else {
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
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                errorMessage?.let { msg ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        onClick = { errorMessage = null }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = msg,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }

        if (isUpscaling) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp)
                    )

                    Text(
                        text = currentLog,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            }
        }
    }

    if (showUpscalerDialog) {
        var tempSelectedUpscalerId by remember {
            mutableStateOf(upscalerPreferences.getString("${modelId}_selected_upscaler", null))
        }
        var downloadingUpscalerId by remember { mutableStateOf<String?>(null) }
        var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }

        val downloadState by ModelDownloadService.downloadState.collectAsState()

        LaunchedEffect(downloadState) {
            when (val state = downloadState) {
                is ModelDownloadService.DownloadState.Downloading -> {
                    val upscaler = upscalerRepository.upscalers.find { it.id == state.modelId }
                    if (upscaler != null) {
                        downloadingUpscalerId = upscaler.id
                        downloadProgress = DownloadProgress(
                            progress = state.progress,
                            downloadedBytes = state.downloadedBytes,
                            totalBytes = state.totalBytes
                        )
                    }
                }

                is ModelDownloadService.DownloadState.Success -> {
                    upscalerRepository.refreshUpscalerState(state.modelId)
                    downloadingUpscalerId = null
                    downloadProgress = null
                    Toast.makeText(
                        context,
                        context.getString(R.string.download_done),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                is ModelDownloadService.DownloadState.Error -> {
                    downloadingUpscalerId = null
                    downloadProgress = null
                    Toast.makeText(
                        context,
                        context.getString(R.string.error_download_failed, state.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                is ModelDownloadService.DownloadState.Extracting -> {
                    val upscaler = upscalerRepository.upscalers.find { it.id == state.modelId }
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

        UpscalerSelectDialog(
            upscalers = upscalerRepository.upscalers,
            selectedUpscalerId = tempSelectedUpscalerId,
            downloadingUpscalerId = downloadingUpscalerId,
            downloadProgress = downloadProgress,
            onDismiss = { showUpscalerDialog = false },
            onSelectUpscaler = { upscalerId ->
                tempSelectedUpscalerId = upscalerId
            },
            onConfirm = {
                val selectedUpscaler =
                    upscalerRepository.upscalers.find { it.id == tempSelectedUpscalerId }
                if (selectedUpscaler != null && selectedUpscaler.isDownloaded) {
                    upscalerPreferences.edit {
                        putString("${modelId}_selected_upscaler", selectedUpscaler.id)
                    }
                    showUpscalerDialog = false

                    selectedBitmap?.let { bitmap ->
                        isUpscaling = true
                        scope.launch {
                            try {
                                val resultBitmap = performUpscale(
                                    context = context,
                                    bitmap = bitmap,
                                    modelId = modelId,
                                    upscalerId = selectedUpscaler.id
                                )
                                upscaledBitmap = resultBitmap

                                resultBitmap.let { bmp ->
                                    withContext(Dispatchers.IO) {
                                        try {
                                            val tempFile = File(
                                                context.cacheDir,
                                                "upscaled_temp_${System.currentTimeMillis()}.jpg"
                                            )
                                            FileOutputStream(tempFile).use { out ->
                                                bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
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
                                    context.getString(
                                        R.string.upscale_failed,
                                        e.message ?: "Unknown error"
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } finally {
                                isUpscaling = false
                            }
                        }
                    }
                } else if (selectedUpscaler != null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.download_model_first),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDownload = { upscaler ->
                downloadingUpscalerId = upscaler.id
                downloadProgress = null
                upscaler.startDownload(context)
            }
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
    val runtimeDir = File(context.filesDir, "runtime_libs").apply {
        if (!exists()) {
            mkdirs()
        }
    }

    try {
        val qnnlibsAssets = context.assets.list("qnnlibs")
        qnnlibsAssets?.forEach { fileName ->
            val targetLib = File(runtimeDir, fileName)

            val needsCopy = !targetLib.exists() || run {
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

@Composable
fun ZoomableImage(
    imageUri: Uri?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    onTransform: (scale: Float, offsetX: Float, offsetY: Float) -> Unit,
    useOriginalSize: Boolean = false
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
                    size(Size.ORIGINAL)
                    memoryCacheKey(imageUri.toString() + "_original")
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
            }
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
                    translationY = currentOffsetY
                ),
            contentScale = ContentScale.Fit
        )
    }
}