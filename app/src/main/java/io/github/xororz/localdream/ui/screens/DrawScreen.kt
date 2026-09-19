package io.github.xororz.localdream.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Approval
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import io.github.xororz.localdream.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CloneMode { OFF, SELECTING, DRAWING }

data class StrokePath(
    val path: Path,
    val color: Color,
    val strokeWidth: Float,
    val alpha: Float,
    val isEraser: Boolean = false,
    val blurRadius: Float = 0f,
    val cloneShader: BitmapShader? = null,
)

private fun StrokePath.matchesBrush(color: Color, width: Float, alpha: Float, blur: Float, shader: BitmapShader?): Boolean = !isEraser && this.color == color && strokeWidth == width && this.alpha == alpha &&
    blurRadius == blur && cloneShader == shader

private fun drawStrokePaths(canvas: Canvas, paths: List<StrokePath>) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    paths.forEach { stroke ->
        paint.strokeWidth = stroke.strokeWidth
        paint.maskFilter = if (stroke.blurRadius > 0f) {
            BlurMaskFilter(stroke.blurRadius, BlurMaskFilter.Blur.NORMAL)
        } else {
            null
        }
        paint.color = stroke.color.toArgb()
        paint.alpha = (stroke.alpha * 255).toInt()
        paint.shader = if (stroke.isEraser) null else stroke.cloneShader
        paint.xfermode = if (stroke.isEraser) PorterDuffXfermode(PorterDuff.Mode.DST_OUT) else null
        canvas.drawPath(stroke.path.asAndroidPath(), paint)
    }
}

