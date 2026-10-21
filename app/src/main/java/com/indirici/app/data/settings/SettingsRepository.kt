package com.indirici.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.indirici.app.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "indirici_settings")

class SettingsRepository(private val context: Context) {

    private val defaultChunkCountKey = intPreferencesKey("default_chunk_count")
    private val maxConcurrentDownloadsKey = intPreferencesKey("max_concurrent_downloads")

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val chunks = (prefs[defaultChunkCountKey] ?: AppSettings.DEFAULT_CHUNK_COUNT)
            .coerceIn(AppSettings.MIN_CHUNK_COUNT, AppSettings.MAX_CHUNK_COUNT)
        val maxConcurrent = (prefs[maxConcurrentDownloadsKey] ?: AppSettings.DEFAULT_MAX_CONCURRENT)
            .coerceIn(AppSettings.MIN_CONCURRENT, AppSettings.MAX_CONCURRENT)

        AppSettings(
            defaultChunkCount = chunks,
            maxConcurrentDownloads = maxConcurrent
        )
    }

    suspend fun updateSettings(defaultChunkCount: Int, maxConcurrentDownloads: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[defaultChunkCountKey] = defaultChunkCount.coerceIn(
                AppSettings.MIN_CHUNK_COUNT,
                AppSettings.MAX_CHUNK_COUNT
            )
            prefs[maxConcurrentDownloadsKey] = maxConcurrentDownloads.coerceIn(
                AppSettings.MIN_CONCURRENT,
                AppSettings.MAX_CONCURRENT
            )
        }
    }
}
