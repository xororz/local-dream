package io.github.xororz.localdream.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.ModelStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the app in the foreground while [ModelStorage] moves models, so a
 * multi-gigabyte move is not frozen or killed once the app is left. The move
 * itself runs in ModelStorage; this only mirrors its progress and stops when
 * it ends.
 */
class ModelMoveService : Service() {
    companion object {
        private const val TAG = "ModelMoveService"
        private const val CHANNEL_ID = "model_move_channel"
        private const val NOTIFICATION_ID = 2002
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.model_storage),
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, createNotification(0))
        } catch (e: Exception) {
            // The move goes on without it; the system may just reclaim the
            // process sooner, and the move resumes on the next launch.
            Log.w(TAG, "startForeground rejected: ${e.message}")
        }
        if (watcher == null) {
            watcher = scope.launch {
                ModelStorage.moveState
                    .map { state ->
                        (state as? ModelStorage.MoveState.Moving)?.let {
                            if (it.totalBytes > 0) (it.doneBytes * 100 / it.totalBytes).toInt() else 0
                        }
                    }
                    // Whole percents only: the system drops notification
                    // updates that come faster than a few per second.
                    .distinctUntilChanged()
                    .collect { percent ->
                        if (percent != null) {
                            notificationManager.notify(NOTIFICATION_ID, createNotification(percent))
                        } else {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Log.w(TAG, "Foreground service timeout (fgsType=$fgsType)")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotification(percent: Int): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.model_storage_moving))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setProgress(100, percent, false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
