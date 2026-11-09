package com.kexpspotifysync.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kexpspotifysync.data.SyncPreferences
import com.kexpspotifysync.sync.SpotifyTrackSyncer
import com.kexpspotifysync.util.NotificationHelper

/**
 * Ultra-low-power AccessibilityService strictly scoped by the OS (`packageNames="org.kexp.android"`)
 * and event filter (`typeViewClicked|typeViewSelected`).
 *
 * Built specifically for the Flutter-based KEXP Android app (`org.kexp.android`):
 * - In KEXP's playlist history, each song row is an `android.widget.ImageView` whose
 *   `contentDescription` has the format:
 *      "Track Title\nArtist Name\n6:55 AM"  (or "Track Title\nArtist Name\nAlbum\n6:55 AM")
 *   and contains a child `android.widget.Button` (the Heart icon).
 * - In KEXP's live bottom mini-player bar, the parent `android.widget.ImageView` has
 *   `contentDescription` = "LIVE\nTrack Title\nArtist Name" and contains an `ImageView` (Heart)
 *   and a `Button` (Play/Pause).
 *
 * Supports:
 * 1. Liking ANY historical track in the KEXP playlist history (hours or days back).
 * 2. Toggling (Unliking on Spotify) if you tap the heart a second time to un-heart a song!
 * 3. Bulk-syncing existing KEXP favorites when you open the "Favorites" tab in the KEXP app.
 */
class KexpUiAccessibilityService : AccessibilityService() {

    companion object {
        private val TIME_LINE_REGEX = Regex("^\\d{1,2}:\\d{2}\\s*(AM|PM)?$", RegexOption.IGNORE_CASE)
        private val TOP_BAR_IGNORE = setOf("donate", "live", "shows", "favorites")
        private val SECTION_HEADERS = setOf(
            "recently played",
            "now playing",
            "up next",
            "featured",
            "favorites",
            "favorite songs",
            "recent plays"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != KexpMediaNotificationListener.KEXP_PACKAGE) return

        // If the user is viewing KEXP's "Favorites" tab, index visible pre-liked songs
        // so that if they un-heart a pre-liked song, we know it was already favorited!
        indexFavoritesTabIfActive()

        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SELECTED
        ) {
            return
        }

        val sourceNode = event.source ?: return
        val viewId = sourceNode.viewIdResourceName ?: ""
        val contentDesc = (sourceNode.contentDescription ?: event.contentDescription ?: "").toString()
        val className = (sourceNode.className ?: "").toString()

        val parentNode = sourceNode.parent
        val parentDesc = (parentNode?.contentDescription ?: "").toString()

        recordClickedViewInWatchdog(className, viewId, contentDesc, parentDesc)

        // 1. Check if Re-Learn Mode is active
        if (SyncPreferences.isRelearnModeActive(this)) {
            val learnedSig = viewId.ifEmpty { "$className|${parentDesc.lines().firstOrNull() ?: contentDesc}" }
            SyncPreferences.setLearnedUiViewId(this, learnedSig)
            NotificationHelper.notifySyncSuccess(
                this,
                artist = "Learned KEXP UI Heart",
                track = learnedSig,
                source = "Self-Heal Complete"
            )
        }

        // 2. Check if the user tapped a Heart button inside a KEXP Playlist Row or Mini-Player
        val parsedFromParent = parseKexpPlayNodeDescription(parentDesc)

        if (parsedFromParent != null) {
            val (track, artist, album) = parsedFromParent

            // Distinguish between the Heart button and the Play/Pause button on the LIVE mini-player bar:
            // On a Playlist Row ("Track\nArtist\n6:55 AM"), the ONLY child button is the Heart button.
            // On the LIVE mini-player ("LIVE\nTrack\nArtist"), child 0 (ImageView) is Heart, child 1 (Button) is Play/Pause.
            val isLiveMiniPlayer = parentDesc.lines().firstOrNull()?.trim().equals("LIVE", ignoreCase = true)
            val isHeartClick = if (isLiveMiniPlayer) {
                // On the LIVE mini-player bar, the Heart is the ImageView (or leftmost clickable child)
                className.contains("ImageView") || isLeftmostChild(sourceNode, parentNode)
            } else {
                // On any historical Playlist Row (or Favorites Row), the child Button is the Heart!
                className.contains("Button") || className.contains("ImageView")
            }

            if (isHeartClick) {
                // Check if the user is currently inside KEXP's "Favorites" tab:
                // Every row inside the Favorites tab is ALREADY favorited in KEXP, so tapping the Heart
                // inside the Favorites tab is ALWAYS an un-favorite action!
                if (isCurrentlyOnFavoritesScreen()) {
                    if (SyncPreferences.hasAlreadyLiked(this, artist, track)) {
                        SpotifyTrackSyncer.triggerUnlikeSync(
                            context = this,
                            rawArtist = artist,
                            rawTrack = track,
                            rawAlbum = album,
                            source = "KEXP Favorites Tab"
                        )
                    } else {
                        // Pre-liked before installing the app -> do nothing on Spotify, just clear known state
                        SyncPreferences.removeKnownKexpFavorite(this, artist, track)
                    }
                    return
                }

                SpotifyTrackSyncer.triggerToggleHeartSync(
                    context = this,
                    rawArtist = artist,
                    rawTrack = track,
                    rawAlbum = album,
                    source = if (isLiveMiniPlayer) "KEXP Live Bar" else "KEXP Playlist History"
                )
                return
            }
        }

