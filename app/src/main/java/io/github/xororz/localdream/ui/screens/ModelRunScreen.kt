package io.github.xororz.localdream.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect as AndroidRect
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.xororz.localdream.BuildConfig
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.GenerationDefaults
import io.github.xororz.localdream.data.GenerationMode
import io.github.xororz.localdream.data.GenerationPreferences
import io.github.xororz.localdream.data.HistoryFilter
import io.github.xororz.localdream.data.HistoryItem
import io.github.xororz.localdream.data.HistoryManager
import io.github.xororz.localdream.data.ModelRepository
import io.github.xororz.localdream.data.PatchScanner
import io.github.xororz.localdream.data.RemoteRepository
import io.github.xororz.localdream.data.Resolution
import io.github.xororz.localdream.data.TagAutocompleteRepository
import io.github.xororz.localdream.data.TagMatchType
import io.github.xororz.localdream.data.TagSuggestion
import io.github.xororz.localdream.data.UpscalerRepository
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.service.BackgroundGenerationService
import io.github.xororz.localdream.service.BackgroundGenerationService.GenerationState
import io.github.xororz.localdream.ui.components.BlockingProgressOverlay
import io.github.xororz.localdream.ui.components.GenerationParamsDialog
import io.github.xororz.localdream.ui.components.ImportParametersDialog
import io.github.xororz.localdream.ui.components.OverlayIconButton
import io.github.xororz.localdream.ui.components.ReproduceParametersDialog
import io.github.xororz.localdream.ui.components.ShareParamsFlow
import io.github.xororz.localdream.ui.components.SmoothLinearWavyProgressIndicator
import io.github.xororz.localdream.ui.components.ZoomableImageOverlay
import io.github.xororz.localdream.ui.theme.Motion
import io.github.xororz.localdream.utils.ImportedParams
import io.github.xororz.localdream.utils.LogCapture
import io.github.xororz.localdream.utils.ParamShare
import io.github.xororz.localdream.utils.ParamShareField
import io.github.xororz.localdream.utils.performUpscale
import io.github.xororz.localdream.utils.reportImage
import io.github.xororz.localdream.utils.saveImage
import io.github.xororz.localdream.utils.saveImageFromFile
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("DefaultLocale")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModelRunScreen(
    modelId: String,
    navController: NavController,
    modifier: Modifier = Modifier,
    isRemote: Boolean = false,
) {
    val serviceState by BackgroundGenerationService.generationState.collectAsState()
    val backendState by BackendService.backendState.collectAsState()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val generationPreferences = remember { GenerationPreferences(context) }
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val modelRepository = remember { ModelRepository.getInstance(context) }
    val remoteRepository = remember { RemoteRepository.getInstance(context) }
    // Control client for the host device; null in local mode. The generation
    // host feeds every backend HTTP call (generate/tokenize/health).
    val remoteClient = remember(remoteRepository.connection) {
        if (isRemote) remoteRepository.client() else null
    }
    val backendHost = remoteClient?.generationHost
        ?: BackgroundGenerationService.LOCAL_BACKEND_HOST

    // String resources hoisted to composable scope (lint: LocalContextGetResourceValueCall).
    val msgMediaPermissionHint = stringResource(R.string.media_permission_hint)
    val msgBackendFailed = stringResource(R.string.backend_failed)
    val msgImportNoParams = stringResource(R.string.import_no_params)
    val msgImageSaved = stringResource(R.string.image_saved)
    val msgDeleted = stringResource(R.string.deleted)
    val msgDeleteFailedMessage = stringResource(R.string.delete_failed_message)
    val msgImportApplied = stringResource(R.string.import_applied)
    val msgUpscaleFailed = stringResource(R.string.upscale_failed)
    val msgUltrafixFailed = stringResource(R.string.ultrafix_failed)
    val msgSavedCountWithFailed = stringResource(R.string.saved_count_with_failed)
    val msgDeletedCountWithFailed = stringResource(R.string.deleted_count_with_failed)
    val msgUnknownError = stringResource(R.string.unknown_error)
    val msgSaveFailed = stringResource(R.string.save_failed_detail)
    val msgImg2imgFailed = stringResource(R.string.img2img_failed_detail)
    val msgPleaseCropFirst = stringResource(R.string.please_crop_first)
    val msgReportSuccess = stringResource(R.string.report_success)
    val msgReportFailed = stringResource(R.string.report_failed)
    val msgNoImageAvailable = stringResource(R.string.no_image_available)
    val msgImageLoadFailed = stringResource(R.string.image_load_failed)
    val msgGenerationInterrupted = stringResource(R.string.generation_interrupted)
    val msgRemoteSelectFailed = stringResource(R.string.remote_select_failed)
    // Reaches the screen with the repository already loaded on the normal
    // navigation path; resolves asynchronously after process recreation.
    val model = if (isRemote) {
        remember(remoteRepository.models) { remoteRepository.models.find { it.id == modelId } }
    } else {
        remember(modelRepository.models) { modelRepository.models.find { it.id == modelId } }
    }
    LaunchedEffect(Unit) {
        if (!isRemote) {
            modelRepository.ensureLoaded()
        }
    }
    val historyManager = remember { HistoryManager(context) }
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var showResetConfirmDialog by remember { mutableStateOf(false) }
    var showOpenCLWarningDialog by remember { mutableStateOf(false) }
    var showInterruptDialog by remember { mutableStateOf(false) }

    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var intermediateBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var imageVersion by remember { mutableIntStateOf(0) }
    var generationParams by remember { mutableStateOf<GenerationParameters?>(null) }
    var generationParamsModelId by remember { mutableStateOf(modelId) }

    // History state
    var historyFilter by remember(modelId) {
        mutableStateOf(HistoryFilter(modelIds = setOf(modelId)))
    }
    val pagedHistory = remember(historyFilter) { historyManager.pager(historyFilter) }
        .collectAsLazyPagingItems()
    val historyTotalCount by remember(historyFilter) { historyManager.observeCount(historyFilter) }
        .collectAsState(initial = 0)
    // Bounded newest-first feed for the result-page thumbnail strip and the
    // seed-on-open effect; avoids materializing the full history.
    val recentHistory by remember(historyFilter) { historyManager.observeRecent(historyFilter, 20) }
        .collectAsState(initial = emptyList())
    val knownModelIds by remember { historyManager.observeKnownModelIds() }
        .collectAsState(initial = emptyList())
    val knownSchedulers by remember { historyManager.observeKnownSchedulers() }
        .collectAsState(initial = emptyList())
    val knownSizes by remember { historyManager.observeKnownSizes() }
        .collectAsState(initial = emptyList())
    var showHistoryFilterSheet by remember { mutableStateOf(false) }
    var selectedHistoryItem by remember { mutableStateOf<HistoryItem?>(null) }
    var showHistoryDetailDialog by remember { mutableStateOf(false) }
    var showHistoryParametersDialog by remember { mutableStateOf(false) }
    var showDeleteHistoryDialog by remember { mutableStateOf(false) }
    var showReproduceParamsDialog by remember { mutableStateOf(false) }
    var pendingReproduceParams by remember { mutableStateOf<GenerationParameters?>(null) }

    // Parameter share state
    var shareSourceParams by remember { mutableStateOf<GenerationParameters?>(null) }
    var shareSourceModelId by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<ImportedParams?>(null) }
    var clipboardImportChecked by remember { mutableStateOf(false) }
    val shareUseBase64 by remember { generationPreferences.observeShareUseBase64() }
        .collectAsState(initial = true)
    val shareClearClipboardOnImport by remember {
        generationPreferences.observeShareClearClipboardOnImport()
    }.collectAsState(initial = true)

    // Selection mode state
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showBatchSaveDialog by remember { mutableStateOf(false) }
    var isBatchSaving by remember { mutableStateOf(false) }
    var batchSaveTotal by remember { mutableIntStateOf(0) }
    var batchSaveCurrent by remember { mutableIntStateOf(0) }
    var batchSaveFailed by remember { mutableIntStateOf(0) }

    var generationParamsTmp by remember {
        mutableStateOf(
            GenerationParameters(
                steps = 0,
                cfg = 0f,
                seed = 0,
                prompt = "",
                negativePrompt = "",
                generationTime = "",
                width = defaultGenerationSize(
                    model?.usesFixedCanvas == true,
                    model?.runOnCpu == true,
                    model?.isDit == true,
                ),
                height = defaultGenerationSize(
                    model?.usesFixedCanvas == true,
                    model?.runOnCpu == true,
                    model?.isDit == true,
                ),
                runOnCpu = model?.runOnCpu ?: false,
            ),
        )
    }
    var cfg by remember { mutableFloatStateOf(GenerationDefaults.GLOBAL.cfg) }
    var steps by remember { mutableFloatStateOf(GenerationDefaults.GLOBAL.steps) }
    var seed by remember { mutableStateOf(GenerationDefaults.GLOBAL.seed) }
    var denoiseStrength by remember { mutableFloatStateOf(GenerationDefaults.GLOBAL.denoiseStrength) }
    var useOpenCL by remember { mutableStateOf(false) }
    var batchCounts by remember { mutableIntStateOf(GenerationDefaults.GLOBAL.batchCounts) }
    var scheduler by remember { mutableStateOf(GenerationDefaults.GLOBAL.scheduler) }
    var aspectRatio by remember { mutableStateOf(GenerationDefaults.GLOBAL.aspectRatio) }
    var showCustomAspectRatioDialog by remember { mutableStateOf(false) }
    var currentBatchIndex by remember { mutableIntStateOf(0) }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var base64EncodeDone by remember { mutableStateOf(false) }
    var returnedSeed by remember { mutableStateOf<Long?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isCheckingBackend by remember { mutableStateOf(true) }

    // True only after a health check succeeded (and reset when a restart
    // begins). Gates tokenizer calls: "checking finished" alone also covers
    // the failure case, where firing tokenize requests is pointless.
    var backendReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (isRemote) {
            // After process recreation the remote catalog is empty; re-fetch it
            // from the saved host so the model resolves again.
            remoteRepository.restore()
            if (remoteRepository.models.isEmpty()) {
                remoteRepository.refresh()
            }
            if (remoteRepository.models.none { it.id == modelId }) {
                // Host unreachable or the model is gone from it: drop the
                // loading overlay so the offline body below is visible.
                isCheckingBackend = false
            }
        }
    }
    var showParametersDialog by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    var generationStartTime by remember { mutableStateOf<Long?>(null) }
    var hasInitialized by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }

    // The prompt fields live on page 0. When the user swipes to the result or
    // history page the suggestion popup is anchored absolutely and would linger,
    // so drop focus (which clears the suggestions) as soon as we leave page 0.
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != 0) {
            focusManager.clearFocus()
        }
    }

    var currentWidth by remember {
        mutableIntStateOf(
            defaultGenerationSize(
                model?.usesFixedCanvas == true,
                model?.runOnCpu == true,
                model?.isDit == true,
            ),
        )
    }
    var currentHeight by remember {
        mutableIntStateOf(
            defaultGenerationSize(
                model?.usesFixedCanvas == true,
                model?.runOnCpu == true,
                model?.isDit == true,
            ),
        )
    }
    var availableResolutions by remember { mutableStateOf<List<Resolution>>(emptyList()) }
    var showResolutionChangeDialog by remember { mutableStateOf(false) }
    var pendingResolution by remember { mutableStateOf<Resolution?>(null) }
    var backendRestartTrigger by remember { mutableIntStateOf(0) }
    var showAdvancedSettings by remember { mutableStateOf(false) }

    var isPreviewMode by remember { mutableStateOf(false) }
    val preferences = remember {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }
    // Both settings can only change on the model list screen, so a snapshot
    // taken once per screen entry is enough; re-reading on every recomposition
    // would hit SharedPreferences in the hottest path of this composable.
    // In remote mode the img2img capability is the host's, not this device's.
    val localUseImg2img = remember { preferences.getBoolean("use_img2img", true) }
    val useImg2img = if (isRemote) remoteRepository.useImg2img else localUseImg2img
    val enableTagAutocomplete = remember { preferences.getBoolean("enable_tag_autocomplete", true) }
    val tagSuggestionCount = 128
    val tagAutocompleteRepository = remember { TagAutocompleteRepository.getInstance(context) }
    val tagDictState by tagAutocompleteRepository.state.collectAsState()
    val tagAutocompleteAvailable = enableTagAutocomplete && tagDictState.mainImported

    LaunchedEffect(tagAutocompleteAvailable) {
        if (tagAutocompleteAvailable) {
            tagAutocompleteRepository.warmUp()
        }
    }

    // Names of imported textual-inversion embeddings (filename stems). Refreshed
    // when either prompt field gains focus so newly-imported embeddings show up
    // without re-entering the screen.
    var embeddingNames by remember { mutableStateOf<List<String>>(emptyList()) }

    // Build embedding TagSuggestion rows for the current query. Returns at most
    // `limit` entries: prefix matches first, then contains matches. Comparison
    // normalizes spaces/dashes to underscores so users typing either form match.
    fun embeddingSuggestionsFor(query: String, limit: Int = 5): List<TagSuggestion> {
        if (embeddingNames.isEmpty()) return emptyList()
        val q = query.trim()
            .lowercase()
            .replace(' ', '_')
            .replace('-', '_')
        if (q.isEmpty()) return emptyList()
        val prefix = mutableListOf<TagSuggestion>()
        val contains = mutableListOf<TagSuggestion>()
        for (name in embeddingNames) {
            val normalized = name.lowercase().replace(' ', '_').replace('-', '_')
            val idx = normalized.indexOf(q)
            if (idx < 0) continue
            val suggestion = TagSuggestion(
                replacementTag = name,
                primaryText = name,
                secondaryText = null,
                matchType = TagMatchType.Embedding,
                category = 0,
                postCount = 0,
                score = 0,
            )
            if (idx == 0) prefix += suggestion else contains += suggestion
        }
        return (prefix + contains).take(limit)
    }

    val promptField = rememberPromptFieldController(
        repository = tagAutocompleteRepository,
        suggestionCount = tagSuggestionCount,
        embeddingSuggestionsFor = { embeddingSuggestionsFor(it) },
    )
    val negativePromptField = rememberPromptFieldController(
        repository = tagAutocompleteRepository,
        suggestionCount = tagSuggestionCount,
        embeddingSuggestionsFor = { embeddingSuggestionsFor(it) },
    )
    promptField.autocompleteAvailable = tagAutocompleteAvailable
    negativePromptField.autocompleteAvailable = tagAutocompleteAvailable

    LaunchedEffect(promptField.isFocused, negativePromptField.isFocused) {
        if (!promptField.isFocused && !negativePromptField.isFocused) return@LaunchedEffect
        val names = withContext(Dispatchers.IO) {
            File(context.filesDir, "embeddings")
                .takeIf { it.isDirectory }
                ?.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.extension.equals("safetensors", ignoreCase = true) }
                ?.map { it.nameWithoutExtension }
                ?.sortedBy { it.lowercase() }
                ?.toList()
                .orEmpty()
        }
        embeddingNames = names
    }

    var showCropScreen by remember { mutableStateOf(false) }
    var imageUriForCrop by remember { mutableStateOf<Uri?>(null) }
    var croppedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var showInpaintScreen by remember { mutableStateOf(false) }
    var maskBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isInpaintMode by remember { mutableStateOf(false) }
    var savedPathHistory by remember { mutableStateOf<List<PathData>?>(null) }
    var cropRect by remember { mutableStateOf<AndroidRect?>(null) }

    var showDrawScreen by remember { mutableStateOf(false) }
    // Transparent edits are also retained for full-image inpaint exports.
    var drawingOverlayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var snapshotDrawingOverlayBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // True only when selectedImageUri points to a real source image from the gallery picker.
    // False when img2img was seeded from a result/history bitmap (selectedImageUri is a
    // synthetic tmp.txt path that holds base64, not a decodable image).
    var hasOriginalImageForStitch by remember { mutableStateOf(false) }

    var snapshotIsInpaintMode by remember { mutableStateOf(false) }
    var snapshotSelectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var snapshotCropRect by remember { mutableStateOf<AndroidRect?>(null) }
    var snapshotMaskBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var snapshotHasOriginalImage by remember { mutableStateOf(false) }
    // History-item ids whose bitmaps may be stitched back into the inpaint source:
    // the just-completed inpaint generation plus any upscaled copies derived from
    // it. Compared against currentDisplayedHistoryId so saving only stitches when
    // the bitmap on screen really is one of those (regardless of whether it's the
    // original Bitmap object or a fresh decode from clicking the thumbnail again).
    var stitchableHistoryIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var currentDisplayedHistoryId by remember { mutableStateOf<Long?>(null) }
    // Favorite flag of the image currently on the result page, looked up by id
    // so it stays correct even when the row isn't in a loaded history page.
    val displayedFavorite by remember(currentDisplayedHistoryId) {
        currentDisplayedHistoryId
            ?.let { historyManager.observeFavorite(it) }
            ?: flowOf(null)
    }.collectAsState(initial = null)

    var saveAllJob: Job? by remember { mutableStateOf(null) }
    var batchGenerationJob: Job? by remember { mutableStateOf(null) }

    // Upscaler related states
    var showUpscalerDialog by remember { mutableStateOf(false) }
    var isUpscaling by remember { mutableStateOf(false) }

    // Ultrafix (tiled img2img repair of an upscaled image) states.
    // pendingUltrafix marks the in-flight generation as an ultrafix run so the
    // completion handler can record the right mode.
    var showUltrafixConfirmDialog by remember { mutableStateOf(false) }
    var showUltrafixImportDialog by remember { mutableStateOf(false) }
    var pendingUltrafix by remember { mutableStateOf(false) }
    var isUltrafixPreparing by remember { mutableStateOf(false) }
    // UltraFix runs with its own steps/denoise (defaults 10 / 0.4), persisted
    // globally and kept independent of the main generation params so tweaking
    // them in the UltraFix dialog never touches the prompt-page settings.
    var ultrafixSteps by remember { mutableFloatStateOf(GenerationDefaults.GLOBAL.ultrafixSteps) }
    // Denoise is controlled as a step count (0..min(10, steps)); the backend
    // strength is derived from it at run time.
    var ultrafixDenoiseSteps by remember {
        mutableIntStateOf(GenerationDefaults.GLOBAL.ultrafixDenoiseSteps)
    }
    // On (default): UltraFix runs on neutral quality tags instead of the
    // prompt-page prompt. Off: uses the prompt-page prompt (legacy behavior).
    var ultrafixQualityDenoise by remember {
        mutableStateOf(GenerationDefaults.GLOBAL.ultrafixQualityDenoise)
    }
    var ultrafixSaveJob: Job? by remember { mutableStateOf(null) }
    LaunchedEffect(Unit) {
        ultrafixSteps = generationPreferences.observeUltrafixSteps(modelId).first()
        val maxDenoiseSteps =
            minOf(GenerationDefaults.ULTRAFIX_DENOISE_STEPS_MAX, ultrafixSteps.roundToInt())
        ultrafixDenoiseSteps =
            generationPreferences.observeUltrafixDenoiseSteps(modelId).first().coerceIn(0, maxDenoiseSteps)
        ultrafixQualityDenoise = generationPreferences.observeUltrafixQualityDenoise(modelId).first()
    }
    val upscalerRepository = remember { UpscalerRepository.getInstance(context) }
    val upscalerPreferences =
        remember { context.getSharedPreferences("upscaler_prefs", Context.MODE_PRIVATE) }

    // (effectiveWidth, effectiveHeight) is the size of the visible result.
    // For SDXL with non-1:1 aspect_ratio it equals the centered target_w/target_h
    // inside the 1024x1024 generation canvas; otherwise it equals the canvas itself.
    val effectiveSize = remember(model?.usesFixedCanvas, aspectRatio, currentWidth, currentHeight) {
        computeAspectTargetSize(model?.usesFixedCanvas == true, aspectRatio)
            ?: Pair(currentWidth, currentHeight)
    }
    val effectiveWidth = effectiveSize.first
    val effectiveHeight = effectiveSize.second

    fun clearImg2imgState() {
        selectedImageUri = null
        croppedBitmap = null
        drawingOverlayBitmap = null
        maskBitmap = null
        isInpaintMode = false
        cropRect = null
        savedPathHistory = null
        base64EncodeDone = false
        hasOriginalImageForStitch = false
    }

    fun saveAllFields() {
        saveAllJob?.cancel()
        saveAllJob = scope.launch(Dispatchers.IO) {
            delay(1000)
            generationPreferences.saveAllFields(
                modelId = modelId,
                prompt = promptField.text,
                negativePrompt = negativePromptField.text,
                steps = steps,
                cfg = cfg,
                seed = seed,
                width = currentWidth,
                height = currentHeight,
                denoiseStrength = denoiseStrength,
                useOpenCL = useOpenCL,
                batchCounts = batchCounts,
                scheduler = scheduler,
                aspectRatio = aspectRatio,
            )
        }
    }

    fun saveUltrafixParams() {
        ultrafixSaveJob?.cancel()
        ultrafixSaveJob = scope.launch(Dispatchers.IO) {
            delay(500)
            generationPreferences.saveUltrafixParams(
                modelId,
                ultrafixSteps,
                ultrafixDenoiseSteps,
                ultrafixQualityDenoise,
            )
        }
    }

    val onStepsChange = remember {
        { value: Float ->
            steps = value
            saveAllFields()
        }
    }
    val onCfgChange = remember {
        { value: Float ->
            cfg = value
            saveAllFields()
        }
    }
    val onSizeChange = remember {
        { value: Float ->
            val rounded = (value / 64).roundToInt() * 64
            val newSize = rounded.coerceIn(128, 512)
            currentWidth = newSize
            currentHeight = newSize
            saveAllFields()
        }
    }
    // DiT models take the requested size directly, so width and height are two
    // plain controls; aspectRatio is kept in step for the img2img crop and for
    // whatever reads it back from history.
    val onDitWidthChange = remember {
        { value: Float ->
            currentWidth = snapDitSize(value)
            aspectRatio = inferAspectRatioString(currentWidth, currentHeight)
            saveAllFields()
        }
    }
    val onDitHeightChange = remember {
        { value: Float ->
            currentHeight = snapDitSize(value)
            aspectRatio = inferAspectRatioString(currentWidth, currentHeight)
            saveAllFields()
        }
    }
    val onDenoiseStrengthChange =
        remember {
            { value: Float ->
                denoiseStrength = value
                saveAllFields()
            }
        }
    val onSeedChange = remember {
        { value: String ->
            seed = value
            saveAllFields()
        }
    }
    promptField.onTextCommitted = { saveAllFields() }
    negativePromptField.onTextCommitted = { saveAllFields() }

    PromptTokenCountEffect(promptField, backendReady = backendReady, backendHost = backendHost)
    PromptTokenCountEffect(negativePromptField, backendReady = backendReady, backendHost = backendHost)

    val onBatchCountsChange = remember {
        { value: Float ->
            batchCounts = value.roundToInt().coerceIn(1, 10)
            saveAllFields()
        }
    }

    fun processSelectedImage(uri: Uri) {
        imageUriForCrop = uri
        showCropScreen = true
    }

    @Suppress("UnusedParameter") // base64String from cropify callback is re-derived later
    fun handleCropComplete(base64String: String, bitmap: Bitmap, rect: AndroidRect) {
        showCropScreen = false
        val sourceUri = imageUriForCrop
        selectedImageUri = sourceUri
        imageUriForCrop = null
        hasOriginalImageForStitch = true

        // CropImageScreen returns the cropped bitmap via cropify, whose output
        // can carry a sub-pixel offset relative to the cropRect we computed
        // from frameRect / imageRect. That's invisible when the patch is later
        // pasted back as a whole (SD1.5 / SDXL 1:1), but in SDXL aspect-pad
        // mode the patch goes through scale → pad → backend center-crop →
        // scale-back, and the per-step rounding leaks the offset as a
        // few-pixel stitch misalignment.
        //
        // Fix: re-crop directly from the original image using BitmapRegionDecoder
        // so the bitmap content is *strictly* the cropRect region in the
        // original's coordinate space. cropRect is also clamped to the original
        // bounds, and the clamped value is saved so stitch later paints to the
        // exact same pixel range we cropped from.
        scope.launch(Dispatchers.IO) {
            try {
                base64EncodeDone = false
                val aspectTarget =
                    computeAspectTargetSize(model?.usesFixedCanvas == true, aspectRatio)
                val targetW = aspectTarget?.first ?: currentWidth
                val targetH = aspectTarget?.second ?: currentHeight

                var clampedRect = rect
                val freshCropped: Bitmap? = try {
                    sourceUri?.let { uri ->
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            @Suppress("DEPRECATION")
                            val decoder = BitmapRegionDecoder.newInstance(input, false)
                                ?: throw IllegalStateException(
                                    "BitmapRegionDecoder.newInstance returned null",
                                )
                            try {
                                val safeLeft = rect.left.coerceAtLeast(0)
                                val safeTop = rect.top.coerceAtLeast(0)
                                val safeRight = rect.right.coerceAtMost(decoder.width)
                                val safeBottom = rect.bottom.coerceAtMost(decoder.height)
                                if (safeRight > safeLeft && safeBottom > safeTop) {
                                    val region = snapInpaintCropRect(
                                        rect = AndroidRect(safeLeft, safeTop, safeRight, safeBottom),
                                        imageWidth = decoder.width,
                                        imageHeight = decoder.height,
                                        tolerance = (2f * context.resources.displayMetrics.density)
                                            .roundToInt().coerceAtLeast(1),
                                    )
                                    clampedRect = region
                                    decoder.decodeRegion(region, BitmapFactory.Options())
                                } else {
                                    null
                                }
                            } finally {
                                decoder.recycle()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(
                        "ModelRunScreen",
                        "BitmapRegionDecoder failed, fall back to cropify bitmap: ${e.message}",
                    )
                    null
                }

                val sourceBitmap = freshCropped ?: bitmap

                val scaled = withContext(Dispatchers.Default) {
                    if (sourceBitmap.width != targetW || sourceBitmap.height != targetH) {
                        sourceBitmap.scale(targetW, targetH)
                    } else {
                        sourceBitmap
                    }
                }

                val needsPad =
                    scaled.width != currentWidth || scaled.height != currentHeight
                val payload = if (needsPad) {
                    bitmapToBase64Png(padBitmapToCanvas(scaled, currentWidth, currentHeight))
                } else {
                    bitmapToBase64Png(scaled)
                }

                withContext(Dispatchers.Main) {
                    cropRect = clampedRect
                    croppedBitmap = scaled
                    drawingOverlayBitmap = null
                }

                val tmpFile = File(context.filesDir, "tmp.txt")
                tmpFile.writeText(payload)
                base64EncodeDone = true
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        msgSaveFailed.format(e.message ?: msgUnknownError),
                        Toast.LENGTH_SHORT,
                    ).show()
                    selectedImageUri = null
                    croppedBitmap = null
                    drawingOverlayBitmap = null
                    cropRect = null
                    hasOriginalImageForStitch = false
                }
            }
        }
    }

    fun handleInpaintComplete(maskBase64: String, maskBmp: Bitmap, pathHistory: List<PathData>) {
        showInpaintScreen = false
        isInpaintMode = true
        maskBitmap = maskBmp
        savedPathHistory = pathHistory

        scope.launch(Dispatchers.IO) {
            try {
                // The mask comes back at target size (matching the cropped image fed
                // into InpaintScreen). For SDXL aspect-pad mode we re-encode after
                // padding to currentWidth x currentHeight so it lines up with the
                // padded image upload.
                val needsPad = maskBmp.width != currentWidth || maskBmp.height != currentHeight
                val payload = if (needsPad) {
                    bitmapToBase64Png(padBitmapToCanvas(maskBmp, currentWidth, currentHeight))
                } else {
                    maskBase64
                }
                val maskFile = File(context.filesDir, "mask.txt")
                maskFile.writeText(payload)

                withContext(Dispatchers.Main) {
                    base64EncodeDone = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        msgSaveFailed.format(e.message ?: msgUnknownError),
                        Toast.LENGTH_SHORT,
                    ).show()
                    isInpaintMode = false
                    maskBitmap = null
                    savedPathHistory = null
                }
            }
        }
    }

    fun sendBitmapToImg2img(bitmap: Bitmap) {
        scope.launch {
            val ready = try {
                base64EncodeDone = false
                val aspectTarget = computeAspectTargetSize(model?.usesFixedCanvas == true, aspectRatio)
                val targetW = aspectTarget?.first ?: currentWidth
                val targetH = aspectTarget?.second ?: currentHeight

                // 1) Center-crop+scale the source to (targetW, targetH).
                // 2) If aspect padding is in effect, pad up to (currentWidth, currentHeight).
                val resized = withContext(Dispatchers.Default) {
                    val srcRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                    val dstRatio = targetW.toFloat() / targetH.toFloat()
                    val centerCropped = if (kotlin.math.abs(srcRatio - dstRatio) < 1e-3f) {
                        bitmap
                    } else {
                        val (cropW, cropH) = if (srcRatio > dstRatio) {
                            Pair((bitmap.height * dstRatio).toInt(), bitmap.height)
                        } else {
                            Pair(bitmap.width, (bitmap.width / dstRatio).toInt())
                        }
                        val cx = (bitmap.width - cropW) / 2
                        val cy = (bitmap.height - cropH) / 2
                        Bitmap.createBitmap(bitmap, cx, cy, cropW, cropH)
                    }
                    val scaled =
                        if (centerCropped.width != targetW || centerCropped.height != targetH) {
                            centerCropped.scale(targetW, targetH)
                        } else {
                            centerCropped.copy(Bitmap.Config.ARGB_8888, false)
                        }
                    scaled
                }

                val displayBitmap = resized
                val uploadBitmap =
                    if (resized.width != currentWidth || resized.height != currentHeight) {
                        padBitmapToCanvas(resized, currentWidth, currentHeight)
                    } else {
                        resized
                    }

                val base64String = withContext(Dispatchers.IO) {
                    bitmapToBase64Png(uploadBitmap)
                }

                withContext(Dispatchers.IO) {
                    File(context.filesDir, "tmp.txt").writeText(base64String)
                }

                croppedBitmap = displayBitmap
                drawingOverlayBitmap = null
                cropRect = AndroidRect(0, 0, displayBitmap.width, displayBitmap.height)
                selectedImageUri = Uri.fromFile(File(context.filesDir, "tmp.txt"))
                hasOriginalImageForStitch = false
                base64EncodeDone = true
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    msgImg2imgFailed.format(e.message ?: msgUnknownError),
                    Toast.LENGTH_SHORT,
                ).show()
                base64EncodeDone = false
                selectedImageUri = null
                croppedBitmap = null
                drawingOverlayBitmap = null
                cropRect = null
                hasOriginalImageForStitch = false
                false
            }

            if (ready) {
                try {
                    pagerState.animateScrollToPage(0)
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // Animation interrupted by another scroll — img2img data is already set, ignore
                }
            }
        }
    }

    // Runs tiled img2img ("ultrafix") over the currently displayed (upscaled)
    // bitmap at its full resolution, using the prompt-page parameters. The
    // base image goes through its own file so a pending img2img selection in
    // tmp.txt is left untouched.
    fun startUltrafix() {
        val bmp = currentBitmap ?: return
        val tileSize = maxOf(currentWidth, currentHeight)
        val totalSteps = ultrafixSteps.roundToInt()
        // Derive the strength that makes the backend run exactly the chosen
        // number of denoise steps (clamped to the total).
        val ultrafixDenoiseStrength = ultrafixDenoiseStrength(ultrafixDenoiseSteps, totalSteps)
        // When quality-denoise is on (default), UltraFix runs on neutral quality
        // tags instead of the prompt-page prompt; off uses the box prompt.
        val ultrafixPrompt =
            if (ultrafixQualityDenoise) GenerationDefaults.ULTRAFIX_QUALITY_PROMPT else promptField.text
        isUltrafixPreparing = true
        generationParamsTmp = GenerationParameters(
            steps = totalSteps,
            cfg = cfg,
            seed = 0,
            // Record the prompt UltraFix actually ran with (quality tags when
            // the toggle is on), so history reflects what produced the result.
            prompt = ultrafixPrompt,
            negativePrompt = negativePromptField.text,
            generationTime = "",
            width = bmp.width,
            height = bmp.height,
            runOnCpu = false,
            denoiseStrength = ultrafixDenoiseStrength,
            useOpenCL = false,
            scheduler = scheduler,
        )
        batchGenerationJob = coroutineScope.launch {
            // The progress card lives on the prompt page; bring it into view.
            try {
                pagerState.animateScrollToPage(0)
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Scroll interrupted by another gesture; the run still starts.
            }
            val started = try {
                withContext(Dispatchers.IO) {
                    File(context.filesDir, "ultrafix.txt").writeText(bitmapToBase64Jpeg(bmp))
                }
                pendingUltrafix = true
                val intent = Intent(context, BackgroundGenerationService::class.java).apply {
                    putExtra("prompt", ultrafixPrompt)
                    putExtra("negative_prompt", negativePromptField.text)
                    putExtra("steps", totalSteps)
                    putExtra("cfg", cfg)
                    seed.toLongOrNull()?.let { putExtra("seed", it) }
                    putExtra("width", bmp.width)
                    putExtra("height", bmp.height)
                    putExtra("effective_width", bmp.width)
                    putExtra("effective_height", bmp.height)
                    putExtra("denoise_strength", ultrafixDenoiseStrength)
                    putExtra("scheduler", scheduler)
                    putExtra("ultrafix", true)
                    putExtra("ultrafix_tile_size", tileSize)
                    putExtra("backend_host", backendHost)
                }
                context.startForegroundService(intent)
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingUltrafix = false
                Toast.makeText(
                    context,
                    msgUltrafixFailed.format(e.message ?: "Unknown error"),
                    Toast.LENGTH_SHORT,
                ).show()
                false
            } finally {
                isUltrafixPreparing = false
            }
            if (!started) return@launch

            // Same teardown as the batch loop: wait for the terminal state and
            // the service stop, then clear the running flag so the prompt-page
            // progress card doesn't linger at 0%.
            BackgroundGenerationService.generationState.first { state ->
                state is GenerationState.Complete || state is GenerationState.Error
            }
            withTimeoutOrNull(5000L) {
                BackgroundGenerationService.isServiceRunning.first { !it }
            }
            BackgroundGenerationService.resetState()
            isRunning = false
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        PickVisualMedia(),
    ) { uri ->
        uri?.let { processSelectedImage(it) }
    }

    // Places an imported (already size-validated and resized) local image
    // into the result slot as the UltraFix source. Synthetic params: only the
    // dimensions matter (they drive the size gating); everything else marks
    // "not generated by this app". The import itself is never written to
    // history - only the repaired result is.
    fun applyUltrafixImport(bitmap: Bitmap) {
        currentBitmap = bitmap
        generationParams = GenerationParameters(
            steps = 0,
            cfg = cfg,
            seed = null,
            prompt = "",
            negativePrompt = "",
            generationTime = null,
            width = bitmap.width,
            height = bitmap.height,
            runOnCpu = false,
            mode = GenerationMode.UNKNOWN,
        )
        generationParamsModelId = modelId
        currentDisplayedHistoryId = null
        stitchableHistoryIds = emptySet()
        imageVersion++
    }

    val contentPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let { processSelectedImage(it) }
    }

    val requestStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            contentPickerLauncher.launch("image/*")
        } else {
            Toast.makeText(
                context,
                msgMediaPermissionHint,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun onSelectImageClick() {
        when {
            // Android 13+
            Build.VERSION.SDK_INT >= 33 -> {
                // PhotoPicker API
                photoPickerLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
            }

            // Android 12-
            else -> {
                when {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                    ) == PackageManager.PERMISSION_GRANTED -> {
                        contentPickerLauncher.launch("image/*")
                    }

                    else -> {
                        requestStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }
            }
        }
    }

    fun handleSaveImage(context: Context, bitmap: Bitmap, onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (!checkStoragePermission(context)) {
            onError("need storage permission to save image")
            return
        }

        // Only stitch when:
        //  - the image currently shown is the most recent inpaint generation or an
        //    upscaled copy of it (matched via history-item id, so clicking another
        //    thumbnail and back still works while clicks on unrelated thumbnails
        //    don't stitch), and
        //  - the source img2img/inpaint image was a real gallery image with a decodable
        //    URI (not a synthetic tmp.txt from sendBitmapToImg2img).
        val shouldStitch = snapshotIsInpaintMode &&
            snapshotCropRect != null &&
            snapshotSelectedImageUri != null &&
            snapshotHasOriginalImage &&
            currentDisplayedHistoryId != null &&
            currentDisplayedHistoryId in stitchableHistoryIds

        coroutineScope.launch {
            if (shouldStitch) {
                withContext(Dispatchers.IO) {
                    try {
                        val originalBitmap =
                            context.contentResolver.openInputStream(snapshotSelectedImageUri!!)!!
                                .use { BitmapFactory.decodeStream(it) }

                        val mutableOriginal =
                            originalBitmap.copy(Bitmap.Config.ARGB_8888, true)

                        snapshotDrawingOverlayBitmap?.let { drawing ->
                            drawImageOverlay(mutableOriginal, drawing, snapshotCropRect!!)
                        }

                        val rectW = snapshotCropRect!!.width()
                        val rectH = snapshotCropRect!!.height()
                        val resizedPatch = bitmap.scale(rectW, rectH)

                        // Feather-blend along the mask instead of pasting the
                        // whole rectangle: the patch's unmasked pixels went
                        // through a resample round trip and differ subtly from
                        // the original, which shows up as a square seam.
                        drawInpaintPatch(
                            target = mutableOriginal,
                            patch = resizedPatch,
                            mask = snapshotMaskBitmap,
                            left = snapshotCropRect!!.left,
                            top = snapshotCropRect!!.top,
                        )

                        saveImage(
                            context = context,
                            bitmap = mutableOriginal,
                            onSuccess = onSuccess,
                            onError = onError,
                        )
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            onError("Failed to create composite image: ${e.localizedMessage}")
                        }
                    }
                }
            } else {
                saveImage(
                    context = context,
                    bitmap = bitmap,
                    onSuccess = onSuccess,
                    onError = onError,
                )
            }
        }
    }

    fun cleanup() {
        try {
            currentBitmap = null
            generationParams = null
            BackgroundGenerationService.stop(context)
            if (isRemote) {
                // Ask the host to unload its backend, mirroring the local
                // teardown semantics (leaving the screen releases the model).
                // Scoped to this model id: the host ignores the stop if a
                // newer selection (e.g. the next screen's model) has landed
                // first, so a delayed stop can never kill it.
                remoteRepository.stopHostBackendAsync(modelId)
            } else {
                // Use an explicit STOP command instead of stopService(): when the
                // backend was just started, its Service may not have reached
                // onCreate yet, and stopService() on a not-yet-running Service is a
                // no-op that would leak the native process. Routing through
                // onStartCommand(ACTION_STOP) guarantees the stop is honored after
                // the pending start, mirroring BackgroundGenerationService.stop().
                val backendServiceIntent = Intent(context, BackendService::class.java)
                    .setAction(BackendService.ACTION_STOP)
                context.startForegroundService(backendServiceIntent)
            }
            isRunning = false
            progress = 0f
            errorMessage = null
            currentBatchIndex = 0
            generationStartTime = null
            BackgroundGenerationService.resetState()
            coroutineScope.launch {
                pagerState.scrollToPage(0)
            }
            saveAllJob?.cancel()
            batchGenerationJob?.cancel()
        } catch (e: Exception) {
            Log.e("ModelRunScreen", "error", e)
        }
    }

    fun handleExit() {
        cleanup()
        BackgroundGenerationService.clearCompleteState()
        navController.navigateUp()
    }

    // Stops the in-flight generation (single or batch) but keeps the backend
    // and the screen alive, so the user can immediately generate again.
    fun interruptGeneration() {
        batchGenerationJob?.cancel()
        batchGenerationJob = null
        BackgroundGenerationService.stop(context)
        BackgroundGenerationService.resetState()
        intermediateBitmap = null
        pendingUltrafix = false
        isRunning = false
        progress = 0f
        currentBatchIndex = 0
        generationStartTime = null
        Toast.makeText(
            context,
            msgGenerationInterrupted,
            Toast.LENGTH_SHORT,
        ).show()
    }

    DisposableEffect(modelId) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val captureEnabled = prefs.getBoolean("enable_log_capture", false)
        if (captureEnabled) {
            LogCapture.start()
        }
        onDispose {
            if (captureEnabled) {
                LogCapture.stopAndPublish()
            }
            // Safety net for paths that bypass handleExit() (e.g. predictive back
            // popping the destination while not running).
            BackgroundGenerationService.clearCompleteState()
        }
    }

    LaunchedEffect(modelId, model?.runOnCpu) {
        if (model?.runOnCpu == false && !model.usesFixedCanvas && !model.isDit) {
            val baseResolution = Resolution(512, 512)
            // Remote models report the host's patch resolutions in the
            // catalog; local ones are scanned from the model directory.
            val patchResolutions = if (isRemote) {
                remoteRepository.resolutionsFor(modelId)
            } else {
                withContext(Dispatchers.IO) {
                    PatchScanner.scanAvailableResolutions(context, modelId)
                }
            }

            val allResolutions =
                (listOf(baseResolution) + patchResolutions).distinctBy { "${it.width}x${it.height}" }
            availableResolutions = allResolutions
        }
    }

    LaunchedEffect(modelId, model) {
        if (!hasInitialized && model != null) {
            val prefs = generationPreferences.getPreferences(modelId).first()
            val isFirstRun = !prefs.hasSaved
            val defaults = model.defaults

            promptField.replaceText(if (isFirstRun) defaults.prompt else prefs.prompt)
            negativePromptField.replaceText(
                if (isFirstRun) defaults.negativePrompt else prefs.negativePrompt,
            )

            steps = if (isFirstRun) defaults.steps else prefs.steps
            cfg = if (isFirstRun) defaults.cfg else prefs.cfg
            seed = prefs.seed
            denoiseStrength = prefs.denoiseStrength
            useOpenCL = prefs.useOpenCL
            batchCounts = prefs.batchCounts
            scheduler = if (isFirstRun) defaults.scheduler else prefs.scheduler
            // Without img2img the backend has no VAE encoder, so a stored
            // non-1:1 ratio would silently fall back to 1024x1024 anyway.
            aspectRatio = if (useImg2img) prefs.aspectRatio else "1:1"

            currentWidth = when {
                model.usesFixedCanvas -> 1024

                prefs.width == -1 -> defaultGenerationSize(
                    usesFixedCanvas = false,
                    runOnCpu = model.runOnCpu,
                    isDit = model.isDit,
                )

                else -> if (model.isDit) snapDitSize(prefs.width.toFloat()) else prefs.width
            }
            currentHeight = when {
                model.usesFixedCanvas -> 1024

                prefs.height == -1 -> defaultGenerationSize(
                    usesFixedCanvas = false,
                    runOnCpu = model.runOnCpu,
                    isDit = model.isDit,
                )

                else -> if (model.isDit) snapDitSize(prefs.height.toFloat()) else prefs.height
            }

            // Preferences are keyed by bare modelId and shared with a local
            // model of the same id, so the saved resolution may be a patch
            // the HOST doesn't have (e.g. 768 saved locally, host only has
            // 512). Sending it would make the host silently fall back to 512
            // while this screen still generates at 768 - shape mismatch.
            if (isRemote && !model.usesFixedCanvas && !model.runOnCpu && !model.isDit) {
                val allowed = listOf(Resolution(512, 512)) +
                    remoteRepository.resolutionsFor(modelId)
                if (allowed.none { it.width == currentWidth && it.height == currentHeight }) {
                    currentWidth = 512
                    currentHeight = 512
                }
            }

            if (isFirstRun ||
                (model.isDit && (prefs.width != currentWidth || prefs.height != currentHeight))
            ) {
                saveAllFields()
            }

            hasInitialized = true
        }
    }

    LaunchedEffect(hasInitialized) {
        if (hasInitialized) {
            if (isRemote) {
                // Ask the host to start (or keep serving) this model, and only
                // then kick off the health check (via the restart trigger):
                // polling in parallel could see the host still Ready on a
                // previous selection.
                val ok = remoteClient?.selectModel(modelId, currentWidth, currentHeight)
                    ?: false
                if (ok) {
                    backendRestartTrigger++
                } else {
                    isCheckingBackend = false
                    errorMessage = msgRemoteSelectFailed
                }
            } else {
                // Always declare the target; BackendService reconciles idempotently
                // (reuses a live process for the same model, restarts otherwise).
                // Reading the shared backendState here to decide would race with the
                // previous screen's still-pending stop and could skip the start.
                val intent = Intent(context, BackendService::class.java).apply {
                    putExtra("modelId", model?.id)
                    putExtra("backendType", model?.backendType)
                    putExtra("width", currentWidth)
                    putExtra("height", currentHeight)
                    putExtra("use_opencl", useOpenCL)
                }
                context.startForegroundService(intent)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_DESTROY -> {
                    cleanup()
                }

                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cleanup()
        }
    }

    // Seed the result page with the most recent history image when nothing
    // has been generated this session yet; the empty state only remains for
    // models without any history. Re-checked after the decode so a result
    // that completed meanwhile is never overwritten.
    LaunchedEffect(recentHistory) {
        if (currentBitmap != null) return@LaunchedEffect
        val item = recentHistory.firstOrNull() ?: return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(item.imageFile.absolutePath)
        }
        if (bitmap != null && currentBitmap == null) {
            currentBitmap = bitmap
            generationParams = item.params
            generationParamsModelId = item.modelId
            currentDisplayedHistoryId = item.id
            imageVersion++
        }
    }

    LaunchedEffect(serviceState) {
        when (val state = serviceState) {
            is GenerationState.Progress -> {
                if (generationStartTime == null) {
                    generationStartTime = System.currentTimeMillis()
                }
                progress = state.progress
                isRunning = true
                state.intermediateImage?.let { intermediateBitmap = it }
            }

            is GenerationState.Complete -> {
                intermediateBitmap = null
                withContext(Dispatchers.Main) {
                    Log.d("ModelRunScreen", "update bitmap")

                    state.seed?.let { returnedSeed = it }
                    progress = 0f

                    val genTime = generationStartTime?.let { startTime ->
                        val endTime = System.currentTimeMillis()
                        val duration = endTime - startTime
                        when {
                            duration < 1000 -> "${duration}ms"

                            duration < 60000 -> String.format(Locale.US, "%.1fs", duration / 1000.0)

                            else -> String.format(
                                Locale.US,
                                "%dm%ds",
                                duration / 60000,
                                (duration % 60000) / 1000,
                            )
                        }
                    }

                    val wasUltrafix = pendingUltrafix
                    pendingUltrafix = false
                    val currentGenerationMode = when {
                        wasUltrafix -> GenerationMode.ULTRAFIX
                        isInpaintMode -> GenerationMode.INPAINT
                        selectedImageUri != null -> GenerationMode.IMG2IMG
                        else -> GenerationMode.TXT2IMG
                    }

                    val newParams = GenerationParameters(
                        steps = generationParamsTmp.steps,
                        cfg = generationParamsTmp.cfg,
                        seed = returnedSeed,
                        prompt = generationParamsTmp.prompt,
                        negativePrompt = generationParamsTmp.negativePrompt,
                        generationTime = genTime,
                        width = if (model?.runOnCpu == true) generationParamsTmp.width else state.bitmap.width,
                        height = if (model?.runOnCpu == true) generationParamsTmp.height else state.bitmap.height,
                        runOnCpu = model?.runOnCpu ?: false,
                        denoiseStrength = generationParamsTmp.denoiseStrength,
                        useOpenCL = generationParamsTmp.useOpenCL,
                        scheduler = generationParamsTmp.scheduler,
                        mode = currentGenerationMode,
                    )

                    // Save to disk and update history list. The saved item's id is
                    // forwarded to both the snapshot and the currently-displayed marker
                    // so handleSaveImage can later confirm the user is still looking at
                    // this generation (and not a different history thumbnail).
                    coroutineScope.launch(Dispatchers.IO) {
                        val savedItem = historyManager.saveGeneratedImage(
                            modelId = modelId,
                            bitmap = state.bitmap,
                            params = newParams,
                            mode = currentGenerationMode,
                        )
                        if (savedItem != null) {
                            withContext(Dispatchers.Main) {
                                // An ultrafix result is a standalone image, not a
                                // stitchable inpaint patch.
                                if (!wasUltrafix) {
                                    stitchableHistoryIds = setOf(savedItem.id)
                                }
                                currentDisplayedHistoryId = savedItem.id
                            }
                        }
                    }

                    currentBitmap = state.bitmap
                    generationParams = newParams
                    generationParamsModelId = modelId
                    imageVersion += 1

                    if (!wasUltrafix) {
                        snapshotIsInpaintMode = isInpaintMode
                        snapshotSelectedImageUri = selectedImageUri
                        snapshotCropRect = cropRect
                        snapshotMaskBitmap = if (isInpaintMode) maskBitmap else null
                        snapshotDrawingOverlayBitmap = drawingOverlayBitmap
                        snapshotHasOriginalImage = hasOriginalImageForStitch
                    }
                    // stitchableHistoryIds / currentDisplayedHistoryId are set once
                    // the DB save above resolves.
                    stitchableHistoryIds = emptySet()
                    currentDisplayedHistoryId = null

                    Log.d(
                        "ModelRunScreen",
                        "params update: ${generationParams?.steps}, ${generationParams?.cfg}",
                    )

                    generationStartTime = null

                    if (pagerState.currentPage == 0 && !showAdvancedSettings) {
                        try {
                            pagerState.animateScrollToPage(1)
                        } finally {
                            BackgroundGenerationService.markBitmapConsumed()
                        }
                    } else {
                        BackgroundGenerationService.markBitmapConsumed()
                    }
                }
            }

            is GenerationState.Error -> {
                intermediateBitmap = null
                errorMessage = state.message
                isRunning = false
                progress = 0f
                generationStartTime = null
                pendingUltrafix = false
            }

            else -> {
                isRunning = false
                progress = 0f
            }
        }
    }

    // Only intercept back while a generation is running: back then offers to
    // interrupt the generation and stays on the screen (a second back exits).
    // In idle state the predictive back gesture can show NavHost's peek of
    // the previous destination.
    if (isRunning) {
        BackHandler { showInterruptDialog = true }
    }

    if (showInterruptDialog) {
        ModelRunConfirmDialog(
            title = stringResource(R.string.interrupt_generation),
            text = stringResource(R.string.interrupt_generation_hint),
            onConfirm = {
                showInterruptDialog = false
                interruptGeneration()
            },
            onDismiss = { showInterruptDialog = false },
        )
    }
    if (showOpenCLWarningDialog) {
        ModelRunConfirmDialog(
            title = stringResource(R.string.gpu_runtime_warning_title),
            text = stringResource(R.string.opencl_warning),
            onConfirm = {
                showOpenCLWarningDialog = false
                useOpenCL = true
                saveAllFields()
            },
            onDismiss = { showOpenCLWarningDialog = false },
        )
    }

    if (showCustomAspectRatioDialog) {
        CustomAspectRatioDialog(
            onConfirm = { newRatio ->
                if (newRatio != aspectRatio) {
                    aspectRatio = newRatio
                    clearImg2imgState()
                    saveAllFields()
                }
                showCustomAspectRatioDialog = false
            },
            onDismiss = { showCustomAspectRatioDialog = false },
        )
    }

    if (showResolutionChangeDialog && pendingResolution != null) {
        ModelRunConfirmDialog(
            title = stringResource(R.string.switch_resolution),
            text = stringResource(R.string.switch_resolution_hint),
            onConfirm = {
                pendingResolution?.let { resolution ->
                    // Check aspect ratio change
                    val oldRatio =
                        if (currentHeight > 0) currentWidth.toFloat() / currentHeight.toFloat() else 1f
                    val newRatio =
                        if (resolution.height >
                            0
                        ) {
                            resolution.width.toFloat() / resolution.height.toFloat()
                        } else {
                            1f
                        }

                    if (kotlin.math.abs(oldRatio - newRatio) > 0.01f) {
                        clearImg2imgState()
                    }

                    currentWidth = resolution.width
                    currentHeight = resolution.height
                    scope.launch {
                        generationPreferences.saveResolution(
                            modelId,
                            resolution.width,
                            resolution.height,
                        )
                    }
                    if (isRemote) {
                        // The host's BackendService reconciles the new
                        // resolution into a restart on its own. The health
                        // check only passes once the host reports the new
                        // resolution, so triggering it alongside the in-flight
                        // select is safe.
                        isCheckingBackend = true
                        backendReady = false
                        scope.launch {
                            val ok = remoteClient?.selectModel(
                                modelId,
                                resolution.width,
                                resolution.height,
                            ) ?: false
                            if (!ok) {
                                isCheckingBackend = false
                                errorMessage = msgRemoteSelectFailed
                            }
                        }
                        backendRestartTrigger++
                    } else {
                        model?.let { m ->
                            val serviceIntent =
                                Intent(context, BackendService::class.java).apply {
                                    action = BackendService.ACTION_RESTART
                                    putExtra("modelId", modelId)
                                    putExtra("backendType", m.backendType)
                                    putExtra("width", resolution.width)
                                    putExtra("height", resolution.height)
                                }
                            context.startForegroundService(serviceIntent)
                            isCheckingBackend = true
                            backendReady = false
                            backendRestartTrigger++
                        }
                    }
                }
                showResolutionChangeDialog = false
                pendingResolution = null
                showAdvancedSettings = false
            },
            onDismiss = {
                showResolutionChangeDialog = false
                pendingResolution = null
            },
        )
    }

    if (showResetConfirmDialog) {
        ModelRunConfirmDialog(
            title = stringResource(R.string.reset),
            text = stringResource(R.string.reset_hint),
            destructiveConfirm = true,
            onConfirm = {
                val defaults = model?.defaults ?: GenerationDefaults.GLOBAL
                steps = defaults.steps
                cfg = defaults.cfg
                seed = defaults.seed
                batchCounts = defaults.batchCounts
                scheduler = defaults.scheduler
                aspectRatio = defaults.aspectRatio
                promptField.replaceText(defaults.prompt)
                negativePromptField.replaceText(defaults.negativePrompt)
                denoiseStrength = defaults.denoiseStrength
                scope.launch(Dispatchers.IO) {
                    generationPreferences.saveAllFields(
                        modelId = modelId,
                        prompt = defaults.prompt,
                        negativePrompt = defaults.negativePrompt,
                        steps = defaults.steps,
                        cfg = defaults.cfg,
                        seed = defaults.seed,
                        width = defaultGenerationSize(
                            model?.usesFixedCanvas == true,
                            model?.runOnCpu == true,
                            model?.isDit == true,
                        ),
                        height = defaultGenerationSize(
                            model?.usesFixedCanvas == true,
                            model?.runOnCpu == true,
                            model?.isDit == true,
                        ),
                        denoiseStrength = defaults.denoiseStrength,
                        useOpenCL = useOpenCL,
                        batchCounts = defaults.batchCounts,
                        scheduler = defaults.scheduler,
                        aspectRatio = defaults.aspectRatio,
                    )
                }
                showResetConfirmDialog = false
            },
            onDismiss = { showResetConfirmDialog = false },
        )
    }

    // Local mode reads the shared BackendService state; remote mode polls the
    // host's /status endpoint plus its generation port's /health. Remote Ready
    // additionally requires the host's (model, width, height) to match this
    // screen's current config, so a process left over from an older
    // resolution can never be mistaken for ready.
    suspend fun awaitBackendReady() {
        if (isRemote) {
            val client = remoteClient
            if (client == null) {
                isCheckingBackend = false
                errorMessage = msgBackendFailed
                return
            }
            checkRemoteBackendHealth(
                client = client,
                expectedModelId = modelId,
                expectedWidth = currentWidth,
                expectedHeight = currentHeight,
                onHealthy = {
                    isCheckingBackend = false
                    backendReady = true
                },
                onUnhealthy = {
                    isCheckingBackend = false
                    errorMessage = msgBackendFailed
                },
            )
        } else {
            checkBackendHealth(
                backendState = BackendService.backendState,
                servingModelId = BackendService.servingModelId,
                expectedModelId = modelId,
                onHealthy = {
                    isCheckingBackend = false
                    backendReady = true
                },
                onUnhealthy = {
                    isCheckingBackend = false
                    errorMessage = msgBackendFailed
                },
            )
        }
    }

    // Remote mode starts its health check only after /select has been sent
    // (in the hasInitialized effect); checking in parallel could see the host
    // still Ready on a previous model and race the switch.
    LaunchedEffect(Unit) {
        if (!isRemote) {
            awaitBackendReady()
        }
    }

    LaunchedEffect(backendRestartTrigger) {
        if (backendRestartTrigger > 0) {
            delay(500)
            awaitBackendReady()
        }
    }

    // === Page Composable Functions ===
    @Composable
    fun PromptPage() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Reserve the IME area so the scroll viewport ends above the
                // keyboard; the focused prompt field is then scrolled above the IME
                // (which also keeps its window position accurate for the popup).
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AnimatedVisibility(
                visible = intermediateBitmap == null,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.prompt_settings),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (useImg2img) {
                                    TextButton(
                                        onClick = { onSelectImageClick() },
                                        contentPadding = PaddingValues(
                                            horizontal = 8.dp,
                                            vertical = 8.dp,
                                        ),
                                    ) {
                                        Text(
                                            "img2img",
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(end = 4.dp),
                                        )
                                        Icon(
                                            Icons.Default.Image,
                                            contentDescription = "select image",
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = { showAdvancedSettings = true },
                                    contentPadding = PaddingValues(
                                        horizontal = 8.dp,
                                        vertical = 8.dp,
                                    ),
                                ) {
                                    Text(
                                        stringResource(R.string.advanced_settings),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(end = 4.dp),
                                    )
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = stringResource(R.string.settings),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            if (showAdvancedSettings) {
                                AdvancedSettingsDialog(
                                    isSdxl = model?.usesFixedCanvas == true,
                                    isDit = model?.isDit == true,
                                    onDitWidthChange = onDitWidthChange,
                                    onDitHeightChange = onDitHeightChange,
                                    runOnCpu = model?.runOnCpu ?: false,
                                    useImg2img = useImg2img,
                                    isRunning = isRunning,
                                    aspectRatio = aspectRatio,
                                    availableResolutions = availableResolutions,
                                    currentWidth = currentWidth,
                                    currentHeight = currentHeight,
                                    scheduler = scheduler,
                                    steps = steps,
                                    cfg = cfg,
                                    useOpenCL = useOpenCL,
                                    batchCounts = batchCounts,
                                    denoiseStrength = denoiseStrength,
                                    seed = seed,
                                    returnedSeed = returnedSeed,
                                    onAspectRatioSelected = { ratio ->
                                        if (!isRunning && aspectRatio != ratio) {
                                            aspectRatio = ratio
                                            clearImg2imgState()
                                            saveAllFields()
                                        }
                                    },
                                    onCustomAspectRatioClick = {
                                        if (!isRunning) {
                                            showCustomAspectRatioDialog = true
                                        }
                                    },
                                    onResolutionSelected = { resolution ->
                                        if (!isRunning &&
                                            (
                                                resolution.width != currentWidth ||
                                                    resolution.height != currentHeight
                                                )
                                        ) {
                                            pendingResolution = resolution
                                            showResolutionChangeDialog = true
                                        }
                                    },
                                    onSchedulerChange = { value ->
                                        scheduler = value
                                        saveAllFields()
                                    },
                                    onStepsChange = onStepsChange,
                                    onCfgChange = onCfgChange,
                                    onSizeChange = onSizeChange,
                                    onCpuSelected = {
                                        useOpenCL = false
                                        saveAllFields()
                                    },
                                    onGpuSelected = { showOpenCLWarningDialog = true },
                                    onBatchCountsChange = onBatchCountsChange,
                                    onDenoiseStrengthChange = onDenoiseStrengthChange,
                                    onSeedChange = onSeedChange,
                                    onUseLastSeed = {
                                        seed = returnedSeed.toString()
                                        saveAllFields()
                                    },
                                    onImportFromClipboard = {
                                        val clipboard =
                                            context.getSystemService(
                                                Context.CLIPBOARD_SERVICE,
                                            ) as? ClipboardManager
                                        val raw = clipboard?.primaryClip
                                            ?.takeIf { it.itemCount > 0 }
                                            ?.getItemAt(0)
                                            ?.coerceToText(context)
                                            ?.toString()
                                        val imported = ParamShare.tryDecode(raw)
                                        if (imported != null) {
                                            pendingImport = imported
                                            clipboardImportChecked = true
                                        } else {
                                            Toast.makeText(
                                                context,
                                                msgImportNoParams,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    },
                                    onShare = {
                                        val currentMode = when {
                                            isInpaintMode -> GenerationMode.INPAINT
                                            selectedImageUri != null -> GenerationMode.IMG2IMG
                                            else -> GenerationMode.TXT2IMG
                                        }
                                        shareSourceParams = GenerationParameters(
                                            steps = steps.toInt(),
                                            cfg = cfg,
                                            seed = seed.toLongOrNull(),
                                            prompt = promptField.text,
                                            negativePrompt = negativePromptField.text,
                                            generationTime = null,
                                            width = currentWidth,
                                            height = currentHeight,
                                            runOnCpu = model?.runOnCpu ?: false,
                                            denoiseStrength = denoiseStrength,
                                            useOpenCL = useOpenCL,
                                            scheduler = scheduler,
                                            mode = currentMode,
                                        )
                                        shareSourceModelId = modelId
                                    },
                                    onReset = { showResetConfirmDialog = true },
                                    onDismiss = { showAdvancedSettings = false },
                                )
                            }
                        }

                        ControlledPromptTagTextField(
                            controller = promptField,
                            autocompleteAvailable = tagAutocompleteAvailable,
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                PromptCountLabel(
                                    label = stringResource(R.string.image_prompt),
                                    count = promptField.tokenCount,
                                    max = promptField.tokenMax,
                                    showCount = promptField.text.isNotEmpty(),
                                )
                            },
                        )

                        ControlledPromptTagTextField(
                            controller = negativePromptField,
                            autocompleteAvailable = tagAutocompleteAvailable,
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                PromptCountLabel(
                                    label = stringResource(R.string.negative_prompt),
                                    count = negativePromptField.tokenCount,
                                    max = negativePromptField.tokenMax,
                                    showCount = negativePromptField.text.isNotEmpty(),
                                )
                            },
                        )

                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                pendingUltrafix = false
                                Log.d(
                                    "ModelRunScreen",
                                    "start generation",
                                )
                                generationParamsTmp = GenerationParameters(
                                    steps = steps.roundToInt(),
                                    cfg = cfg,
                                    seed = 0,
                                    prompt = promptField.text,
                                    negativePrompt = negativePromptField.text,
                                    generationTime = "",
                                    width = currentWidth,
                                    height = currentHeight,
                                    runOnCpu = model?.runOnCpu ?: false,
                                    denoiseStrength = denoiseStrength,
                                    useOpenCL = useOpenCL,
                                    scheduler = scheduler,
                                )

                                Log.d(
                                    "ModelRunScreen",
                                    "start generation batch: $batchCounts times",
                                )

                                // If seed is set, only generate once regardless of batch count
                                val actualBatchCount =
                                    if (seed.isNotBlank()) 1 else batchCounts

                                batchGenerationJob = coroutineScope.launch {
                                    for (i in 0 until actualBatchCount) {
                                        currentBatchIndex = i + 1
                                        Log.d(
                                            "ModelRunScreen",
                                            "preparing batch $i",
                                        )

                                        // Update generationParamsTmp to reflect current parameters
                                        // This allows parameters to be changed during batch execution
                                        generationParamsTmp = GenerationParameters(
                                            steps = steps.roundToInt(),
                                            cfg = cfg,
                                            seed = 0,
                                            prompt = promptField.text,
                                            negativePrompt = negativePromptField.text,
                                            generationTime = "",
                                            width = currentWidth,
                                            height = currentHeight,
                                            runOnCpu = model?.runOnCpu ?: false,
                                            denoiseStrength = denoiseStrength,
                                            useOpenCL = useOpenCL,
                                            scheduler = scheduler,
                                        )

                                        val batchIntent = Intent(
                                            context,
                                            BackgroundGenerationService::class.java,
                                        ).apply {
                                            putExtra("prompt", promptField.text)
                                            putExtra(
                                                "negative_prompt",
                                                negativePromptField.text,
                                            )
                                            putExtra("steps", steps.roundToInt())
                                            putExtra("cfg", cfg)
                                            seed.toLongOrNull()
                                                ?.let { putExtra("seed", it) }
                                            putExtra("width", currentWidth)
                                            putExtra("height", currentHeight)
                                            // Backend now crops progress previews to the
                                            // visible target rectangle, so the service must
                                            // decode each preview with the effective dims
                                            // (target_w/h), not the 1024 canvas size.
                                            putExtra("effective_width", effectiveWidth)
                                            putExtra("effective_height", effectiveHeight)
                                            putExtra(
                                                "denoise_strength",
                                                denoiseStrength,
                                            )
                                            putExtra("use_opencl", useOpenCL)
                                            putExtra("scheduler", scheduler)
                                            putExtra("aspect_ratio", aspectRatio)
                                            putExtra("batch_index", i)
                                            putExtra("backend_host", backendHost)
                                            if (selectedImageUri != null && base64EncodeDone) {
                                                putExtra("has_image", true)
                                                if (isInpaintMode && maskBitmap != null) {
                                                    putExtra("has_mask", true)
                                                }
                                            }
                                        }

                                        Log.d(
                                            "ModelRunScreen",
                                            "start service - batch $i",
                                        )

                                        context.startForegroundService(batchIntent)
                                        Log.d(
                                            "ModelRunScreen",
                                            "start service sent - batch $i",
                                        )

                                        BackgroundGenerationService.generationState
                                            .first { state ->
                                                state is GenerationState.Complete ||
                                                    state is GenerationState.Error
                                            }

                                        Log.d(
                                            "ModelRunScreen",
                                            "batch $i completed, waiting for service to stop",
                                        )

                                        // Wait for service to actually stop
                                        val waitStartTime =
                                            System.currentTimeMillis()
                                        val stopped = withTimeoutOrNull(5000L) {
                                            BackgroundGenerationService.isServiceRunning
                                                .first { !it }
                                        }
                                        if (stopped == null) {
                                            Log.w(
                                                "ModelRunScreen",
                                                "Timeout waiting for service to stop",
                                            )
                                        }

                                        Log.d(
                                            "ModelRunScreen",
                                            "service stopped, wait time: ${System.currentTimeMillis() - waitStartTime}ms",
                                        )

                                        BackgroundGenerationService.resetState()
                                        Log.d(
                                            "ModelRunScreen",
                                            "service state reset, ready for next batch",
                                        )
                                    }
                                    currentBatchIndex = 0
                                    isRunning = false
                                    Log.d(
                                        "ModelRunScreen",
                                        "all batches completed, isRunning set to false",
                                    )
                                }
                            },
                            enabled = serviceState !is GenerationState.Progress &&
                                !isRunning && !isUpscaling && !isUltrafixPreparing,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            AnimatedContent(
                                targetState = serviceState is GenerationState.Progress || isUpscaling,
                                transitionSpec = {
                                    (
                                        fadeIn(animationSpec = tween(Motion.DurationShort)) + scaleIn(
                                            initialScale = 0.8f,
                                            animationSpec = tween(Motion.DurationShort),
                                        )
                                        )
                                        .togetherWith(
                                            fadeOut(animationSpec = tween(Motion.DurationShort)) + scaleOut(
                                                targetScale = 0.8f,
                                                animationSpec = tween(Motion.DurationShort),
                                            ),
                                        )
                                },
                                label = "GenerateButtonContent",
                            ) { isLoading ->
                                if (isLoading) {
                                    LoadingIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else {
                                    Text(stringResource(R.string.generate_image))
                                }
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = errorMessage != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                errorMessage?.let { msg ->
                    Card(
                        onClick = { errorMessage = null },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                msg,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = isRunning,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (currentBatchIndex > 0) {
                                "${
                                    stringResource(
                                        R.string.generating,
                                    )
                                } ($currentBatchIndex/$batchCounts)…"
                            } else {
                                stringResource(
                                    R.string.generating,
                                )
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        SmoothLinearWavyProgressIndicator(
                            progress = progress,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        intermediateBitmap?.let { bitmap ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f),
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Generation Preview",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = selectedImageUri != null && base64EncodeDone,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        Card(
                            modifier = Modifier
                                .size(100.dp),
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Box {
                                croppedBitmap?.let { bitmap ->
                                    AsyncImage(
                                        model = ImageRequest.Builder(
                                            LocalContext.current,
                                        )
                                            .data(bitmap)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = "Cropped Image",
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } ?: selectedImageUri?.let { uri ->
                                    AsyncImage(
                                        model = ImageRequest.Builder(
                                            LocalContext.current,
                                        )
                                            .data(uri)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = "Selected Image",
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        selectedImageUri = null
                                        croppedBitmap = null
                                        drawingOverlayBitmap = null
                                        maskBitmap = null
                                        isInpaintMode = false
                                        cropRect = null
                                        savedPathHistory = null
                                        hasOriginalImageForStitch = false
                                    },
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.surface.copy(
                                                alpha = 0.7f,
                                            ),
                                            shape = CircleShape,
                                        )
                                        .align(Alignment.TopEnd),
                                ) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "Remove Image",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        showDrawScreen = true
                                    },
                                    enabled = !isRunning && croppedBitmap != null,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.surface.copy(
                                                alpha = 0.7f,
                                            ),
                                            shape = CircleShape,
                                        )
                                        .align(Alignment.TopStart),
                                ) {
                                    Icon(
                                        Icons.Default.Draw,
                                        contentDescription = "Draw Image",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = croppedBitmap != null && !isInpaintMode,
                            enter = fadeIn() + expandHorizontally(),
                            exit = fadeOut() + shrinkHorizontally(),
                        ) {
                            Row {
                                Spacer(modifier = Modifier.width(12.dp))
                                SmallFloatingActionButton(
                                    onClick = {
                                        if (croppedBitmap != null) {
                                            showInpaintScreen = true
                                        } else {
                                            Toast.makeText(
                                                context,
                                                msgPleaseCropFirst,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Brush,
                                        contentDescription = "Set Mask",
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = isInpaintMode && maskBitmap != null,
                            enter = fadeIn() + expandHorizontally(),
                            exit = fadeOut() + shrinkHorizontally(),
                        ) {
                            Row {
                                Spacer(modifier = Modifier.width(8.dp))
                                Card(
                                    onClick = {
                                        if (croppedBitmap != null && maskBitmap != null) {
                                            showInpaintScreen = true
                                        }
                                    },
                                    modifier = Modifier.size(100.dp),
                                    shape = MaterialTheme.shapes.small,
                                ) {
                                    Box {
                                        maskBitmap?.let { mb ->
                                            AsyncImage(
                                                model = ImageRequest.Builder(
                                                    LocalContext.current,
                                                )
                                                    .data(mb)
                                                    .crossfade(true)
                                                    .build(),
                                                contentDescription = "Mask Image",
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                maskBitmap = null
                                                isInpaintMode = false
                                                savedPathHistory = null
                                            },
                                            modifier = Modifier
                                                .size(24.dp)
                                                .background(
                                                    color = MaterialTheme.colorScheme.surface.copy(
                                                        alpha = 0.7f,
                                                    ),
                                                    shape = CircleShape,
                                                )
                                                .align(Alignment.TopEnd),
                                        ) {
                                            Icon(
                                                Icons.Default.Clear,
                                                contentDescription = "Clear Mask",
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                focusManager.clearFocus()
            },
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title = {
                        // Hide title when collapsed
                        if (scrollBehavior.state.collapsedFraction < 0.5f) {
                            Column {
                                Text(
                                    text = model?.name ?: "Running Model",
                                    fontWeight = FontWeight.Normal,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                )
                                Text(
                                    text = model?.description ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (isRunning) {
                                showInterruptDialog = true
                            } else {
                                handleExit()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    scrollBehavior = scrollBehavior,
                    actions = {
                        Row {
                            val tabs = listOf(
                                stringResource(R.string.prompt_tab),
                                stringResource(R.string.result_tab),
                                stringResource(R.string.history_tab),
                            )
                            tabs.forEachIndexed { index, label ->
                                val selected = pagerState.currentPage == index
                                TextButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            focusManager.clearFocus()
                                            pagerState.animateScrollToPage(index)
                                        }
                                    },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    ),
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    },
                )
            },
        ) { paddingValues ->
            if (model == null && isRemote && !isCheckingBackend) {
                // Process recreation with the host unreachable (or the model
                // removed from it): the catalog re-fetch failed, so there is
                // no model to render. Show an explicit state instead of an
                // empty scaffold.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.remote_banner_offline),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (model != null) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        // Mark the Scaffold insets (incl. the navigation bar) as
                        // consumed so the prompt page's imePadding only reserves the
                        // remaining IME height, instead of double-counting the nav
                        // bar and leaving a background strip above the keyboard.
                        .consumeWindowInsets(paddingValues),
                ) { page ->
                    when (page) {
                        0 -> PromptPage()

                        1 -> ModelRunResultPage(
                            currentBitmap = currentBitmap,
                            imageVersion = imageVersion,
                            generationParams = generationParams,
                            recentHistory = recentHistory,
                            showReportButton = BuildConfig.FLAVOR == "filter",
                            // Upscaling is only offered for the NPU runtime and resolutions <= 1024.
                            // A CPU diffusion backend never initializes the QNN
                            // runtime, so its /upscale cannot load the .bin
                            // upscaler; that holds for the host's backend too.
                            // Remote mode additionally needs an upscaler
                            // installed on the host.
                            showUpscaleButton = !model.runOnCpu &&
                                (!isRemote || remoteRepository.upscalerPaths.isNotEmpty()) &&
                                generationParams?.let { maxOf(it.width, it.height) <= 1024 } == true,
                            upscaleEnabled = !isRunning && !isUpscaling && !isUltrafixPreparing,
                            // Ultrafix takes over where upscaling stops: SDXL
                            // only (SD1.5 quality was not worth it), restricted
                            // to DMD2-class few-step checkpoints (detected via
                            // cfg = 1 - the inversion/injection recipe is tuned
                            // for that regime), image larger than the upscale
                            // ceiling, every UNet/VAE tile fitting inside the
                            // shorter edge, and a backend started with its VAE
                            // encoder (useImg2img).
                            showUltrafixButton = useImg2img && model.isSdxl &&
                                cfg == 1f &&
                                generationParams?.let {
                                    maxOf(it.width, it.height) > 1024 &&
                                        minOf(it.width, it.height) >=
                                        maxOf(currentWidth, currentHeight, 512)
                                } == true,
                            ultrafixEnabled = !isRunning && !isUpscaling && !isUltrafixPreparing,
                            // The upscale button also exists on models that
                            // can't run UltraFix (SD1.5 NPU); its long press
                            // only opens the import dialog when the model can.
                            onUpscaleLongClick = {
                                if (useImg2img && model.isSdxl && cfg == 1f) {
                                    showUltrafixImportDialog = true
                                }
                            },
                            onUltrafixLongClick = { showUltrafixImportDialog = true },
                            isFavorite = if (currentDisplayedHistoryId != null) {
                                displayedFavorite
                            } else {
                                null
                            },
                            onFavoriteClick = {
                                val id = currentDisplayedHistoryId
                                val current = displayedFavorite
                                if (id != null && current != null) {
                                    scope.launch(Dispatchers.IO) {
                                        historyManager.setFavorite(id, !current)
                                    }
                                }
                            },
                            onReportClick = { showReportDialog = true },
                            onUpscaleClick = { showUpscalerDialog = true },
                            onUltrafixClick = { showUltrafixConfirmDialog = true },
                            onSaveClick = { bitmap ->
                                handleSaveImage(
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
                                        Toast.makeText(
                                            context,
                                            error,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                )
                            },
                            onPreviewClick = { isPreviewMode = true },
                            onShowParameters = { showParametersDialog = true },
                            onHistoryThumbClick = { item ->
                                scope.launch {
                                    val bitmap = withContext(Dispatchers.IO) {
                                        BitmapFactory.decodeFile(item.imageFile.absolutePath)
                                    }
                                    if (bitmap != null) {
                                        currentBitmap = bitmap
                                        generationParams = item.params
                                        generationParamsModelId = item.modelId
                                        currentDisplayedHistoryId = item.id
                                        imageVersion++
                                    }
                                }
                            },
                            onGoToGenerate = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(0)
                                }
                            },
                        )

                        2 -> ModelRunHistoryPage(
                            historyFilter = historyFilter,
                            currentModelId = modelId,
                            pagedItems = pagedHistory,
                            totalCount = historyTotalCount,
                            isSelectionMode = isSelectionMode,
                            selectedIds = selectedIds.toSet(),
                            isBatchSaving = isBatchSaving,
                            onFilterChange = { historyFilter = it },
                            onShowFilterSheet = { showHistoryFilterSheet = true },
                            onItemClick = { item ->
                                if (isSelectionMode) {
                                    // Toggle selection
                                    if (item.id in selectedIds) {
                                        selectedIds.remove(item.id)
                                        if (selectedIds.isEmpty()) {
                                            isSelectionMode = false
                                        }
                                    } else {
                                        selectedIds.add(item.id)
                                    }
                                } else {
                                    // Normal preview
                                    selectedHistoryItem = item
                                    showHistoryDetailDialog = true
                                }
                            },
                            onItemLongClick = { item ->
                                if (!isSelectionMode) {
                                    isSelectionMode = true
                                    selectedIds.clear()
                                    selectedIds.add(item.id)
                                }
                            },
                            onExitSelection = {
                                isSelectionMode = false
                                selectedIds.clear()
                            },
                            onToggleSelectAll = {
                                // Select-all covers every match via an id query,
                                // not only the loaded pages.
                                if (historyTotalCount > 0 && selectedIds.size >= historyTotalCount) {
                                    selectedIds.clear()
                                    isSelectionMode = false
                                } else {
                                    scope.launch {
                                        val allIds = historyManager.queryIds(historyFilter)
                                        selectedIds.clear()
                                        selectedIds.addAll(allIds)
                                    }
                                }
                            },
                            onBatchSave = { showBatchSaveDialog = true },
                            onBatchDelete = { showBatchDeleteDialog = true },
                        )
                    }
                }
            }
        }
        if (showCropScreen && imageUriForCrop != null) {
            val aspectTarget = computeAspectTargetSize(model?.usesFixedCanvas == true, aspectRatio)
            val cropW = aspectTarget?.first ?: currentWidth
            val cropH = aspectTarget?.second ?: currentHeight
            CropImageScreen(
                imageUri = imageUriForCrop!!,
                width = cropW,
                height = cropH,
                onCropComplete = { base64String, bitmap, rect ->
                    handleCropComplete(base64String, bitmap, rect)
                },
                onCancel = {
                    showCropScreen = false
                    imageUriForCrop = null
                    selectedImageUri = null
                    hasOriginalImageForStitch = false
                },
            )
        }
        if (showInpaintScreen && croppedBitmap != null) {
            InpaintScreen(
                originalBitmap = croppedBitmap!!,
                existingMaskBitmap = if (isInpaintMode) maskBitmap else null,
                existingPathHistory = savedPathHistory,
                onInpaintComplete = { maskBase64, originalBitmap, maskBitmap, pathHistory ->
                    handleInpaintComplete(maskBase64, maskBitmap, pathHistory)
                },
                onCancel = {
                    showInpaintScreen = false
                },
            )
        }
        if (showDrawScreen && croppedBitmap != null) {
            DrawScreen(
                originalBitmap = croppedBitmap!!,
                onDrawingSaved = { sketchedBitmap, drawing ->
                    val previousDrawing = drawingOverlayBitmap
                    val combinedDrawing = withContext(Dispatchers.Default) {
                        mergeDrawingLayers(previousDrawing, drawing)
                    }
                    withContext(Dispatchers.IO) {
                        // Match the upload canvas used by cropping and by the inpaint mask.
                        val upload = padBitmapToCanvas(sketchedBitmap, currentWidth, currentHeight)
                        val payload = bitmapToBase64Png(upload)
                        File(context.filesDir, "tmp.txt").writeText(payload)
                        if (upload !== sketchedBitmap) upload.recycle()
                    }
                    croppedBitmap = sketchedBitmap
                    drawingOverlayBitmap = combinedDrawing
                    showDrawScreen = false
                },
                onNavigateBack = {
                    showDrawScreen = false
                },
            )
        }
    }
    if (showReportDialog && currentBitmap != null && generationParams != null) {
        ModelRunConfirmDialog(
            title = stringResource(R.string.report),
            text = stringResource(R.string.report_image_confirm),
            confirmText = stringResource(R.string.report),
            dismissText = stringResource(R.string.cancel),
            destructiveConfirm = true,
            onConfirm = {
                showReportDialog = false
                coroutineScope.launch {
                    currentBitmap?.let { bitmap ->
                        reportImage(
                            bitmap = bitmap,
                            modelName = model?.name ?: "",
                            params = generationParams!!,
                            onSuccess = {
                                Toast.makeText(
                                    context,
                                    msgReportSuccess,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            onError = {
                                Toast.makeText(
                                    context,
                                    msgReportFailed,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    }
                }
            },
            onDismiss = { showReportDialog = false },
        )
    }

    if (showParametersDialog && generationParams != null) {
        GenerationParamsDialog(
            title = stringResource(R.string.params_detail),
            params = generationParams!!,
            modelId = generationParamsModelId,
            showImg2imgButton = useImg2img,
            onShare = {
                shareSourceParams = generationParams
                shareSourceModelId = generationParamsModelId
            },
            onSendToImg2img = {
                val bmp = currentBitmap
                if (bmp != null) {
                    sendBitmapToImg2img(bmp)
                    showParametersDialog = false
                } else {
                    Toast.makeText(
                        context,
                        msgNoImageAvailable,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onReproduce = {
                generationParams?.let {
                    pendingReproduceParams = it
                    showParametersDialog = false
                    showReproduceParamsDialog = true
                }
            },
            onDismiss = { showParametersDialog = false },
        )
    }

    if (isPreviewMode && currentBitmap != null) {
        ZoomableImageOverlay(
            bitmap = currentBitmap,
            onDismiss = { isPreviewMode = false },
            showScaleIndicator = true,
            topEndContent = {
                OverlayIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "close preview",
                    onClick = { isPreviewMode = false },
                )
            },
        )
    }

    // Import-and-resize a local image for UltraFix. A confirmed image that
    // already satisfies the UltraFix window goes straight to the parameter
    // dialog; a smaller one just lands on the result page, where the visible
    // upscale button is the natural next step.
    if (showUltrafixImportDialog) {
        UltrafixImportDialog(
            tileSize = maxOf(currentWidth, currentHeight, 512),
            onDismiss = { showUltrafixImportDialog = false },
            onConfirm = { bitmap ->
                applyUltrafixImport(bitmap)
                showUltrafixImportDialog = false
                val tile = maxOf(currentWidth, currentHeight, 512)
                if (maxOf(bitmap.width, bitmap.height) > 1024 &&
                    minOf(bitmap.width, bitmap.height) >= tile
                ) {
                    showUltrafixConfirmDialog = true
                }
            },
        )
    }

    // Ultrafix parameter confirmation.
    if (showUltrafixConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showUltrafixConfirmDialog = false },
            title = { Text(stringResource(R.string.ultrafix)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.ultrafix_confirm_hint))
                    Text(
                        stringResource(
                            R.string.ultrafix_source_info,
                            currentBitmap?.width ?: 0,
                            currentBitmap?.height ?: 0,
                            maxOf(currentWidth, currentHeight),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        stringResource(R.string.ultrafix_longpress_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Independent UltraFix steps (1..20). Lowering steps also
                    // tightens the denoise-step cap below.
                    val denoiseStepsMax =
                        minOf(GenerationDefaults.ULTRAFIX_DENOISE_STEPS_MAX, ultrafixSteps.roundToInt())
                    Text(
                        stringResource(R.string.steps, ultrafixSteps.roundToInt()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = ultrafixSteps,
                        onValueChange = {
                            ultrafixSteps = it
                            val newMax =
                                minOf(GenerationDefaults.ULTRAFIX_DENOISE_STEPS_MAX, it.roundToInt())
                            if (ultrafixDenoiseSteps > newMax) ultrafixDenoiseSteps = newMax
                            saveUltrafixParams()
                        },
                        valueRange = GenerationDefaults.ULTRAFIX_STEPS_MIN..GenerationDefaults.ULTRAFIX_STEPS_MAX,
                        steps = 18,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Independent UltraFix denoise steps (0..min(10, steps)):
                    // how many of the total steps actually denoise.
                    Text(
                        stringResource(R.string.ultrafix_denoise_steps_label, ultrafixDenoiseSteps),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = ultrafixDenoiseSteps.toFloat(),
                        onValueChange = {
                            ultrafixDenoiseSteps = it.roundToInt()
                            saveUltrafixParams()
                        },
                        valueRange = 0f..denoiseStepsMax.toFloat(),
                        steps = (denoiseStepsMax - 1).coerceAtLeast(0),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Quality-denoise toggle: run UltraFix on neutral quality
                    // tags instead of the prompt-page prompt. On by default.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(
                                R.string.ultrafix_quality_denoise_desc,
                                GenerationDefaults.ULTRAFIX_QUALITY_PROMPT,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = ultrafixQualityDenoise,
                            onCheckedChange = {
                                ultrafixQualityDenoise = it
                                saveUltrafixParams()
                            },
                        )
                    }

                    TextButton(
                        onClick = {
                            ultrafixSteps = GenerationDefaults.GLOBAL.ultrafixSteps
                            ultrafixDenoiseSteps = GenerationDefaults.GLOBAL.ultrafixDenoiseSteps
                            ultrafixQualityDenoise = GenerationDefaults.GLOBAL.ultrafixQualityDenoise
                            saveUltrafixParams()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.ultrafix_restore_defaults))
                    }

                    Text(
                        stringResource(R.string.ultrafix_other_params_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The prompts are only a reminder of what will run; show a
                    // truncated preview instead of the full text. With the
                    // toggle on, UltraFix runs on the quality tags, so preview
                    // those instead of the prompt-page text.
                    fun preview(text: String) = if (text.length > 80) text.take(80) + "..." else text
                    val ultrafixPreviewPrompt =
                        if (ultrafixQualityDenoise) {
                            GenerationDefaults.ULTRAFIX_QUALITY_PROMPT
                        } else {
                            promptField.text
                        }
                    if (ultrafixPreviewPrompt.isNotBlank()) {
                        Text(
                            stringResource(R.string.image_prompt),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            preview(ultrafixPreviewPrompt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (negativePromptField.text.isNotBlank()) {
                        Text(
                            stringResource(R.string.negative_prompt),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            preview(negativePromptField.text),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showUltrafixConfirmDialog = false
                    startUltrafix()
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUltrafixConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Upscaler dialog
    if (showUpscalerDialog) {
        UpscalerPickerFlow(
            modelId = modelId,
            upscalerRepository = upscalerRepository,
            upscalerPreferences = upscalerPreferences,
            upscalersOverride = if (isRemote) remoteRepository.remoteUpscalers() else null,
            onDismiss = { showUpscalerDialog = false },
            onUpscalerConfirmed = { selectedUpscaler, selectedScale ->
                showUpscalerDialog = false

                // Execute upscale
                currentBitmap?.let { bitmap ->
                    // If the source image is stitch-eligible (an inpaint result or
                    // an upscaled copy of one), its upscaled copy is too.
                    val sourceIsStitchable =
                        currentDisplayedHistoryId != null &&
                            currentDisplayedHistoryId in stitchableHistoryIds
                    isUpscaling = true
                    scope.launch {
                        try {
                            val upscaledBitmap = performUpscale(
                                context = context,
                                bitmap = bitmap,
                                upscalerId = selectedUpscaler.id,
                                targetScale = selectedScale,
                                backendHost = backendHost,
                                remoteUpscalerPath = if (isRemote) {
                                    remoteRepository.upscalerPaths[selectedUpscaler.id]
                                } else {
                                    null
                                },
                            )

                            // Save upscaled image via HistoryManager (DB + JPG file)
                            generationParams?.let { params ->
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val updatedParams = params.copy(
                                            width = upscaledBitmap.width,
                                            height = upscaledBitmap.height,
                                        )
                                        // The displayed image's params carry its
                                        // generation mode (set on completion and
                                        // by every history-load path), so the
                                        // upscaled copy inherits the right one.
                                        val sourceMode = params.mode
                                        val saved = historyManager.saveGeneratedImage(
                                            modelId = modelId,
                                            bitmap = upscaledBitmap,
                                            params = updatedParams,
                                            mode = sourceMode,
                                            upscalerId = selectedUpscaler.id,
                                        )
                                        if (saved != null) {
                                            withContext(Dispatchers.Main) {
                                                currentBitmap = upscaledBitmap
                                                generationParams = updatedParams
                                                generationParamsModelId = modelId
                                                currentDisplayedHistoryId = saved.id
                                                if (sourceIsStitchable) {
                                                    stitchableHistoryIds =
                                                        stitchableHistoryIds + saved.id
                                                }
                                                imageVersion++
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(
                                            "ModelRunScreen",
                                            "Failed to save upscaled image",
                                            e,
                                        )
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
            },
        )
    }

    BlockingProgressOverlay(visible = isCheckingBackend) {
        ContainedLoadingIndicator()
        Text(
            text = stringResource(R.string.loading_model),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    BlockingProgressOverlay(visible = isUpscaling) {
        ContainedLoadingIndicator()
        Text(
            text = stringResource(R.string.upscaling_image),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    BlockingProgressOverlay(visible = isUltrafixPreparing) {
        ContainedLoadingIndicator()
        Text(
            text = stringResource(R.string.ultrafix_preparing),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    if (showHistoryFilterSheet) {
        HistoryFilterSheet(
            initialFilter = historyFilter,
            knownModelIds = knownModelIds,
            knownSchedulers = knownSchedulers,
            knownSizes = knownSizes,
            onApply = {
                historyFilter = it
                showHistoryFilterSheet = false
            },
            onDismiss = { showHistoryFilterSheet = false },
        )
    }

    // History detail dialog
    if (showHistoryDetailDialog && selectedHistoryItem != null) {
        val detailImagePath = selectedHistoryItem?.imageFile?.absolutePath
        val historyBitmap by produceState<Bitmap?>(null, detailImagePath) {
            value = withContext(Dispatchers.IO) {
                detailImagePath?.let { BitmapFactory.decodeFile(it) }
            }
        }
        val dismissDetail = {
            showHistoryDetailDialog = false
            selectedHistoryItem = null
        }
        // Same gating as the result page, applied to the previewed item. The
        // click path loads the item into the result-page state (exactly like
        // tapping a thumbnail) and reuses the regular upscale/ultrafix flows.
        val detailItem = selectedHistoryItem
        val detailIdle = !isRunning && !isUpscaling && !isUltrafixPreparing
        val detailCanUpscale = historyBitmap != null && detailItem != null &&
            detailIdle && model?.runOnCpu == false &&
            (!isRemote || remoteRepository.upscalerPaths.isNotEmpty()) &&
            maxOf(detailItem.params.width, detailItem.params.height) <= 1024
        val detailCanUltrafix = historyBitmap != null && detailItem != null &&
            detailIdle && useImg2img && model?.isSdxl == true && cfg == 1f &&
            maxOf(detailItem.params.width, detailItem.params.height) > 1024 &&
            minOf(detailItem.params.width, detailItem.params.height) >=
            maxOf(currentWidth, currentHeight, 512)
        val loadDetailIntoResult = fun(): Boolean {
            val bmp = historyBitmap ?: return false
            val item = detailItem ?: return false
            currentBitmap = bmp
            generationParams = item.params
            generationParamsModelId = item.modelId
            currentDisplayedHistoryId = item.id
            imageVersion++
            dismissDetail()
            return true
        }
        ZoomableImageOverlay(
            bitmap = historyBitmap,
            onDismiss = dismissDetail,
            topEndContent = {
                OverlayIconButton(
                    icon = Icons.Default.Info,
                    contentDescription = "View parameters",
                    onClick = {
                        if (selectedHistoryItem != null) {
                            showHistoryParametersDialog = true
                        }
                    },
                )
                OverlayIconButton(
                    icon = if (detailItem?.favorite == true) {
                        Icons.Default.Favorite
                    } else {
                        Icons.Default.FavoriteBorder
                    },
                    contentDescription = "toggle favorite",
                    onClick = {
                        val item = selectedHistoryItem
                        if (item != null) {
                            // Keep the dialog's own copy in sync; the grid
                            // refreshes through the observed flow.
                            selectedHistoryItem = item.copy(favorite = !item.favorite)
                            scope.launch(Dispatchers.IO) {
                                historyManager.setFavorite(item.id, !item.favorite)
                            }
                        }
                    },
                )
                if (detailCanUpscale) {
                    OverlayIconButton(
                        icon = Icons.Default.AutoFixHigh,
                        contentDescription = "upscale image",
                        onClick = {
                            if (loadDetailIntoResult()) {
                                showUpscalerDialog = true
                            }
                        },
                    )
                }
                if (detailCanUltrafix) {
                    OverlayIconButton(
                        icon = Icons.Default.AutoAwesome,
                        contentDescription = "ultrafix image",
                        onClick = {
                            if (loadDetailIntoResult()) {
                                showUltrafixConfirmDialog = true
                            }
                        },
                    )
                }
                OverlayIconButton(
                    icon = Icons.Default.Save,
                    contentDescription = "Save to gallery",
                    onClick = {
                        val bitmapToSave = historyBitmap
                        if (bitmapToSave != null) {
                            scope.launch {
                                saveImage(
                                    context = context,
                                    bitmap = bitmapToSave,
                                    onSuccess = {
                                        Toast.makeText(
                                            context,
                                            msgImageSaved,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                    onError = { errorMsg ->
                                        Toast.makeText(
                                            context,
                                            errorMsg,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                )
                            }
                        }
                    },
                )
            },
        )
    }

    // History parameters dialog
    if (showHistoryParametersDialog && selectedHistoryItem != null) {
        val params = selectedHistoryItem!!.params
        GenerationParamsDialog(
            title = stringResource(R.string.generation_params_title),
            params = params,
            modelId = selectedHistoryItem?.modelId ?: "",
            displayMode = selectedHistoryItem?.mode,
            showImg2imgButton = useImg2img,
            onShare = {
                shareSourceParams = params
                shareSourceModelId = selectedHistoryItem?.modelId
            },
            onSendToImg2img = {
                val item = selectedHistoryItem
                if (item != null) {
                    scope.launch {
                        val bmp = withContext(Dispatchers.IO) {
                            BitmapFactory.decodeFile(item.imageFile.absolutePath)
                        }
                        if (bmp != null) {
                            sendBitmapToImg2img(bmp)
                            showHistoryParametersDialog = false
                            showHistoryDetailDialog = false
                            selectedHistoryItem = null
                        } else {
                            Toast.makeText(
                                context,
                                msgImageLoadFailed,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            },
            onReproduce = {
                pendingReproduceParams = selectedHistoryItem!!.params
                showHistoryParametersDialog = false
                showReproduceParamsDialog = true
            },
            onDismiss = { showHistoryParametersDialog = false },
        )
    }

    // Reproduce parameters dialog
    if (showReproduceParamsDialog && pendingReproduceParams != null) {
        val params = pendingReproduceParams!!
        ReproduceParametersDialog(
            params = params,
            onApply = { selectedFields ->
                if (ParamShareField.PROMPT in selectedFields) {
                    promptField.replaceText(params.prompt)
                }
                if (ParamShareField.NEGATIVE_PROMPT in selectedFields) {
                    negativePromptField.replaceText(params.negativePrompt)
                }
                // An UltraFix image was produced by the tiled img2img repair pass,
                // whose steps/denoise live in their own UltraFix variables. Route
                // the reproduced values there (denoise is stored as a strength but
                // edited as a step count) instead of the prompt-page settings.
                val isUltrafixParams = params.mode == GenerationMode.ULTRAFIX
                if (ParamShareField.STEPS in selectedFields) {
                    if (isUltrafixParams) {
                        ultrafixSteps = params.steps.toFloat()
                        val maxDenoiseSteps = minOf(
                            GenerationDefaults.ULTRAFIX_DENOISE_STEPS_MAX,
                            ultrafixSteps.roundToInt(),
                        )
                        ultrafixDenoiseSteps = ultrafixDenoiseSteps.coerceIn(0, maxDenoiseSteps)
                    } else {
                        steps = params.steps.toFloat()
                    }
                }
                if (ParamShareField.CFG in selectedFields) {
                    cfg = params.cfg
                }
                if (ParamShareField.SEED in selectedFields) {
                    seed = params.seed?.toString() ?: ""
                }
                if (ParamShareField.SCHEDULER in selectedFields) {
                    scheduler = params.scheduler
                }
                if (ParamShareField.DENOISE_STRENGTH in selectedFields) {
                    if (isUltrafixParams) {
                        // Invert strength = (steps - 0.5) / total -> steps.
                        val total = params.steps
                        val maxDenoiseSteps = minOf(
                            GenerationDefaults.ULTRAFIX_DENOISE_STEPS_MAX,
                            total,
                        )
                        ultrafixDenoiseSteps =
                            (params.denoiseStrength * total + 0.5f).roundToInt()
                                .coerceIn(0, maxDenoiseSteps)
                    } else {
                        denoiseStrength = params.denoiseStrength
                    }
                }
                if (isUltrafixParams) {
                    saveUltrafixParams()
                }
                if (model?.usesFixedCanvas == true && useImg2img) {
                    val newRatio = inferAspectRatioString(params.width, params.height)
                    if (newRatio != aspectRatio) {
                        aspectRatio = newRatio
                        clearImg2imgState()
                    }
                }
                saveAllFields()

                showReproduceParamsDialog = false
                pendingReproduceParams = null
                showHistoryDetailDialog = false
                selectedHistoryItem = null
                scope.launch {
                    pagerState.animateScrollToPage(0)
                }
            },
            onDismiss = {
                showReproduceParamsDialog = false
                pendingReproduceParams = null
                showHistoryDetailDialog = false
                selectedHistoryItem = null
            },
        )
    }

    // Delete confirmation dialog
    if (showDeleteHistoryDialog && selectedHistoryItem != null) {
        ModelRunConfirmDialog(
            title = stringResource(R.string.delete_image),
            text = stringResource(R.string.delete_image_confirm),
            confirmText = stringResource(R.string.delete),
            destructiveConfirm = true,
            onConfirm = {
                scope.launch {
                    val success = historyManager.deleteHistoryItem(
                        item = selectedHistoryItem!!,
                    )
                    if (success) {
                        showDeleteHistoryDialog = false
                        showHistoryDetailDialog = false
                        selectedHistoryItem = null
                        Toast.makeText(
                            context,
                            msgDeleted,
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            msgDeleteFailedMessage,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onDismiss = { showDeleteHistoryDialog = false },
        )
    }

    // Batch save confirmation dialog
    if (showBatchSaveDialog && selectedIds.isNotEmpty()) {
        ModelRunConfirmDialog(
            title = stringResource(R.string.batch_save),
            text = pluralStringResource(
                R.plurals.batch_save_confirm,
                selectedIds.size,
                selectedIds.size,
            ),
            confirmText = stringResource(R.string.yes),
            onConfirm = {
                val ids = selectedIds.toList()
                showBatchSaveDialog = false
                if (ids.isNotEmpty()) {
                    batchSaveTotal = ids.size
                    batchSaveCurrent = 0
                    batchSaveFailed = 0
                    isBatchSaving = true
                    scope.launch(Dispatchers.IO) {
                        // Resolve ids to items; any gone-missing counts as failed.
                        val items = historyManager.getItems(ids)
                        val missing = ids.size - items.size
                        if (missing > 0) {
                            withContext(Dispatchers.Main) {
                                batchSaveFailed += missing
                                batchSaveCurrent += missing
                            }
                        }
                        items.forEach { item ->
                            var success = false
                            if (item.imageFile.exists()) {
                                saveImageFromFile(
                                    context = context,
                                    sourceFile = item.imageFile,
                                    onSuccess = { success = true },
                                    onError = { },
                                )
                            }
                            withContext(Dispatchers.Main) {
                                batchSaveCurrent += 1
                                if (!success) batchSaveFailed += 1
                            }
                        }
                        withContext(Dispatchers.Main) {
                            val total = batchSaveTotal
                            val failed = batchSaveFailed
                            val saved = total - failed
                            val message = if (failed == 0) {
                                resources.getQuantityString(
                                    R.plurals.saved_count,
                                    saved,
                                    saved,
                                )
                            } else {
                                msgSavedCountWithFailed.format(saved, failed)
                            }
                            Toast.makeText(
                                context,
                                message,
                                Toast.LENGTH_SHORT,
                            ).show()
                            isBatchSaving = false
                            selectedIds.clear()
                            isSelectionMode = false
                        }
                    }
                }
            },
            onDismiss = { showBatchSaveDialog = false },
        )
    }

    // Batch save progress dialog (modal — blocks other interactions)
    if (isBatchSaving) {
        BatchSaveProgressDialog(current = batchSaveCurrent, total = batchSaveTotal)
    }

    // Batch delete confirmation dialog
    if (showBatchDeleteDialog && selectedIds.isNotEmpty()) {
        ModelRunConfirmDialog(
            title = stringResource(R.string.batch_delete),
            text = pluralStringResource(
                R.plurals.batch_delete_confirm,
                selectedIds.size,
                selectedIds.size,
            ),
            confirmText = stringResource(R.string.delete),
            destructiveConfirm = true,
            onConfirm = {
                val ids = selectedIds.toList()
                showBatchDeleteDialog = false
                scope.launch {
                    val itemsToDelete = historyManager.getItems(ids)
                    val successCount = historyManager.deleteHistoryItems(itemsToDelete)
                    val failCount = ids.size - successCount

                    selectedIds.clear()
                    isSelectionMode = false

                    val message = if (failCount == 0) {
                        resources.getQuantityString(
                            R.plurals.deleted_count,
                            successCount,
                            successCount,
                        )
                    } else {
                        msgDeletedCountWithFailed.format(successCount, failCount)
                    }
                    Toast.makeText(
                        context,
                        message,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onDismiss = { showBatchDeleteDialog = false },
        )
    }

    // Detect shared params on the clipboard once the model is ready. Remote
    // mode has no local backend, so readiness is the health check finishing.
    LaunchedEffect(backendState, hasInitialized, isCheckingBackend) {
        val backendReady = if (isRemote) {
            !isCheckingBackend && errorMessage == null
        } else {
            backendState is BackendService.BackendState.Running
        }
        if (!clipboardImportChecked &&
            hasInitialized &&
            backendReady
        ) {
            clipboardImportChecked = true
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val raw = clipboard?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
            ParamShare.tryDecode(raw)?.let { pendingImport = it }
        }
    }

    // Share parameters dialog
    shareSourceParams?.let { source ->
        ShareParamsFlow(
            source = source,
            modelId = shareSourceModelId,
            useBase64Initial = shareUseBase64,
            onUseBase64Changed = { value ->
                scope.launch { generationPreferences.setShareUseBase64(value) }
            },
            onCopied = { clipboardImportChecked = true },
            onDismiss = {
                shareSourceParams = null
                shareSourceModelId = null
            },
        )
    }

    // Import shared parameters dialog
    pendingImport?.let { imported ->
        val clearClipboardAction = {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? ClipboardManager
            runCatching {
                // Build.VERSION_CODES.P is API 28, minSdk = 28, so the legacy
                // setPrimaryClip(empty) fallback is unreachable.
                clipboard?.clearPrimaryClip()
            }
        }
        ImportParametersDialog(
            imported = imported,
            clearClipboardInitial = shareClearClipboardOnImport,
            onClearClipboardChanged = { value ->
                scope.launch {
                    generationPreferences.setShareClearClipboardOnImport(value)
                }
            },
            onApply = { selectedFields, clearClipboard ->
                if (ParamShareField.PROMPT in selectedFields) {
                    imported.prompt?.let { promptField.replaceText(it) }
                }
                if (ParamShareField.NEGATIVE_PROMPT in selectedFields) {
                    imported.negativePrompt?.let { negativePromptField.replaceText(it) }
                }
                if (ParamShareField.STEPS in selectedFields) {
                    imported.steps?.let { steps = it.toFloat() }
                }
                if (ParamShareField.CFG in selectedFields) {
                    imported.cfg?.let { cfg = it }
                }
                if (ParamShareField.SEED in selectedFields) {
                    seed = imported.seed?.toString() ?: ""
                }
                if (ParamShareField.SCHEDULER in selectedFields) {
                    imported.scheduler?.let { scheduler = it }
                }
                if (ParamShareField.DENOISE_STRENGTH in selectedFields) {
                    imported.denoiseStrength?.let { denoiseStrength = it }
                }
                saveAllFields()
                if (clearClipboard) {
                    clearClipboardAction()
                }
                pendingImport = null
                Toast.makeText(
                    context,
                    msgImportApplied,
                    Toast.LENGTH_SHORT,
                ).show()
            },
            onDismiss = { clearClipboard ->
                if (clearClipboard) {
                    clearClipboardAction()
                }
                pendingImport = null
            },
        )
    }
}

@Composable
private fun PromptCountLabel(label: String, count: Int, max: Int, showCount: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        if (showCount) {
            Spacer(Modifier.width(6.dp))
            Text(if (max == 0) "$count/∞" else "$count/$max")
        }
    }
}
