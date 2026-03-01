package com.example.einhanamer.monitor

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.einhanamer.data.CameraAccessEvent
import com.example.einhanamer.data.EventLog
import com.example.einhanamer.data.LocationAccessEvent
import com.example.einhanamer.data.MicAccessEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * SensorMonitorService (kept as MicMonitorService for manifest compatibility).
 *
 * Monitors three sensors for ALL installed apps that hold the relevant permission:
 *   • Microphone  — OPSTR_RECORD_AUDIO
 *   • Camera      — OPSTR_CAMERA  +  CameraManager.AvailabilityCallback fallback
 *   • Location    — OPSTR_FINE_LOCATION
 *
 * Each sensor uses a poll loop that calls [AppOpsManager.isOpActive] every 500 ms.
 * On stock (non-rooted) devices, isOpActive for other UIDs requires
 * android.permission.GET_APP_OPS_STATS (signature-level). Grant via ADB:
 *   adb shell pm grant com.example.einhanamer android.permission.GET_APP_OPS_STATS
 *
 * When GET_APP_OPS_STATS is unavailable the camera falls back to
 * CameraManager.AvailabilityCallback (no app attribution, but reliable).
 * Mic and location fall back silently (no events emitted for those sensors
 * on stock devices without the ADB grant).
 */
class MicMonitorService : Service() {

    companion object {
        private const val TAG             = "SensorMonitor"
        private const val CHANNEL_ID      = "mic_monitor"
        private const val NOTIFICATION_ID = 2
        private const val POLL_INTERVAL_MS = 500L
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var appOps: AppOpsManager

    /** True until the first SecurityException from isOpActive — avoids repeated exception overhead. */
    @Volatile private var canUseIsOpActive = true

    // Per-sensor "currently active" sets (packageName)
    private val activeMic      = mutableSetOf<String>()
    private val activeCamera   = mutableSetOf<String>()
    private val activeLocation = mutableSetOf<String>()

    private var cameraCallback: CameraManager.AvailabilityCallback? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        ensureNotification()
        startMonitoring()
    }

