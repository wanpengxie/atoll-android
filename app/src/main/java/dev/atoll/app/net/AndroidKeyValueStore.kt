package dev.atoll.app.net

import android.content.SharedPreferences

/** Small platform adapter; credentials themselves are never persisted. */
class AndroidKeyValueStore(private val preferences: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = preferences.getString(key, null)
    override fun put(key: String, value: String) { preferences.edit().putString(key, value).apply() }
    override fun remove(key: String) { preferences.edit().remove(key).apply() }
}
