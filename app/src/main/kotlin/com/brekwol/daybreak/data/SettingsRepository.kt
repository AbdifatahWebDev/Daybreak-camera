package com.brekwol.daybreak.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.brekwol.daybreak.engine.EncodeSettings
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class DaybreakSettings(
    val quality: Int = 90,
    val effort: Int = 7,
    val lossless: Boolean = false,
    val grid: Boolean = true,
    /** Look for a new version on GitHub once a day (GitHub builds only). */
    val updateAutoCheck: Boolean = true,
    /** Download a new version in the background when on Wi-Fi. */
    val updateAutoDownload: Boolean = true,
    /** Also offer pre-releases (test builds). */
    val updatePreReleases: Boolean = false,
    /** When the last update check succeeded, in epoch milliseconds. */
    val lastUpdateCheck: Long = 0L,
) {
    val encodeSettings: EncodeSettings
        get() = EncodeSettings(quality = quality, effort = effort, lossless = lossless)
}

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val quality = intPreferencesKey("jxl_quality")
        val effort = intPreferencesKey("jxl_effort")
        val lossless = booleanPreferencesKey("jxl_lossless")
        val grid = booleanPreferencesKey("grid")
        val updateAutoCheck = booleanPreferencesKey("update_auto_check")
        val updateAutoDownload = booleanPreferencesKey("update_auto_download")
        val updatePreReleases = booleanPreferencesKey("update_pre_releases")
        val lastUpdateCheck = longPreferencesKey("update_last_check")
    }

    val settings: Flow<DaybreakSettings> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs ->
            DaybreakSettings(
                quality = prefs[Keys.quality] ?: 90,
                effort = prefs[Keys.effort] ?: 7,
                lossless = prefs[Keys.lossless] ?: false,
                grid = prefs[Keys.grid] ?: true,
                updateAutoCheck = prefs[Keys.updateAutoCheck] ?: true,
                updateAutoDownload = prefs[Keys.updateAutoDownload] ?: true,
                updatePreReleases = prefs[Keys.updatePreReleases] ?: false,
                lastUpdateCheck = prefs[Keys.lastUpdateCheck] ?: 0L,
            )
        }

    suspend fun current(): DaybreakSettings = settings.first()

    suspend fun setQuality(value: Int) {
        store.edit { it[Keys.quality] = value.coerceIn(50, 100) }
    }

    suspend fun setEffort(value: Int) {
        store.edit { it[Keys.effort] = value.coerceIn(1, 10) }
    }

    suspend fun setLossless(value: Boolean) {
        store.edit { it[Keys.lossless] = value }
    }

    suspend fun setGrid(value: Boolean) {
        store.edit { it[Keys.grid] = value }
    }

    suspend fun setUpdateAutoCheck(value: Boolean) {
        store.edit { it[Keys.updateAutoCheck] = value }
    }

    suspend fun setUpdateAutoDownload(value: Boolean) {
        store.edit { it[Keys.updateAutoDownload] = value }
    }

    suspend fun setUpdatePreReleases(value: Boolean) {
        store.edit { it[Keys.updatePreReleases] = value }
    }

    suspend fun setLastUpdateCheck(value: Long) {
        store.edit { it[Keys.lastUpdateCheck] = value }
    }
}
