package com.kexpspotifysync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kexpspotifysync.auth.SpotifyAuthManager
import com.kexpspotifysync.data.SyncPreferences
import com.kexpspotifysync.data.SyncedTrack
import com.kexpspotifysync.data.WatchdogReport
import com.kexpspotifysync.sync.SpotifyTrackSyncer
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF1DB954), // Spotify Green
                    secondary = Color(0xFFFF9800), // KEXP Orange
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "kexpspotifysync" && data.host == "callback") {
            SpotifyAuthManager.handleCallback(this, data)
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stateTick by SyncPreferences.stateUpdated.collectAsState()

    var clientId by remember { mutableStateOf(SyncPreferences.getSpotifyClientId(context)) }
    var isNotificationEnabled by remember { mutableStateOf(checkNotificationListenerEnabled(context)) }
    var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityEnabled(context)) }
    var isSpotifyConnected by remember { mutableStateOf(SyncPreferences.isSpotifyConnected(context)) }
    var relearnMode by remember { mutableStateOf(SyncPreferences.isRelearnModeActive(context)) }
    var watchdogReport by remember { mutableStateOf(SyncPreferences.getWatchdogReport(context)) }
    var history by remember { mutableStateOf(SyncPreferences.getSyncHistory(context)) }
    var currentTrack by remember { mutableStateOf(SyncPreferences.getCurrentPlayingTrack(context)) }

    // Refresh permissions & state when returning from Android Settings or state updates
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, stateTick) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isNotificationEnabled = checkNotificationListenerEnabled(context)
                isAccessibilityEnabled = checkAccessibilityEnabled(context)
                isSpotifyConnected = SyncPreferences.isSpotifyConnected(context)
                relearnMode = SyncPreferences.isRelearnModeActive(context)
                watchdogReport = SyncPreferences.getWatchdogReport(context)
                history = SyncPreferences.getSyncHistory(context)
                currentTrack = SyncPreferences.getCurrentPlayingTrack(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // Also immediately refresh when stateTick changes
        isSpotifyConnected = SyncPreferences.isSpotifyConnected(context)
        relearnMode = SyncPreferences.isRelearnModeActive(context)
        watchdogReport = SyncPreferences.getWatchdogReport(context)
        history = SyncPreferences.getSyncHistory(context)
        currentTrack = SyncPreferences.getCurrentPlayingTrack(context)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "KEXP → Spotify Sync",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Automatically likes songs on Spotify when you tap the Heart in the KEXP app or on Android Auto.",
                fontSize = 14.sp,
                color = Color(0xFFB3B3B3)
            )
        }

        // 1. Permissions Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1. Android Permissions (Zero-Polling)", fontWeight = FontWeight.Bold, color = Color.White)

                    PermissionRow(
                        title = "Media & Android Auto Listener",
                        subtitle = if (isNotificationEnabled) "Active — watching KEXP MediaSession" else "Required for Android Auto & Notification hearts",
                        enabled = isNotificationEnabled,
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    )

                    PermissionRow(
                        title = "KEXP In-App UI Detector",
                        subtitle = if (isAccessibilityEnabled) "Active — scoped strictly to org.kexp.android" else "Optional: catches hearts inside KEXP playlist view",
                        enabled = isAccessibilityEnabled,
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    )

                    if (!isNotificationEnabled || !isAccessibilityEnabled) {
                        Divider(color = Color(0xFF333333))
                        Text(
                            text = "💡 Android 13+ Tip: If a toggle is grayed out ('Restricted setting'), tap below to open App Info → tap ⋮ in the top-right corner → tap 'Allow restricted settings'.",
                            fontSize = 12.sp,
                            color = Color(0xFFFFCC80)
                        )
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open App Info (Allow Restricted Settings)", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 2. Spotify OAuth PKCE Connection Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("2. Spotify Account (PKCE)", fontWeight = FontWeight.Bold, color = Color.White)
                        StatusBadge(
                            text = if (isSpotifyConnected) "CONNECTED" else "NOT CONNECTED",
                            color = if (isSpotifyConnected) Color(0xFF1DB954) else Color(0xFFE53935)
                        )
                    }

                    OutlinedTextField(
                        value = clientId,
                        onValueChange = {
                            clientId = it
                            SyncPreferences.setSpotifyClientId(context, it)
                        },
                        label = { Text("Spotify Developer Client ID") },
                        placeholder = { Text("e.g. 4a1b2c3d4e5f...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "Step 1: Create a free app at developer.spotify.com/dashboard with Redirect URI:\nkexpspotifysync://callback",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFB3B3B3)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Spotify Redirect URI", SpotifyAuthManager.REDIRECT_URI))
                                Toast.makeText(context, "Copied kexpspotifysync://callback", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Copy Redirect URI", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.spotify.com/dashboard"))
                                )
                            }
                        ) {
                            Text("Open Spotify Dashboard ↗", fontSize = 12.sp)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { SpotifyAuthManager.startLogin(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954))
                        ) {
                            Text(if (isSpotifyConnected) "Reconnect Spotify" else "Connect Spotify", color = Color.Black)
                        }

                        if (isSpotifyConnected) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        SpotifyTrackSyncer.triggerHeartSync(
                                            context = context,
                                            rawArtist = currentTrack.first,
                                            rawTrack = currentTrack.second,
                                            rawAlbum = currentTrack.third,
                                            source = "Manual Live KEXP Test"
                                        )
                                    }
                                }
                            ) {
                                Text("Like Current KEXP Song Now")
                            }
                        }
                    }
                }
            }
        }

        // 3. Watchdog & Self-Heal Inspector Card
        item {
            WatchdogCard(
                report = watchdogReport,
                relearnMode = relearnMode,
                learnedMediaId = SyncPreferences.getLearnedMediaActionId(context),
                learnedUiId = SyncPreferences.getLearnedUiViewId(context),
                onToggleRelearn = {
                    val next = !relearnMode
                    SyncPreferences.setRelearnModeActive(context, next)
                    relearnMode = next
                }
            )
        }

        // 4. Sync History & Retry Queue
        item {
            Text(
                text = "Recent Synced Songs (${history.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White
            )
        }

        if (history.isEmpty()) {
            item {
                Text(
                    text = "No songs synced yet. Tap a heart in KEXP or Android Auto (or use 'Share Favorites' in KEXP Settings)!",
                    fontSize = 13.sp,
                    color = Color(0xFF888888)
                )
            }
        } else {
            items(history) { item ->
                HistoryTrackRow(item)
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(subtitle, color = Color(0xFFB3B3B3), fontSize = 12.sp)
        }
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (enabled) Color(0xFF2E7D32) else Color(0xFFFF9800)
            )
        ) {
            Text(if (enabled) "Granted ✓" else "Enable", color = Color.White, fontSize = 12.sp)
        }
    }
}