internal fun renderDrawingLayer(
    paths: List<StrokePath>,
    viewportWidth: Int,
    viewportHeight: Int,
    bitmapWidth: Int,
    bitmapHeight: Int,
): Bitmap {
    val scale = minOf(viewportWidth.toFloat() / bitmapWidth, viewportHeight.toFloat() / bitmapHeight)
    val offsetX = (viewportWidth - bitmapWidth * scale) / 2f
    val offsetY = (viewportHeight - bitmapHeight * scale) / 2f
    val layer = createBitmap(bitmapWidth, bitmapHeight)
    val canvas = Canvas(layer)
    canvas.scale(1f / scale, 1f / scale)
    canvas.translate(-offsetX, -offsetY)
    drawStrokePaths(canvas, paths)
    return layer
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawScreen(
    originalBitmap: Bitmap,
    onDrawingSaved: suspend (Bitmap, Bitmap) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("brush_prefs", Context.MODE_PRIVATE) }
    var brushColor by remember { mutableStateOf(Color(prefs.getInt("brush_color", Color.Red.toArgb()))) }
    var brushSize by remember { mutableFloatStateOf(prefs.getFloat("brush_size", 60f)) }
    var brushAlpha by remember { mutableFloatStateOf(prefs.getFloat("brush_alpha", 1f)) }
    var brushBlur by remember { mutableFloatStateOf(prefs.getFloat("brush_blur", 10f)) }
    var eraserSize by remember { mutableFloatStateOf(prefs.getFloat("eraser_size", 60f)) }
    var eraserAlpha by remember { mutableFloatStateOf(prefs.getFloat("eraser_alpha", 1f)) }
    var eraserBlur by remember { mutableFloatStateOf(prefs.getFloat("eraser_blur", 0f)) }

    val paths = remember { mutableStateListOf<StrokePath>() }
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var pathUpdateTrigger by remember { mutableStateOf(0) }
    var imageCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    var isEraserMode by remember { mutableStateOf(false) }
    var isPickerMode by remember { mutableStateOf(false) }
    var isZoomMode by remember { mutableStateOf(false) }
    var isTouchpadMode by remember { mutableStateOf(true) }

    var cloneMode by remember { mutableStateOf(CloneMode.OFF) }
    var activeCloneShader by remember { mutableStateOf<BitmapShader?>(null) }

    var zoomScale by remember { mutableFloatStateOf(1f) }
    var zoomOffset by remember { mutableStateOf(Offset.Zero) }
    var showColorPickerDialog by remember { mutableStateOf(false) }
    var isAdjustingBrush by remember { mutableStateOf(false) }
    val showPreview = isTouchpadMode || isAdjustingBrush || (cloneMode == CloneMode.SELECTING)
    var previewOffset by remember { mutableStateOf(Offset.Zero) }
    var isOffsetInitialized by remember { mutableStateOf(false) }

    val currentSize = if (isEraserMode) eraserSize else brushSize
    val currentAlpha = if (isEraserMode) eraserAlpha else brushAlpha
    val currentBlur = if (isEraserMode) eraserBlur else brushBlur
    var forceNewLayerNextDraw by remember { mutableStateOf(false) }

    var showClearDialog by remember { mutableStateOf(false) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val previewBitmap = remember(viewportSize) {
        if (viewportSize.width > 0 && viewportSize.height > 0) {
            createBitmap(viewportSize.width, viewportSize.height)
        } else {
            null
        }
    }

    BackHandler {
        if (!isSaving) onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.draw_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, enabled = !isSaving) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            if (paths.isEmpty()) {
                                onNavigateBack()
                                return@Button
                            }
                            val coords = imageCoordinates ?: return@Button
                            val viewport = coords.size
                            if (viewport.width <= 0 || viewport.height <= 0) return@Button
                            // Keep the saved strokes stable while rendering off the UI thread.
                            val savedPaths = paths.map { stroke ->
                                stroke.copy(path = Path().apply { addPath(stroke.path) })
                            }
                            isSaving = true
                            scope.launch {
                                try {
                                    val (result, drawing) = withContext(Dispatchers.Default) {
                                        val layer = renderDrawingLayer(
                                            savedPaths,
                                            viewport.width,
                                            viewport.height,
                                            originalBitmap.width,
                                            originalBitmap.height,
                                        )
                                        val result = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
                                        Canvas(result).drawBitmap(layer, 0f, 0f, null)
                                        result to layer
                                    }
                                    onDrawingSaved(result, drawing)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to save drawing: ${e.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    isSaving = false
                                }
                            }
                        },
                        enabled = !isSaving,
                    ) { Text(stringResource(R.string.draw_done)) }
                },
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                Column {
                    BrushToolsComponent(
                        currentColor = brushColor,
                        onColorClick = { showColorPickerDialog = true },
                        currentSize = currentSize,
                        onSizeChange = { value ->
                            if (isEraserMode) {
                                eraserSize = value
                                prefs.edit { putFloat("eraser_size", value) }
                            } else {
                                brushSize = value
                                prefs.edit { putFloat("brush_size", value) }
                            }
                            isAdjustingBrush = true
                        },
                        currentAlpha = currentAlpha,
                        onAlphaChange = { value ->
                            if (isEraserMode) {
                                eraserAlpha = value
                                prefs.edit { putFloat("eraser_alpha", value) }
                            } else {
                                brushAlpha = value
                                prefs.edit { putFloat("brush_alpha", value) }
                            }
                            isAdjustingBrush = true
                        },
                        currentBlur = currentBlur,
                        onBlurChange = { value ->
                            if (isEraserMode) {
                                eraserBlur = value
                                prefs.edit { putFloat("eraser_blur", value) }
                            } else {
                                brushBlur = value
                                prefs.edit { putFloat("brush_blur", value) }
                            }
                            isAdjustingBrush = true
                        },
                        isEraserMode = isEraserMode,
                        onEraserModeChange = {
                            isEraserMode = it
                            if (it) {
                                isPickerMode = false
                                isZoomMode = false
                                cloneMode = CloneMode.OFF
                            }
                        },
                        isPickerMode = isPickerMode,
                        onPickerModeChange = {
                            isPickerMode = it
                            if (it) {
                                isEraserMode = false
                                isZoomMode = false
                                cloneMode = CloneMode.OFF
                            }
                        },
                        isZoomMode = isZoomMode,
                        onZoomModeChange = {
                            isZoomMode = it
                            if (it) {
                                isEraserMode = false
                                isPickerMode = false
                                cloneMode = CloneMode.OFF
                            }
                        },
                        isTouchpadMode = isTouchpadMode,
                        onTouchpadModeChange = { isTouchpadMode = it },
                        onUndo = { if (paths.isNotEmpty()) paths.removeAt(paths.lastIndex) },
                        onClearAll = {
                            showClearDialog = true
                        },
                        onAdjustmentStateChange = { isDragging -> isAdjustingBrush = isDragging },
                        onNewLayerClick = {
                            forceNewLayerNextDraw = true
                            Toast.makeText(
                                context,
                                "New layer",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                        cloneMode = cloneMode,
                        onCloneModeClick = {
                            when (cloneMode) {
                                CloneMode.OFF -> {
                                    cloneMode = CloneMode.SELECTING
                                    isEraserMode = false
                                    isPickerMode = false
                                    isZoomMode = false
                                }

                                CloneMode.SELECTING -> {
                                    imageCoordinates?.let { coords ->
                                        val scale = minOf(
                                            coords.size.width / originalBitmap.width.toFloat(),
                                            coords.size.height / originalBitmap.height.toFloat(),
                                        )
                                        val offsetX = (coords.size.width - (originalBitmap.width * scale)) / 2f
                                        val offsetY = (coords.size.height - (originalBitmap.height * scale)) / 2f
                                        val bX = ((previewOffset.x - offsetX) / scale).toInt()
                                            .coerceIn(0, originalBitmap.width - 1)
                                        val bY = ((previewOffset.y - offsetY) / scale).toInt()
                                            .coerceIn(0, originalBitmap.height - 1)

                                        val radius =
                                            (currentSize / scale / 2f).toInt().coerceIn(8, originalBitmap.width)
                                        val startX = (bX - radius).coerceIn(0, originalBitmap.width - 1)
                                        val startY = (bY - radius).coerceIn(0, originalBitmap.height - 1)
                                        val endX = (bX + radius).coerceIn(0, originalBitmap.width)
                                        val endY = (bY + radius).coerceIn(0, originalBitmap.height)
                                        val w = endX - startX
                                        val h = endY - startY

                                        if (w > 0 && h > 0) {
                                            val crop = Bitmap.createBitmap(originalBitmap, startX, startY, w, h)
                                            val shader =
                                                BitmapShader(crop, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
                                            val matrix = Matrix()
                                            matrix.postScale(scale, scale)
                                            matrix.postTranslate(startX * scale + offsetX, startY * scale + offsetY)
                                            shader.setLocalMatrix(matrix)
                                            activeCloneShader = shader
                                            cloneMode = CloneMode.DRAWING
                                        }
                                    }
                                }

                                CloneMode.DRAWING -> {
                                    cloneMode = CloneMode.OFF
                                    activeCloneShader = null
                                }
                            }
                        },
                    )
                }
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
                .clipToBounds(),
            contentAlignment = Alignment.TopStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned {
                        imageCoordinates = it
                        viewportSize = it.size
                        if (!isOffsetInitialized && it.size.width > 0) {
                            previewOffset = Offset(it.size.width / 2f, it.size.height / 2f)
                            isOffsetInitialized = true
                        }
                    }
                    .testTag("drawing_canvas")
                    .graphicsLayer(
                        scaleX = zoomScale,
                        scaleY = zoomScale,
                        translationX = zoomOffset.x,
                        translationY = zoomOffset.y,
                    )
                    .pointerInput(isZoomMode, isPickerMode, isTouchpadMode, cloneMode, isSaving) {
                        if (isSaving) return@pointerInput
                        if (isZoomMode) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val panInViewport = pan * zoomScale
                                zoomScale = (zoomScale * zoom).coerceIn(1f, 8f)
                                zoomOffset = if (zoomScale > 1f) zoomOffset + panInViewport else Offset.Zero
                            }
                        } else {
                            awaitEachGesture {
                                var isMultiTouch = false
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (!isPickerMode && cloneMode != CloneMode.SELECTING) {
                                    val startPoint = if (isTouchpadMode) previewOffset else down.position
                                    currentPath = Path().apply { moveTo(startPoint.x, startPoint.y) }
                                    pathUpdateTrigger++
                                }
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.size > 1) {
                                        isMultiTouch = true
                                        currentPath = null
                                        pathUpdateTrigger++
                                    }
                                    val pointerChange = event.changes.first()
                                    val dragAmount = pointerChange.position - pointerChange.previousPosition
                                    if (isTouchpadMode || cloneMode == CloneMode.SELECTING) {
                                        previewOffset += dragAmount
                                    } else {
                                        previewOffset = pointerChange.position
                                    }

                                    if (isPickerMode) {
                                        imageCoordinates?.let { coords ->
                                            val scale = minOf(
                                                coords.size.width / originalBitmap.width.toFloat(),
                                                coords.size.height / originalBitmap.height.toFloat(),
                                            )
                                            val offsetX = (coords.size.width - (originalBitmap.width * scale)) / 2f
                                            val offsetY = (coords.size.height - (originalBitmap.height * scale)) / 2f
                                            val targetPoint =
                                                if (isTouchpadMode) previewOffset else pointerChange.position
                                            val bitmapX = ((targetPoint.x - offsetX) / scale).toInt()
                                                .coerceIn(0, originalBitmap.width - 1)
                                            val bitmapY = ((targetPoint.y - offsetY) / scale).toInt()
                                                .coerceIn(0, originalBitmap.height - 1)

                                            // Average the colors in a radius around the point
                                            val sampleRadius = 5 // Sampling radius in pixels
                                            var totalRed = 0f
                                            var totalGreen = 0f
                                            var totalBlue = 0f
                                            var totalAlpha = 0f
                                            var count = 0

                                            for (dx in -sampleRadius..sampleRadius) {
                                                for (dy in -sampleRadius..sampleRadius) {
                                                    val x = (bitmapX + dx).coerceIn(0, originalBitmap.width - 1)
                                                    val y = (bitmapY + dy).coerceIn(0, originalBitmap.height - 1)
                                                    val pixel = originalBitmap[x, y]
                                                    totalRed += android.graphics.Color.red(pixel)
                                                    totalGreen += android.graphics.Color.green(pixel)
                                                    totalBlue += android.graphics.Color.blue(pixel)
                                                    totalAlpha += android.graphics.Color.alpha(pixel)
                                                    count++
                                                }
                                            }

                                            val avgRed = (totalRed / count).toInt()
                                            val avgGreen = (totalGreen / count).toInt()
                                            val avgBlue = (totalBlue / count).toInt()
                                            val avgAlpha = (totalAlpha / count).toInt()

                                            val pickedColor =
                                                Color(android.graphics.Color.argb(avgAlpha, avgRed, avgGreen, avgBlue))
                                            brushColor = pickedColor
                                            prefs.edit { putInt("brush_color", pickedColor.toArgb()) }
                                        }
                                    } else if (!isMultiTouch && cloneMode != CloneMode.SELECTING) {
                                        val currentPoint = if (isTouchpadMode) previewOffset else pointerChange.position
                                        currentPath?.lineTo(currentPoint.x, currentPoint.y)
                                        pathUpdateTrigger++
                                    }
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                                if (isPickerMode) {
                                    isPickerMode = false
                                    Toast.makeText(context, "Color changed", Toast.LENGTH_SHORT).show()
                                } else if (!isMultiTouch && cloneMode != CloneMode.SELECTING) {
                                    currentPath?.let { finishedPath ->
                                        val snapColor = if (isEraserMode) Color.Transparent else brushColor
                                        val snapWidth = if (isEraserMode) eraserSize else brushSize
                                        val snapAlpha = if (isEraserMode) eraserAlpha else brushAlpha
                                        val snapBlur = if (isEraserMode) eraserBlur else brushBlur
                                        val snapShader =
                                            if (cloneMode == CloneMode.DRAWING && !isEraserMode) activeCloneShader else null
                                        val lastStroke = paths.lastOrNull()

                                        if (!isEraserMode && !forceNewLayerNextDraw && lastStroke?.matchesBrush(snapColor, snapWidth, snapAlpha, snapBlur, snapShader) == true) {
                                            lastStroke.path.addPath(finishedPath)
                                        } else {
                                            paths.add(
                                                StrokePath(
                                                    finishedPath,
                                                    snapColor,
                                                    snapWidth,
                                                    snapAlpha,
                                                    isEraserMode,
                                                    snapBlur,
                                                    snapShader,
                                                ),
                                            )
                                            forceNewLayerNextDraw = false
                                        }
                                    }
                                }
                                currentPath = null
                                pathUpdateTrigger++
                            }
                        }
                    },
            ) {
                Image(
                    bitmap = originalBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.draw_original_background),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                ComposeCanvas(
                    modifier = Modifier.fillMaxSize(),
                    onDraw = {
                        pathUpdateTrigger
                        // BlurMaskFilter is not supported by the hardware canvas.
                        // Render preview strokes in software, as the saved bitmap is rendered.
                        previewBitmap?.let { layer ->
                            layer.eraseColor(android.graphics.Color.TRANSPARENT)
                            val canvas = Canvas(layer)
                            drawStrokePaths(canvas, paths)
                            currentPath?.let { path ->
                                drawStrokePaths(
                                    canvas,
                                    listOf(
                                        StrokePath(
                                            path = path,
                                            color = brushColor,
                                            strokeWidth = currentSize,
                                            alpha = currentAlpha,
                                            isEraser = isEraserMode,
                                            blurRadius = currentBlur,
                                            cloneShader = if (cloneMode == CloneMode.DRAWING) activeCloneShader else null,
                                        ),
                                    ),
                                )
                            }
                            drawImage(layer.asImageBitmap())
                        }
                    },
                )
                if (showPreview) {
                    val density = LocalDensity.current
                    val brushSizeInDp = with(density) { currentSize.toDp() }
                    val isSelecting = cloneMode == CloneMode.SELECTING
                    val baseColor = if (isEraserMode) {
                        Color.White
                    } else if (isSelecting) {
                        Color.Cyan
                    } else {
                        brushColor
                    }
                    val finalAlpha = if (isEraserMode) {
                        0.4f
                    } else if (isSelecting) {
                        0.6f
                    } else {
                        currentAlpha
                    }

                    Box(
                        modifier = Modifier
                            .size(brushSizeInDp)
                            .offset(
                                x = with(density) { (previewOffset.x - currentSize / 2f).toDp() },
                                y = with(density) { (previewOffset.y - currentSize / 2f).toDp() },
                            )
                            .testTag("brush_indicator")
                            .border(
                                width = if (isSelecting) 2.5.dp else 1.5.dp,
                                color = if (isEraserMode) {
                                    Color.Red
                                } else if (isSelecting) {
                                    Color.Cyan
                                } else {
                                    Color.White
                                },
                                shape = if (isSelecting) RectangleShape else CircleShape,
                            ),
                    ) {
                        if (cloneMode != CloneMode.SELECTING) {
                            ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                                val radius = size.minDimension / 2f
                                if (currentBlur > 0f) {
                                    val ratio = (currentBlur / currentSize).coerceIn(0f, 0.5f)
                                    val startRadiusRatio = (1f - ratio * 2f).coerceIn(0f, 1f)
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            colorStops = arrayOf(
                                                0.0f to baseColor.copy(alpha = finalAlpha),
                                                startRadiusRatio to baseColor.copy(alpha = finalAlpha),
                                                1.0f to Color.Transparent,
                                            ),
                                            center = center,
                                            radius = radius,
                                        ),
                                        radius = radius,
                                        center = center,
                                    )
                                } else {
                                    drawCircle(color = baseColor.copy(alpha = finalAlpha), radius = radius, center = center)
                                }
                            }
                        }
                    }
                }
            }
            if (showColorPickerDialog) {
                SimpleColorPickerDialog(
                    initialColor = brushColor,
                    onColorSelected = { pickedColor ->
                        brushColor = pickedColor
                        prefs.edit {
                            putInt(
                                "brush_color",
                                pickedColor.toArgb(),
                            )
                        }
                    },
                    onDismiss = { showColorPickerDialog = false },
                )
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(text = stringResource(R.string.draw_clear_title)) },
            text = { Text(text = stringResource(R.string.draw_clear_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        paths.clear()
                        zoomScale = 1f
                        zoomOffset = Offset.Zero
                        imageCoordinates?.let { previewOffset = Offset(it.size.width / 2f, it.size.height / 2f) }

                        // Close dialog
                        showClearDialog = false
                    },
                ) {
                    Text(stringResource(R.string.draw_clear_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
fun BrushToolsComponent(
    currentColor: Color,
    onColorClick: () -> Unit,
    currentSize: Float,
    onSizeChange: (Float) -> Unit,
    currentAlpha: Float,
    onAlphaChange: (Float) -> Unit,
    currentBlur: Float,
    onBlurChange: (Float) -> Unit,
    isEraserMode: Boolean,
    onEraserModeChange: (Boolean) -> Unit,
    isPickerMode: Boolean,
    onPickerModeChange: (Boolean) -> Unit,
    isZoomMode: Boolean,
    onZoomModeChange: (Boolean) -> Unit,
    isTouchpadMode: Boolean,
    onTouchpadModeChange: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onClearAll: () -> Unit,
    onAdjustmentStateChange: (Boolean) -> Unit,
    onNewLayerClick: () -> Unit,
    cloneMode: CloneMode,
    onCloneModeClick: () -> Unit,
) {
    val sizeInteractionSource = remember { MutableInteractionSource() }
    val alphaInteractionSource = remember { MutableInteractionSource() }
    val blurInteractionSource = remember { MutableInteractionSource() }
    val isSizeDragged by sizeInteractionSource.collectIsDraggedAsState()
    val isAlphaDragged by alphaInteractionSource.collectIsDraggedAsState()
    val isBlurDragged by blurInteractionSource.collectIsDraggedAsState()

    LaunchedEffect(isSizeDragged, isAlphaDragged, isBlurDragged) {
        onAdjustmentStateChange(isSizeDragged || isAlphaDragged || isBlurDragged)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Slider(
                    value = currentSize,
                    onValueChange = onSizeChange,
                    valueRange = 5f..200f,
                    interactionSource = sizeInteractionSource,
                )
                Text(
                    text = stringResource(R.string.draw_size, currentSize.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Slider(
                    value = currentAlpha,
                    onValueChange = onAlphaChange,
                    valueRange = 0.1f..1f,
                    interactionSource = alphaInteractionSource,
                )
                Text(
                    text = stringResource(R.string.draw_transparency, (currentAlpha * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Slider(
                    value = currentBlur,
                    onValueChange = onBlurChange,
                    valueRange = 0f..100f,
                    interactionSource = blurInteractionSource,
                )
                Text(
                    text = stringResource(R.string.draw_blur, currentBlur.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            // horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Current color / Select color
            FilledIconButton(
                onClick = onColorClick,
                enabled = !isEraserMode && cloneMode == CloneMode.OFF,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = currentColor, // Color shown while the button is active
                    disabledContainerColor = currentColor.copy(alpha = 0.38f),
                ),
            ) {}

            // Pipette
            FilledIconToggleButton(checked = isPickerMode, onCheckedChange = onPickerModeChange) {
                Icon(imageVector = Icons.Default.Colorize, contentDescription = stringResource(R.string.draw_pipette))
            }

            // Stamp / Clone
            FilledIconToggleButton(checked = (cloneMode != CloneMode.OFF), onCheckedChange = { onCloneModeClick() }) {
                Icon(
                    imageVector = when (cloneMode) {
                        CloneMode.OFF -> Icons.Default.Approval
                        CloneMode.SELECTING -> Icons.Default.SelectAll
                        CloneMode.DRAWING -> Icons.Default.Brush
                    },
                    contentDescription = when (cloneMode) {
                        CloneMode.OFF -> "Stamp disabled"
                        CloneMode.SELECTING -> "Selecting"
                        CloneMode.DRAWING -> "Drawing"
                    },
                )
            }

            // Eraser
            FilledIconToggleButton(checked = isEraserMode, onCheckedChange = onEraserModeChange) {
                Icon(imageVector = Icons.Default.ContentCut, contentDescription = stringResource(R.string.draw_eraser))
            }

            // New layer
            FilledIconToggleButton(checked = false, onCheckedChange = { onNewLayerClick() }) {
                Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(R.string.draw_new_layer))
            }

            // Undo
            FilledIconToggleButton(checked = false, onCheckedChange = { onUndo() }) {
                Icon(imageVector = Icons.AutoMirrored.Default.Undo, contentDescription = stringResource(R.string.undo))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            // horizontalArrangement = Arrangement.Spa,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Zoom
            FilledIconToggleButton(checked = isZoomMode, onCheckedChange = onZoomModeChange) {
                Icon(imageVector = Icons.Default.Search, contentDescription = stringResource(R.string.draw_zoom))
            }

            // Touch mode
            FilledIconToggleButton(
                checked = !isTouchpadMode,
                onCheckedChange = { isChecked -> onTouchpadModeChange(!isChecked) },
            ) {
                Icon(imageVector = Icons.Default.TouchApp, contentDescription = stringResource(R.string.draw_touch_mode))
            }

            // Clear all
            FilledIconToggleButton(checked = false, onCheckedChange = { onClearAll() }) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = stringResource(R.string.draw_clear_all))
            }
        }
    }
}

@Composable
fun SimpleColorPickerDialog(initialColor: Color, onColorSelected: (Color) -> Unit, onDismiss: () -> Unit) {
    var hue by remember { mutableFloatStateOf(0f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var value by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(initialColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(initialColor.toArgb(), hsv)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
    }
    val currentSelectedColor = remember(hue, saturation, value) { Color.hsv(hue, saturation, value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.draw_select_color)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .background(
                            brush = Brush.horizontalGradient(colors = listOf(Color.White, Color.hsv(hue, 1f, 1f))),
                            shape = MaterialTheme.shapes.medium,
                        )
                        .pointerInput(hue) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                fun select(position: Offset) {
                                    saturation = (position.x / size.width).coerceIn(0f, 1f)
                                    value = 1f - (position.y / size.height).coerceIn(0f, 1f)
                                }
                                select(down.position)
                                down.consume()
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.first()
                                    select(change.position)
                                    change.consume()
                                } while (event.changes.any { it.pressed })
                            }
                        },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black)),
                                shape = MaterialTheme.shapes.medium,
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .offset(x = (saturation * 200).dp - 8.dp, y = ((1f - value) * 200).dp - 8.dp)
                            .size(16.dp)
                            .background(Color.Transparent, shape = CircleShape)
                            .border(
                                width = 2.dp,
                                color = if (value < 0.5f) Color.White else Color.Black,
                                shape = CircleShape,
                            ),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                val hueColors = remember {
                    listOf(
                        Color.Red,
                        Color.Yellow,
                        Color.Green,
                        Color.Cyan,
                        Color.Blue,
                        Color.Magenta,
                        Color.Red,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .background(
                            brush = Brush.horizontalGradient(colors = hueColors),
                            shape = MaterialTheme.shapes.small,
                        ),
                )
                Slider(
                    value = hue,
                    onValueChange = { hue = it },
                    valueRange = 0f..360f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent,
                    ),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.draw_result))
                    Box(
                        modifier = Modifier
                            .size(60.dp, 30.dp)
                            .background(currentSelectedColor, shape = MaterialTheme.shapes.small)
                            .border(1.dp, Color.Gray, MaterialTheme.shapes.small),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onColorSelected(currentSelectedColor)
                onDismiss()
            }) { Text(stringResource(R.string.draw_select)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
