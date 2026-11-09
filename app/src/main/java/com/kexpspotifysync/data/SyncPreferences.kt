package com.kexpspotifysync.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SyncedTrack(
    val artist: String,
    val track: String,
    val album: String?,
    val source: String, // "Android Auto / MediaSession", "KEXP App UI", "Share Export"
    val status: String, // "LIKED", "PENDING_RETRY", "NOT_FOUND", "ERROR"
    val spotifyUri: String?,
    val timestamp: String,
    val errorMessage: String? = null
)

data class WatchdogReport(
    val kexpAppVersion: String,
    val lastSessionInspectedAt: String,
    val detectedCustomActionIds: List<String>,
    val detectedCustomActionNames: List<String>,
    val matchedFavoriteActionId: String?,
    val lastAccessibilityViewIds: List<String>,
    val healthStatus: String, // "HEALTHY", "NEEDS_ATTENTION", "WAITING_FOR_KEXP"
    val warningMessage: String?
)

object SyncPreferences {
    private const val PREFS_NAME = "kexp_spotify_sync_prefs"
    private const val KEY_SPOTIFY_CLIENT_ID = "spotify_client_id"
    private const val KEY_SPOTIFY_ACCESS_TOKEN = "spotify_access_token"
    private const val KEY_SPOTIFY_REFRESH_TOKEN = "spotify_refresh_token"
    private const val KEY_SPOTIFY_TOKEN_EXPIRY = "spotify_token_expiry"
    private const val KEY_PKCE_VERIFIER = "pkce_verifier"

    // Configurable / Self-learning detector patterns
    private const val KEY_LEARNED_MEDIA_ACTION_ID = "learned_media_action_id"
    private const val KEY_LEARNED_MEDIA_FAVORITED_ICON_RES = "learned_media_favorited_icon_res"
    private const val KEY_LEARNED_UI_VIEW_ID = "learned_ui_view_id"
    private const val KEY_RELEARN_MODE_ACTIVE = "relearn_mode_active"

    // Current playing track cache (updated by MediaSession so UI accessibility taps also know the active track)
    private const val KEY_CURRENT_ARTIST = "current_artist"
    private const val KEY_CURRENT_TRACK = "current_track"
    private const val KEY_CURRENT_ALBUM = "current_album"

    // History & Watchdog state
    private const val KEY_SYNC_HISTORY_JSON = "sync_history_json"
    private const val KEY_PRE_LIKED_SONGS_JSON = "pre_liked_songs_json"
    private const val KEY_WATCHDOG_JSON = "watchdog_json"
    private const val KEY_NOTIFY_ON_SUCCESS = "notify_on_success"

    private val _stateUpdated = MutableStateFlow(0L)
    val stateUpdated: StateFlow<Long> = _stateUpdated.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun notifyChange() {
        _stateUpdated.value = System.currentTimeMillis()
    }

    private val DEFAULT_SPOTIFY_CLIENT_ID: String = com.kexpspotifysync.BuildConfig.DEFAULT_SPOTIFY_CLIENT_ID

    fun getSpotifyClientId(context: Context): String =
        prefs(context).getString(KEY_SPOTIFY_CLIENT_ID, DEFAULT_SPOTIFY_CLIENT_ID)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_SPOTIFY_CLIENT_ID

    fun setSpotifyClientId(context: Context, clientId: String) {
        prefs(context).edit().putString(KEY_SPOTIFY_CLIENT_ID, clientId.trim()).apply()
        notifyChange()
    }

    fun savePkceVerifier(context: Context, verifier: String) {
        prefs(context).edit().putString(KEY_PKCE_VERIFIER, verifier).apply()
    }

    fun getPkceVerifier(context: Context): String? =
        prefs(context).getString(KEY_PKCE_VERIFIER, null)

    fun saveSpotifyTokens(
        context: Context,
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Int
    ) {
        val expiryMillis = System.currentTimeMillis() + (expiresInSeconds - 60) * 1000L
        val editor = prefs(context).edit()
            .putString(KEY_SPOTIFY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_SPOTIFY_TOKEN_EXPIRY, expiryMillis)
        if (!refreshToken.isNullOrBlank()) {
            editor.putString(KEY_SPOTIFY_REFRESH_TOKEN, refreshToken)
        }
        editor.apply()
        notifyChange()
    }

    fun getAccessToken(context: Context): String? =
        prefs(context).getString(KEY_SPOTIFY_ACCESS_TOKEN, null)

    fun getRefreshToken(context: Context): String? =
        prefs(context).getString(KEY_SPOTIFY_REFRESH_TOKEN, null)

    fun isTokenExpired(context: Context): Boolean {
        val expiry = prefs(context).getLong(KEY_SPOTIFY_TOKEN_EXPIRY, 0L)
        return System.currentTimeMillis() >= expiry
    }

    fun isSpotifyConnected(context: Context): Boolean =
        !getRefreshToken(context).isNullOrBlank() || !getAccessToken(context).isNullOrBlank()

