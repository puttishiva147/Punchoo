package com.pullnotify.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.pullNotifyDataStore by preferencesDataStore(name = "pullnotify_settings")

/** DataStore is the durable settings copy; SharedPreferences remains a synchronous compatibility mirror
 * for the notification service and overlay so they can react immediately without blocking a callback.
 */
object SettingsRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun persist(context: Context, key: String, value: Any) {
        val app = context.applicationContext
        scope.launch {
            try {
                app.pullNotifyDataStore.edit { settings ->
                    when (value) {
                        is String -> settings[stringPreferencesKey(key)] = value
                        is Boolean -> settings[booleanPreferencesKey(key)] = value
                        is Int -> settings[intPreferencesKey(key)] = value
                        is Set<*> -> settings[stringSetPreferencesKey(key)] = value.filterIsInstance<String>().toSet()
                    }
                }
            } catch (_: Exception) {
                // The immediate compatibility mirror still keeps the current session usable.
            }
        }
    }

    fun restore(context: Context, onComplete: () -> Unit) {
        val app = context.applicationContext
        scope.launch {
            try {
                val saved = app.pullNotifyDataStore.data.first()
                val editor = app.getSharedPreferences("pullnotify", Context.MODE_PRIVATE).edit()
                saved.asMap().forEach { (key, value) ->
                    when (value) {
                        is String -> editor.putString(key.name, value)
                        is Boolean -> editor.putBoolean(key.name, value)
                        is Int -> editor.putInt(key.name, value)
                        is Set<*> -> editor.putStringSet(key.name, value.filterIsInstance<String>().toSet())
                    }
                }
                editor.apply()
            } catch (_: Exception) {
                // Missing or unreadable DataStore data falls back to the existing local settings.
            }
            withContext(Dispatchers.Main) { onComplete() }
        }
    }
}
