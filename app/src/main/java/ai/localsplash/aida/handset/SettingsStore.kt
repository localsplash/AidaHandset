package ai.localsplash.aida.handset

import android.content.Context
import android.content.SharedPreferences

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var automaticMonitor: Boolean
        get() = prefs.getBoolean(KEY_AUTOMATIC_MONITOR, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTOMATIC_MONITOR, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "handset_settings"
        const val KEY_AUTOMATIC_MONITOR = "automatic_monitor"
    }
}
