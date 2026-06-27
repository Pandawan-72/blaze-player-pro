package fr.retrospare.blazeplayer.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.retrospare.blazeplayer.data.repository.MediaRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val mediaRepository: MediaRepository
) : ViewModel() {

    companion object {
        val KEY_DEFAULT_RATIO    = intPreferencesKey("default_ratio")
        val KEY_DEFAULT_SPEED    = intPreferencesKey("default_speed")
        val KEY_RESUME_PLAYBACK  = booleanPreferencesKey("resume_playback")
        val KEY_SHORT_SKIP       = intPreferencesKey("short_skip")
        val KEY_LONG_SKIP        = intPreferencesKey("long_skip")
        val KEY_AUTO_ROTATE      = booleanPreferencesKey("auto_rotate")
        val KEY_HW_DECODE        = booleanPreferencesKey("hw_decode")
        val KEY_HDR              = booleanPreferencesKey("hdr")
        val KEY_DECODE_MODE      = intPreferencesKey("decode_mode")
        val KEY_SUBTITLE_SIZE    = intPreferencesKey("subtitle_size")
        val KEY_SUBTITLE_BG      = intPreferencesKey("subtitle_bg")
        val KEY_SUBTITLE_DELAY   = intPreferencesKey("subtitle_delay")
        val KEY_ASS_SUPPORT      = booleanPreferencesKey("ass_support")
        val KEY_WIFI_ONLY        = booleanPreferencesKey("wifi_only")
        val KEY_CACHE_SIZE       = intPreferencesKey("cache_size")
        val KEY_CHROMECAST       = booleanPreferencesKey("chromecast")
        val KEY_RESUME_MODE      = intPreferencesKey("resume_mode")
        val KEY_AUTO_PLAY        = booleanPreferencesKey("auto_play")
        val KEY_SPEED_INDEX      = intPreferencesKey("speed_index")
        val KEY_SEEK_TIME_INDEX  = intPreferencesKey("seek_time_index")
        val KEY_ORIENTATION      = intPreferencesKey("orientation")
        val KEY_PIP              = booleanPreferencesKey("pip")
        val KEY_GESTURES         = booleanPreferencesKey("gestures")
        val KEY_AUDIO_LANG       = intPreferencesKey("audio_lang")
        val KEY_REMEMBER_VOLUME  = booleanPreferencesKey("remember_volume")
        val KEY_SUBTITLES_DEFAULT = booleanPreferencesKey("subtitles_default")
        val KEY_SUBTITLE_LANG    = intPreferencesKey("subtitle_lang")
        val KEY_SHOW_HIDDEN      = booleanPreferencesKey("show_hidden")
        val KEY_SHOW_AUDIO       = booleanPreferencesKey("show_audio")
        val KEY_THEME            = intPreferencesKey("theme")
        val KEY_LANGUAGE         = intPreferencesKey("language")
        val KEY_BROWSER_VIEW     = intPreferencesKey("browser_view")
        val KEY_SORT_MODE        = intPreferencesKey("sort_mode")
    }

    private fun getInt(key: Preferences.Key<Int>, default: Int): Int = runBlocking {
        dataStore.data.first()[key] ?: default
    }

    private fun getBool(key: Preferences.Key<Boolean>, default: Boolean): Boolean = runBlocking {
        dataStore.data.first()[key] ?: default
    }

    private fun setInt(key: Preferences.Key<Int>, value: Int) = viewModelScope.launch {
        dataStore.edit { it[key] = value }
    }

    private fun setBool(key: Preferences.Key<Boolean>, value: Boolean) = viewModelScope.launch {
        dataStore.edit { it[key] = value }
    }

    fun getDefaultRatio()      = getInt(KEY_DEFAULT_RATIO, 0)
    fun setDefaultRatio(v: Int) = setInt(KEY_DEFAULT_RATIO, v)

    fun getDefaultSpeed()      = getInt(KEY_DEFAULT_SPEED, 2)
    fun setDefaultSpeed(v: Int) = setInt(KEY_DEFAULT_SPEED, v)

    fun toggleResumePlayback() = setBool(KEY_RESUME_PLAYBACK, !getBool(KEY_RESUME_PLAYBACK, true))

    fun getShortSkip()         = getInt(KEY_SHORT_SKIP, 10)
    fun setShortSkip(v: Int)   = setInt(KEY_SHORT_SKIP, v)

    fun getLongSkip()          = getInt(KEY_LONG_SKIP, 30)
    fun setLongSkip(v: Int)    = setInt(KEY_LONG_SKIP, v)

    fun toggleAutoRotate()     = setBool(KEY_AUTO_ROTATE, !getBool(KEY_AUTO_ROTATE, true))

    fun toggleHardwareDecode() = setBool(KEY_HW_DECODE, !getBool(KEY_HW_DECODE, true))
    fun toggleHdr()            = setBool(KEY_HDR, !getBool(KEY_HDR, true))

    fun getDecodeMode()        = getInt(KEY_DECODE_MODE, 0)
    fun setDecodeMode(v: Int)  = setInt(KEY_DECODE_MODE, v)

    fun getSubtitleSize()      = getInt(KEY_SUBTITLE_SIZE, 1)
    fun setSubtitleSize(v: Int) = setInt(KEY_SUBTITLE_SIZE, v)

    fun getSubtitleBackground()      = getInt(KEY_SUBTITLE_BG, 0)
    fun setSubtitleBackground(v: Int) = setInt(KEY_SUBTITLE_BG, v)

    fun getSubtitleDelay()     = getInt(KEY_SUBTITLE_DELAY, 0)
    fun setSubtitleDelay(v: Int) = setInt(KEY_SUBTITLE_DELAY, v)

    fun toggleAssSupport()     = setBool(KEY_ASS_SUPPORT, !getBool(KEY_ASS_SUPPORT, false))

    fun toggleWifiOnly()       = setBool(KEY_WIFI_ONLY, !getBool(KEY_WIFI_ONLY, true))

    fun getCacheSize()         = getInt(KEY_CACHE_SIZE, 2)
    fun setCacheSize(v: Int)   = setInt(KEY_CACHE_SIZE, v)

    fun toggleChromecast()     = setBool(KEY_CHROMECAST, !getBool(KEY_CHROMECAST, false))

    fun getTheme()             = getInt(KEY_THEME, 0)
    fun setTheme(v: Int)       = setInt(KEY_THEME, v)

    fun getLanguage()          = getInt(KEY_LANGUAGE, 0)
    fun setLanguage(v: Int)    = setInt(KEY_LANGUAGE, v)

    fun getBrowserView()       = getInt(KEY_BROWSER_VIEW, 0)
    fun setBrowserView(v: Int) = setInt(KEY_BROWSER_VIEW, v)

    fun getSortMode()          = getInt(KEY_SORT_MODE, 0)
    fun setSortMode(v: Int)    = setInt(KEY_SORT_MODE, v)


    fun getResumeMode() = getInt(KEY_RESUME_MODE, 1)
    fun setResumeMode(v: Int) = setInt(KEY_RESUME_MODE, v)
    fun getAutoPlay() = getBool(KEY_AUTO_PLAY, true)
    fun setAutoPlay(v: Boolean) = setBool(KEY_AUTO_PLAY, v)
    fun getSpeedIndex() = getInt(KEY_SPEED_INDEX, 3)
    fun setSpeedIndex(v: Int) = setInt(KEY_SPEED_INDEX, v)
    fun getSeekTimeIndex() = getInt(KEY_SEEK_TIME_INDEX, 1)
    fun setSeekTimeIndex(v: Int) = setInt(KEY_SEEK_TIME_INDEX, v)
    fun getOrientationIndex() = getInt(KEY_ORIENTATION, 0)
    fun setOrientationIndex(v: Int) = setInt(KEY_ORIENTATION, v)
    fun getPip() = getBool(KEY_PIP, false)
    fun setPip(v: Boolean) = setBool(KEY_PIP, v)
    fun getGestures() = getBool(KEY_GESTURES, true)
    fun setGestures(v: Boolean) = setBool(KEY_GESTURES, v)
    fun getAudioLangIndex() = getInt(KEY_AUDIO_LANG, 0)
    fun setAudioLangIndex(v: Int) = setInt(KEY_AUDIO_LANG, v)
    fun getRememberVolume() = getBool(KEY_REMEMBER_VOLUME, false)
    fun setRememberVolume(v: Boolean) = setBool(KEY_REMEMBER_VOLUME, v)
    fun getSubtitlesDefault() = getBool(KEY_SUBTITLES_DEFAULT, true)
    fun setSubtitlesDefault(v: Boolean) = setBool(KEY_SUBTITLES_DEFAULT, v)
    fun getSubtitleLangIndex() = getInt(KEY_SUBTITLE_LANG, 0)
    fun setSubtitleLangIndex(v: Int) = setInt(KEY_SUBTITLE_LANG, v)
    fun getChromecast() = getBool(KEY_CHROMECAST, false)
    fun getShowHidden() = getBool(KEY_SHOW_HIDDEN, false)
    fun setShowHidden(v: Boolean) = setBool(KEY_SHOW_HIDDEN, v)
    fun getShowAudio() = getBool(KEY_SHOW_AUDIO, false)
    fun setShowAudio(v: Boolean) = setBool(KEY_SHOW_AUDIO, v)

    fun removeFromHistory(item: fr.retrospare.blazeplayer.data.model.MediaItem) {
        viewModelScope.launch { mediaRepository.removeRecentItem(item.id) }
    }
    fun clearAllData() = viewModelScope.launch {
        dataStore.edit { it.clear() }
        mediaRepository.clearHistory()
    }
}