    override fun onDestroy() {
        scope.cancel()
        cameraCallback?.let {
            runCatching {
                (getSystemService(CAMERA_SERVICE) as CameraManager).unregisterAvailabilityCallback(it)
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Monitoring setup ──────────────────────────────────────────────────────

    private fun startMonitoring() {
        val micApps      = resolveAppsWithPermission(Manifest.permission.RECORD_AUDIO)
        val cameraApps   = resolveAppsWithPermission(Manifest.permission.CAMERA)
        val locationApps = resolveAppsWithPermission(Manifest.permission.ACCESS_FINE_LOCATION)

        Log.i(TAG, "Monitoring: mic=${micApps.size} camera=${cameraApps.size} location=${locationApps.size} apps")

        startPollLoop("mic",      micApps,      AppOpsManager.OPSTR_RECORD_AUDIO)  { pkg, uid, active -> emitMic(pkg, uid, active) }
        startPollLoop("camera",   cameraApps,   AppOpsManager.OPSTR_CAMERA)         { pkg, uid, active -> emitCamera(pkg, uid, active) }
        startPollLoop("location", locationApps, AppOpsManager.OPSTR_FINE_LOCATION) { pkg, uid, active -> emitLocation(pkg, uid, active) }

        // Camera fallback: CameraManager.AvailabilityCallback fires without any permission
        registerCameraFallback()
    }

    private fun startPollLoop(
        name:     String,
        apps:     Map<String, Int>,
        opStr:    String,
        onEdge:   (String, Int, Boolean) -> Unit,
    ) {
        val active = when (name) {
            "mic"      -> activeMic
            "camera"   -> activeCamera
            "location" -> activeLocation
            else       -> mutableSetOf()
        }
        scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                apps.forEach { (pkg, uid) ->
                    val now      = isOpActive(opStr, uid, pkg)
                    val wasActive = pkg in active
                    when {
                        now && !wasActive -> { active.add(pkg);    onEdge(pkg, uid, true)  }
                        !now && wasActive -> { active.remove(pkg); onEdge(pkg, uid, false) }
                    }
                }
            }
        }
    }

    private fun registerCameraFallback() {
        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
        cameraCallback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) {
                // If the per-app poll loop already attributed this, skip the anonymous event.
                if (!canUseIsOpActive) {
                    EventLog.logCamera(CameraAccessEvent(
                        timestampMs = System.currentTimeMillis(),
                        packageName = null,
                        uid         = -1,
                        isActive    = true,
                    ))
                    Log.i(TAG, "CAMERA ▶ (unattributed) camera=$cameraId")
                }
            }
            override fun onCameraAvailable(cameraId: String) {
                if (!canUseIsOpActive) {
                    EventLog.logCamera(CameraAccessEvent(
                        timestampMs = System.currentTimeMillis(),
                        packageName = null,
                        uid         = -1,
                        isActive    = false,
                    ))
                    Log.i(TAG, "CAMERA ■ (unattributed) camera=$cameraId")
                }
            }
        }.also { mgr.registerAvailabilityCallback(it, null) }
    }

    // ── Op-active check ───────────────────────────────────────────────────────

    private fun isOpActive(opStr: String, uid: Int, packageName: String): Boolean {
        if (!canUseIsOpActive) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.isOpActive(opStr, uid, packageName)
            } else {
                appOps.checkOpNoThrow(opStr, uid, packageName) == AppOpsManager.MODE_ALLOWED
            }
        } catch (e: SecurityException) {
            canUseIsOpActive = false
            Log.w(TAG, "GET_APP_OPS_STATS not granted — per-app sensor monitoring unavailable. " +
                "Grant via: adb shell pm grant com.example.einhanamer android.permission.GET_APP_OPS_STATS")
            false
        } catch (e: Exception) {
            Log.w(TAG, "isOpActive($packageName/$opStr): ${e.message}")
            false
        }
    }

    // ── Event emission ────────────────────────────────────────────────────────

    private fun emitMic(packageName: String, uid: Int, isActive: Boolean) {
        EventLog.logMic(MicAccessEvent(
            packageName = packageName,
            uid         = uid,
            isMeta      = MetaApps.PACKAGES.contains(packageName),
            isActive    = isActive,
        ))
        Log.w(TAG, "MIC ${if (isActive) "▶ START" else "■ STOP "} [$packageName]")
    }

    private fun emitCamera(packageName: String, uid: Int, isActive: Boolean) {
        EventLog.logCamera(CameraAccessEvent(
            timestampMs = System.currentTimeMillis(),
            packageName = packageName,
            uid         = uid,
            isActive    = isActive,
        ))
        Log.w(TAG, "CAMERA ${if (isActive) "▶ START" else "■ STOP "} [$packageName]")
    }

    private fun emitLocation(packageName: String, uid: Int, isActive: Boolean) {
        EventLog.logLocation(LocationAccessEvent(
            timestampMs = System.currentTimeMillis(),
            packageName = packageName,
            uid         = uid,
            isActive    = isActive,
        ))
        Log.w(TAG, "LOCATION ${if (isActive) "▶ START" else "■ STOP "} [$packageName]")
    }

    // ── Package resolution ────────────────────────────────────────────────────

    private fun resolveAppsWithPermission(permissionName: String): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        try {
            val packages = packageManager.getInstalledPackages(0)
            for (pkg in packages) {
                if (packageManager.checkPermission(permissionName, pkg.packageName) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    result[pkg.packageName] = pkg.applicationInfo?.uid ?: continue
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveAppsWithPermission($permissionName): ${e.message}")
        }
        return result
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun ensureNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sensor Monitor", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Monitors microphone, camera and location access" }
            )
        }
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Sensor Monitor running")
                .setContentText("Watching microphone, camera and location access")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        )
    }
}
