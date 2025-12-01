package io.github.xororz.localdream.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect as AndroidRect
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.moyuru.cropify.Cropify
import io.moyuru.cropify.CropifyOption
import io.moyuru.cropify.CropifySize
import io.moyuru.cropify.rememberCropifyState
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropImageScreen(
    imageUri: Uri,
    width: Int,
    height: Int,
    onCropComplete: (String, Bitmap, AndroidRect) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val cropifyState = rememberCropifyState()

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val aspectRatio = if (width > 0 && height > 0) {
        height.toFloat() / width.toFloat()
    } else {
        1f
    }

    val handleCroppedImage: (ImageBitmap) -> Unit = { bitmap ->
        coroutineScope.launch {
            isLoading = true
            try {
                val androidBitmap = bitmap.asAndroidBitmap()

                // Reflection to get frameRect and imageRect
                val frameRectField =
                    cropifyState::class.java.getDeclaredField("frameRect\$delegate")
                frameRectField.isAccessible = true
                val frameRectState =
                    frameRectField.get(cropifyState) as State<androidx.compose.ui.geometry.Rect>
                val frameRect = frameRectState.value

                val imageRectField =
                    cropifyState::class.java.getDeclaredField("imageRect\$delegate")
                imageRectField.isAccessible = true
                val imageRectState =
                    imageRectField.get(cropifyState) as State<androidx.compose.ui.geometry.Rect>
                val imageRect = imageRectState.value

                // Get original image dimensions
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(imageUri)!!.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
                val originalWidth = options.outWidth

                // Calculate crop rect in original image coordinates
                val scale = originalWidth.toFloat() / imageRect.width
                val cropX = ((frameRect.left - imageRect.left) * scale).toInt()
                val cropY = ((frameRect.top - imageRect.top) * scale).toInt()
                val cropWidth = (frameRect.width * scale).toInt()
                val cropHeight = (frameRect.height * scale).toInt()

                val cropRect = AndroidRect(cropX, cropY, cropX + cropWidth, cropY + cropHeight)

                val base64String = withContext(Dispatchers.IO) {
                    val byteArrayOutputStream = ByteArrayOutputStream()
                    androidBitmap.compress(Bitmap.CompressFormat.PNG, 90, byteArrayOutputStream)
                    val byteArray = byteArrayOutputStream.toByteArray()
                    Base64.getEncoder().encodeToString(byteArray)
                }
                onCropComplete(base64String, androidBitmap, cropRect)
            } catch (e: Exception) {
                errorMessage = "Error: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    BackHandler {
        onCancel()
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.crop_image)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { cropifyState.crop() }) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Crop"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Cropify(
                uri = imageUri,
                state = cropifyState,
                onImageCropped = handleCroppedImage,
                onFailedToLoadImage = { error ->
                    errorMessage = "Error: ${error.message}"
                },
                option = CropifyOption(
                    frameSize = CropifySize.FixedAspectRatio(aspectRatio),
                    frameColor = MaterialTheme.colorScheme.primary,
                    gridColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.BottomCenter)
            ) {
                Text(
                    text = stringResource(R.string.crop_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            errorMessage?.let { error ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}