package com.geminianytype.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val API_KEY = stringPreferencesKey("anytype_api_key")
        private val BASE_URL = stringPreferencesKey("anytype_base_url")
        private val SELECTED_SPACE_ID = stringPreferencesKey("selected_space_id")
        private val SERVICE_RUNNING = booleanPreferencesKey("service_running")
        const val DEFAULT_BASE_URL = "http://192.168.1.100:31009"
    }
    
    val apiKey: Flow<String> = context.dataStore.data.map { it[API_KEY] ?: "" }
    val baseUrl: Flow<String> = context.dataStore.data.map { it[BASE_URL] ?: DEFAULT_BASE_URL }
    val selectedSpaceId: Flow<String?> = context.dataStore.data.map { it[SELECTED_SPACE_ID] }
    val isServiceRunning: Flow<Boolean> = context.dataStore.data.map { it[SERVICE_RUNNING] ?: false }
    
    suspend fun setApiKey(key: String) {
        context.dataStore.edit { it[API_KEY] = key }
    }
    
    suspend fun setBaseUrl(url: String) {
        context.dataStore.edit { it[BASE_URL] = url }
    }
    
    suspend fun setSelectedSpace(id: String) {
        context.dataStore.edit { it[SELECTED_SPACE_ID] = id }
    }
    
    suspend fun setServiceRunning(running: Boolean) {
        context.dataStore.edit { it[SERVICE_RUNNING] = running }
    }
}
