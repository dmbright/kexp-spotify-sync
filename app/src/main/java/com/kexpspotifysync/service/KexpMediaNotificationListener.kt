package com.kexpspotifysync.service

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.kexpspotifysync.data.SyncPreferences
import com.kexpspotifysync.data.WatchdogReport
import com.kexpspotifysync.sync.SpotifyTrackSyncer
import com.kexpspotifysync.util.NotificationHelper

/**
 * Event-driven MediaSession observer that attaches ONLY to `org.kexp.android`.
 *
 * Battery optimization:
 * - Zero polling, zero timers, zero wake locks.
 * - Attaches a MediaController.Callback only when KEXP has an active MediaSession.
 * - Immediately detaches when KEXP's session closes.
 *
 * Self-Diagnosing Watchdog:
 * - Every time KEXP enters STATE_PLAYING, inspects KEXP's CustomActions & Metadata.
 * - If KEXP updates its app and renames or removes its Favorite custom action,
 *   fires a high-priority system notification explaining the schema change and
 *   offering 1-tap "Re-Learn Mode".
 */
class KexpMediaNotificationListener : NotificationListenerService() {

    companion object {
        const val KEXP_PACKAGE = "org.kexp.android"

        private val FAVORITE_KEYWORDS = listOf(
            "fav", "heart", "like", "thumb", "star", "save", "bookmark"
        )
    }

    private var mediaSessionManager: MediaSessionManager? = null
    private var activeKexpController: MediaController? = null

