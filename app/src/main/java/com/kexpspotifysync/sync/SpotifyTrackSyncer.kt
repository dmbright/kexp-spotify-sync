package com.kexpspotifysync.sync

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kexpspotifysync.auth.SpotifyAuthManager
import com.kexpspotifysync.data.SyncPreferences
import com.kexpspotifysync.data.SyncedTrack
import com.kexpspotifysync.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class KexpBroadcastPlay(
    val artist: String,
    val song: String,
    val album: String?
)

object SpotifyTrackSyncer {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val ioScope = CoroutineScope(Dispatchers.IO)

    /**
     * Triggers an immediate async sync on a Heart tap.
     * If artist/track are missing (e.g. tapped inside UI when MediaSession metadata wasn't populated),
     * it automatically queries KEXP's live v2 API for the currently playing broadcast song.
     */
    fun triggerHeartSync(
        context: Context,
        rawArtist: String?,
        rawTrack: String?,
        rawAlbum: String?,
        source: String
    ) {
        ioScope.launch {
            syncSingleTrack(context, rawArtist, rawTrack, rawAlbum, source)
        }
    }

    /**
     * Toggles a track's Liked state on Spotify:
     * - If the track was ALREADY liked via this app, removes it from Spotify (`DELETE /v1/me/library`).
     * - If the track was pre-liked in KEXP BEFORE installing this app (learned from KEXP's Favorites tab
     *   or MediaSession), un-hearting it in KEXP simply clears that pre-liked state and does NOTHING on Spotify;
     *   then clicking the heart a second time (re-liking) will add it to Spotify!
     * - Otherwise, likes it on Spotify (`PUT /v1/me/library`).
     */
    fun triggerToggleHeartSync(
        context: Context,
        rawArtist: String?,
        rawTrack: String?,
        rawAlbum: String?,
        source: String
    ) {
        ioScope.launch {
            val (artist, track, album) = resolveArtistAndTrack(rawArtist, rawTrack, rawAlbum)
            if (artist.isEmpty() || track.isEmpty()) return@launch

            when {
                SyncPreferences.hasAlreadyLiked(context, artist, track) -> {
                    SyncPreferences.removeKnownKexpFavorite(context, artist, track)
                    unlikeSingleTrack(context, artist, track, album, source)
                }
                SyncPreferences.isCurrentlyFavoritedInKexp(context, artist, track) -> {
                    // Pre-liked song was just un-hearted in KEXP!
                    // Clear it from known KEXP favorites and do nothing on Spotify,
                    // so the NEXT tap (re-liking) will register as a new Spotify like!
                    SyncPreferences.removeKnownKexpFavorite(context, artist, track)
                }
                else -> {
                    syncSingleTrack(context, artist, track, album, source)
                }
            }
        }
    }

    fun triggerUnlikeSync(
        context: Context,
        rawArtist: String?,
        rawTrack: String?,
        rawAlbum: String?,
        source: String
    ) {
        ioScope.launch {
            val (artist, track, album) = resolveArtistAndTrack(rawArtist, rawTrack, rawAlbum)
            if (artist.isNotEmpty() && track.isNotEmpty()) {
                unlikeSingleTrack(context, artist, track, album, source)
            }
        }
    }

    private fun resolveArtistAndTrack(
        rawArtist: String?,
        rawTrack: String?,
        rawAlbum: String?
    ): Triple<String, String, String?> {
        var artist = rawArtist?.trim() ?: ""
        var track = rawTrack?.trim() ?: ""
        var album = rawAlbum?.trim()?.takeIf { it.isNotEmpty() }

        if (artist.isEmpty() && track.contains(" - ")) {
            val parts = track.split(" - ", limit = 2)
            artist = parts[0].trim()
            track = parts[1].trim()
        }

        if (artist.isEmpty() || track.isEmpty() || looksLikeShowTitle(artist, track)) {
            val livePlay = fetchLatestLiveKexpPlay()
            if (livePlay != null) {
                artist = livePlay.artist
                track = livePlay.song
                album = livePlay.album ?: album
            }
        }
        return Triple(artist, track, album)
    }