    fun disconnectSpotify(context: Context) {
        prefs(context).edit()
            .remove(KEY_SPOTIFY_ACCESS_TOKEN)
            .remove(KEY_SPOTIFY_REFRESH_TOKEN)
            .remove(KEY_SPOTIFY_TOKEN_EXPIRY)
            .apply()
        notifyChange()
    }

    // Re-learn / Self-Heal Mode
    fun isRelearnModeActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RELEARN_MODE_ACTIVE, false)

    fun setRelearnModeActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_RELEARN_MODE_ACTIVE, active).apply()
        notifyChange()
    }

    fun getLearnedMediaActionId(context: Context): String? =
        prefs(context).getString(KEY_LEARNED_MEDIA_ACTION_ID, null)

    fun setLearnedMediaActionId(context: Context, actionId: String, iconResId: Int = 0) {
        prefs(context).edit()
            .putString(KEY_LEARNED_MEDIA_ACTION_ID, actionId)
            .putInt(KEY_LEARNED_MEDIA_FAVORITED_ICON_RES, iconResId)
            .putBoolean(KEY_RELEARN_MODE_ACTIVE, false)
            .apply()
        notifyChange()
    }

    fun getLearnedFavoritedIconRes(context: Context): Int =
        prefs(context).getInt(KEY_LEARNED_MEDIA_FAVORITED_ICON_RES, 0)

    fun getLearnedUiViewId(context: Context): String? =
        prefs(context).getString(KEY_LEARNED_UI_VIEW_ID, null)

    fun setLearnedUiViewId(context: Context, viewId: String) {
        prefs(context).edit()
            .putString(KEY_LEARNED_UI_VIEW_ID, viewId)
            .putBoolean(KEY_RELEARN_MODE_ACTIVE, false)
            .apply()
        notifyChange()
    }

    fun setCurrentPlayingTrack(context: Context, artist: String?, track: String?, album: String?) {
        prefs(context).edit()
            .putString(KEY_CURRENT_ARTIST, artist ?: "")
            .putString(KEY_CURRENT_TRACK, track ?: "")
            .putString(KEY_CURRENT_ALBUM, album ?: "")
            .apply()
        notifyChange()
    }

    fun getCurrentPlayingTrack(context: Context): Triple<String, String, String> {
        val p = prefs(context)
        return Triple(
            p.getString(KEY_CURRENT_ARTIST, "") ?: "",
            p.getString(KEY_CURRENT_TRACK, "") ?: "",
            p.getString(KEY_CURRENT_ALBUM, "") ?: ""
        )
    }

    fun shouldNotifyOnSuccess(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFY_ON_SUCCESS, true)

    fun setNotifyOnSuccess(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_ON_SUCCESS, enabled).apply()
        notifyChange()
    }

    fun recordSyncedTrack(context: Context, item: SyncedTrack) {
        val current = getSyncHistory(context).toMutableList()
        // Replace existing entry if same artist+track was pending retry
        current.removeAll {
            it.artist.equals(item.artist, ignoreCase = true) &&
                it.track.equals(item.track, ignoreCase = true) &&
                it.status == "PENDING_RETRY"
        }
        current.add(0, item)
        val trimmed = current.take(150)
        val arr = JSONArray()
        for (t in trimmed) {
            arr.put(
                JSONObject().apply {
                    put("artist", t.artist)
                    put("track", t.track)
                    put("album", t.album ?: "")
                    put("source", t.source)
                    put("status", t.status)
                    put("spotifyUri", t.spotifyUri ?: "")
                    put("timestamp", t.timestamp)
                    put("errorMessage", t.errorMessage ?: "")
                }
            )
        }
        prefs(context).edit().putString(KEY_SYNC_HISTORY_JSON, arr.toString()).apply()
        notifyChange()
    }

    fun hasAlreadyLiked(context: Context, artist: String, track: String): Boolean {
        return getSyncHistory(context).firstOrNull {
            it.artist.equals(artist.trim(), ignoreCase = true) &&
                it.track.equals(track.trim(), ignoreCase = true)
        }?.status == "LIKED"
    }

    fun findLastLikedTrackUri(context: Context, artist: String, track: String): String? {
        return getSyncHistory(context).firstOrNull {
            it.status == "LIKED" &&
                it.artist.equals(artist.trim(), ignoreCase = true) &&
                it.track.equals(track.trim(), ignoreCase = true)
        }?.spotifyUri
    }

    /**
     * Tracks songs that are currently favorited inside the KEXP app (including songs favorited
     * BEFORE installing KEXP -> Spotify Sync, learned automatically when viewing the Favorites tab
     * or importing Favorites).
     */
    private fun normalizeKey(artist: String, track: String): String =
        "${artist.trim().lowercase()}|${track.trim().lowercase()}"

    fun isCurrentlyFavoritedInKexp(context: Context, artist: String, track: String): Boolean {
        val key = normalizeKey(artist, track)
        if (getPreLikedSet(context).contains(key)) return true
        return hasAlreadyLiked(context, artist, track)
    }

    fun markKnownKexpFavorites(context: Context, pairs: List<Pair<String, String>>) {
        val set = getPreLikedSet(context).toMutableSet()
        var changed = false
        for ((artist, track) in pairs) {
            val key = normalizeKey(artist, track)
            if (set.add(key)) changed = true
        }
        if (changed) {
            prefs(context).edit().putString(KEY_PRE_LIKED_SONGS_JSON, JSONArray(set.toList()).toString()).apply()
        }
    }

    fun removeKnownKexpFavorite(context: Context, artist: String, track: String) {
        val set = getPreLikedSet(context).toMutableSet()
        val key = normalizeKey(artist, track)
        if (set.remove(key)) {
            prefs(context).edit().putString(KEY_PRE_LIKED_SONGS_JSON, JSONArray(set.toList()).toString()).apply()
        }
    }

    private fun getPreLikedSet(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_PRE_LIKED_SONGS_JSON, null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun getSyncHistory(context: Context): List<SyncedTrack> {
        val raw = prefs(context).getString(KEY_SYNC_HISTORY_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<SyncedTrack>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    SyncedTrack(
                        artist = obj.optString("artist"),
                        track = obj.optString("track"),
                        album = obj.optString("album").takeIf { it.isNotEmpty() },
                        source = obj.optString("source"),
                        status = obj.optString("status"),
                        spotifyUri = obj.optString("spotifyUri").takeIf { it.isNotEmpty() },
                        timestamp = obj.optString("timestamp"),
                        errorMessage = obj.optString("errorMessage").takeIf { it.isNotEmpty() }
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveWatchdogReport(context: Context, report: WatchdogReport) {
        val obj = JSONObject().apply {
            put("kexpAppVersion", report.kexpAppVersion)
            put("lastSessionInspectedAt", report.lastSessionInspectedAt)
            put("detectedCustomActionIds", JSONArray(report.detectedCustomActionIds))
            put("detectedCustomActionNames", JSONArray(report.detectedCustomActionNames))
            put("matchedFavoriteActionId", report.matchedFavoriteActionId ?: "")
            put("lastAccessibilityViewIds", JSONArray(report.lastAccessibilityViewIds))
            put("healthStatus", report.healthStatus)
            put("warningMessage", report.warningMessage ?: "")
        }
        prefs(context).edit().putString(KEY_WATCHDOG_JSON, obj.toString()).apply()
        notifyChange()
    }

    fun getWatchdogReport(context: Context): WatchdogReport {
        val raw = prefs(context).getString(KEY_WATCHDOG_JSON, null)
            ?: return WatchdogReport(
                kexpAppVersion = getInstalledKexpVersion(context),
                lastSessionInspectedAt = "Never",
                detectedCustomActionIds = emptyList(),
                detectedCustomActionNames = emptyList(),
                matchedFavoriteActionId = getLearnedMediaActionId(context),
                lastAccessibilityViewIds = emptyList(),
                healthStatus = "WAITING_FOR_KEXP",
                warningMessage = null
            )
        return try {
            val obj = JSONObject(raw)
            val idsArr = obj.optJSONArray("detectedCustomActionIds") ?: JSONArray()
            val namesArr = obj.optJSONArray("detectedCustomActionNames") ?: JSONArray()
            val viewsArr = obj.optJSONArray("lastAccessibilityViewIds") ?: JSONArray()
            WatchdogReport(
                kexpAppVersion = obj.optString("kexpAppVersion", getInstalledKexpVersion(context)),
                lastSessionInspectedAt = obj.optString("lastSessionInspectedAt", "Never"),
                detectedCustomActionIds = (0 until idsArr.length()).map { idsArr.optString(it) },
                detectedCustomActionNames = (0 until namesArr.length()).map { namesArr.optString(it) },
                matchedFavoriteActionId = obj.optString("matchedFavoriteActionId").takeIf { it.isNotEmpty() },
                lastAccessibilityViewIds = (0 until viewsArr.length()).map { viewsArr.optString(it) },
                healthStatus = obj.optString("healthStatus", "WAITING_FOR_KEXP"),
                warningMessage = obj.optString("warningMessage").takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            WatchdogReport(
                kexpAppVersion = getInstalledKexpVersion(context),
                lastSessionInspectedAt = "Never",
                detectedCustomActionIds = emptyList(),
                detectedCustomActionNames = emptyList(),
                matchedFavoriteActionId = null,
                lastAccessibilityViewIds = emptyList(),
                healthStatus = "WAITING_FOR_KEXP",
                warningMessage = null
            )
        }
    }

    fun getInstalledKexpVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo("org.kexp.android", 0)
            "${pInfo.versionName} (${pInfo.longVersionCode})"
        } catch (e: Exception) {
            "Not Installed / Unknown"
        }
    }

    fun nowFormatted(): String =
        SimpleDateFormat("MMM d, h:mm:ss a", Locale.US).format(Date())
}
