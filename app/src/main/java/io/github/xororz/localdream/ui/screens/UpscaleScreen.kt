package io.github.xororz.localdream.ui.screens

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.DownloadProgress
import io.github.xororz.localdream.data.DownloadResult
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.data.UpscalerRepository
import io.github.xororz.localdream.utils.performUpscale
import io.github.xororz.localdream.utils.saveImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

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
    var backendState by remember { mutableStateOf<BackendState>(BackendState.Idle) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var backendLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentLog by remember { mutableStateOf("") }

    var showUpscalerDialog by remember { mutableStateOf(false) }
    val upscalerRepository = remember { UpscalerRepository(context) }
    val upscalerPreferences =
        remember { context.getSharedPreferences("upscaler_prefs", Context.MODE_PRIVATE) }
    var selectedUpscalerId by remember {
        mutableStateOf(upscalerPreferences.getString("selected_upscaler_standalone", null))
    }

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

        backendState = BackendState.Starting
        scope.launch(Dispatchers.IO) {
            try {
                val runtimeDir = prepareRuntimeDir(context)
                val nativeDir = context.applicationInfo.nativeLibraryDir
                val executableFile = File(nativeDir, "libstable_diffusion_core.so")

                if (!executableFile.exists()) {
                    withContext(Dispatchers.Main) {
                        backendState = BackendState.Error("Executable file not found")
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
                        scope.launch(Dispatchers.Main) {
                            backendState = BackendState.Error("Backend process exited: $exitCode")
                        }
                    } catch (e: Exception) {
                        Log.e("UpscaleBackend", "Monitor error", e)
                    }
                }.apply {
                    isDaemon = true
                    start()
                }

                withContext(Dispatchers.Main) {
                    backendState = BackendState.Running
                }

            } catch (e: Exception) {
                Log.e("UpscaleScreen", "Failed to start backend", e)
                withContext(Dispatchers.Main) {
                    backendState = BackendState.Error("Failed to start backend: ${e.message}")
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
                backendState = BackendState.Idle
            } catch (e: Exception) {
                Log.e("UpscaleScreen", "Failed to stop backend", e)
            } finally {
                backendProcess = null
            }
        }
    }

    LaunchedEffect(Unit) {
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
        },
        snackbarHost = {
            errorMessage?.let { message ->
                Snackbar(
                    action = {
                        TextButton(onClick = { errorMessage = null }) {
                            Text(stringResource(R.string.close))
                        }
                    }
                ) {
                    Text(message)
                }
            }
        }
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            val availableHeight = this.maxHeight
            val imageBoxHeight = (availableHeight - 80.dp) / 2

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(imageBoxHeight)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(12.dp)
                            )
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
                            AsyncImage(
                                model = selectedImageUri,
                                contentDescription = stringResource(R.string.selected_image),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                                contentScale = ContentScale.Fit
                            )
                        }

                        if (selectedImageUri != null) {
                            FilledTonalIconButton(
                                onClick = {
                                    selectedImageUri = null
                                    selectedBitmap = null
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
                    }

                    if (selectedBitmap != null) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .offset(y = 24.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "${selectedBitmap!!.width} × ${selectedBitmap!!.height}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

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

                Spacer(modifier = Modifier.height(12.dp))

                if (upscaledImageUri != null) {
                    Box(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(imageBoxHeight)
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                        ) {
                            AsyncImage(
                                model = upscaledImageUri,
                                contentDescription = stringResource(R.string.upscaled_image),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                                contentScale = ContentScale.Fit
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
                        }

                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(y = (-24).dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "${upscaledBitmap!!.width} × ${upscaledBitmap!!.height}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
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
                    upscalerPreferences.edit()
                        .putString("${modelId}_selected_upscaler", selectedUpscaler.id).apply()
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

                                resultBitmap?.let { bmp ->
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
                } else if (selectedUpscaler != null && !selectedUpscaler.isDownloaded) {
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
                scope.launch {
                    upscalerRepository.downloadUpscaler(upscaler).collect { result ->
                        when (result) {
                            is DownloadResult.Progress -> {
                                downloadProgress = result.progress
                            }

                            is DownloadResult.Success -> {
                                downloadingUpscalerId = null
                                downloadProgress = null
                                upscalerRepository.refreshUpscalerState(upscaler.id)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.download_done),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }

                            is DownloadResult.Error -> {
                                downloadingUpscalerId = null
                                downloadProgress = null
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.error_download_failed,
                                        result.message
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
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
