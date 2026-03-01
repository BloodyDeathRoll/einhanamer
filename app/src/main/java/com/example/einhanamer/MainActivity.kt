package com.example.einhanamer

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.einhanamer.data.BlockList
import com.example.einhanamer.data.EventLog
import com.example.einhanamer.data.CameraAccessEvent
import com.example.einhanamer.data.LocationAccessEvent
import com.example.einhanamer.data.MicAccessEvent
import com.example.einhanamer.data.TrafficEvent
import com.example.einhanamer.monitor.MicMonitorService
import com.example.einhanamer.ui.theme.EinHanamerTheme
import com.example.einhanamer.vpn.LocalVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// ── IP display resolution ───────────────────────────────────────────────────────

private val ipv4Regex = Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""")
private val ipv6Regex = Regex("""[0-9a-fA-F]{0,4}(:[0-9a-fA-F]{0,4}){2,7}""")
private fun String.isIpAddress() = matches(ipv4Regex) || matches(ipv6Regex)

/** Well-known individual IPs (v4 + v6) → service name (checked before any DNS lookup). */
private val knownIpServices = mapOf(
    // IPv4
    "1.1.1.1"         to "Cloudflare DNS",
    "1.0.0.1"         to "Cloudflare DNS",
    "8.8.8.8"         to "Google DNS",
    "8.8.4.4"         to "Google DNS",
    "9.9.9.9"         to "Quad9 DNS",
    "149.112.112.112" to "Quad9 DNS",
    "208.67.222.222"  to "OpenDNS",
    "208.67.220.220"  to "OpenDNS",
    // IPv6
    "2606:4700:4700::1111" to "Cloudflare DNS",
    "2606:4700:4700::1001" to "Cloudflare DNS",
    "2001:4860:4860::8888" to "Google DNS",
    "2001:4860:4860::8844" to "Google DNS",
    "2620:fe::fe"          to "Quad9 DNS",
    "2620:fe::9"           to "Quad9 DNS",
    "2620:119:35::35"      to "OpenDNS",
    "2620:119:53::53"      to "OpenDNS",
)

/**
 * Registrable-domain → service label.
 * After a PTR lookup we extract the last two hostname components
 * (e.g. "edge-star.facebook.com" → "facebook.com") and look up here.
 */
private val knownDomainServices = mapOf(
    "facebook.com"           to "Meta",
    "fbcdn.net"              to "Meta CDN",
    "fbsbx.com"              to "Meta",
    "instagram.com"          to "Meta / Instagram",
    "cdninstagram.com"       to "Meta / Instagram CDN",
    "whatsapp.com"           to "Meta / WhatsApp",
    "whatsapp.net"           to "Meta / WhatsApp",
    "google.com"             to "Google",
    "googleapis.com"         to "Google APIs",
    "googlevideo.com"        to "YouTube / Google",
    "youtube.com"            to "YouTube",
    "ytimg.com"              to "YouTube CDN",
    "gstatic.com"            to "Google Static",
    "googletagmanager.com"   to "Google Tag Manager",
    "doubleclick.net"        to "Google Ads",
    "ggpht.com"              to "Google",
    "amazonaws.com"          to "Amazon AWS",
    "cloudfront.net"         to "Amazon CloudFront",
    "apple.com"              to "Apple",
    "icloud.com"             to "Apple iCloud",
    "apple-relay.com"        to "Apple Private Relay",
    "microsoft.com"          to "Microsoft",
    "live.com"               to "Microsoft",
    "azure.com"              to "Microsoft Azure",
    "akamaiedge.net"         to "Akamai CDN",
    "akamaihd.net"           to "Akamai CDN",
    "akamaitechnologies.com" to "Akamai",
    "fastly.net"             to "Fastly CDN",
    "cloudflare.com"         to "Cloudflare",
    "snap.com"               to "Snapchat",
    "snapchat.com"           to "Snapchat",
    "sc-cdn.net"             to "Snapchat CDN",
    "tiktok.com"             to "TikTok",
    "byteoversea.com"        to "TikTok / ByteDance",
    "tiktokcdn.com"          to "TikTok CDN",
    "twitter.com"            to "Twitter / X",
    "twimg.com"              to "Twitter / X CDN",
    "x.com"                  to "Twitter / X",
    "spotify.com"            to "Spotify",
    "scdn.co"                to "Spotify CDN",
    "spotifycdn.com"         to "Spotify CDN",
    "netflix.com"            to "Netflix",
    "nflxvideo.net"          to "Netflix CDN",
    "nflximg.net"            to "Netflix",
)

/**
 * Domains and service-name labels known to be ad networks, analytics, or tracking SDKs.
 * Checked against the label part of resolveIpDisplay() output (after the ", ").
 */
private val knownTrackerDomains = setOf(
    // Google ad / analytics stack
    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
    "googletagmanager.com", "google-analytics.com", "app-measurement.com",
    // Mobile attribution & analytics
    "appsflyer.com", "adjust.com", "adjust.io", "branch.io",
    "mixpanel.com", "amplitude.com", "segment.com", "segment.io",
    "moengage.com", "clevertap.com", "singular.net", "kochava.com",
    "braze.com", "leanplum.com", "localytics.com",
    // Ad exchanges / DSPs
    "criteo.com", "adnxs.com", "rubiconproject.com", "pubmatic.com",
    "casalemedia.com", "openx.com", "taboola.com", "outbrain.com",
    // Fingerprinting / measurement
    "scorecardresearch.com", "comscore.com", "moatads.com", "adsymptotic.com",
    // Facebook off-site tracking
    "facebook.net",
)
// Service labels (the friendly name after the comma in resolveIpDisplay output)
// that unambiguously indicate tracking, even when the raw domain isn't exposed.
private val knownTrackerServiceLabels = setOf(
    "Google Ads", "Google Tag Manager", "Google Analytics",
)

private fun isTrackerDisplay(display: String): Boolean {
    if (!display.contains(",")) return false
    val label = display.substringAfter(", ").trim()
    return label in knownTrackerServiceLabels || label in knownTrackerDomains
}

private val ipDisplayCache = ConcurrentHashMap<String, String>()

/**
 * Returns a human-readable string for [ip]:
 *   "1.1.1.1, Cloudflare DNS"    — curated exact match
 *   "157.240.241.35, Meta"        — PTR → registrable domain → curated domain match
 *   "52.123.45.67, amazonaws.com" — PTR → registrable domain (unknown service)
 *   "203.0.113.9"                 — no PTR / unrecognised
 */
private suspend fun resolveIpDisplay(ip: String): String {
    knownIpServices[ip]?.let { return "$ip, $it" }
    ipDisplayCache[ip]?.let { return it }
    val result = withContext(Dispatchers.IO) {
        try {
            val host = InetAddress.getByName(ip).canonicalHostName
            if (host == ip) {
                ip                          // no PTR record
            } else {
                val parts  = host.split(".")
                val domain = if (parts.size >= 2)
                    "${parts[parts.size - 2]}.${parts.last()}" else host
                val service = knownDomainServices[domain]
                if (service != null) "$ip, $service" else "$ip, $domain"
            }
        } catch (_: Exception) { ip }
    }
    ipDisplayCache[ip] = result
    return result
}

// ── Domain models ──────────────────────────────────────────────────────────────

private data class RawEvent(
    val timestampMs: Long,
    val detail: String,   // destination IP for network; "Recording started/stopped" for mic
    val bytes: Long,
)

private data class AppEntry(
    val packageName: String?,
    val uid: Int,
    val appName: String,
    val deviceId: String,        // "microphone" | "net_443" etc.
    val subtitle: String,        // "Microphone" | "HTTPS" | "Port 8080" etc.
    val eventCount: Int,
    val totalBytes: Long,
    val events: List<RawEvent>,
    val isMicAllowed: Boolean?,  // null = not a mic activity
)

private data class AppGroup(
    val packageName: String?,
    val uid: Int,
    val appName: String,
    val totalEventCount: Int,
    val totalBytes: Long,
    val isMicAllowed: Boolean?,
    val activities: List<AppEntry>,
    val hasBackgroundActivity: Boolean,  // true if any packet arrived while app was not in foreground
)

private data class SensorAppEntry(
    val packageName: String?,
    val appName: String,
    val uid: Int,
    val eventCount: Int,
    val lastEventMs: Long,
    val isCurrentlyActive: Boolean,
)

private data class SensorGroup(
    val sensorId: String,          // "microphone" | "camera" | "location"
    val displayName: String,
    val icon: ImageVector,
    val isCurrentlyActive: Boolean,
    val totalEventCount: Int,
    val apps: List<SensorAppEntry>,
)

/** One row in the destination-grouped view inside AppDetailLevel. */
private data class DestGroup(
    val ip:     String,
    val count:  Int,
    val bytes:  Long,
    val lastMs: Long,
)

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun knownPort(port: Int): String? = when (port) {
    53   -> "DNS"
    80   -> "HTTP"
    443  -> "HTTPS"
    1194 -> "OpenVPN"
    1935 -> "RTMP"
    3478 -> "STUN / TURN"
    4244 -> "MQTT"
    5222 -> "XMPP"
    5228 -> "FCM"
    8883 -> "MQTT TLS"
    else -> null
}


private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
    bytes >= 1_024     -> "${"%.1f".format(bytes / 1_024.0)} KB"
    else               -> "$bytes B"
}

private fun Context.resolveAppName(packageName: String?): String {
    if (packageName == null) return "Unknown"
    return try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName.substringAfterLast('.')
    }
}

private fun Context.getMicPermissionAllowed(packageName: String, uid: Int): Boolean = try {
    val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    appOps.checkOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, uid, packageName) ==
        AppOpsManager.MODE_ALLOWED
} catch (_: Exception) { true }

// ── App group builder ───────────────────────────────────────────────────────────

private fun buildAppGroups(
    trafficEvents: List<TrafficEvent>,
    micEvents: List<MicAccessEvent>,
    context: Context,
): List<AppGroup> {
    val activeMicPkgs = micEvents.filter { it.isActive }.map { it.packageName }.toSet()
    val allPkgs = (trafficEvents.map { it.packageName } + micEvents.map { it.packageName })
        .filterNotNull()
        .distinct()

    return allPkgs.map { pkg ->
        val pkgTraffic = trafficEvents.filter { it.packageName == pkg }
        val pkgMic     = micEvents.filter { it.packageName == pkg }
        val uid        = pkgTraffic.firstOrNull()?.uid ?: pkgMic.firstOrNull()?.uid ?: 0
        val appName    = context.resolveAppName(pkg)

        val activities = mutableListOf<AppEntry>()

        pkgTraffic.groupBy { it.dstPort }.forEach { (port, portEvents) ->
            activities.add(AppEntry(
                packageName  = pkg,
                uid          = uid,
                appName      = appName,
                deviceId     = "net_$port",
                subtitle     = knownPort(port) ?: "Port $port",
                eventCount   = portEvents.size,
                totalBytes   = portEvents.sumOf { it.byteCount.toLong() },
                events       = portEvents
                    .map { ev -> RawEvent(ev.timestampMs, ev.dstIp, ev.byteCount.toLong()) }
                    .sortedByDescending { it.timestampMs },
                isMicAllowed = null,
            ))
        }

        if (pkgMic.isNotEmpty()) {
            val micUid = pkgMic.first().uid
            activities.add(AppEntry(
                packageName  = pkg,
                uid          = micUid,
                appName      = appName,
                deviceId     = "microphone",
                subtitle     = "Microphone",
                eventCount   = pkgMic.size,
                totalBytes   = 0L,
                events       = pkgMic
                    .map { ev -> RawEvent(ev.timestampMs, if (ev.isActive) "Recording started" else "Recording stopped", 0L) }
                    .sortedByDescending { it.timestampMs },
                isMicAllowed = context.getMicPermissionAllowed(pkg, micUid),
            ))
        }

        AppGroup(
            packageName           = pkg,
            uid                   = uid,
            appName               = appName,
            totalEventCount       = pkgTraffic.size + pkgMic.size,
            totalBytes            = pkgTraffic.sumOf { it.byteCount.toLong() },
            isMicAllowed          = pkgMic.takeIf { it.isNotEmpty() }
                ?.let { context.getMicPermissionAllowed(pkg, uid) },
            activities            = activities.sortedByDescending { it.eventCount },
            hasBackgroundActivity = pkgTraffic.any { it.isBackground },
        )
    }.sortedWith(
        compareByDescending<AppGroup> { activeMicPkgs.contains(it.packageName) }
            .thenByDescending { it.totalEventCount }
    )
}

// ── Sensor group builder ────────────────────────────────────────────────────────

private fun buildSensorGroups(
    micEvents:      List<MicAccessEvent>,
    cameraEvents:   List<CameraAccessEvent>,
    locationEvents: List<LocationAccessEvent>,
    context:        Context,
): List<SensorGroup> {

    fun buildGroup(
        sensorId:    String,
        displayName: String,
        icon:        ImageVector,
        pkgs:        List<String?>,
        uids:        List<Int>,
        active:      List<Boolean>,
        timestamps:  List<Long>,
    ): SensorGroup {
        val isActive = active.any { it }
        val byApp = pkgs.indices.groupBy { pkgs[it] }
        val apps = byApp.map { (pkg, indices) ->
            SensorAppEntry(
                packageName       = pkg,
                appName           = context.resolveAppName(pkg),
                uid               = uids[indices.first()],
                eventCount        = indices.size,
                lastEventMs       = indices.maxOf { timestamps[it] },
                isCurrentlyActive = indices.any { active[it] },
            )
        }.sortedWith(
            compareByDescending<SensorAppEntry> { it.isCurrentlyActive }
                .thenByDescending { it.eventCount }
        )
        return SensorGroup(
            sensorId          = sensorId,
            displayName       = displayName,
            icon              = icon,
            isCurrentlyActive = isActive,
            totalEventCount   = pkgs.size,
            apps              = apps,
        )
    }

    return listOf(
        buildGroup("microphone", "Microphone", Icons.Outlined.Mic,
            micEvents.map { it.packageName },
            micEvents.map { it.uid },
            micEvents.map { it.isActive },
            micEvents.map { it.timestampMs },
        ),
        buildGroup("camera", "Camera", Icons.Outlined.Camera,
            cameraEvents.map { it.packageName },
            cameraEvents.map { it.uid },
            cameraEvents.map { it.isActive },
            cameraEvents.map { it.timestampMs },
        ),
        buildGroup("location", "Location", Icons.Outlined.LocationOn,
            locationEvents.map { it.packageName },
            locationEvents.map { it.uid },
            locationEvents.map { it.isActive },
            locationEvents.map { it.timestampMs },
        ),
    )
}

// ── Activity ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, MicMonitorService::class.java))
        enableEdgeToEdge()
        setContent { EinHanamerTheme { MonitorScreen() } }
    }
}

// ── Root screen ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen() {
    val context = LocalContext.current

    var startVpnPending by remember { mutableStateOf(false) }
    val vpnPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == ComponentActivity.RESULT_OK) context.startVpnService()
        startVpnPending = false
    }

    val vpnConnected by LocalVpnService.isRunning.collectAsState()

    val trafficEvents by EventLog.traffic
        .runningFold(EventLog.recentTraffic()) { acc, ev -> (listOf(ev) + acc).take(500) }
        .collectAsState(initial = EventLog.recentTraffic())

    val micEvents by EventLog.mic
        .runningFold(EventLog.recentMic()) { acc, ev -> (listOf(ev) + acc).take(100) }
        .collectAsState(initial = EventLog.recentMic())

    val blockedApps   by BlockList.blocked.collectAsState()
    val blockedStreams by BlockList.blockedStreams.collectAsState()

    val allApps = remember(trafficEvents, micEvents, blockedApps, blockedStreams) {
        buildAppGroups(trafficEvents, micEvents, context).sortedWith(
            // Apps with any blocked stream/app first (A–Z), then the rest (A–Z)
            compareByDescending<AppGroup> { group ->
                val appBlocked = group.packageName != null && group.packageName in blockedApps
                val streamBlocked = group.activities.any { activity ->
                    val p = if (activity.deviceId.startsWith("net_"))
                        activity.deviceId.removePrefix("net_").toIntOrNull() else null
                    p != null && activity.packageName != null &&
                        "${activity.packageName}:$p" in blockedStreams
                }
                appBlocked || streamBlocked
            }.thenBy { it.appName }
        )
    }

    var selectedApp    by remember { mutableStateOf<AppGroup?>(null) }
    var stealthOnly    by remember { mutableStateOf(false) }
    val displayApps    = if (stealthOnly) allApps.filter { it.hasBackgroundActivity } else allApps

    var connectedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(vpnConnected) {
        if (vpnConnected) {
            connectedSeconds = 0L
            while (true) { delay(1000L); connectedSeconds++ }
        } else {
            connectedSeconds = 0L
        }
    }
    val timerStr = "%02d:%02d:%02d".format(
        connectedSeconds / 3600, (connectedSeconds % 3600) / 60, connectedSeconds % 60
    )

    val networkType = remember { context.getNetworkType() }

    val cameraEvents by EventLog.camera
        .runningFold(EventLog.recentCamera()) { acc, ev -> (listOf(ev) + acc).take(100) }
        .collectAsState(initial = EventLog.recentCamera())

    val locationEvents by EventLog.location
        .runningFold(EventLog.recentLocation()) { acc, ev -> (listOf(ev) + acc).take(100) }
        .collectAsState(initial = EventLog.recentLocation())

    val sensorGroups = remember(micEvents, cameraEvents, locationEvents) {
        buildSensorGroups(micEvents, cameraEvents, locationEvents, context)
    }

    var selectedSensor by remember { mutableStateOf<SensorGroup?>(null) }

    var blockTrackers by remember { mutableStateOf(false) }
    var trackerBlockedStreams by remember { mutableStateOf<Set<String>>(emptySet()) }
    var trackerStreamCount by remember { mutableStateOf(0) }
    LaunchedEffect(allApps) {
        var count = 0
        allApps.forEach { group ->
            group.activities.forEach { activity ->
                val port = if (activity.deviceId.startsWith("net_"))
                    activity.deviceId.removePrefix("net_").toIntOrNull() else null
                if (port != null && activity.packageName != null) {
                    val isTracker = activity.events
                        .map { it.detail }
                        .filter { it.isIpAddress() }
                        .any { ip ->
                            val display = ipDisplayCache[ip] ?: resolveIpDisplay(ip)
                            isTrackerDisplay(display)
                        }
                    if (isTracker) count++
                }
            }
        }
        trackerStreamCount = count
    }
    LaunchedEffect(blockTrackers, allApps) {
        if (!blockTrackers) {
            trackerBlockedStreams.forEach { key ->
                val pkg  = key.substringBeforeLast(":")
                val port = key.substringAfterLast(":").toIntOrNull() ?: return@forEach
                BlockList.unblockStream(pkg, port)
            }
            trackerBlockedStreams = emptySet()
        } else {
            val newTrackerStreams = mutableSetOf<String>()
            allApps.forEach { group ->
                group.activities.forEach { activity ->
                    val port = if (activity.deviceId.startsWith("net_"))
                        activity.deviceId.removePrefix("net_").toIntOrNull() else null
                    if (port != null && activity.packageName != null) {
                        val isTracker = activity.events
                            .map { it.detail }
                            .filter { it.isIpAddress() }
                            .any { ip ->
                                val display = ipDisplayCache[ip] ?: resolveIpDisplay(ip)
                                isTrackerDisplay(display)
                            }
                        if (isTracker) {
                            val key = "${activity.packageName}:$port"
                            newTrackerStreams.add(key)
                            BlockList.blockStream(activity.packageName, port)
                        }
                    }
                }
            }
            (trackerBlockedStreams - newTrackerStreams).forEach { key ->
                val pkg  = key.substringBeforeLast(":")
                val port = key.substringAfterLast(":").toIntOrNull() ?: return@forEach
                BlockList.unblockStream(pkg, port)
            }
            trackerBlockedStreams = newTrackerStreams
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF3B2B8F), Color(0xFF1A0A50))
                )
            ),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title  = { },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    actions = {
                        IconButton(onClick = { /* flows update automatically */ }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = Color.White)
                        }
                        IconButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = Color.White)
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
            ) {
                item { Spacer(Modifier.height(48.dp)) }

                // ── Power button ──────────────────────────────────────────────────
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        PowerButton(connected = vpnConnected, onClick = {
                            if (vpnConnected) {
                                context.stopVpnService()
                            } else {
                                val intent = VpnService.prepare(context)
                                if (intent == null) context.startVpnService()
                                else { startVpnPending = true; vpnPermLauncher.launch(intent) }
                            }
                        })
                    }
                }

                item { Spacer(Modifier.height(18.dp)) }

                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text       = if (vpnConnected) "Connected  |  $timerStr" else "Disconnected",
                            fontSize   = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color      = Color.White,
                        )
                    }
                }

                // ── Sensor + app list (visible only when connected) ───────────────
                if (vpnConnected) {
                    item { Spacer(Modifier.height(32.dp)) }

                    // ── Block Trackers toggle ─────────────────────────────────────
                    item {
                        val hasTrackers = trackerStreamCount > 0
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (blockTrackers) Color(0xFF3D2B8F) else Color(0xFF1E0B50)
                                )
                                .then(
                                    if (hasTrackers) Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication        = null,
                                    ) { blockTrackers = !blockTrackers }
                                    else Modifier
                                )
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment     = Alignment.CenterVertically,
                        ) {
                            Text(
                                text       = "Block Trackers",
                                fontSize   = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color      = Color.White.copy(alpha = if (hasTrackers) 1f else 0.38f),
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment     = Alignment.CenterVertically,
                            ) {
                                Switch(
                                    checked         = blockTrackers,
                                    enabled         = hasTrackers,
                                    onCheckedChange = { blockTrackers = it },
                                    colors          = SwitchDefaults.colors(
                                        checkedThumbColor            = Color(0xFFFFC107),
                                        checkedTrackColor            = Color(0xFF8B6CF0),
                                        checkedBorderColor           = Color.Transparent,
                                        uncheckedThumbColor          = Color(0xFF9B8EC4),
                                        uncheckedTrackColor          = Color(0xFF2D1B6B),
                                        uncheckedBorderColor         = Color.Transparent,
                                        disabledUncheckedThumbColor  = Color(0xFF9B8EC4).copy(alpha = 0.38f),
                                        disabledUncheckedTrackColor  = Color(0xFF2D1B6B).copy(alpha = 0.38f),
                                        disabledUncheckedBorderColor = Color.Transparent,
                                    ),
                                )
                                Box(
                                    modifier         = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Color(0xFF2D1B6B).copy(alpha = if (hasTrackers) 1f else 0.38f)
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text       = "$trackerStreamCount",
                                        fontSize   = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color      = Color.White.copy(alpha = if (hasTrackers) 1f else 0.38f),
                                    )
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(12.dp)) }

                    // ── Compact sensor strip (Mic · Camera · Location) ───────────
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF1E0B50))
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment     = Alignment.CenterVertically,
                        ) {
                            sensorGroups.forEachIndexed { idx, sensor ->
                                if (idx > 0) {
                                    Box(
                                        modifier = Modifier
                                            .width(0.5.dp)
                                            .height(36.dp)
                                            .background(Color(0xFF2D1B6B)),
                                    )
                                }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier            = Modifier
                                        .weight(1f)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication        = null,
                                        ) { selectedSensor = sensor },
                                ) {
                                    Row(
                                        verticalAlignment     = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Icon(
                                            imageVector        = sensor.icon,
                                            contentDescription = sensor.displayName,
                                            tint               = Color(0xFFFF9A3C),
                                            modifier           = Modifier.size(24.dp),
                                        )
                                        Text(
                                            text       = "${sensor.totalEventCount}",
                                            fontSize   = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color      = Color.White,
                                        )
                                    }
                                    Spacer(Modifier.height(5.dp))
                                    Row(
                                        verticalAlignment     = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (sensor.isCurrentlyActive) Color(0xFF4CAF50)
                                                    else Color.White.copy(alpha = 0.3f)
                                                ),
                                        )
                                        Text(
                                            text     = if (sensor.isCurrentlyActive) "In use" else "Idle",
                                            fontSize = 11.sp,
                                            color    = Color.White.copy(alpha = 0.6f),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(20.dp)) }

                    item {
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment     = Alignment.CenterVertically,
                        ) {
                            Text(
                                text     = "Data Stream by App",
                                fontSize = 13.sp,
                                color    = Color(0xFF9B8EC4),
                            )
                            val stealthCount = allApps.count { it.hasBackgroundActivity }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (stealthOnly) Color(0xFF3D1F7A)
                                        else Color(0xFF1E0B50)
                                    )
                                    .clickable { stealthOnly = !stealthOnly }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text       = "Stealth ($stealthCount)",
                                    fontSize   = 11.sp,
                                    fontWeight = if (stealthOnly) FontWeight.Medium else FontWeight.Normal,
                                    color      = if (stealthOnly) Color(0xFFCFB3FF) else Color(0xFF9B8EC4),
                                )
                            }
                        }
                    }

                    item { Spacer(Modifier.height(14.dp)) }

                    items(displayApps, key = { it.packageName ?: it.uid.toString() }) { app ->
                        AppGroupRow(appGroup = app, networkType = networkType, onClick = { selectedApp = app })
                        Spacer(Modifier.height(10.dp))
                    }

                    if (allApps.isEmpty()) {
                        item {
                            Text(
                                "No data captured yet.",
                                color    = Color(0xFF9B8EC4),
                                fontSize = 14.sp,
                            )
                        }
                    }

                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    selectedApp?.let { app ->
        AppGroupDialog(appGroup = app, context = context, onDismiss = { selectedApp = null })
    }
    selectedSensor?.let { sensor ->
        SensorGroupDialog(sensorGroup = sensor, onDismiss = { selectedSensor = null })
    }
}

// ── Power button ───────────────────────────────────────────────────────────────

@Composable
private fun PowerButton(connected: Boolean, onClick: () -> Unit) {
    val innerColor = if (connected) Color(0xFFFF9A3C) else Color(0xFFFF6B6B)

    Box(
        modifier         = Modifier
            .size(160.dp)
            .clip(CircleShape)
            .background(Color(0xFF2D1B6B))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier         = Modifier
                .size(110.dp)
                .clip(CircleShape)
                .background(innerColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Outlined.PowerSettingsNew,
                contentDescription = if (connected) "Stop" else "Start",
                tint               = Color.White,
                modifier           = Modifier.size(44.dp),
            )
        }
    }
}

// ── Device info content ────────────────────────────────────────────────────────

private data class InfoSection(val heading: String, val body: String)

private fun deviceInfoContent(deviceId: String, displayName: String): List<InfoSection> =
    when {
        deviceId == "microphone" -> listOf(
            InfoSection(
                "What it accesses",
                "Live audio captured from the device microphone — raw PCM audio or compressed " +
                "streams depending on the app's implementation.",
            ),
            InfoSection(
                "Where data goes",
                "Directly to the requesting app's own servers. Common destinations include " +
                "Meta's voice-processing infrastructure, Google Speech APIs, or proprietary " +
                "on-device models.",
            ),
            InfoSection(
                "Why apps use it",
                "Legitimate uses: voice messages, calls, live video, voice search, and " +
                "accessibility features. Less obvious uses: ambient sound detection, ad-targeting " +
                "triggers, and always-on wake-word listeners.",
            ),
            InfoSection(
                "Privacy risk",
                "HIGH — Audio data is the most sensitive media type. Some apps have been " +
                "documented accessing the microphone outside of obvious user interactions. " +
                "Any active recording session is flagged in this app.",
            ),
        )
        deviceId == "net_53" -> listOf(
            InfoSection(
                "What it sends",
                "DNS queries — plain-text requests asking 'what IP address is this domain?' " +
                "for every server an app attempts to reach. Captured before encryption.",
            ),
            InfoSection(
                "Where queries go",
                "To the DNS resolver configured by this VPN (1.1.1.1 — Cloudflare). " +
                "Without a VPN, queries go unencrypted to your ISP's resolver.",
            ),
            InfoSection(
                "Why it matters",
                "Every network connection starts with a DNS lookup. The full list of queried " +
                "domains is a precise log of all internet activity — which apps are active, " +
                "which services they contact, and when.",
            ),
            InfoSection(
                "Privacy risk",
                "MEDIUM-HIGH — DNS is unencrypted by default. Even with HTTPS content " +
                "encryption, DNS reveals which services you connect to. Frequent Meta-domain " +
                "lookups indicate active tracking or data-sync activity.",
            ),
        )
        deviceId == "net_443" -> listOf(
            InfoSection(
                "What it sends",
                "TLS-encrypted HTTPS traffic — the bulk of all modern app-to-server " +
                "communication including API calls, media uploads, and analytics.",
            ),
            InfoSection(
                "Where data goes",
                "Varies by app. Destination IPs visible in this log can be reverse-looked up " +
                "to identify CDNs, ad networks, analytics services, and social platforms.",
            ),
            InfoSection(
                "Why apps use it",
                "Secure transmission of login credentials, user-generated content, behavioral " +
                "telemetry, crash reports, and real-time sync. Also used to deliver ads and " +
                "transmit analytics to third-party SDKs bundled in apps.",
            ),
            InfoSection(
                "Privacy risk",
                "MEDIUM — Content is encrypted; this app sees only destination IP, volume, " +
                "and timing. However, traffic volume and destination patterns alone can reveal " +
                "significant behavioral information.",
            ),
        )
        deviceId == "net_80" -> listOf(
            InfoSection(
                "What it sends",
                "Unencrypted HTTP requests — full URLs, request headers, cookies, and " +
                "sometimes request bodies are transmitted in plain text.",
            ),
            InfoSection(
                "Where data goes",
                "Any web server. HTTP traffic on port 80 is intercepted in full by this VPN " +
                "and visible to any network observer between the device and destination.",
            ),
            InfoSection(
                "Why apps use it",
                "Legacy APIs, ad-delivery networks, analytics beacons, and software update " +
                "checks sometimes still use plain HTTP despite best practices.",
            ),
            InfoSection(
                "Privacy risk",
                "HIGH — All content is readable in plain text. Credentials, session tokens, " +
                "and personal data transmitted over HTTP are exposed to network eavesdroppers.",
            ),
        )
        deviceId == "net_4244" -> listOf(
            InfoSection(
                "What it sends",
                "Lightweight MQTT pub/sub messages used exclusively by Meta's apps " +
                "(Facebook, Instagram, Messenger, WhatsApp) for real-time event delivery.",
            ),
            InfoSection(
                "Where data goes",
                "Meta's MQTT broker infrastructure. Port 4244 is a Meta-specific port not " +
                "used by other known services.",
            ),
            InfoSection(
                "Why apps use it",
                "Delivers real-time notifications, typing indicators, active-status updates, " +
                "read receipts, and live reaction counts with minimal battery overhead.",
            ),
            InfoSection(
                "Privacy risk",
                "MEDIUM — Message content may be encrypted, but connection frequency and " +
                "timing reveals when you are actively using the app and your online presence.",
            ),
        )
        deviceId == "net_5228" -> listOf(
            InfoSection(
                "What it sends",
                "Firebase Cloud Messaging (FCM) — a persistent keep-alive connection and " +
                "encrypted push-notification payloads from Google's servers to your device.",
            ),
            InfoSection(
                "Where data goes",
                "Google Firebase servers (fcm.googleapis.com). Most installed Android apps " +
                "share this single persistent connection.",
            ),
            InfoSection(
                "Why apps use it",
                "Delivers background push notifications (messages, alerts, news) without " +
                "each app maintaining its own always-on connection.",
            ),
            InfoSection(
                "Privacy risk",
                "LOW-MEDIUM — Google can observe which apps receive notifications and " +
                "their frequency. Notification metadata (sender, topic) may be readable " +
                "by Google even if the payload is app-encrypted.",
            ),
        )
        deviceId == "net_3478" -> listOf(
            InfoSection(
                "What it sends",
                "STUN/TURN NAT traversal packets — used to discover your public IP address " +
                "and establish direct peer-to-peer connections for real-time communication.",
            ),
            InfoSection(
                "Where data goes",
                "STUN servers operated by the app developer (Meta, Google, etc.) and " +
                "eventually to the remote peer (another user's device).",
            ),
            InfoSection(
                "Why apps use it",
                "Required by WebRTC for voice and video calls. STUN discovers the device's " +
                "public-facing IP; TURN relays media when direct connection fails.",
            ),
            InfoSection(
                "Privacy risk",
                "MEDIUM — Your public IP address is sent to STUN servers and shared with " +
                "the call peer, potentially revealing your geographic location.",
            ),
        )
        deviceId == "net_1935" -> listOf(
            InfoSection(
                "What it sends",
                "RTMP (Real-Time Messaging Protocol) stream — a continuous feed of encoded " +
                "audio and/or video data during a live broadcast.",
            ),
            InfoSection(
                "Where data goes",
                "Live-streaming ingest servers (Facebook Live, Instagram Live, third-party " +
                "RTMP endpoints). Data volume can be several MB/s during active streaming.",
            ),
            InfoSection(
                "Why apps use it",
                "Sends live video and audio from the device camera/microphone to a streaming " +
                "platform for real-time broadcast to viewers.",
            ),
            InfoSection(
                "Privacy risk",
                "HIGH — An active RTMP connection means the app is streaming live media, " +
                "likely including camera and microphone input, to a remote server.",
            ),
        )
        deviceId == "net_8883" -> listOf(
            InfoSection(
                "What it sends",
                "TLS-encrypted MQTT messages — same publish/subscribe pattern as port 4244 " +
                "but with an additional TLS encryption layer.",
            ),
            InfoSection(
                "Where data goes",
                "Secure MQTT brokers (IoT platforms, enterprise messaging, or Meta's " +
                "encrypted messaging infrastructure).",
            ),
            InfoSection(
                "Why apps use it",
                "Same as MQTT on port 4244: real-time notifications and messaging, " +
                "with stronger transport security for sensitive payloads.",
            ),
            InfoSection(
                "Privacy risk",
                "LOW-MEDIUM — Content is encrypted end-to-end; connection metadata " +
                "(timing, frequency) still reveals app-usage patterns.",
            ),
        )
        deviceId == "net_1194" -> listOf(
            InfoSection(
                "What it sends",
                "OpenVPN tunnel packets — an app on your device is routing some or all of " +
                "its own traffic through a VPN provider.",
            ),
            InfoSection(
                "Where data goes",
                "A VPN server operated by the app's vendor. The destination of the inner " +
                "traffic is not visible at this layer.",
            ),
            InfoSection(
                "Why apps use it",
                "Privacy tools, corporate remote-access clients, and some security apps " +
                "use OpenVPN to encrypt and route traffic through their own servers.",
            ),
            InfoSection(
                "Privacy risk",
                "VARIABLE — The VPN provider gains full visibility into the app's traffic. " +
                "Trust depends entirely on the VPN operator's data policies.",
            ),
        )
        else -> listOf(
            InfoSection(
                "What it sends",
                "Network traffic to destination port ${displayName.removePrefix("Port ")} — " +
                "no specific protocol profile is available for this port.",
            ),
            InfoSection(
                "Where data goes",
                "The destination IPs visible in this device's event log. " +
                "Reverse DNS lookup on those IPs can identify the service operator.",
            ),
            InfoSection(
                "Why apps use it",
                "Could be a proprietary protocol, a non-standard service port, or a " +
                "lesser-known standard protocol. Examine the destination IPs for more context.",
            ),
            InfoSection(
                "Privacy risk",
                "UNKNOWN — Without protocol identification, the risk level cannot be " +
                "determined. Monitor destination IPs and data volumes for anomalies.",
            ),
        )
    }

@Composable
private fun DeviceInfoDialog(
    deviceId:    String,
    displayName: String,
    appName:     String? = null,
    dstIps:      List<String> = emptyList(),
    onDismiss:   () -> Unit,
) {
    val sections = remember(deviceId, appName) {
        val all = deviceInfoContent(deviceId, displayName)
        if (appName != null) {
            val why    = all.find { it.heading.startsWith("Why") }
            val others = all.filter { !it.heading.startsWith("Why") }
            if (why != null)
                listOf(InfoSection("Why $appName uses $displayName", why.body)) + others
            else all
        } else all
    }

    // Async resolve observed destination IPs → hostnames
    val uniqueIps = remember(dstIps) {
        dstIps.filter { it.isIpAddress() }.distinct().take(8)
    }
    val resolvedDests = remember(uniqueIps) { mutableStateListOf<String>() }
    LaunchedEffect(uniqueIps) {
        resolvedDests.clear()
        uniqueIps.forEach { ip ->
            val resolved = resolveIpDisplay(ip)
            if (resolved.contains(",") && resolved !in resolvedDests) resolvedDests.add(resolved)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.80f),
            shape    = RoundedCornerShape(20.dp),
            color    = Color(0xFF1A0A50),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Text(
                        text       = displayName,
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color      = Color.White,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color(0xFF8B6CF0))
                    }
                }
                HorizontalDivider(color = Color(0xFF2D1B6B), modifier = Modifier.padding(top = 4.dp))
                LazyColumn(
                    modifier            = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }

                    // ── Observed destinations (live, from actual traffic) ────────
                    if (resolvedDests.isNotEmpty()) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text          = "Observed destinations",
                                    fontSize      = 13.sp,
                                    fontWeight    = FontWeight.Bold,
                                    color         = Color(0xFF9B8EC4),
                                    letterSpacing = 0.5.sp,
                                )
                                resolvedDests.forEach { host ->
                                    Text(
                                        text      = host,
                                        fontSize  = 13.sp,
                                        color     = Color(0xFFFF9A3C),
                                        lineHeight = 19.sp,
                                    )
                                }
                            }
                        }
                    }

                    items(sections) { section ->
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                text          = section.heading,
                                fontSize      = 13.sp,
                                fontWeight    = FontWeight.Bold,
                                color         = Color(0xFF9B8EC4),
                                letterSpacing = 0.5.sp,
                            )
                            Text(
                                text      = section.body,
                                fontSize  = 14.sp,
                                color     = Color.White,
                                lineHeight = 21.sp,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

// ── Sensor group dialog ─────────────────────────────────────────────────────────

@Composable
private fun SensorGroupDialog(sensorGroup: SensorGroup, onDismiss: () -> Unit) {
    val dateFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.80f),
            shape    = RoundedCornerShape(20.dp),
            color    = Color(0xFF1A0A50),
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector        = sensorGroup.icon,
                            contentDescription = null,
                            tint               = Color.White,
                            modifier           = Modifier.size(20.dp),
                        )
                        Text(
                            text       = sensorGroup.displayName,
                            fontSize   = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color      = Color.White,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color(0xFF8B6CF0))
                    }
                }
                HorizontalDivider(color = Color(0xFF2D1B6B), modifier = Modifier.padding(top = 4.dp))

                if (sensorGroup.apps.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No access recorded yet.", color = Color(0xFF9B8EC4), fontSize = 14.sp)
                    }
                } else {
                    LazyColumn(
                        modifier            = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item { Spacer(Modifier.height(4.dp)) }
                        items(sensorGroup.apps, key = { it.packageName ?: it.appName }) { entry ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF2D1B6B))
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Row(
                                    modifier              = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment     = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        verticalAlignment     = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text       = entry.appName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize   = 15.sp,
                                            color      = Color.White,
                                            maxLines   = 1,
                                            overflow   = TextOverflow.Ellipsis,
                                        )
                                        if (entry.isCurrentlyActive) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF4CAF50)),
                                            )
                                        }
                                    }
                                    Text(
                                        text     = "${entry.eventCount} event${if (entry.eventCount != 1) "s" else ""}",
                                        fontSize = 13.sp,
                                        color    = Color(0xFF9B8EC4),
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text     = "Last: ${dateFmt.format(Date(entry.lastEventMs))}",
                                    fontSize = 12.sp,
                                    color    = Color(0xFF9B8EC4),
                                )
                            }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }
}

// ── App group row (main screen list) ──────────────────────────────────────────

@Composable
private fun AppGroupRow(appGroup: AppGroup, networkType: String, onClick: () -> Unit) {
    var showInfo by remember { mutableStateOf(false) }

    val blockedApps   by BlockList.blocked.collectAsState()
    val blockedStreams by BlockList.blockedStreams.collectAsState()
    val isAppBlocked  = appGroup.packageName != null && appGroup.packageName in blockedApps
    val totalStreams   = appGroup.activities.size
    val blockedCount  = if (isAppBlocked) totalStreams else appGroup.activities.count { activity ->
        val p = if (activity.deviceId.startsWith("net_"))
            activity.deviceId.removePrefix("net_").toIntOrNull() else null
        p != null && activity.packageName != null &&
            "${activity.packageName}:$p" in blockedStreams
    }
    val allowedCount  = totalStreams - blockedCount

    val hasMic     = appGroup.activities.any { it.deviceId == "microphone" }
    val hasNetwork = appGroup.activities.any { it.deviceId.startsWith("net_") }
    val dataSource = when {
        hasMic && hasNetwork -> "$networkType · Microphone"
        hasMic               -> "Microphone"
        hasNetwork           -> networkType
        else                 -> ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1E0B50))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text       = appGroup.appName,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 16.sp,
                        color      = Color.White,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier.weight(1f, fill = false),
                    )
                    Box(
                        modifier         = Modifier
                            .size(22.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication        = null,
                                onClick           = { showInfo = true },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector        = Icons.Outlined.Info,
                            contentDescription = "About ${appGroup.appName}",
                            tint               = Color(0xFF9B8EC4),
                            modifier           = Modifier.size(12.dp),
                        )
                    }
                }
                if (dataSource.isNotEmpty()) {
                    Text(
                        text     = dataSource,
                        fontSize = 12.sp,
                        color    = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                // Allowed streams badge (purple — always shown)
                Box(
                    modifier         = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2D1B6B)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text       = "$allowedCount",
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color      = Color.White,
                    )
                }
                // Blocked streams badge (faded red — only when something is blocked)
                if (blockedCount > 0) {
                    Box(
                        modifier         = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF5722).copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text       = "$blockedCount",
                            fontSize   = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color      = Color.White,
                        )
                    }
                }
                // Background-activity badge — app connected without the user opening it
                if (appGroup.hasBackgroundActivity) {
                    Box(
                        modifier         = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFFFB300).copy(alpha = 0.18f))
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text       = "bg",
                            fontSize   = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color      = Color(0xFFFFB300),
                        )
                    }
                }
            }
        }
    }

    if (showInfo) {
        AppInfoDialog(
            packageName = appGroup.packageName,
            appName     = appGroup.appName,
            onDismiss   = { showInfo = false },
        )
    }
}

// ── App group dialog (two levels: activity list → event detail) ───────────────

@Composable
private fun AppGroupDialog(appGroup: AppGroup, context: Context, onDismiss: () -> Unit) {
    var selectedActivity by remember { mutableStateOf<AppEntry?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f),
            shape    = RoundedCornerShape(20.dp),
            color    = Color(0xFF1A0A50),
        ) {
            Column(Modifier.fillMaxSize()) {

                // Navigation row: back (in detail view) on left, close on right
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = if (selectedActivity != null) {
                            { selectedActivity = null }
                        } else {
                            onDismiss
                        }
                    ) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Outlined.ArrowBackIos,
                            contentDescription = "Back",
                            tint               = Color(0xFF8B6CF0),
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color(0xFF8B6CF0))
                    }
                }

                if (selectedActivity == null) {
                    ActivityListLevel(
                        appGroup           = appGroup,
                        onActivitySelected = { selectedActivity = it },
                    )
                } else {
                    AppDetailLevel(
                        headerTitle = selectedActivity!!.subtitle,
                        app         = selectedActivity!!,
                        context     = context,
                    )
                }
            }
        }
    }
}

// ── Per-app info content ───────────────────────────────────────────────────────

private fun appInfoContent(packageName: String?, appName: String): List<InfoSection> = when (packageName) {

    "com.facebook.katana", "com.facebook.lite" -> listOf(
        InfoSection("What is sent",
            "Behavioural analytics (every tap, scroll, dwell time), ad-interaction events, " +
            "content seen, search queries, device identifiers (Android ID, advertising ID), " +
            "location (if granted), contact list hashes for 'People You May Know', " +
            "battery level, network type, installed-app list, and facial recognition vectors " +
            "from photos and videos you view or upload."),
        InfoSection("Where it goes",
            "Meta's primary data centres (graph.facebook.com, edge-network servers), " +
            "the Atlas advertising platform (atdmt.com), and third-party data-broker " +
            "partners under Meta's data-sharing agreements."),
        InfoSection("Why",
            "Targeted advertising (the primary revenue source), engagement optimisation, " +
            "feed-ranking models, friend-recommendation algorithms, and cross-app tracking " +
            "via the Meta Audience Network SDK embedded in thousands of other apps."),
        InfoSection("Notable risks",
            "Meta has been fined billions across multiple jurisdictions for unlawful data " +
            "collection. The app requests microphone access and has been documented activating " +
            "it outside obvious user interactions on some firmware versions."),
    )

    "com.facebook.orca", "com.facebook.mlite" -> listOf(
        InfoSection("What is sent",
            "Message metadata (participants, timestamps, message length, read receipts), " +
            "call duration and frequency, contact graph, device location, voice call audio " +
            "routed through Meta's TURN/STUN infrastructure, typing indicators, and reaction data."),
        InfoSection("Where it goes",
            "Meta servers (edge-mqtt.facebook.com for real-time delivery, " +
            "graph.facebook.com for account data). Voice/video call media transits " +
            "Meta's media-relay servers."),
        InfoSection("Why",
            "Message delivery, call routing, spam detection, and cross-platform identity " +
            "linking to Facebook's advertising profile."),
        InfoSection("Notable risks",
            "End-to-end encryption is opt-in (Secret Conversations) and not the default " +
            "for standard chats. All metadata — who talks to whom, when, and how often — " +
            "is visible to Meta regardless of encryption."),
    )

    "com.instagram.android" -> listOf(
        InfoSection("What is sent",
            "Posts and Stories viewed (with duration), Explore search queries, hashtag " +
            "interest signals, Shopping product interactions, DM metadata, Reel watch " +
            "percentages, device identifiers, camera roll thumbnail hashes, and " +
            "biometric-adjacent data extracted from face filters."),
        InfoSection("Where it goes",
            "Meta's infrastructure (cdninstagram.com for media, graph.facebook.com for " +
            "social graph). Fully merged with Facebook's ad-targeting profile since 2016."),
        InfoSection("Why",
            "Personalised feed ranking, targeted ads (especially Shopping), influencer " +
            "analytics, and feeding the shared Meta advertising identity across Facebook, " +
            "Instagram, WhatsApp and Threads."),
        InfoSection("Notable risks",
            "Instagram's data practices were central to the FTC's complaint against Meta. " +
            "The app has microphone permission and has been reported to access it during " +
            "background activity in multiple independent analyses."),
    )

    "com.whatsapp", "com.whatsapp.w4b" -> listOf(
        InfoSection("What is sent",
            "Message metadata (sender, recipient, timestamp, message type, read status), " +
            "phone-number contact graph (uploaded on install and periodically refreshed), " +
            "group membership, status view history, last-seen timestamps, device fingerprint, " +
            "IP address, and call timing. Message content itself is end-to-end encrypted."),
        InfoSection("Where it goes",
            "WhatsApp/Meta servers (whatsapp.com, whatsapp.net). Since Meta's 2021 " +
            "privacy-policy update, metadata is explicitly shared with the broader " +
            "Meta advertising ecosystem."),
        InfoSection("Why",
            "Message and call delivery, spam/abuse detection, and — following the 2021 " +
            "policy change — enriching Meta's advertising profiles with contact-graph and " +
            "usage-pattern data."),
        InfoSection("Notable risks",
            "The 2021 policy update caused a mass exodus to Signal/Telegram due to " +
            "mandatory metadata sharing. WhatsApp was fined €225 million by the Irish DPC " +
            "for opaque data-sharing disclosures. Content is encrypted but metadata is not."),
    )

    "com.instagram.barcelona" -> listOf(
        InfoSection("What is sent",
            "Post text and media, followed accounts, interaction events (likes, reposts, " +
            "replies), feed scroll behaviour, and device identifiers. Directly linked to " +
            "the Instagram account and shares data with Meta's unified profile."),
        InfoSection("Where it goes",
            "Meta infrastructure, same pipeline as Instagram and Facebook."),
        InfoSection("Why",
            "Content delivery and Meta's unified cross-platform advertising identity."),
        InfoSection("Notable risks",
            "Threads requires an Instagram account and cannot be used without one, " +
            "creating a mandatory data link to Meta's full behavioural profile."),
    )

    "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> listOf(
        InfoSection("What is sent",
            "Every video watched (with completion percentage), search queries, liked and " +
            "shared content, comments typed (including drafts not submitted), clipboard " +
            "contents (documented in 2020 iOS audit), device fingerprint including IMEI " +
            "and MAC address, location, face geometry from video, voice characteristics, " +
            "and installed-app list."),
        InfoSection("Where it goes",
            "ByteDance servers — primary data centres in the US and Singapore. Under " +
            "Chinese law, ByteDance must provide data to the Chinese government on request. " +
            "TikTok's 'Project Texas' is an ongoing attempt to ring-fence US user data " +
            "on Oracle infrastructure, but audits remain incomplete."),
        InfoSection("Why",
            "Feed recommendation algorithm (widely regarded as the most effective in the " +
            "industry), targeted advertising, trend detection, and — under Chinese " +
            "jurisdiction — potential government intelligence access."),
        InfoSection("Notable risks",
            "Banned on government devices in the US, EU, UK, Canada, Australia, and India. " +
            "Multiple documented instances of accessing clipboard, microphone, and precise " +
            "location beyond declared scope. US legislation to force divestiture is ongoing."),
    )

    "com.snapchat.android" -> listOf(
        InfoSection("What is sent",
            "Snap send/receive metadata (but not content after viewing by default), " +
            "Snap Map location (precise GPS if enabled), Story views, friend-interaction " +
            "frequency, Discover content engagement, Lens usage patterns, device identifiers, " +
            "and ad-interaction events."),
        InfoSection("Where it goes",
            "Snap Inc. servers (AWS-hosted, primarily US). Ad data shared with " +
            "Snap's advertising partners."),
        InfoSection("Why",
            "Content delivery, Snap Map, targeted advertising, and Snap's Creator analytics."),
        InfoSection("Notable risks",
            "Snap Map exposes precise real-time location to friends (and to Snap). " +
            "The ephemeral design creates a false sense of privacy — recipients can " +
            "screenshot or record snaps. Snap's 2014 breach exposed 4.6 million accounts."),
    )

    "com.twitter.android", "com.X.android" -> listOf(
        InfoSection("What is sent",
            "Tweets, likes, follows, retweets, DM metadata, link-click tracking via " +
            "t.co redirect, location (if enabled), device identifiers, ad interactions, " +
            "and third-party website visits if the Twitter pixel is present."),
        InfoSection("Where it goes",
            "Twitter/X servers. Ad data shared with partners. Under Elon Musk's ownership " +
            "since 2022, data practices and staff reductions in trust-and-safety teams " +
            "have raised additional concerns."),
        InfoSection("Why",
            "Content personalisation, targeted advertising, safety systems, and " +
            "post-2022 X Premium subscription analytics."),
        InfoSection("Notable risks",
            "Twitter has had multiple high-profile breaches and insider-threat incidents. " +
            "In 2022, phone numbers and emails of 5.4 million accounts were leaked via " +
            "an API vulnerability. DMs are not end-to-end encrypted."),
    )

    "com.spotify.music" -> listOf(
        InfoSection("What is sent",
            "Full listening history (every track, podcast, and playlist), search queries, " +
            "playback position and skip behaviour, voice search audio, social sharing " +
            "actions, device identifiers, location, and payment method metadata."),
        InfoSection("Where it goes",
            "Spotify AB servers (Sweden/GCP). Anonymised listening data is aggregated " +
            "and sold to labels and advertisers. Podcast play data is shared with " +
            "show creators."),
        InfoSection("Why",
            "Music recommendations (Discover Weekly, Daily Mixes), personalised ads on " +
            "the free tier, royalty calculation for rights holders, and trend analytics " +
            "sold to the music industry."),
        InfoSection("Notable risks",
            "Spotify's privacy policy allows it to collect photos, contacts, and precise " +
            "location — far beyond what music streaming requires. Voice search stores " +
            "queries on Spotify's servers."),
    )

    "com.netflix.mediaclient" -> listOf(
        InfoSection("What is sent",
            "Every title browsed (with dwell time), playback start/pause/seek events, " +
            "watched percentage, subtitle and audio-language preferences, search queries, " +
            "device capabilities, and ad-interaction events (ad-supported tier)."),
        InfoSection("Where it goes",
            "Netflix's own CDN (nflxvideo.net) for media, and netflix.com APIs for " +
            "account and recommendation data. The ad-supported tier routes ad data " +
            "to Microsoft Advertising (Netflix's exclusive ad partner)."),
        InfoSection("Why",
            "Content recommendations, bandwidth-optimised streaming, and — on the ad " +
            "tier — targeted advertising using Microsoft's identity graph."),
        InfoSection("Notable risks",
            "Browse history and partial views are retained even for content never finished. " +
            "The ad tier shares viewing data with Microsoft, which can be linked to " +
            "your broader Microsoft/LinkedIn identity."),
    )

    "com.google.android.youtube" -> listOf(
        InfoSection("What is sent",
            "Full watch history (every video, with timestamps and completion), search " +
            "queries, comments, likes, channel subscriptions, ad clicks, and " +
            "YouTube Music listening history if used. Tied to the Google account and " +
            "merged into Google's advertising profile."),
        InfoSection("Where it goes",
            "Google/YouTube servers. Watch history feeds Google's advertising identity " +
            "and is used for targeting across all Google properties and the AdSense network."),
        InfoSection("Why",
            "Video recommendations, targeted pre-roll advertising (YouTube's primary " +
            "revenue), Content ID enforcement for rights holders, and enriching Google's " +
            "unified cross-product user profile."),
        InfoSection("Notable risks",
            "YouTube watch history is one of the richest behavioural datasets Google holds. " +
            "It is used to infer political affiliation, religious beliefs, health conditions " +
            "and more for ad targeting. Autoplay systematically extends watch time to maximise data collection."),
    )

    "com.android.chrome", "com.google.android.apps.chrome" -> listOf(
        InfoSection("What is sent",
            "Every URL visited if Sync is enabled, search queries (to Google by default), " +
            "Safe Browsing URL-hash lookups (sent to Google for malware checking), " +
            "form autofill data, crash reports, usage metrics, and extension telemetry."),
        InfoSection("Where it goes",
            "Google's servers. Safe Browsing sends partial URL hashes; with Enhanced " +
            "Protection enabled, full URLs are sent. Sync pushes full history, bookmarks " +
            "and passwords to Google's servers."),
        InfoSection("Why",
            "Safe Browsing protection, Google Search integration, Chrome Sync, and " +
            "improving Chrome's own product (crash/performance metrics)."),
        InfoSection("Notable risks",
            "Chrome's Privacy Sandbox initiative replaces third-party cookies with " +
            "on-device ad targeting (Topics API) that still feeds advertising signals " +
            "to Google. Disabling Sync stops history sync but not Safe Browsing lookups."),
    )

    "com.google.android.gm" -> listOf(
        InfoSection("What is sent",
            "Email metadata (sender, recipient, subject, timestamps) processed by " +
            "Google's servers for spam filtering, Smart Reply suggestions, and " +
            "Smart Compose. Search queries within Gmail, and interaction events " +
            "(open, reply, delete, unsubscribe actions)."),
        InfoSection("Where it goes",
            "Google's mail servers. Google states it does not scan Gmail content for " +
            "advertising since 2017, but metadata and usage patterns are still processed."),
        InfoSection("Why",
            "Email delivery and filtering, Smart features (Reply, Compose, Summarise), " +
            "and spam/phishing detection."),
        InfoSection("Notable risks",
            "Third-party apps given Gmail API access can read full email content — " +
            "Google's 2018 audit found hundreds of such apps. Smart features require " +
            "Google to process message content server-side."),
    )

    "com.google.android.apps.maps" -> listOf(
        InfoSection("What is sent",
            "Search queries, every navigation session (origin, destination, route taken, " +
            "speed, stops), business searches, review submissions, real-time location " +
            "data during navigation, and — with Location History enabled — a permanent " +
            "timestamped record of everywhere you go."),
        InfoSection("Where it goes",
            "Google's servers. Location History (Timeline) is stored in the Google account " +
            "and retained indefinitely unless manually deleted. Aggregated mobility data " +
            "is sold as Google Maps Platform traffic insights."),
        InfoSection("Why",
            "Navigation, real-time traffic calculation (using anonymous speed data from " +
            "all users), business analytics for the Local Ads product, and building " +
            "Google's location graph for advertising."),
        InfoSection("Notable risks",
            "In 2022 Google agreed to a \$391.5 million settlement for tracking location " +
            "even after users turned off Location History. Location data infers home " +
            "address, workplace, medical visits, religious attendance, and political activity."),
    )

    else -> {
        val vendor = when {
            packageName?.startsWith("com.facebook") == true ||
            packageName?.startsWith("com.instagram") == true ||
            packageName?.startsWith("com.meta") == true ||
            packageName?.startsWith("com.oculus") == true ->
                "This is a Meta (Facebook) platform app."
            packageName?.startsWith("com.google") == true ->
                "This is a Google platform app."
            packageName?.startsWith("com.microsoft") == true ->
                "This is a Microsoft app."
            packageName?.startsWith("com.amazon") == true ->
                "This is an Amazon app."
            else -> null
        }
        listOf(
            InfoSection("What is sent",
                "Network traffic is being captured from $appName. Without a specific " +
                "profile for this app, the exact data payload is unknown." +
                if (vendor != null) " $vendor" else ""),
            InfoSection("Where it goes",
                "Inspect the destination IPs in the event log below. Reverse DNS lookup " +
                "on those addresses will identify the service operator (CDN, analytics " +
                "provider, ad network, or first-party server)."),
            InfoSection("Why",
                "Could include analytics, crash reporting, license checks, content delivery, " +
                "or advertising. Apps frequently embed third-party SDKs (Analytics, Ads, " +
                "Crash reporting) that make their own independent network calls."),
            InfoSection("Notable risks",
                "Without a known profile, assess risk by examining destination IPs and " +
                "data volumes. Unexpectedly large uploads or connections to known ad/tracker " +
                "domains warrant investigation."),
        )
    }
}

@Composable
private fun AppInfoDialog(packageName: String?, appName: String, onDismiss: () -> Unit) {
    val sections = remember(packageName) {
        appInfoContent(packageName, appName)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.82f),
            shape    = RoundedCornerShape(20.dp),
            color    = Color(0xFF1A0A50),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Text(
                        text       = appName,
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color      = Color.White,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color(0xFF8B6CF0))
                    }
                }
                if (packageName != null) {
                    Text(
                        text     = packageName,
                        fontSize = 11.sp,
                        color    = Color(0xFF9B8EC4),
                        modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                    )
                }
                HorizontalDivider(color = Color(0xFF2D1B6B))
                LazyColumn(
                    modifier            = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(sections) { section ->
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                text          = section.heading.uppercase(),
                                fontSize      = 11.sp,
                                fontWeight    = FontWeight.Bold,
                                color         = Color(0xFF9B8EC4),
                                letterSpacing = 0.8.sp,
                            )
                            Text(
                                text       = section.body,
                                fontSize   = 14.sp,
                                color      = Color.White,
                                lineHeight  = 21.sp,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

// ── Level 1: activity list ─────────────────────────────────────────────────────

@Composable
private fun ActivityListLevel(appGroup: AppGroup, onActivitySelected: (AppEntry) -> Unit) {
    val blockedApps    by BlockList.blocked.collectAsState()
    val blockedStreams  by BlockList.blockedStreams.collectAsState()
    val isBlocked      = appGroup.packageName != null && appGroup.packageName in blockedApps
    val totalStreams    = appGroup.activities.size
    val streamBlockedCount = appGroup.activities.count { activity ->
        val p = if (activity.deviceId.startsWith("net_"))
            activity.deviceId.removePrefix("net_").toIntOrNull() else null
        p != null && activity.packageName != null &&
            "${activity.packageName}:$p" in blockedStreams
    }
    val blockedCount = if (isBlocked) totalStreams else streamBlockedCount
    val allowedCount = totalStreams - blockedCount
    var showAppInfo by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 20.dp)) {

        // ── Card 1: app header with allow / block ──────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF2D1B6B))
                .padding(16.dp),
        ) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier              = Modifier.weight(1f),
                ) {
                    Text(
                        text       = appGroup.appName,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 18.sp,
                        color      = Color.White,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier.weight(1f, fill = false),
                    )
                    Box(
                        modifier         = Modifier
                            .size(20.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication        = null,
                                onClick           = { showAppInfo = true },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector        = Icons.Outlined.Info,
                            contentDescription = "About ${appGroup.appName}",
                            tint               = Color(0xFF9B8EC4),
                            modifier           = Modifier.size(12.dp),
                        )
                    }
                }
                Box(
                    modifier         = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3D2B8F)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text       = "${appGroup.totalEventCount}",
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color      = Color.White,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                // Constant stream counters
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text       = "$allowedCount",
                            fontSize   = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color      = Color(0xFFFFC107),
                        )
                        Text(
                            text     = "Allowed",
                            fontSize = 11.sp,
                            color    = Color(0xFF9B8EC4),
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text       = "$blockedCount",
                            fontSize   = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color      = Color(0xFFFF5252),
                        )
                        Text(
                            text     = "Blocked",
                            fontSize = 11.sp,
                            color    = Color(0xFF9B8EC4),
                        )
                    }
                }
                // Allow All / Block All CTAs
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    if (blockedCount > 0) {
                        Text(
                            text           = "Allow All",
                            fontSize       = 14.sp,
                            fontWeight     = FontWeight.Bold,
                            color          = Color(0xFF8B6CF0),
                            textDecoration = TextDecoration.Underline,
                            modifier       = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication        = null,
                            ) {
                                val pkg = appGroup.packageName ?: return@clickable
                                BlockList.unblock(pkg)
                                val prefix = "$pkg:"
                                blockedStreams
                                    .filter { it.startsWith(prefix) }
                                    .forEach { key ->
                                        val port = key.substringAfterLast(":").toIntOrNull()
                                        if (port != null) BlockList.unblockStream(pkg, port)
                                    }
                            },
                        )
                    }
                    if (allowedCount > 0) {
                        Text(
                            text           = "Block All",
                            fontSize       = 14.sp,
                            fontWeight     = FontWeight.Bold,
                            color          = Color(0xFF8B6CF0),
                            textDecoration = TextDecoration.Underline,
                            modifier       = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication        = null,
                            ) {
                                val pkg = appGroup.packageName ?: return@clickable
                                BlockList.block(pkg)
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Card 2: activity rows ──────────────────────────────────────────────
        if (appGroup.activities.isEmpty()) {
            Text("No activity recorded yet.", color = Color(0xFF9B8EC4), fontSize = 14.sp)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF2D1B6B)),
            ) {
                appGroup.activities.forEachIndexed { index, activity ->
                    ActivityRow(activity = activity, onClick = { onActivitySelected(activity) })
                    if (index < appGroup.activities.lastIndex) {
                        HorizontalDivider(color = Color(0xFF3D2880), thickness = 0.5.dp)
                    }
                }
            }
        }
    }

    if (showAppInfo) {
        AppInfoDialog(
            packageName = appGroup.packageName,
            appName     = appGroup.appName,
            onDismiss   = { showAppInfo = false },
        )
    }
}

// ── Activity row (inside the activity list card) ───────────────────────────────

@Composable
private fun ActivityRow(activity: AppEntry, onClick: () -> Unit) {
    val port = if (activity.deviceId.startsWith("net_"))
        activity.deviceId.removePrefix("net_").toIntOrNull() else null
    val blockedStreams by BlockList.blockedStreams.collectAsState()
    val isStreamBlocked = port != null && activity.packageName != null &&
        "${activity.packageName}:$port" in blockedStreams

    // Async-resolve destination IPs: top 3 for preview labels, all for tracker detection.
    val allUniqueIps = remember(activity.events) {
        activity.events.map { it.detail }.filter { it.isIpAddress() }.distinct()
    }
    val topLabels          = remember(allUniqueIps) { mutableStateListOf<String>() }
    var isSuspectedTracker by remember { mutableStateOf(false) }
    LaunchedEffect(allUniqueIps) {
        topLabels.clear()
        isSuspectedTracker = false
        allUniqueIps.forEachIndexed { idx, ip ->
            val resolved = resolveIpDisplay(ip)
            if (idx < 3) {
                val label = if (resolved.contains(",")) resolved.substringAfter(", ") else resolved
                if (label !in topLabels) topLabels.add(label)
            }
            if (!isSuspectedTracker && isTrackerDisplay(resolved)) isSuspectedTracker = true
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        // Left: stream name + ℹ, destinations preview, stats, tracker notice
        Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = activity.subtitle,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 15.sp,
                    color      = Color.White,
                )
                if (topLabels.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text     = "→ " + topLabels.joinToString(" · "),
                        fontSize = 12.sp,
                        color    = Color(0xFF9B8EC4),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                val statsText = buildString {
                    append("${activity.eventCount} event${if (activity.eventCount != 1) "s" else ""}")
                    if (activity.totalBytes > 0L) append(" · ${formatBytes(activity.totalBytes)}")
                }
                Text(text = statsText, fontSize = 12.sp, color = Color(0xFF6B5A9A))
                if (isSuspectedTracker) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFFF5252))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text       = "Suspected Tracker",
                            fontSize   = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color      = Color.White,
                        )
                    }
                }
        }

        Icon(
            imageVector        = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint               = Color(0xFF8B6CF0),
            modifier           = Modifier.size(18.dp),
        )
    }

    // Stream-level block/unblock link for network activities
    if (port != null && activity.packageName != null) {
        val streamName = knownPort(port) ?: "Port $port"
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text           = if (isStreamBlocked) "Unblock $streamName" else "Block $streamName",
                fontSize       = 13.sp,
                fontWeight     = FontWeight.SemiBold,
                color          = Color(0xFF8B6CF0),
                textDecoration = TextDecoration.Underline,
                modifier       = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null,
                ) {
                    if (isStreamBlocked) BlockList.unblockStream(activity.packageName, port)
                    else BlockList.blockStream(activity.packageName, port)
                },
            )
        }
    }
    } // end Column
}

// ── Level 2: app detail ────────────────────────────────────────────────────────

@Composable
private fun AppDetailLevel(
    headerTitle: String,
    app: AppEntry,
    context: Context,
) {
    val dateFmt = remember { SimpleDateFormat("EEE, MMM d, HH:mm:ss", Locale.getDefault()) }
    var showDeviceInfo by remember { mutableStateOf(false) }
    val blockedStreams by BlockList.blockedStreams.collectAsState()
    val portLabel = if (app.deviceId.startsWith("net_"))
        "Port ${app.deviceId.removePrefix("net_")}" else null
    val port = if (app.deviceId.startsWith("net_"))
        app.deviceId.removePrefix("net_").toIntOrNull() else null
    val isStreamBlocked = port != null && app.packageName != null &&
        "${app.packageName}:$port" in blockedStreams

    // Pre-compute destination groups before LazyColumn (remember is composable, can't live in LazyListScope)
    val destGroups = remember(app.events) {
        app.events
            .filter { it.detail.isIpAddress() }
            .groupBy { it.detail }
            .map { (ip, evs) ->
                DestGroup(
                    ip     = ip,
                    count  = evs.size,
                    bytes  = evs.sumOf { it.bytes },
                    lastMs = evs.maxOf { it.timestampMs },
                )
            }
            .sortedByDescending { it.count }
    }
    val textEvents = remember(app.events) {
        app.events.filter { !it.detail.isIpAddress() }
    }

    LazyColumn(
        modifier            = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── Summary card ───────────────────────────────────────────────────────
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF2D1B6B))
                    .padding(16.dp),
            ) {
                // Activity name + ℹ | port
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier              = Modifier.weight(1f),
                    ) {
                        Text(
                            text       = headerTitle,
                            fontSize   = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color      = Color.White,
                        )
                        Box(
                            modifier         = Modifier
                                .size(20.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication        = null,
                                    onClick           = { showDeviceInfo = true },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector        = Icons.Outlined.Info,
                                contentDescription = "About $headerTitle",
                                tint               = Color(0xFF9B8EC4),
                                modifier           = Modifier.size(12.dp),
                            )
                        }
                    }
                    if (portLabel != null) {
                        Text(text = portLabel, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Event count + bytes
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${app.eventCount} event${if (app.eventCount != 1) "s" else ""}",
                        fontSize = 13.sp, color = Color(0xFF9B8EC4),
                    )
                    if (app.totalBytes > 0L) {
                        Text("${formatBytes(app.totalBytes)} total", fontSize = 13.sp, color = Color(0xFF9B8EC4))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Single block control for this stream ───────────────────────
                if (port != null && app.packageName != null) {
                    // Network stream: stream-level Allowed/Blocked + Block/Unblock
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier         = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (isStreamBlocked) Color(0xFFFF5722) else Color(0xFFFFC107))
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text       = if (isStreamBlocked) "Blocked" else "Allowed",
                                fontSize   = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color      = if (isStreamBlocked) Color.White else Color(0xFF1A0A50),
                            )
                        }
                        Text(
                            text           = if (isStreamBlocked) "Unblock" else "Block",
                            fontSize       = 14.sp,
                            fontWeight     = FontWeight.Bold,
                            color          = Color(0xFF8B6CF0),
                            textDecoration = TextDecoration.Underline,
                            modifier       = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication        = null,
                            ) {
                                if (isStreamBlocked) BlockList.unblockStream(app.packageName, port)
                                else BlockList.blockStream(app.packageName, port)
                            },
                        )
                    }
                } else if (app.isMicAllowed != null && app.packageName != null) {
                    // Mic stream: mic permission pill + link to system settings
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier         = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (app.isMicAllowed) Color(0xFFFFC107) else Color(0xFFFF5722))
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text       = if (app.isMicAllowed) "Allowed" else "Blocked",
                                fontSize   = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color      = if (app.isMicAllowed) Color(0xFF1A0A50) else Color.White,
                            )
                        }
                        Text(
                            text           = if (app.isMicAllowed) "Block" else "Allow",
                            fontSize       = 14.sp,
                            fontWeight     = FontWeight.Bold,
                            color          = Color(0xFF8B6CF0),
                            textDecoration = TextDecoration.Underline,
                            modifier       = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication        = null,
                            ) {
                                val pkg = app.packageName
                                val deepLink = Intent("android.intent.action.MANAGE_APP_PERMISSION").apply {
                                    putExtra("android.intent.extra.PACKAGE_NAME", pkg)
                                    putExtra(
                                        "android.intent.extra.PERMISSION_GROUP_NAME",
                                        "android.permission-group.MICROPHONE",
                                    )
                                }
                                if (deepLink.resolveActivity(context.packageManager) != null) {
                                    context.startActivity(deepLink)
                                } else {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", pkg, null))
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        // ── Destination-grouped cards ──────────────────────────────────────────
        items(destGroups, key = { it.ip }) { dg ->
            var displayName by remember(dg.ip) { mutableStateOf(dg.ip) }
            LaunchedEffect(dg.ip) { displayName = resolveIpDisplay(dg.ip) }

            val serviceName = if (displayName.contains(",")) displayName.substringAfter(", ") else null
            val rawIp       = if (displayName.contains(",")) displayName.substringBefore(",") else displayName

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2D1B6B))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Service name (if resolved) or raw IP as title
                Text(
                    text       = serviceName ?: rawIp,
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color      = Color.White,
                )
                // Raw IP (shown when a service name is available)
                if (serviceName != null) {
                    Text(text = rawIp, fontSize = 12.sp, color = Color(0xFF6B5A9A))
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text     = "${dg.count} connection${if (dg.count != 1) "s" else ""}",
                        fontSize = 13.sp,
                        color    = Color(0xFF9B8EC4),
                    )
                    if (dg.bytes > 0L) {
                        Text(
                            text     = "↑ ${formatBytes(dg.bytes)}",
                            fontSize = 13.sp,
                            color    = Color(0xFF9B8EC4),
                        )
                    }
                }
                Text(
                    text     = "Last seen ${dateFmt.format(Date(dg.lastMs))}",
                    fontSize = 12.sp,
                    color    = Color(0xFF6B5A9A),
                )
            }
        }

        if (textEvents.isNotEmpty()) {
            items(textEvents, key = { it.timestampMs }) { ev ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF2D1B6B))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text       = dateFmt.format(Date(ev.timestampMs)),
                        fontSize   = 12.sp,
                        color      = Color(0xFF6B5A9A),
                    )
                    Text(text = ev.detail, fontSize = 14.sp, color = Color.White)
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }

    if (showDeviceInfo) {
        DeviceInfoDialog(
            deviceId    = app.deviceId,
            displayName = headerTitle,
            appName     = app.appName,
            dstIps      = remember(app.events) { app.events.map { it.detail } },
            onDismiss   = { showDeviceInfo = false },
        )
    }
}

// ── Context extensions ─────────────────────────────────────────────────────────

@Suppress("DEPRECATION")
private fun Context.getNetworkType(): String {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    // When the VPN is active, activeNetwork is the VPN interface.
    // Find the first non-VPN network with internet access to get the real transport.
    val caps = cm.allNetworks
        .mapNotNull { cm.getNetworkCapabilities(it) }
        .firstOrNull { c ->
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        ?: cm.getNetworkCapabilities(cm.activeNetwork)
        ?: return "Network"
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else                                                       -> "Network"
    }
}

private fun Context.startVpnService() =
    startService(Intent(this, LocalVpnService::class.java).setAction(LocalVpnService.ACTION_START))

private fun Context.stopVpnService() =
    startService(Intent(this, LocalVpnService::class.java).setAction(LocalVpnService.ACTION_STOP))
