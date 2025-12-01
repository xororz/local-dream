package io.github.xororz.localdream.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.max
import android.content.Context
import androidx.compose.ui.graphics.Color as ComposeColor
import io.github.xororz.localdream.R
import androidx.core.content.edit
import androidx.core.graphics.createBitmap

enum class ToolMode {
    BRUSH,
    ERASER
}

data class PathData(
    val points: List<Offset>,
    val size: Float,
    val mode: ToolMode,
    val color: Int = Color.WHITE
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InpaintScreen(
    originalBitmap: Bitmap,
    existingMaskBitmap: Bitmap? = null,
    existingPathHistory: List<PathData>? = null,
    onInpaintComplete: (String, Bitmap, Bitmap, List<PathData>) -> Unit,
    onCancel: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current

    val sharedPrefs =
        remember { context.getSharedPreferences("inpaint_prefs", Context.MODE_PRIVATE) }
    val defaultColor = Color.WHITE
    val savedColor = remember { sharedPrefs.getInt("brush_color", defaultColor) }
    val savedToolMode =
        remember { sharedPrefs.getString("tool_mode", ToolMode.BRUSH.name) ?: ToolMode.BRUSH.name }

    var brushColor by remember { mutableStateOf(savedColor) }
    var showColorPicker by remember { mutableStateOf(false) }
    var currentToolMode by remember { mutableStateOf(ToolMode.valueOf(savedToolMode)) }

    LaunchedEffect(currentToolMode) {
        sharedPrefs.edit { putString("tool_mode", currentToolMode.name) }
    }

    val colorOptions = remember {
        arrayOf(
            Color.WHITE,
            Color.RED,
            Color.GREEN,
            Color.BLUE,
            Color.YELLOW,
            Color.CYAN,
            Color.MAGENTA,
            Color.BLACK
        )
    }

    val maskBitmap = remember {
        if (existingMaskBitmap != null) {
            existingMaskBitmap.copy(existingMaskBitmap.config ?: Bitmap.Config.ARGB_8888, true)
        } else {
            createBitmap(originalBitmap.width, originalBitmap.height).apply {
                val canvas = Canvas(this)
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            }
        }
    }

    val tempBitmap = remember {
        createBitmap(originalBitmap.width, originalBitmap.height).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }
    }
    var displayMaskBitmap by remember { mutableStateOf(tempBitmap.asImageBitmap()) }

    val androidPath = remember { Path() }
    var brushSizeDpValue by remember { mutableStateOf(30f) }
    var isDrawing by remember { mutableStateOf(false) }
    val currentPathPoints = remember { mutableStateListOf<Offset>() }

    val pathHistory = remember {
        mutableStateListOf<PathData>().apply {
            existingPathHistory?.let { addAll(it) }
        }
    }
    val redoStack = remember { mutableStateListOf<PathData>() }

    val brushPaint = remember {
        Paint().apply {
            color = brushColor
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
    }

    val eraserPaint = remember {
        Paint().apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
    }

    val finalPaint = remember {
        Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        }
    }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var imageRect by remember { mutableStateOf<Rect?>(null) }

    var displayUpdateTrigger by remember { mutableStateOf(0) }

    fun updateAllBrushPaths(newColor: Int) {
        pathHistory.forEachIndexed { index, pathData ->
            if (pathData.mode == ToolMode.BRUSH) {
                pathHistory[index] = pathData.copy(color = newColor)
            }
        }
    }

    LaunchedEffect(brushColor) {
        brushPaint.color = brushColor
        updateAllBrushPaths(brushColor)
        sharedPrefs.edit { putInt("brush_color", brushColor) }
        displayUpdateTrigger++
    }

    fun mapToImageCoordinate(canvasPoint: Offset): Offset? {
        val rect = imageRect ?: return null
        if (!rect.contains(canvasPoint)) {
            val tolerance = 5f * density.density
            if (canvasPoint.x < rect.left - tolerance || canvasPoint.x > rect.right + tolerance ||
                canvasPoint.y < rect.top - tolerance || canvasPoint.y > rect.bottom + tolerance
            ) {
                return null
            }
        }
        val clampedX = canvasPoint.x.coerceIn(rect.left, rect.right)
        val clampedY = canvasPoint.y.coerceIn(rect.top, rect.bottom)
        val relativeX = (clampedX - rect.left) / rect.width
        val relativeY = (clampedY - rect.top) / rect.height
        return Offset(
            relativeX * originalBitmap.width,
            relativeY * originalBitmap.height
        )
    }

    fun convertDpToImagePixels(
        dpValue: Float,
        density: Density,
        imageRect: Rect?,
        originalWidth: Int
    ): Float {
        val rect = imageRect ?: return dpValue

        val brushSizeInScreenPx = with(density) { dpValue.dp.toPx() }

        val scale = if (rect.width > 0) originalWidth.toFloat() / rect.width else 1f

        return max(1f, brushSizeInScreenPx * scale)
    }

    fun updateDisplayMask(currentDensity: Density, currentImageRect: Rect?) {
        val canvas = Canvas(tempBitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        pathHistory.forEach { pathData ->
            if (pathData.points.size > 1) {
                val paint = when (pathData.mode) {
                    ToolMode.BRUSH -> brushPaint.apply { color = pathData.color }
                    ToolMode.ERASER -> eraserPaint
                }

                paint.strokeWidth = convertDpToImagePixels(
                    pathData.size,
                    currentDensity,
                    currentImageRect,
                    originalBitmap.width
                )

                androidPath.reset()
                androidPath.moveTo(pathData.points[0].x, pathData.points[0].y)
                for (i in 1 until pathData.points.size) {
                    androidPath.lineTo(pathData.points[i].x, pathData.points[i].y)
                }
                canvas.drawPath(androidPath, paint)
            }
        }

        if (isDrawing && currentPathPoints.size > 1) {
            val paint = when (currentToolMode) {
                ToolMode.BRUSH -> brushPaint
                ToolMode.ERASER -> eraserPaint
            }

            paint.strokeWidth = convertDpToImagePixels(
                brushSizeDpValue,
                currentDensity,
                currentImageRect,
                originalBitmap.width
            )
            androidPath.reset()
            androidPath.moveTo(currentPathPoints[0].x, currentPathPoints[0].y)
            for (i in 1 until currentPathPoints.size) {
                androidPath.lineTo(currentPathPoints[i].x, currentPathPoints[i].y)
            }
            canvas.drawPath(androidPath, paint)
        }

        displayMaskBitmap =
            tempBitmap.copy(tempBitmap.config ?: Bitmap.Config.ARGB_8888, true).asImageBitmap()
    }

    fun processMask() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val finalMaskCanvas = Canvas(maskBitmap)
                finalMaskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                for (pathData in pathHistory) {
                    if (pathData.points.isEmpty()) continue

                    val paint = when (pathData.mode) {
                        ToolMode.BRUSH -> finalPaint
                        ToolMode.ERASER -> eraserPaint
                    }

                    paint.strokeWidth = convertDpToImagePixels(
                        pathData.size,
                        density,
                        imageRect,
                        originalBitmap.width
                    )

                    androidPath.reset()
                    androidPath.moveTo(pathData.points[0].x, pathData.points[0].y)
                    for (i in 1 until pathData.points.size) {
                        androidPath.lineTo(pathData.points[i].x, pathData.points[i].y)
                    }
                    finalMaskCanvas.drawPath(androidPath, paint)
                }

                val base64String = withContext(Dispatchers.IO) {
                    val byteArrayOutputStream = ByteArrayOutputStream()
                    maskBitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
                    val byteArray = byteArrayOutputStream.toByteArray()
                    Base64.getEncoder().encodeToString(byteArray)
                }
                onInpaintComplete(base64String, originalBitmap, maskBitmap, pathHistory.toList())
            } catch (e: Exception) {
                errorMessage = "Error: ${e.message}"
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    fun undoLastPath(currentDensity: Density, currentImageRect: Rect?) {
        if (pathHistory.isNotEmpty()) {
            val lastPath = pathHistory.removeAt(pathHistory.size - 1)
            redoStack.add(lastPath)
            updateDisplayMask(currentDensity, currentImageRect)
        }
    }

    fun redoLastPath(currentDensity: Density, currentImageRect: Rect?) {
        if (redoStack.isNotEmpty()) {
            val pathToRedo = redoStack.removeAt(redoStack.size - 1)
            pathHistory.add(pathToRedo)
            updateDisplayMask(currentDensity, currentImageRect)
        }
    }

    BackHandler { onCancel() }

    LaunchedEffect(
        pathHistory.size,
        currentPathPoints.size,
        isDrawing,
        imageRect,
        density,
        displayUpdateTrigger,
        currentToolMode
    ) {
        updateDisplayMask(density, imageRect)
    }

    if (showColorPicker) {
        AlertDialog(
            onDismissRequest = { showColorPicker = false },
            title = { Text(stringResource(R.string.brush_color)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(colorOptions.size) { index ->
                            val color = colorOptions[index]
                            Box(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(ComposeColor(color))
                                    .border(
                                        width = 2.dp,
                                        color = if (color == brushColor) ComposeColor.Black else ComposeColor.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        brushColor = color
                                        showColorPicker = false
                                    }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showColorPicker = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.set_inpaint_area)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!isLoading) processMask()
                        },
                        enabled = !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Complete Marking"
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
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp)
                        .onGloballyPositioned { coordinates ->
                            val size = coordinates.size
                            val imageWidth = size.width.toFloat()
                            val imageHeight = size.height.toFloat()
                            val originalAspect =
                                originalBitmap.width.toFloat() / originalBitmap.height.toFloat()
                            val boxAspect = imageWidth / imageHeight
                            val scaledWidth: Float
                            val scaledHeight: Float
                            if (originalAspect > boxAspect) {
                                scaledWidth = imageWidth
                                scaledHeight = imageWidth / originalAspect
                            } else {
                                scaledHeight = imageHeight
                                scaledWidth = imageHeight * originalAspect
                            }
                            val left = (imageWidth - scaledWidth) / 2
                            val top = (imageHeight - scaledHeight) / 2
                            val newRect =
                                Rect(left, top, left + scaledWidth, bottom = top + scaledHeight)
                            if (newRect != imageRect) {
                                imageRect = newRect
                            }
                        }
                ) {
                    Image(
                        bitmap = originalBitmap.asImageBitmap(),
                        contentDescription = "Original Image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val imagePoint = mapToImageCoordinate(offset)
                                        if (imagePoint != null) {
                                            isDrawing = true
                                            currentPathPoints.clear()
                                            currentPathPoints.add(imagePoint)
                                            redoStack.clear()
                                        } else {
                                            isDrawing = false
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        if (isDrawing) {
                                            val imagePoint = mapToImageCoordinate(change.position)
                                            if (imagePoint != null) {
                                                currentPathPoints.add(imagePoint)
                                                change.consume()
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        if (isDrawing) {
                                            isDrawing = false
                                            if (currentPathPoints.isNotEmpty()) {
                                                pathHistory.add(
                                                    PathData(
                                                        points = currentPathPoints.toList(),
                                                        size = brushSizeDpValue,
                                                        mode = currentToolMode,
                                                        color = brushColor
                                                    )
                                                )
                                                currentPathPoints.clear()
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        if (isDrawing) {
                                            isDrawing = false
                                            if (currentPathPoints.isNotEmpty()) {
                                                pathHistory.add(
                                                    PathData(
                                                        points = currentPathPoints.toList(),
                                                        size = brushSizeDpValue,
                                                        mode = currentToolMode,
                                                        color = brushColor
                                                    )
                                                )
                                                currentPathPoints.clear()
                                            }
                                        }
                                    }
                                )
                            }
                    ) {
                        val rect = imageRect ?: return@Canvas
                        drawImage(
                            image = displayMaskBitmap,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(tempBitmap.width, tempBitmap.height),
                            dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
                            dstSize = IntSize(rect.width.toInt(), rect.height.toInt()),
                            alpha = 0.6f
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.inpaint_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.width(80.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Box(
                                    modifier = Modifier.size(36.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (currentToolMode == ToolMode.BRUSH) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    MaterialTheme.colorScheme.primary.copy(
                                                        alpha = 0.15f
                                                    )
                                                )
                                        )
                                    }

                                    IconButton(
                                        onClick = { currentToolMode = ToolMode.BRUSH },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Brush,
                                            contentDescription = "Brush Tool",
                                            tint = if (currentToolMode == ToolMode.BRUSH)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier.size(36.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (currentToolMode == ToolMode.ERASER) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    MaterialTheme.colorScheme.primary.copy(
                                                        alpha = 0.15f
                                                    )
                                                )
                                        )
                                    }

                                    IconButton(
                                        onClick = { currentToolMode = ToolMode.ERASER },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.FormatPaint,
                                            contentDescription = "Eraser Tool",
                                            tint = if (currentToolMode == ToolMode.ERASER)
                                                MaterialTheme.colorScheme.primary
                                            else
                                                MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            ) {
                                Slider(
                                    value = brushSizeDpValue,
                                    onValueChange = { brushSizeDpValue = it },
                                    valueRange = 5f..50f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .width(50.dp)
                                    .padding(start = 4.dp)
                                    .aspectRatio(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                val indicatorSize = with(density) { brushSizeDpValue.dp }
                                Box(
                                    modifier = Modifier
                                        .size(indicatorSize.coerceAtMost(50.dp))
                                        .clip(CircleShape)
                                        .background(
                                            if (currentToolMode == ToolMode.BRUSH)
                                                ComposeColor(brushColor)
                                            else
                                                ComposeColor.LightGray.copy(alpha = 0.5f)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = ComposeColor.DarkGray.copy(alpha = 0.3f),
                                            shape = CircleShape
                                        )
                                        .clickable(enabled = currentToolMode == ToolMode.BRUSH) {
                                            if (currentToolMode == ToolMode.BRUSH) {
                                                showColorPicker = true
                                            }
                                        }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(
                                16.dp,
                                Alignment.CenterHorizontally
                            )
                        ) {
                            val currentDensity = LocalDensity.current
                            val currentImageRect = imageRect

                            Button(
                                onClick = { undoLastPath(currentDensity, currentImageRect) },
                                enabled = pathHistory.isNotEmpty() && !isLoading
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Undo",
                                    modifier = Modifier.size(ButtonDefaults.IconSize)
                                )
                                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.undo))
                            }

                            Button(
                                onClick = { redoLastPath(currentDensity, currentImageRect) },
                                enabled = redoStack.isNotEmpty() && !isLoading,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                            ) {
                                Icon(
                                    Icons.Default.Redo,
                                    contentDescription = "Redo",
                                    modifier = Modifier.size(ButtonDefaults.IconSize)
                                )
                                Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.redo))
                            }
                        }
                    }
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ComposeColor.Black.copy(alpha = 0.6f))
                        .pointerInput(Unit) {},
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(64.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            if (errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text(
                                text = "Error",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = errorMessage ?: "Unknown error occurred",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { errorMessage = null },
                                modifier = Modifier.align(Alignment.End),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            ) {
                                Text("OK")
                            }
                        }
                    }
                }
            }
        }
    }
}