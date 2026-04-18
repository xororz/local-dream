package io.github.xororz.localdream.utils

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

object LogCapture {
    private const val TAG = "LogCapture"
    private const val MAX_BUFFER_BYTES = 2_000_000
    private const val TRIM_KEEP_BYTES = 1_000_000

    private val lock = Any()
    private val buffer = StringBuilder()
    private var captureProcess: java.lang.Process? = null
    private var captureScope: CoroutineScope? = null
    private var captureJob: Job? = null

    val lastCapturedLogs = mutableStateOf<String?>(null)

    fun start() {
        synchronized(lock) {
            stopInternalLocked()
            buffer.clear()
            try {
                Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "logcat -c failed", e)
            }
            try {
                val pid = android.os.Process.myPid()
                val proc = Runtime.getRuntime().exec(
                    arrayOf("logcat", "--pid=$pid", "-v", "threadtime")
                )
                captureProcess = proc
                val scope = CoroutineScope(Dispatchers.IO)
                captureScope = scope
                captureJob = scope.launch {
                    try {
                        BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                            var line: String? = null
                            while (isActive && reader.readLine().also { line = it } != null) {
                                val current = line ?: continue
                                synchronized(lock) {
                                    buffer.append(current).append('\n')
                                    if (buffer.length > MAX_BUFFER_BYTES) {
                                        val tail = buffer.substring(buffer.length - TRIM_KEEP_BYTES)
                                        buffer.setLength(0)
                                        buffer.append(tail)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "log read loop ended", e)
                    }
                }
                Log.i(TAG, "log capture started for pid=$pid")
            } catch (e: Exception) {
                Log.e(TAG, "failed to start logcat", e)
            }
        }
    }

    fun stopAndPublish() {
        val captured: String
        synchronized(lock) {
            stopInternalLocked()
            captured = buffer.toString()
        }
        lastCapturedLogs.value = captured
        Log.i(TAG, "log capture stopped, ${captured.length} chars")
    }

    fun consume() {
        lastCapturedLogs.value = null
    }

    private fun stopInternalLocked() {
        try {
            captureProcess?.destroy()
        } catch (_: Exception) {
        }
        captureProcess = null
        try {
            captureJob?.cancel()
        } catch (_: Exception) {
        }
        captureJob = null
        try {
            captureScope?.cancel()
        } catch (_: Exception) {
        }
        captureScope = null
    }
}