@Composable
private fun WatchdogCard(
    report: WatchdogReport,
    relearnMode: Boolean,
    learnedMediaId: String?,
    learnedUiId: String?,
    onToggleRelearn: () -> Unit
) {
    val badgeColor = when (report.healthStatus) {
        "HEALTHY" -> Color(0xFF1DB954)
        "NEEDS_ATTENTION" -> Color(0xFFE53935)
        else -> Color(0xFFFF9800)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("3. KEXP Update Watchdog & Diagnostics", fontWeight = FontWeight.Bold, color = Color.White)
                StatusBadge(text = report.healthStatus, color = badgeColor)
            }

            if (!report.warningMessage.isNullOrBlank()) {
                Text(
                    text = "⚠️ ${report.warningMessage}",
                    color = Color(0xFFFF8A80),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = buildString {
                    appendLine("Installed KEXP Version: ${report.kexpAppVersion}")
                    appendLine("Last MediaSession Check: ${report.lastSessionInspectedAt}")
                    appendLine("Detected CustomActions: ${report.detectedCustomActionIds.ifEmpty { listOf("(play KEXP to inspect)") }}")
                    appendLine("Matched/Learned Media Action: ${learnedMediaId ?: report.matchedFavoriteActionId ?: "(auto-detect)"}")
                    appendLine("Learned UI View ID: ${learnedUiId ?: "(auto-detect)"}")
                    if (report.lastAccessibilityViewIds.isNotEmpty()) {
                        append("Recent Tapped KEXP Views: ${report.lastAccessibilityViewIds.take(3)}")
                    }
                },
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFB3B3B3)
            )

            Button(
                onClick = onToggleRelearn,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (relearnMode) Color(0xFFE53935) else Color(0xFF37474F)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (relearnMode) {
                        "Listening... Go tap the Heart in KEXP or Android Auto now (Tap to Cancel)"
                    } else {
                        "Re-Learn KEXP Heart Button (Self-Heal)"
                    },
                    color = Color.White,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun HistoryTrackRow(item: SyncedTrack) {
    val context = LocalContext.current
    val statusColor = when (item.status) {
        "LIKED" -> Color(0xFF1DB954)
        "UNLIKED" -> Color(0xFF78909C)
        "PENDING_RETRY" -> Color(0xFFFFB300)
        else -> Color(0xFFE53935)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF181818)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${item.track} — ${item.artist}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("${item.source} • ${item.timestamp}", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                if (!item.errorMessage.isNullOrBlank()) {
                    Text(item.errorMessage, color = Color(0xFFFF8A80), fontSize = 11.sp)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(text = item.status, color = statusColor)
                if (item.status == "LIKED") {
                    TextButton(
                        onClick = {
                            SpotifyTrackSyncer.triggerUnlikeSync(
                                context,
                                item.artist,
                                item.track,
                                item.album,
                                "Manual Unlike"
                            )
                        }
                    ) {
                        Text("Unlike", color = Color(0xFFFF8A80), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

private fun checkNotificationListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    return flat.contains(context.packageName)
}

private fun checkAccessibilityEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return flat.contains(context.packageName)
}