    // Snapshot of the previous CustomActions (actionId -> iconResId) so we can detect state transitions
    // when the user taps the Heart on Android Auto or the Media Notification
    private val previousActionIcons = mutableMapOf<String, Int>()
    private val previousActionNames = mutableMapOf<String, String>()
    private val previousActionExtras = mutableMapOf<String, String>()
    private var lastRatingWasRated = false
    private var watchdogAlertedForCurrentSession = false

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            evaluateControllers(controllers)
        }

    private val kexpControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handleMetadataUpdate(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            handlePlaybackStateUpdate(state)
        }

        override fun onSessionDestroyed() {
            detachFromKexpController()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            mediaSessionManager = msm
            val componentName = ComponentName(this, KexpMediaNotificationListener::class.java)
            msm.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName)
            evaluateControllers(msm.getActiveSessions(componentName))
        } catch (e: SecurityException) {
            // Notification access not yet granted
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {
        }
        detachFromKexpController()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Only inspect if it's from KEXP and we aren't already attached to KEXP's controller
        if (sbn?.packageName == KEXP_PACKAGE && activeKexpController == null) {
            refreshSessions()
        }
    }

    private fun refreshSessions() {
        try {
            val componentName = ComponentName(this, KexpMediaNotificationListener::class.java)
            val controllers = mediaSessionManager?.getActiveSessions(componentName)
            evaluateControllers(controllers)
        } catch (_: Exception) {
        }
    }

    private fun evaluateControllers(controllers: List<MediaController>?) {
        val kexpController = controllers?.firstOrNull { it.packageName == KEXP_PACKAGE }
        if (kexpController == null) {
            detachFromKexpController()
            return
        }

        if (activeKexpController?.sessionToken == kexpController.sessionToken) {
            return
        }

        detachFromKexpController()
        activeKexpController = kexpController
        watchdogAlertedForCurrentSession = false
        kexpController.registerCallback(kexpControllerCallback)

        // Seed initial state without firing false-positive heart events
        seedInitialControllerState(kexpController)
    }

    private fun detachFromKexpController() {
        try {
            activeKexpController?.unregisterCallback(kexpControllerCallback)
        } catch (_: Exception) {
        }
        activeKexpController = null
        previousActionIcons.clear()
        previousActionNames.clear()
        previousActionExtras.clear()
        lastRatingWasRated = false
    }

    private fun seedInitialControllerState(controller: MediaController) {
        val metadata = controller.metadata
        if (metadata != null) {
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            val track = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
            SyncPreferences.setCurrentPlayingTrack(this, artist, track, album)

            val rating: Rating? = metadata.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
            lastRatingWasRated = rating?.hasHeart() == true || rating?.isThumbUp == true
        }

        val state = controller.playbackState ?: return
        for (ca in state.customActions) {
            previousActionIcons[ca.action] = ca.icon
            previousActionNames[ca.action] = ca.name?.toString() ?: ""
            previousActionExtras[ca.action] = bundleToSignature(ca.extras)
            if (ca.name?.toString()?.trim().equals("Favorite", ignoreCase = true)) {
                val (a, t, _) = SyncPreferences.getCurrentPlayingTrack(this)
                if (a.isNotEmpty() && t.isNotEmpty()) {
                    SyncPreferences.markKnownKexpFavorites(this, listOf(a to t))
                }
            }
        }
        runWatchdogInspection(state)
    }

    private fun handleMetadataUpdate(metadata: MediaMetadata?) {
        if (metadata == null) return
        val prevTrack = SyncPreferences.getCurrentPlayingTrack(this)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val track = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

        val trackChanged = !artist.equals(prevTrack.first, ignoreCase = true) ||
            !track.equals(prevTrack.second, ignoreCase = true)

        SyncPreferences.setCurrentPlayingTrack(this, artist, track, album)

        // Check standard Rating metadata key (if KEXP uses METADATA_KEY_USER_RATING)
        val userRating: Rating? = metadata.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
        val isNowLiked = userRating?.hasHeart() == true || userRating?.isThumbUp == true

        if (trackChanged) {
            lastRatingWasRated = isNowLiked
            // Snapshot current custom action icons for the new track so un-hearting on track change doesn't trigger a like
            activeKexpController?.playbackState?.customActions?.forEach { ca ->
                previousActionIcons[ca.action] = ca.icon
                previousActionNames[ca.action] = ca.name?.toString() ?: ""
                previousActionExtras[ca.action] = bundleToSignature(ca.extras)
                if (ca.name?.toString()?.trim().equals("Favorite", ignoreCase = true) &&
                    artist.isNotEmpty() && track.isNotEmpty()
                ) {
                    SyncPreferences.markKnownKexpFavorites(this, listOf(artist to track))
                }
            }
        } else if (isNowLiked && !lastRatingWasRated) {
            lastRatingWasRated = true
            SpotifyTrackSyncer.triggerHeartSync(
                this,
                artist,
                track,
                album,
                source = "Android Auto / MediaSession Rating"
            )
        } else {
            lastRatingWasRated = isNowLiked
        }
    }

    private fun handlePlaybackStateUpdate(state: PlaybackState?) {
        if (state == null) return

        // Run Watchdog check whenever KEXP is actively playing
        if (state.state == PlaybackState.STATE_PLAYING) {
            runWatchdogInspection(state)
        }

        val relearnMode = SyncPreferences.isRelearnModeActive(this)
        val learnedActionId = SyncPreferences.getLearnedMediaActionId(this)
        val learnedFavIconRes = SyncPreferences.getLearnedFavoritedIconRes(this)

        for (ca in state.customActions) {
            val actionId = ca.action
            val currentIcon = ca.icon
            val currentName = ca.name?.toString() ?: ""
            val currentExtrasSig = bundleToSignature(ca.extras)

            val prevIcon = previousActionIcons[actionId]
            val prevName = previousActionNames[actionId]
            val prevExtrasSig = previousActionExtras[actionId]

            val stateChanged = (prevIcon != null && prevIcon != currentIcon) ||
                (prevName != null && prevName != currentName) ||
                (prevExtrasSig != null && prevExtrasSig != currentExtrasSig)

            // 1. If Re-Learn mode is active and the user just tapped a button that changed state:
            if (relearnMode && stateChanged) {
                SyncPreferences.setLearnedMediaActionId(this, actionId, currentIcon)
                NotificationHelper.notifySyncSuccess(
                    this,
                    artist = "Learned Action: $actionId",
                    track = "Icon $prevIcon → $currentIcon",
                    source = "Self-Heal Complete"
                )
                triggerSyncFromActiveSession("Android Auto / Re-Learned Action")
            } else if (stateChanged && isFavoriteCustomAction(actionId, currentName, learnedActionId)) {
                // 2. Normal operation: A favorite custom action transitioned state!
                // KEXP's MediaSession CustomAction uses:
                //   mName="Not Favorite" (icon=2131230894) when the track is NOT favorited
                //   mName="Favorite"     (icon=2131230893) when the track IS favorited
                val nameLower = currentName.trim().lowercase()
                val prevNameLower = (prevName ?: "").trim().lowercase()

                val transitionedToUnfavorited = nameLower == "not favorite" ||
                    nameLower.startsWith("not fav") ||
                    ca.extras?.getBoolean("is_favorite", true) == false ||
                    ca.extras?.getBoolean("favorited", true) == false

                val transitionedToFavorited = (nameLower == "favorite" && prevNameLower != "favorite") ||
                    ca.extras?.getBoolean("is_favorite", false) == true ||
                    ca.extras?.getBoolean("favorited", false) == true ||
                    (learnedFavIconRes != 0 && currentIcon == learnedFavIconRes)

                val (artist, track, album) = SyncPreferences.getCurrentPlayingTrack(this)

                when {
                    transitionedToUnfavorited -> {
                        // Only remove from Spotify if our app had previously liked it;
                        // if it was pre-liked before installing the app, do nothing!
                        if (SyncPreferences.hasAlreadyLiked(this, artist, track)) {
                            SpotifyTrackSyncer.triggerUnlikeSync(this, artist, track, album, "Android Auto / MediaSession")
                        }
                    }
                    transitionedToFavorited -> {
                        SpotifyTrackSyncer.triggerHeartSync(
                            this,
                            artist,
                            track,
                            album,
                            source = "Android Auto / MediaSession"
                        )
                    }
                    else -> {
                        SpotifyTrackSyncer.triggerToggleHeartSync(
                            this,
                            artist,
                            track,
                            album,
                            source = "Android Auto / MediaSession"
                        )
                    }
                }
            }

            previousActionIcons[actionId] = currentIcon
            previousActionNames[actionId] = currentName
            previousActionExtras[actionId] = currentExtrasSig
        }
    }

    private fun triggerSyncFromActiveSession(source: String) {
        val (artist, track, album) = SyncPreferences.getCurrentPlayingTrack(this)
        SpotifyTrackSyncer.triggerHeartSync(
            this,
            artist,
            track,
            album,
            source = source
        )
    }

    private fun isFavoriteCustomAction(
        actionId: String,
        actionName: String,
        learnedActionId: String?
    ): Boolean {
        if (!learnedActionId.isNullOrBlank() && actionId == learnedActionId) {
            return true
        }
        val combined = "$actionId $actionName".lowercase()
        return FAVORITE_KEYWORDS.any { combined.contains(it) }
    }

    /**
     * Self-Diagnosing Watchdog:
     * Inspects KEXP's live PlaybackState when audio is playing.
     * If KEXP updates its app and removes/renames the favorite action so nothing matches,
     * saves a full diagnostic dump and notifies the user right away.
     */
    private fun runWatchdogInspection(state: PlaybackState) {
        val kexpVersion = SyncPreferences.getInstalledKexpVersion(this)
        val learnedId = SyncPreferences.getLearnedMediaActionId(this)
        val actionIds = state.customActions.map { "${it.action} (icon=${it.icon})" }
        val actionNames = state.customActions.map { it.name?.toString() ?: "" }

        val matchedAction = state.customActions.firstOrNull { ca ->
            isFavoriteCustomAction(ca.action, ca.name?.toString() ?: "", learnedId)
        }

        val existingReport = SyncPreferences.getWatchdogReport(this)

        // Check if metadata supports user rating directly as an alternative
        val supportsRating = activeKexpController?.metadata
            ?.containsKey(MediaMetadata.METADATA_KEY_USER_RATING) == true

        val isHealthy = matchedAction != null || supportsRating || state.customActions.isEmpty()

        val warning = if (!isHealthy) {
            "KEXP ($kexpVersion) is playing audio with CustomActions $actionIds, " +
                "but none matched expected Favorite/Heart identifiers. " +
                "Open the app and tap 'Re-Learn Heart Button' to lock onto the updated ID."
        } else {
            null
        }

        val report = WatchdogReport(
            kexpAppVersion = kexpVersion,
            lastSessionInspectedAt = SyncPreferences.nowFormatted(),
            detectedCustomActionIds = actionIds,
            detectedCustomActionNames = actionNames,
            matchedFavoriteActionId = matchedAction?.action ?: learnedId,
            lastAccessibilityViewIds = existingReport.lastAccessibilityViewIds,
            healthStatus = if (isHealthy) "HEALTHY" else "NEEDS_ATTENTION",
            warningMessage = warning
        )
        SyncPreferences.saveWatchdogReport(this, report)

        if (!isHealthy && !watchdogAlertedForCurrentSession) {
            watchdogAlertedForCurrentSession = true
            NotificationHelper.notifyWatchdogAlert(
                this,
                "⚠️ KEXP Sync Watchdog Alert",
                warning ?: "KEXP MediaSession schema changed."
            )
        }
    }

    private fun bundleToSignature(bundle: Bundle?): String {
        if (bundle == null || bundle.isEmpty) return ""
        return try {
            bundle.keySet().sorted().joinToString("|") { key ->
                "$key=${bundle.get(key)}"
            }
        } catch (_: Exception) {
            ""
        }
    }
}