        // 3. Fallback: check if the tapped node itself has a Favorite/Heart label
        val combinedLower = "$viewId $contentDesc".lowercase()
        if (combinedLower.contains("favorite") || combinedLower.contains("heart")) {
            val (cachedArtist, cachedTrack, cachedAlbum) = SyncPreferences.getCurrentPlayingTrack(this)
            SpotifyTrackSyncer.triggerToggleHeartSync(
                context = this,
                rawArtist = cachedArtist,
                rawTrack = cachedTrack,
                rawAlbum = cachedAlbum,
                source = "KEXP App UI"
            )
        }
    }

    /**
     * Parses KEXP's Flutter semantic contentDescription on playlist cards and the live bar:
     *
     * Format A (Historical Playlist Row):
     *   "Prove It\nTelevision\n6:55 AM"
     *   or "Prove It\nTelevision\nMarquee Moon\n6:55 AM"
     *
     * Format B (Live Mini-Player Bar):
     *   "LIVE\nHold\nMorMor"
     *
     * Returns Triple(track, artist, album?) or null if it's an Air Break or non-song node.
     */
    private fun parseKexpPlayNodeDescription(desc: String): Triple<String, String, String?>? {
        if (desc.isBlank()) return null
        val lines = desc.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (lines.size < 2) return null

        // Ignore "Air Break\n6:47 AM" or tab indicators ("Favorites\nTab 3 of 3")
        if (lines[0].equals("Air Break", ignoreCase = true)) return null
        if (lines.any { it.startsWith("Tab ", ignoreCase = true) }) return null
        if (TOP_BAR_IGNORE.contains(lines[0].lowercase()) && lines.size == 1) return null

        // Case 1: Live Mini-Player Bar ("LIVE\nTrack\nArtist")
        if (lines[0].equals("LIVE", ignoreCase = true)) {
            if (lines.size >= 3) {
                val track = lines[1]
                val artist = lines[2]
                val album = lines.getOrNull(3)
                return Triple(track, artist, album)
            }
            return null
        }

        // Strip leading section headers (e.g., "Recently Played" on the very first playlist row)
        // and trailing timestamp lines (e.g., "6:55 AM")
        val contentLines = lines
            .dropWhile { SECTION_HEADERS.contains(it.lowercase()) }
            .dropLastWhile { TIME_LINE_REGEX.matches(it) }

        if (contentLines.size < 2) return null

        val track = contentLines[0]
        val artist = contentLines[1]
        val album = contentLines.getOrNull(2)

        return Triple(track, artist, album)
    }

    private fun isLeftmostChild(child: AccessibilityNodeInfo, parent: AccessibilityNodeInfo?): Boolean {
        if (parent == null || parent.childCount <= 1) return true
        val firstChild = parent.getChild(0) ?: return true
        return firstChild == child
    }

    /**
     * Checks if the KEXP "Favorites" tab ("Favorites\nTab 3 of 3") is currently selected.
     * If so, records all visible song rows as known KEXP favorites so un-hearting a pre-existing
     * favorite won't mistakenly trigger a new Like on Spotify.
     */
    private fun isCurrentlyOnFavoritesScreen(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val allDescs = mutableListOf<String>()
            collectDescriptions(root, allDescs)
            // On the Live screen, the top header contains "LIVE" and "The Morning/Afternoon/... Show"
            // On the Favorites tab, "LIVE" header is absent or "Share Favorites" / "Favorite Songs" is present
            val hasLiveHeader = allDescs.any { it.equals("LIVE", ignoreCase = true) }
            val hasFavoritesHeader = allDescs.any {
                it.contains("Favorite Songs", ignoreCase = true) ||
                    it.contains("Share Favorites", ignoreCase = true)
            }
            hasFavoritesHeader || !hasLiveHeader
        } catch (_: Exception) {
            false
        }
    }

    private fun indexFavoritesTabIfActive() {
        val root = rootInActiveWindow ?: return
        try {
            val allDescs = mutableListOf<String>()
            collectDescriptions(root, allDescs)
            val hasLiveHeader = allDescs.any { it.equals("LIVE", ignoreCase = true) }
            if (!hasLiveHeader) {
                val pairs = allDescs.mapNotNull { desc ->
                    parseKexpPlayNodeDescription(desc)?.let { (track, artist, _) ->
                        artist to track
                    }
                }
                if (pairs.isNotEmpty()) {
                    SyncPreferences.markKnownKexpFavorites(this, pairs)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun collectDescriptions(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank()) {
            out.add(desc)
        }
        for (i in 0 until node.childCount) {
            collectDescriptions(node.getChild(i), out)
        }
    }

    private fun recordClickedViewInWatchdog(
        className: String,
        viewId: String,
        contentDesc: String,
        parentDesc: String
    ) {
        val shortClass = className.substringAfterLast('.')
        val parentSummary = parentDesc.lines().take(2).joinToString(" / ")
        val signature = buildString {
            append(shortClass)
            if (viewId.isNotEmpty()) append("#$viewId")
            if (contentDesc.isNotEmpty()) append(" desc=\"${contentDesc.lines().firstOrNull()}\"")
            if (parentSummary.isNotEmpty()) append(" in [$parentSummary]")
        }
        val currentReport = SyncPreferences.getWatchdogReport(this)
        val updatedViews = (listOf(signature) + currentReport.lastAccessibilityViewIds)
            .distinct()
            .take(8)
        SyncPreferences.saveWatchdogReport(
            this,
            currentReport.copy(
                kexpAppVersion = SyncPreferences.getInstalledKexpVersion(this),
                lastAccessibilityViewIds = updatedViews
            )
        )
    }

    override fun onInterrupt() {
        // No-op
    }
}
