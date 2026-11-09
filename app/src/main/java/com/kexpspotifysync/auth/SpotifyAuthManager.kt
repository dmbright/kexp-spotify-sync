package com.kexpspotifysync.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.kexpspotifysync.data.SyncPreferences
import com.kexpspotifysync.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Handles Serverless OAuth 2.0 Authorization Code Flow with PKCE for Spotify.
 * No backend server or client secret required — everything runs directly on the phone.
 */
object SpotifyAuthManager {
    const val REDIRECT_URI = "kexpspotifysync://callback"
    private const val AUTH_ENDPOINT = "https://accounts.spotify.com/authorize"
    private const val TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token"
    private const val SCOPES = "user-library-modify user-library-read playlist-modify-private playlist-modify-public"

    private val httpClient = OkHttpClient()
    private val ioScope = CoroutineScope(Dispatchers.IO)

    fun startLogin(context: Context) {
        val clientId = SyncPreferences.getSpotifyClientId(context)
        if (clientId.isBlank()) {
            NotificationHelper.notifySyncError(
                context,
                "Missing Spotify Client ID",
                "Please enter your Spotify Developer Client ID in the app first."
            )
            return
        }

        val verifier = generateCodeVerifier()
        SyncPreferences.savePkceVerifier(context, verifier)
        val challenge = generateCodeChallenge(verifier)

        val authUri = Uri.parse(AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", SCOPES)
            .build()

        val browserIntent = Intent(Intent.ACTION_VIEW, authUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(browserIntent)
    }

    fun handleCallback(context: Context, uri: Uri) {
        val error = uri.getQueryParameter("error")
        if (error != null) {
            NotificationHelper.notifySyncError(
                context,
                "Spotify Authorization Failed",
                "Spotify returned error: $error"
            )
            return
        }

        val code = uri.getQueryParameter("code") ?: return
        val verifier = SyncPreferences.getPkceVerifier(context) ?: return
        val clientId = SyncPreferences.getSpotifyClientId(context)

        ioScope.launch {
            try {
                val formBody = FormBody.Builder()
                    .add("client_id", clientId)
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", REDIRECT_URI)
                    .add("code_verifier", verifier)
                    .build()

                val request = Request.Builder()
                    .url(TOKEN_ENDPOINT)
                    .post(formBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        NotificationHelper.notifySyncError(
                            context,
                            "Spotify Token Exchange Failed",
                            "HTTP ${response.code}: $bodyStr"
                        )
                        return@use
                    }

                    val json = JSONObject(bodyStr)
                    val accessToken = json.getString("access_token")
                    val refreshToken = json.optString("refresh_token", "")
                    val expiresIn = json.optInt("expires_in", 3600)

                    SyncPreferences.saveSpotifyTokens(context, accessToken, refreshToken, expiresIn)
                }
            } catch (e: Exception) {
                NotificationHelper.notifySyncError(
                    context,
                    "Spotify Auth Error",
                    e.localizedMessage ?: "Unknown network error"
                )
            }
        }
    }

    /**
     * Returns a valid access token, automatically refreshing via PKCE refresh_token if expired.
     */
    fun getValidAccessToken(context: Context): String? {
        val currentToken = SyncPreferences.getAccessToken(context)
        if (!currentToken.isNullOrBlank() && !SyncPreferences.isTokenExpired(context)) {
            return currentToken
        }

        val refreshToken = SyncPreferences.getRefreshToken(context) ?: return null
        val clientId = SyncPreferences.getSpotifyClientId(context)
        if (clientId.isBlank()) return null

        return try {
            val formBody = FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()

            val request = Request.Builder()
                .url(TOKEN_ENDPOINT)
                .post(formBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    NotificationHelper.notifySyncError(
                        context,
                        "Spotify Session Expired",
                        "Could not refresh Spotify token (HTTP ${response.code}). Open KEXP → Spotify Sync to reconnect."
                    )
                    return null
                }
                val json = JSONObject(bodyStr)
                val newAccess = json.getString("access_token")
                val newRefresh = json.optString("refresh_token", refreshToken)
                val expiresIn = json.optInt("expires_in", 3600)
                SyncPreferences.saveSpotifyTokens(context, newAccess, newRefresh, expiresIn)
                newAccess
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }
}
