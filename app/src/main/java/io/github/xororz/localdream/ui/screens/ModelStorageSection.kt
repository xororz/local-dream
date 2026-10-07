package io.github.xororz.localdream.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.ModelStorage
import io.github.xororz.localdream.data.ModelStorage.Location
import io.github.xororz.localdream.data.ModelStorage.MoveState
import io.github.xororz.localdream.ui.components.BlockingProgressOverlay
import io.github.xororz.localdream.ui.components.SmoothCircularWavyProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings: keep models in app storage or in Download/LocalDream. */
@Composable
internal fun ModelStorageSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val moveState by ModelStorage.moveState.collectAsState()

    // Re-read on every resume: All files access is granted or revoked in the
    // system settings, outside the app.
    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        revision++
        onPauseOrDispose { }
    }
    val current = remember(revision, moveState) { ModelStorage.location(context) }
    val accessLost = remember(revision, moveState) { ModelStorage.isAccessLost(context) }
    val pending = remember(revision, moveState) { ModelStorage.pendingMove(context) }

    var confirmTarget by remember { mutableStateOf<Location?>(null) }
    var confirmBytes by remember { mutableStateOf(0L) }
    var awaitingAccessFor by remember { mutableStateOf<Location?>(null) }

    val msgBusy = stringResource(R.string.model_storage_busy)
    val msgNoAccess = stringResource(R.string.model_storage_no_access)

    // After an unfinished move either location is a valid target: the
    // one it was headed for finishes it, the other one moves the files back.
    fun canMoveTo(target: Location): Boolean = target != ModelStorage.location(context) ||
        ModelStorage.pendingMove(context) != null

    fun startMove(target: Location) {
        if (!ModelStorage.startMove(context, target)) {
            Toast.makeText(context, msgBusy, Toast.LENGTH_SHORT).show()
        }
    }

    fun askToMove(target: Location) {
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                ModelStorage.sizeAt(context, ModelStorage.other(target))
            }
            if (bytes == 0L) {
                startMove(target)
            } else {
                confirmBytes = bytes
                confirmTarget = target
            }
        }
    }

    val accessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val target = awaitingAccessFor
        awaitingAccessFor = null
        revision++
        if (!ModelStorage.hasAllFilesAccess()) {
            Toast.makeText(context, msgNoAccess, Toast.LENGTH_SHORT).show()
        } else if (target != null && canMoveTo(target)) {
            askToMove(target)
        }
    }

    fun requestAccess(target: Location?) {
        awaitingAccessFor = target
        val launched = ModelStorage.allFilesAccessIntents(context).any { intent ->
            runCatching { accessLauncher.launch(intent) }.isSuccess
        }
        if (!launched) {
            awaitingAccessFor = null
            Toast.makeText(context, msgNoAccess, Toast.LENGTH_SHORT).show()
        }
    }

    fun choose(target: Location) {
        if (moveState is MoveState.Moving || !canMoveTo(target)) return
        if (ModelStorage.isBusy()) {
            Toast.makeText(context, msgBusy, Toast.LENGTH_SHORT).show()
            return
        }
        // Either direction touches Download/LocalDream.
        if (!ModelStorage.hasAllFilesAccess()) {
            requestAccess(target)
        } else {
            askToMove(target)
        }
    }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.FolderShared,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.model_storage),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            StorageOption(
                title = locationLabel(Location.INTERNAL),
                description = stringResource(R.string.model_storage_internal_hint),
                selected = current == Location.INTERNAL,
                enabled = true,
                onClick = { choose(Location.INTERNAL) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            val supported = ModelStorage.isPublicStorageSupported()
            StorageOption(
                title = locationLabel(Location.DOWNLOADS),
                description = if (supported) {
                    stringResource(R.string.model_storage_public_hint)
                } else {
                    stringResource(R.string.model_storage_public_unsupported)
                },
                selected = current == Location.DOWNLOADS,
                enabled = supported,
                onClick = { choose(Location.DOWNLOADS) },
            )
            if (pending != null && moveState !is MoveState.Moving) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Text(
                    stringResource(R.string.model_storage_pending, locationLabel(pending)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (accessLost) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.model_storage_access_lost),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { requestAccess(null) }) {
                        Text(stringResource(R.string.model_storage_grant))
                    }
                }
            }
        }
    }

    confirmTarget?.let { target ->
        val destination = locationLabel(target)
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text(stringResource(R.string.model_storage_move_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.model_storage_move_confirm,
                        formatBytes(confirmBytes),
                        destination,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmTarget = null
                    startMove(target)
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun locationLabel(location: Location): String = when (location) {
    Location.INTERNAL -> stringResource(R.string.model_storage_internal)
    Location.DOWNLOADS -> stringResource(R.string.model_storage_public, ModelStorage.PUBLIC_FOLDER)
}

@Composable
private fun StorageOption(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}

/**
 * Blocks the screen while models move, and resumes a move the app was killed
 * in the middle of. Lives on the model list, not in Settings, so the move is
 * visible wherever the person goes next. [onModelsChanged] rescans the
 * models after a move, and when All files access is switched in system
 * settings while they live in Download/LocalDream.
 */
@Composable
internal fun ModelStorageMoveOverlay(onModelsChanged: () -> Unit) {
    val context = LocalContext.current
    val moveState by ModelStorage.moveState.collectAsState()
    val currentOnModelsChanged by rememberUpdatedState(onModelsChanged)

    LaunchedEffect(Unit) { ModelStorage.resumePendingMove(context) }

    val msgAccessLost = stringResource(R.string.model_storage_access_lost)
    LifecycleResumeEffect(Unit) {
        ModelStorage.pollAccessChange(context)?.let { hasAccess ->
            if (!hasAccess) Toast.makeText(context, msgAccessLost, Toast.LENGTH_LONG).show()
            currentOnModelsChanged()
        }
        onPauseOrDispose { }
    }

    val msgMoved = stringResource(R.string.model_storage_moved)
    LaunchedEffect(moveState) {
        if (moveState !is MoveState.Done) return@LaunchedEffect
        Toast.makeText(context, msgMoved, Toast.LENGTH_LONG).show()
        ModelStorage.clearMoveState()
        currentOnModelsChanged()
    }

    val moving = moveState as? MoveState.Moving
    BlockingProgressOverlay(visible = moving != null) {
        val fraction = moving?.let {
            if (it.totalBytes > 0) it.doneBytes.toFloat() / it.totalBytes else 0f
        } ?: 0f
        SmoothCircularWavyProgressIndicator(
            progress = fraction,
            modifier = Modifier.size(72.dp),
        )
        Text(
            text = stringResource(R.string.model_storage_moving),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "${formatBytes(moving?.doneBytes ?: 0L)} / ${formatBytes(moving?.totalBytes ?: 0L)}",
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    (moveState as? MoveState.Failed)?.let { failed ->
        // Some models may already be on the other side: rescan either way.
        val dismiss = {
            ModelStorage.clearMoveState()
            currentOnModelsChanged()
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.model_storage_move_failed)) },
            text = { Text(failed.message) },
            confirmButton = {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.confirm)) }
            },
        )
    }
}
