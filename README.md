# KEXP → Spotify Sync (Android & Android Auto Companion App)

Automatically likes songs on your **Spotify** account the instant you tap the **Favorite / Heart** button in the **KEXP app (`org.kexp.android`)** — whether on your **Android phone screen**, in the **playlist broadcast history**, on the **system media notification**, or on your car's **Android Auto** display.

---

## Key Features

1. **Hands-Free Android Auto & MediaSession Listener (`KexpMediaNotificationListener.kt`)**
   - Observes `org.kexp.android`'s active `MediaSession` (`PlaybackState.CustomAction` and `METADATA_KEY_USER_RATING`).
   - Detects directional transitions between KEXP's `mName="Not Favorite"` (`icon=2131230894`) and `mName="Favorite"` (`icon=2131230893`) custom actions.
   - Cross-references the playing track metadata with **KEXP's official v2 Broadcast API** (`https://api.kexp.org/v2/plays/`) if stream metadata ever displays a show title or DJ airbreak.

2. **Broadcast History & Smart Toggle (`KexpUiAccessibilityService.kt`)**
   - Scroll back hours or days in KEXP's "Recently Played" playlist—tapping the heart on any row extracts that specific row's track and artist (`"Track\nArtist\nTime"`).
   - **Accidental Like / Unlike Support:** Tap a heart a second time to un-heart it and the song is automatically removed from your Spotify Liked Songs (`DELETE /v1/me/library`).
   - **Pre-Liked Song Awareness:** Songs you had already favorited in KEXP *before* installing this tool (indexed from KEXP's Favorites tab or MediaSession) won't falsely trigger a Spotify Like when you un-heart them; un-hearting clears the pre-liked state and a subsequent tap (re-liking) adds it to Spotify.

3. **Ultra-Low Battery Impact (< 0.2% / day)**
   - **Zero Polling:** No background timers, loops, or wake locks.
   - **OS-Scoped Filtering:** [`accessibility_service_config.xml`](app/src/main/res/xml/accessibility_service_config.xml) sets `android:packageNames="org.kexp.android"`, instructing Android's OS kernel never to wake this app when any other app is on screen.

4. **Proactive KEXP Update Watchdog & 1-Tap "Re-Learn" Mode**
   - Every time KEXP enters `STATE_PLAYING`, the **Watchdog** checks KEXP's `PlaybackState.CustomAction` list.
   - If a future KEXP app update renames its internal Favorite button IDs, you receive a **Watchdog Notification** and can tap **"Re-Learn KEXP Heart Button (Self-Heal)"** inside the app to lock onto the new action/view ID without rebuilding code.

5. **Historical Favorites Batch Import (`ShareImportActivity.kt`)**
   - In the KEXP app, go to **Settings → Share / Export Favorites** and select **Sync KEXP Favorites to Spotify** to backfill all your previously hearted KEXP songs.

6. **Offline Car / Tunnel Retry Queue (`PendingSyncRetryWorker.kt`)**
   - If you heart a song on Android Auto while driving through a dead zone with no cell reception, the song is saved as `PENDING_RETRY` and automatically synced via `WorkManager` as soon as connectivity returns.

---

## Setup Guide (No Coding Required)

### Step 1: Install the APK & Enable Permissions
1. Download the latest `.apk` from **Releases** and install it on your Android phone.
2. *(Android 13+)* If Android grays out the permission toggles for a browser-downloaded APK, tap **"Open App Info (Allow Restricted Settings)"** inside the app → tap **⋮** in the top-right corner → tap **Allow restricted settings**.
3. Tap **Enable** next to **Media & Android Auto Listener** and **KEXP In-App UI Detector**.

### Step 2: Connect Your Spotify Account (OAuth 2.0 PKCE)
Because Spotify limits unverified developer apps to a 25-user allowlist, each user can plug in their own free Spotify Developer Client ID in 60 seconds (no server or client secret required):
1. Inside the app, tap **"Copy Redirect URI"** (`kexpspotifysync://callback`) and **"Open Spotify Dashboard ↗"** ([developer.spotify.com/dashboard](https://developer.spotify.com/dashboard)).
2. Click **Create App**, paste `kexpspotifysync://callback` into **Redirect URIs**, check **Web API**, and save.
3. Copy your **Client ID**, paste it into the app, and tap **Connect Spotify**.

*(For local builds, you can also set `spotify.client.id=YOUR_CLIENT_ID` in your git-ignored `local.properties` to pre-fill your Client ID at compile time.)*

---

## Built with Google Antigravity (Transparency Note)

This project was built by **Doug Bright** pair-programming with **Google Antigravity**:
- **Human Responsibilities:** Product & UX definition, battery/watchdog requirements, Spotify Developer Dashboard setup (`kexpspotifysync://callback`), Wireless ADB device pairing on a Pixel 10 Pro, and live on-device QA testing.
- **Agent Responsibilities:** Architecture evaluation, Kotlin/Jetpack Compose implementation, inspecting `org.kexp.android`'s live `MediaSession` (`com.ryanheise.audioservice`) and Flutter semantics hierarchy over ADB, migrating from Spotify's deprecated `PUT /v1/me/tracks` endpoint to `PUT /v1/me/library`, and building the offline retry queue.

---

## Notes for KEXP Developers (`@KEXP`)

Everything in this repository is MIT-licensed—if the KEXP mobile team ever wants to reference or fold any part of this into `org.kexp.android`'s native roadmap, please feel free! One small accessibility finding from inspecting `org.kexp.android` on Android:
- In the Flutter playlist view, the `"Recently Played"` section heading is merged into the very first song card's semantics node (`"Recently Played\n<Track>\n<Artist>\n<Time>"`) rather than exposed as a separate `Semantics(header: true)` node, which shifts screen-reader announcements on the top row.
