package io.github.xororz.localdream.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.core.content.edit

/**
 * Opt-in privacy switches, stored in the shared "app_prefs" file next to the
 * other feature settings.
 */
object PrivacySettings {
    private const val PREFS = "app_prefs"

    /** Block screenshots and screen recording, and hide the app in Recents. */
    const val KEY_SECURE_SCREEN = "secure_screen"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSecureScreenEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SECURE_SCREEN, false)

    fun setSecureScreenEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_SECURE_SCREEN, enabled) }
        context.findActivity()?.let { applySecureScreen(it, enabled) }
    }

    /**
     * Applies FLAG_SECURE to the activity window. Compose dialogs, bottom
     * sheets and popups inherit it (their default SecureFlagPolicy is Inherit).
     */
    fun applySecureScreen(activity: Activity, enabled: Boolean = isSecureScreenEnabled(activity)) {
        if (enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
