package com.kexpspotifysync.sync

import android.content.Context
import com.kexpspotifysync.data.SyncPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class ParsedFavoriteTrack(
    val artist: String,
    val track: String,
    val album: String? = null
)

/**
 * Parses KEXP's native "Share / Export Favorites" text or CSV output so the user can also
 * batch-sync historical favorites (or share directly from the KEXP Settings screen).
 */
object FavoriteShareParser {

    fun parseExportText(rawText: String): List<ParsedFavoriteTrack> {
        val results = mutableListOf<ParsedFavoriteTrack>()
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }

        for (line in lines) {
            // Skip CSV header lines
            val lower = line.lowercase()
            if (lower.startsWith("song,artist") ||
                lower.startsWith("artist,song") ||
                lower.startsWith("track,artist") ||
                lower.startsWith("\"song\",\"artist\"")
            ) {
                continue
            }

            // 1. Check CSV format: "Song","Artist","Album" or Artist,Song
            if (line.contains(",")) {
                val csvCols = parseCsvLine(line)
                if (csvCols.size >= 2) {
                    val col0 = csvCols[0].trim()
                    val col1 = csvCols[1].trim()
                    val col2 = csvCols.getOrNull(2)?.trim()
                    if (col0.isNotEmpty() && col1.isNotEmpty()) {
                        results.add(ParsedFavoriteTrack(artist = col1, track = col0, album = col2))
                        continue
                    }
                }
            }

            // 2. Check "Song by Artist" format
            if (line.contains(" by ", ignoreCase = true)) {
                val parts = line.split(Regex("\\s+by\\s+", RegexOption.IGNORE_CASE), limit = 2)
                if (parts.size == 2) {
                    results.add(
                        ParsedFavoriteTrack(
                            artist = parts[1].trim(),
                            track = parts[0].trim().trim('"')
                        )
                    )
                    continue
                }
            }

            // 3. Check "Artist - Song" or "Artist — Song" format
            val dashParts = line.split(Regex("\\s+[-–—]\\s+"), limit = 3)
            if (dashParts.size >= 2) {
                results.add(
                    ParsedFavoriteTrack(
                        artist = dashParts[0].trim(),
                        track = dashParts[1].trim(),
                        album = dashParts.getOrNull(2)?.trim()
                    )
                )
            }
        }
        return results.distinctBy { "${it.artist.lowercase()}|${it.track.lowercase()}" }
    }

    suspend fun syncParsedList(
        context: Context,
        tracks: List<ParsedFavoriteTrack>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Int = withContext(Dispatchers.IO) {
        // Also record all shared tracks as known KEXP favorites so un-hearting them later works accurately
        SyncPreferences.markKnownKexpFavorites(
            context,
            tracks.map { it.artist to it.track }
        )
        var newlyLiked = 0
        tracks.forEachIndexed { index, item ->
            onProgress(index + 1, tracks.size)
            if (!SyncPreferences.hasAlreadyLiked(context, item.artist, item.track)) {
                val ok = SpotifyTrackSyncer.syncSingleTrack(
                    context = context,
                    rawArtist = item.artist,
                    rawTrack = item.track,
                    rawAlbum = item.album,
                    source = "KEXP Share Import"
                )
                if (ok) newlyLiked++
                // Polite rate limit spacing for Spotify API batch imports
                delay(250L)
            }
        }
        newlyLiked
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    result.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(ch)
            }
        }
        result.add(sb.toString())
        return result
    }
}
