package com.kexpspotifysync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.kexpspotifysync.sync.FavoriteShareParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receives KEXP's "Share Favorites" text or CSV file via the Android Share Sheet
 * and syncs all new tracks to Spotify in the background.
 */
class ShareImportActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == Intent.ACTION_SEND) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            val streamUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

            val rawContent = when {
                !sharedText.isNullOrBlank() -> sharedText
                streamUri != null -> readUriText(streamUri)
                else -> null
            }

            if (!rawContent.isNullOrBlank()) {
                val parsed = FavoriteShareParser.parseExportText(rawContent)
                Toast.makeText(
                    this,
                    "Syncing ${parsed.size} KEXP favorites to Spotify...",
                    Toast.LENGTH_LONG
                ).show()

                val appContext = applicationContext
                scope.launch {
                    val likedCount = FavoriteShareParser.syncParsedList(appContext, parsed)
                    Toast.makeText(
                        appContext,
                        "Synced $likedCount new KEXP favorites to Spotify 💚",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Toast.makeText(this, "No KEXP favorites found in shared data", Toast.LENGTH_SHORT).show()
            }
        }

        finish()
    }

    private fun readUriText(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }
}
