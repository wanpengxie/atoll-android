package dev.atoll.app.app

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

private val Context.workspacePreferences by preferencesDataStore("workspace")

data class ChannelViewOptions(val mineOnly: Boolean = true, val actorFilters: Set<String> = emptySet(), val showChannelActivity: Boolean = true)

interface ViewPreferences {
    suspend fun get(namespace: String, key: String): String?
    suspend fun put(namespace: String, key: String, value: String)
    suspend fun options(namespace: String, channel: String): ChannelViewOptions
    suspend fun saveOptions(namespace: String, channel: String, options: ChannelViewOptions)
}

class AppPreferences(context: Context) : ViewPreferences {
    private val data = context.applicationContext.workspacePreferences
    suspend fun account(): Pair<String, String> {
        val prefs = data.data.first()
        return (prefs[stringPreferencesKey("server")] ?: "https://tardis.tail6bc2a1.ts.net") to (prefs[stringPreferencesKey("account")] ?: "")
    }
    suspend fun saveAccount(server: String, account: String) { data.edit { it[stringPreferencesKey("server")] = server; it[stringPreferencesKey("account")] = account } }
    override suspend fun get(namespace: String, key: String): String? = data.data.first()[stringPreferencesKey("$namespace.$key")]
    override suspend fun put(namespace: String, key: String, value: String) { data.edit { it[stringPreferencesKey("$namespace.$key")] = value } }
    override suspend fun options(namespace: String, channel: String): ChannelViewOptions {
        val prefs = data.data.first()
        return ChannelViewOptions(prefs[stringPreferencesKey("$namespace.$channel.scope")] != "all",
            prefs[stringSetPreferencesKey("$namespace.$channel.actors")].orEmpty(),
            prefs[booleanPreferencesKey("$namespace.$channel.activities")] != false)
    }
    override suspend fun saveOptions(namespace: String, channel: String, options: ChannelViewOptions) {
        data.edit {
            it[stringPreferencesKey("$namespace.$channel.scope")] = if (options.mineOnly) "mine" else "all"
            it[stringSetPreferencesKey("$namespace.$channel.actors")] = options.actorFilters
            it[booleanPreferencesKey("$namespace.$channel.activities")] = options.showChannelActivity
        }
    }
    suspend fun bootstrap(server: String, account: String) = BootstrapCache.decode(get(accountNamespace(server, account), "bootstrap"))
    suspend fun saveBootstrap(server: String, account: String, value: BootstrapCache) = put(accountNamespace(server, account), "bootstrap", value.encode())
    suspend fun forgetBootstrap(server: String, account: String) { data.edit { it.remove(stringPreferencesKey("${accountNamespace(server, account)}.bootstrap")) } }
    suspend fun clearWorld(namespace: String) { data.edit { values -> values.asMap().keys.filter { it.name.startsWith("$namespace.") }.forEach { values.remove(it) } } }
}

fun accountNamespace(server: String, account: String): String = MessageDigest.getInstance("SHA-256")
    .digest("${server.trimEnd('/')}\n$account".toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