    suspend fun unlikeSingleTrack(
        context: Context,
        artist: String,
        track: String,
        album: String?,
        source: String
    ): Boolean {
        val token = SpotifyAuthManager.getValidAccessToken(context) ?: return false

        // Find Spotify track ID from history or via search
        val cachedUri = SyncPreferences.findLastLikedTrackUri(context, artist, track)
        val trackId = cachedUri?.removePrefix("spotify:track:")
            ?: searchSpotifyTrackId(token, artist, track, album)
            ?: return false

        return try {
            val removedOk = deleteSpotifyLikedTrack(token, trackId)
            if (removedOk) {
                SyncPreferences.recordSyncedTrack(
                    context,
                    SyncedTrack(
                        artist = artist,
                        track = track,
                        album = album,
                        source = source,
                        status = "UNLIKED",
                        spotifyUri = "spotify:track:$trackId",
                        timestamp = SyncPreferences.nowFormatted()
                    )
                )
                NotificationHelper.notifyUnlikedSuccess(context, artist, track, source)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun syncSingleTrack(
        context: Context,
        rawArtist: String?,
        rawTrack: String?,
        rawAlbum: String?,
        source: String
    ): Boolean {
        var artist = rawArtist?.trim() ?: ""
        var track = rawTrack?.trim() ?: ""
        var album = rawAlbum?.trim()?.takeIf { it.isNotEmpty() }

        // Sometimes KEXP formats MediaMetadata title as "Artist - Song" or puts show name in subtitle.
        if (artist.isEmpty() && track.contains(" - ")) {
            val parts = track.split(" - ", limit = 2)
            artist = parts[0].trim()
            track = parts[1].trim()
        }

        // Fallback to KEXP Official v2 API if metadata is empty or looks like a show name ("The Morning Show")
        if (artist.isEmpty() || track.isEmpty() || looksLikeShowTitle(artist, track)) {
            val livePlay = fetchLatestLiveKexpPlay()
            if (livePlay != null) {
                artist = livePlay.artist
                track = livePlay.song
                album = livePlay.album ?: album
            }
        }

        if (artist.isEmpty() || track.isEmpty()) {
            NotificationHelper.notifySyncError(
                context,
                "Could Not Identify KEXP Track",
                "Heart tap detected ($source), but no active track was found on KEXP's stream or API."
            )
            return false
        }

        // Deduplicate if already liked
        if (SyncPreferences.hasAlreadyLiked(context, artist, track)) {
            return true
        }

        val token = SpotifyAuthManager.getValidAccessToken(context)
        if (token.isNullOrBlank()) {
            SyncPreferences.recordSyncedTrack(
                context,
                SyncedTrack(
                    artist = artist,
                    track = track,
                    album = album,
                    source = source,
                    status = "PENDING_RETRY",
                    spotifyUri = null,
                    timestamp = SyncPreferences.nowFormatted(),
                    errorMessage = "Spotify not connected or token expired"
                )
            )
            NotificationHelper.notifySyncError(
                context,
                "Spotify Not Connected",
                "Queued '$track' by $artist. Connect your Spotify account in the app to sync."
            )
            scheduleOfflineRetry(context)
            return false
        }

        return try {
            val trackId = searchSpotifyTrackId(token, artist, track, album)
            if (trackId == null) {
                SyncPreferences.recordSyncedTrack(
                    context,
                    SyncedTrack(
                        artist = artist,
                        track = track,
                        album = album,
                        source = source,
                        status = "NOT_FOUND",
                        spotifyUri = null,
                        timestamp = SyncPreferences.nowFormatted(),
                        errorMessage = "Track not found on Spotify catalog"
                    )
                )
                NotificationHelper.notifySyncError(
                    context,
                    "Song Not Found on Spotify",
                    "'$track' by $artist isn't available on Spotify (may be a KEXP exclusive/live session)."
                )
                false
            } else {
                val (likedOk, errorDetail) = putSpotifyLikedTrack(token, trackId)
                if (likedOk) {
                    SyncPreferences.recordSyncedTrack(
                        context,
                        SyncedTrack(
                            artist = artist,
                            track = track,
                            album = album,
                            source = source,
                            status = "LIKED",
                            spotifyUri = "spotify:track:$trackId",
                            timestamp = SyncPreferences.nowFormatted()
                        )
                    )
                    NotificationHelper.notifySyncSuccess(context, artist, track, source)
                    true
                } else {
                    throw IllegalStateException(errorDetail ?: "Spotify library save failed")
                }
            }
        } catch (e: Exception) {
            // Network drop in car / tunnel -> Queue for automatic WorkManager retry when back online
            SyncPreferences.recordSyncedTrack(
                context,
                SyncedTrack(
                    artist = artist,
                    track = track,
                    album = album,
                    source = source,
                    status = "PENDING_RETRY",
                    spotifyUri = null,
                    timestamp = SyncPreferences.nowFormatted(),
                    errorMessage = e.localizedMessage ?: "Network error — queued for auto-retry"
                )
            )
            scheduleOfflineRetry(context)
            false
        }
    }

    private fun looksLikeShowTitle(artist: String, track: String): Boolean {
        val combined = "$artist $track".lowercase()
        return combined.contains("kexp") &&
            (combined.contains("morning show") ||
                combined.contains("midday show") ||
                combined.contains("afternoon show") ||
                combined.contains("drive time") ||
                combined.contains("airbreak"))
    }

    /**
     * Queries KEXP's public v2 API (`https://api.kexp.org/v2/plays/?format=json&limit=5`)
     * and returns the most recent non-airbreak track play.
     */
    fun fetchLatestLiveKexpPlay(): KexpBroadcastPlay? {
        return try {
            val request = Request.Builder()
                .url("https://api.kexp.org/v2/plays/?format=json&limit=5")
                .header("User-Agent", "KexpSpotifySync/1.0 (Android)")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: return null
                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val playType = item.optString("play_type", "")
                    val artist = item.optString("artist", "").trim()
                    val song = item.optString("song", "").trim()
                    val album = item.optString("album", "").trim().takeIf { it.isNotEmpty() }
                    if (playType.equals("trackplay", ignoreCase = true) &&
                        artist.isNotEmpty() &&
                        song.isNotEmpty()
                    ) {
                        return KexpBroadcastPlay(artist = artist, song = song, album = album)
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun searchSpotifyTrackId(
        accessToken: String,
        artist: String,
        track: String,
        album: String?
    ): String? {
        val cleanTrack = cleanParentheticalNoise(track)
        val cleanArtist = cleanParentheticalNoise(artist)

        // 1st attempt: strict field query track + artist
        val strictQuery = "track:\"$cleanTrack\" artist:\"$cleanArtist\""
        executeSpotifySearch(accessToken, strictQuery)?.let { return it }

        // 2nd attempt: relaxed query without quotes
        val relaxedQuery = "$cleanTrack $cleanArtist"
        return executeSpotifySearch(accessToken, relaxedQuery)
    }

    private fun executeSpotifySearch(accessToken: String, query: String): String? {
        val url = Uri.parse("https://api.spotify.com/v1/search").buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("type", "track")
            .appendQueryParameter("limit", "1")
            .build()
            .toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val items = json.optJSONObject("tracks")?.optJSONArray("items") ?: return null
            if (items.length() == 0) return null
            return items.getJSONObject(0).optString("id").takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Uses Spotify's unified Library API (`PUT /v1/me/library?uris=spotify:track:{id}`),
     * with automatic fallback to `PUT /v1/me/tracks?ids={id}`.
     */
    private fun putSpotifyLikedTrack(accessToken: String, trackId: String): Pair<Boolean, String?> {
        val libraryUrl = Uri.parse("https://api.spotify.com/v1/me/library").buildUpon()
            .appendQueryParameter("uris", "spotify:track:$trackId")
            .build()
            .toString()

        val emptyBody = ByteArray(0).toRequestBody(null, 0, 0)

        val primaryReq = Request.Builder()
            .url(libraryUrl)
            .header("Authorization", "Bearer $accessToken")
            .put(emptyBody)
            .build()

        httpClient.newCall(primaryReq).execute().use { response ->
            if (response.isSuccessful) {
                return true to null
            }
            val errBody = response.body?.string()?.take(180) ?: ""

            // Fallback: tryPUT /v1/me/tracks?ids={trackId}
            val fallbackUrl = Uri.parse("https://api.spotify.com/v1/me/tracks").buildUpon()
                .appendQueryParameter("ids", trackId)
                .build()
                .toString()
            val jsonBody = JSONObject().apply {
                put("ids", JSONArray().put(trackId))
            }.toString().toRequestBody("application/json".toMediaType())

            val fallbackReq = Request.Builder()
                .url(fallbackUrl)
                .header("Authorization", "Bearer $accessToken")
                .put(jsonBody)
                .build()

            httpClient.newCall(fallbackReq).execute().use { fbResp ->
                if (fbResp.isSuccessful) {
                    return true to null
                }
                return false to "HTTP ${response.code} on /v1/me/library ($errBody)"
            }
        }
    }

    /**
     * Removes a track from the user's Spotify Liked Songs (`DELETE /v1/me/library?uris=spotify:track:{id}`).
     */
    private fun deleteSpotifyLikedTrack(accessToken: String, trackId: String): Boolean {
        val libraryUrl = Uri.parse("https://api.spotify.com/v1/me/library").buildUpon()
            .appendQueryParameter("uris", "spotify:track:$trackId")
            .build()
            .toString()

        val primaryReq = Request.Builder()
            .url(libraryUrl)
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()

        httpClient.newCall(primaryReq).execute().use { response ->
            if (response.isSuccessful) return true

            val fallbackUrl = Uri.parse("https://api.spotify.com/v1/me/tracks").buildUpon()
                .appendQueryParameter("ids", trackId)
                .build()
                .toString()
            val fallbackReq = Request.Builder()
                .url(fallbackUrl)
                .header("Authorization", "Bearer $accessToken")
                .delete()
                .build()
            httpClient.newCall(fallbackReq).execute().use { fbResp ->
                return fbResp.isSuccessful
            }
        }
    }

    private fun cleanParentheticalNoise(input: String): String {
        return input
            .replace(Regex("\\s*\\(feat\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\[feat\\..*?]", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    fun scheduleOfflineRetry(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<PendingSyncRetryWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "kexp_spotify_pending_retry",
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }
}

class PendingSyncRetryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val pending = SyncPreferences.getSyncHistory(applicationContext)
            .filter { it.status == "PENDING_RETRY" }
        if (pending.isEmpty()) return Result.success()

        var allSucceeded = true
        for (item in pending) {
            val ok = SpotifyTrackSyncer.syncSingleTrack(
                applicationContext,
                item.artist,
                item.track,
                item.album,
                "${item.source} (Retry)"
            )
            if (!ok) allSucceeded = false
        }
        return if (allSucceeded) Result.success() else Result.retry()
    }
}
