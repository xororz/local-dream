package io.github.xororz.localdream

import android.app.Application
import io.github.xororz.localdream.data.HistoryManager
import io.github.xororz.localdream.data.HistoryMigration
import io.github.xororz.localdream.data.MigrationState
import io.github.xororz.localdream.data.db.AppDatabase
import io.github.xororz.localdream.service.BackgroundGenerationService
import io.github.xororz.localdream.utils.PrivacySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LocalDreamApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _migrationState = MutableStateFlow<MigrationState>(MigrationState.Idle)
    val migrationState: StateFlow<MigrationState> = _migrationState.asStateFlow()

    private var migrationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startMigration()
        // "Don't keep history": every fresh start (after the app was swiped
        // away from Recents, killed or rebooted) begins with an empty history.
        // Runs once the history migration has settled; waits through a failed
        // migration until a retry or skip settles it.
        appScope.launch {
            migrationState.first { it is MigrationState.NotNeeded || it is MigrationState.Done }
            clearHistoryIfEnabled()
        }
    }

    /**
     * Deletes the whole generation history if "Don't keep history" is on.
     * Skipped while a background generation is still running, since its result
     * is saved to the history when the user comes back; the next start clears it.
     */
    fun clearHistoryIfEnabled() {
        if (!PrivacySettings.isForgetHistoryEnabled(this)) return
        if (BackgroundGenerationService.isServiceRunning.value) return
        appScope.launch {
            HistoryManager(this@LocalDreamApplication).clearAllHistory()
        }
    }

    private fun startMigration() {
        migrationJob?.cancel()
        migrationJob = appScope.launch {
            try {
                if (HistoryMigration.isDone(this@LocalDreamApplication)) {
                    _migrationState.value = MigrationState.NotNeeded
                    return@launch
                }
                HistoryMigration.migrate(
                    this@LocalDreamApplication,
                    AppDatabase.get(this@LocalDreamApplication),
                    _migrationState,
                )
            } catch (e: Throwable) {
                _migrationState.value = MigrationState.Failed(e)
            }
        }
    }

    fun retryMigration() {
        _migrationState.value = MigrationState.Idle
        startMigration()
    }

    fun skipMigration() {
        migrationJob?.cancel()
        appScope.launch {
            try {
                HistoryMigration.markDoneExternal(this@LocalDreamApplication)
            } catch (_: Throwable) {
                // ignore — UI will still proceed
            }
            _migrationState.value = MigrationState.Done
        }
    }
}
