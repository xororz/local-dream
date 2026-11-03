package io.github.xororz.localdream.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavController
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.RemoteConnectResult
import io.github.xororz.localdream.data.RemoteRepository
import io.github.xororz.localdream.navigation.popBackStackIfResumed
import io.github.xororz.localdream.remote.RemoteProtocol
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.service.RemoteHostService
import io.github.xororz.localdream.utils.BackendAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Device Link" screen: host-mode dashboard (this device is controlled by
 * another one) and controller setup (this device drives a remote host).
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RemoteScreen(navController: NavController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remoteRepository = remember { RemoteRepository.getInstance(context) }

    val hostRunning by RemoteHostService.isRunning.collectAsState()
    val servingModelId by BackendService.servingModelId.collectAsState()

    val msgConnectedTo = stringResource(R.string.remote_connected_to)
    val msgUnreachable = stringResource(R.string.remote_connect_failed_unreachable)
    val msgUnauthorized = stringResource(R.string.remote_connect_failed_code)

    var hostInput by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var localCode by remember { mutableStateOf(BackendAuth.localCode(context)) }
    var connecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }

    // Pure-black overlay for OLED screens: keeps the device awake (so the
    // control server and backend stay at full performance) while every pixel
    // is off.
    var screenShield by remember { mutableStateOf(false) }

    // Host mode is meant to keep serving while the user leaves the device on
    // this screen; prevent the system from dozing the CPU via screen off.
    val view = LocalView.current
    DisposableEffect(hostRunning) {
        view.keepScreenOn = hostRunning
        onDispose { view.keepScreenOn = false }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    if (screenShield) {
        ScreenShieldOverlay(onExit = { screenShield = false })
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remote_link)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStackIfResumed() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        val focusManager = LocalFocusManager.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                // Tapping outside the address field drops its focus (and the
                // keyboard); no ripple, it's not an interactive element.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    focusManager.clearFocus()
                }
                // Keep the focused field above the IME instead of behind it.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // === Host mode (this device runs the models) ===
            Column {
                SectionHeader(
                    icon = Icons.Default.Devices,
                    title = stringResource(R.string.remote_host_section),
                )
                Text(
                    stringResource(R.string.remote_host_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // The code a controller must enter; also required by the
                        // generation port, so nobody else on the network can use it.
                        Text(
                            stringResource(R.string.remote_access_code),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            BackendAuth.format(localCode),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            stringResource(R.string.remote_access_code_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // A running backend keeps the code it was started with,
                        // so only allow a new one while nothing is serving.
                        OutlinedButton(
                            onClick = { localCode = BackendAuth.regenerateLocalCode(context) },
                            enabled = !hostRunning && servingModelId == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.remote_access_code_regenerate))
                        }
                        HorizontalDivider()
                        if (hostRunning) {
                            Text(
                                stringResource(R.string.remote_host_addresses),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            val addresses = remember(hostRunning) {
                                RemoteHostService.localIpAddresses()
                            }
                            if (addresses.isEmpty()) {
                                Text(
                                    stringResource(R.string.remote_no_network),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                addresses.forEach { address ->
                                    Text(
                                        address,
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            HorizontalDivider()
                            Text(
                                servingModelId?.let {
                                    stringResource(R.string.remote_host_serving, it)
                                } ?: stringResource(R.string.remote_host_no_model),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { screenShield = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.remote_screen_shield))
                            }
                            OutlinedButton(
                                onClick = { RemoteHostService.stop(context) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.remote_host_stop))
                            }
                        } else {
                            Button(
                                onClick = { RemoteHostService.start(context) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.remote_host_start))
                            }
                        }
                    }
                }
            }

            // === Controller mode (this device drives a remote host) ===
            Column {
                SectionHeader(
                    icon = Icons.Default.Wifi,
                    title = stringResource(R.string.remote_connect_section),
                )
                Text(
                    stringResource(R.string.remote_connect_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val connection = remoteRepository.connection
                        if (connection != null) {
                            Text(
                                stringResource(
                                    R.string.remote_connected_to,
                                    connection.deviceName,
                                ),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "${connection.host}:${connection.port}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch { remoteRepository.refresh() }
                                    },
                                    enabled = !remoteRepository.refreshing,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.remote_refresh_models))
                                }
                                OutlinedButton(
                                    onClick = { remoteRepository.disconnect() },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.remote_disconnect))
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = hostInput,
                                onValueChange = { hostInput = it },
                                label = { Text(stringResource(R.string.remote_host_field)) },
                                placeholder = {
                                    Text("192.168.1.100")
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = codeInput,
                                onValueChange = { codeInput = it },
                                label = { Text(stringResource(R.string.remote_access_code_field)) },
                                placeholder = { Text("XXXX-XXXX-XXXX") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Characters,
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Password,
                                ),
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            connectError?.let { error ->
                                Text(
                                    error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Button(
                                onClick = {
                                    connectError = null
                                    connecting = true
                                    scope.launch {
                                        val result = remoteRepository.connect(
                                            hostInput.trim(),
                                            BackendAuth.normalize(codeInput),
                                        )
                                        connecting = false
                                        when (result) {
                                            is RemoteConnectResult.Success -> {
                                                Toast.makeText(
                                                    context,
                                                    msgConnectedTo.format(result.deviceName),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                                navController.popBackStackIfResumed()
                                            }

                                            is RemoteConnectResult.Unreachable ->
                                                connectError = msgUnreachable

                                            is RemoteConnectResult.Unauthorized ->
                                                connectError = msgUnauthorized
                                        }
                                    }
                                },
                                enabled = !connecting &&
                                    hostInput.isNotBlank() &&
                                    BackendAuth.isValidCode(BackendAuth.normalize(codeInput)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (connecting) {
                                    LoadingIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else {
                                    Text(stringResource(R.string.remote_connect))
                                }
                            }
                            Text(
                                stringResource(
                                    R.string.remote_port_hint,
                                    RemoteProtocol.CONTROL_PORT,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// How long the shield's exit hint stays visible before fading to full black.
private const val HINT_HIDE_DELAY_MS = 5_000L

/**
 * Full-screen pure-black overlay for host mode on OLED screens: the window
 * stays on (keepScreenOn is held by the screen underneath), so the control
 * server and backend keep full performance, while every pixel is off.
 * Tap or back dismisses it.
 */
@Composable
private fun ScreenShieldOverlay(onExit: () -> Unit) {
    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // Immersive mode on the dialog's own window: hide the status bar and
        // the navigation bar (gesture pill) so no pixel stays lit, and keep
        // the screen on ourselves (the activity window's keepScreenOn flag
        // belongs to the window below this one).
        val dialogView = LocalView.current
        DisposableEffect(Unit) {
            dialogView.keepScreenOn = true
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            val controller = window?.let { WindowInsetsControllerCompat(it, dialogView) }
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                dialogView.keepScreenOn = false
                controller?.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        // The exit hint itself fades out after a few seconds so the panel
        // ends up fully black; tapping anywhere still exits.
        var hintVisible by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            delay(HINT_HIDE_DELAY_MS)
            hintVisible = false
        }
        // Intentionally literal black/white: this is an OLED power/burn-in
        // shield that must be pure black in every theme, not a themed surface.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onExit,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(
                visible = hintVisible,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(
                    stringResource(R.string.remote_screen_shield_exit),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.25f),
                    modifier = Modifier.padding(bottom = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
