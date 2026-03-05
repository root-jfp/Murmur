package com.murmur.reader.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "murmur_prefs")

data class UserPreferences(
    val voiceName: String = "en-US-AriaNeural",
    val ratePercent: Float = 0f,        // -100 to +200, maps to rate string "+0%"
    val pitchHz: Float = 0f,            // -100 to +100, maps to pitch string "+0Hz"
    val fontSize: Float = 18f,          // sp
    val useDarkTheme: Boolean = true,
    val keepScreenOn: Boolean = true,
    val useSerifFont: Boolean = true,   // Noto Serif vs sans-serif
)

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    private object Keys {
        val VOICE_NAME = stringPreferencesKey("voice_name")
        val RATE_PERCENT = floatPreferencesKey("rate_percent")
        val PITCH_HZ = floatPreferencesKey("pitch_hz")
        val FONT_SIZE = floatPreferencesKey("font_size")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SERIF_FONT = booleanPreferencesKey("serif_font")
    }

    val preferences: Flow<UserPreferences> = dataStore.data.map { prefs ->
        UserPreferences(
            voiceName = prefs[Keys.VOICE_NAME] ?: "en-US-AriaNeural",
            ratePercent = prefs[Keys.RATE_PERCENT] ?: 0f,
            pitchHz = prefs[Keys.PITCH_HZ] ?: 0f,
            fontSize = prefs[Keys.FONT_SIZE] ?: 18f,
            useDarkTheme = prefs[Keys.DARK_THEME] ?: true,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            useSerifFont = prefs[Keys.SERIF_FONT] ?: true,
        )
    }

    suspend fun setVoiceName(name: String) {
        dataStore.edit { it[Keys.VOICE_NAME] = name }
    }

    suspend fun setRate(percent: Float) {
        dataStore.edit { it[Keys.RATE_PERCENT] = percent }
    }

    suspend fun setPitch(hz: Float) {
        dataStore.edit { it[Keys.PITCH_HZ] = hz }
    }

    suspend fun setFontSize(sp: Float) {
        dataStore.edit { it[Keys.FONT_SIZE] = sp }
    }

    suspend fun setDarkTheme(dark: Boolean) {
        dataStore.edit { it[Keys.DARK_THEME] = dark }
    }

    suspend fun setKeepScreenOn(keep: Boolean) {
        dataStore.edit { it[Keys.KEEP_SCREEN_ON] = keep }
    }

    suspend fun setSerifFont(serif: Boolean) {
        dataStore.edit { it[Keys.SERIF_FONT] = serif }
    }

    /** Converts stored ratePercent (-100..200) to SSML rate string (+0%, +50%, etc.) */
    fun rateToSsml(percent: Float): String {
        val sign = if (percent >= 0) "+" else ""
        return "$sign${percent.toInt()}%"
    }

    /** Converts stored pitchHz (-100..100) to SSML pitch string (+0Hz, +5Hz, etc.) */
    fun pitchToSsml(hz: Float): String {
        val sign = if (hz >= 0) "+" else ""
        return "$sign${hz.toInt()}Hz"
    }
}
