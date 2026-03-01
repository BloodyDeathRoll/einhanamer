package com.example.einhanamer.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.einhanamer.MainActivity
import com.example.einhanamer.data.BlockList
import com.example.einhanamer.data.EventLog
import com.example.einhanamer.data.Protocol
import com.example.einhanamer.data.TrafficEvent
import com.example.einhanamer.monitor.MetaApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * LocalVpnService — Phase 1 scaffold.
 *
 * Sets up a "catch-all" TUN interface that captures every outbound IP packet,
 * parses it, resolves the owning UID → package name, and logs events to
 * [EventLog].
 *
 * Forwarding:
 *   • UDP: fully relayed via a protected DatagramChannel per flow.
 *   • TCP: captured and logged; forwarding requires a userspace TCP stack
 *          (see TODO below). Internet will be disrupted for TCP while active
 *          unless you integrate tun2socks / pcap4android in Phase 2.
 *
 * Permissions required in the manifest:
 *   INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE,
 *   ACCESS_NETWORK_STATE
 */
class LocalVpnService : VpnService() {

    companion object {
        private const val TAG = "LocalVpnService"

        const val ACTION_START = "com.example.einhanamer.START_VPN"
        const val ACTION_STOP  = "com.example.einhanamer.STOP_VPN"

        private const val CHANNEL_ID       = "vpn_monitor"
        private const val NOTIFICATION_ID  = 1

        private const val VPN_ADDRESS    = "10.99.0.1"
        private const val VPN_ADDRESS_V6 = "fd00:1::"
        private const val MTU            = 1500

        /** Observed by the UI to drive the connected/disconnected toggle. */
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunOut: FileOutputStream? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private class UdpFlow(val socket: DatagramSocket, val job: kotlinx.coroutines.Job)

    /** One bidirectional UDP relay per (srcIp, srcPort, dstIp, dstPort) 4-tuple. */
    private val udpFlows = ConcurrentHashMap<String, UdpFlow>()

    /** One TcpSession per active TCP flow (keyed by "srcIp:srcPort->dstIp:dstPort"). */
    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()

    /**
     * Packages whose last UsageEvents event was MOVE_TO_FOREGROUND (i.e. currently visible).
     * Refreshed every 2 seconds by [startForegroundMonitor].
     */
    @Volatile private var foregroundPackages: Set<String> = emptySet()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        ensureNotification()
        startVpn()
        return START_STICKY
    }

    override fun onRevoke() = stopVpn()          // user revoked from Settings
    override fun onDestroy() { stopVpn(); super.onDestroy() }

    // ── VPN setup ─────────────────────────────────────────────────────────────

    private fun startVpn() {
        if (vpnInterface != null) return
        vpnInterface = buildInterface() ?: run {
            Log.e(TAG, "establish() returned null — VPN permission missing?")
            stopSelf()
            return
        }
        tunOut = FileOutputStream(vpnInterface!!.fileDescriptor)
        _isRunning.value = true
        scope.launch { captureLoop() }
        startForegroundMonitor()
    }

    private fun buildInterface(): ParcelFileDescriptor? = try {
        Builder()
            .setSession("EinHanamer Privacy Monitor")
            .addAddress(VPN_ADDRESS, 32)
            .addAddress(VPN_ADDRESS_V6, 128)
            .addRoute("0.0.0.0", 0)         // all IPv4
            .addRoute("::", 0)              // all IPv6
            .addDnsServer("1.1.1.1")
            .addDnsServer("2606:4700:4700::1111")
            .setMtu(MTU)
            .setBlocking(true)              // simplifies the read loop
            .addDisallowedApplication(packageName)  // exclude our own traffic
            .establish()
    } catch (e: Exception) {
        Log.e(TAG, "VPN builder failed", e)
        null
    }

    // ── Capture loop ──────────────────────────────────────────────────────────

    private suspend fun captureLoop() = withContext(Dispatchers.IO) {
        val pfd = vpnInterface ?: return@withContext
        val input  = FileInputStream(pfd.fileDescriptor)
        val buffer = ByteArray(MTU)
        Log.i(TAG, "Capture loop started")

        while (isActive) {
            val bytesRead = try {
                input.read(buffer)
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "TUN read error", e)
                break
            }
            if (bytesRead <= 0) continue

            val raw = buffer.copyOf(bytesRead)
            launch { handlePacket(raw, bytesRead) }
        }
        Log.i(TAG, "Capture loop ended")
    }

    // ── Per-packet processing ─────────────────────────────────────────────────

    private fun handlePacket(raw: ByteArray, len: Int) {
        val packet = PacketParser.parse(raw, len) ?: return
        val uid     = resolveUid(packet)
        val pkgName = if (uid > 0) packageManager.getPackagesForUid(uid)?.firstOrNull() else null
        val isMeta  = pkgName != null && MetaApps.PACKAGES.contains(pkgName)

        EventLog.logTraffic(
            TrafficEvent(
                uid          = uid,
                packageName  = pkgName,
                protocol     = packet.protocol,
                srcIp        = packet.srcIp,
                dstIp        = packet.dstIp,
                srcPort      = packet.srcPort,
                dstPort      = packet.dstPort,
                byteCount    = len,
                isMeta       = isMeta,
                isBackground = pkgName != null && pkgName !in foregroundPackages,
            )
        )

        if (isMeta) {
            Log.w(TAG, "META [$pkgName] ${packet.protocol} → ${packet.dstIp}:${packet.dstPort}  uid=$uid")
        }

        // Drop all packets for apps the user has blocked — log the event but skip forwarding.
        if (pkgName != null && BlockList.isBlocked(pkgName)) {
            Log.d(TAG, "BLOCKED [$pkgName] ${packet.protocol} → ${packet.dstIp}:${packet.dstPort}")
            return
        }

        // Drop packets for specific streams the user has blocked (e.g. DNS for Instagram).
        if (pkgName != null && BlockList.isStreamBlocked(pkgName, packet.dstPort)) {
            Log.d(TAG, "STREAM-BLOCKED [$pkgName] port ${packet.dstPort}")
            return
        }

        // Forward so the device keeps internet access
        when (packet.protocol) {
            Protocol.UDP -> forwardUdp(packet, raw)
            Protocol.TCP -> forwardTcp(packet, raw)
            else -> Unit
        }
    }

    // ── UID resolution ────────────────────────────────────────────────────────

    /**
     * API 29+: use the fast kernel-assisted [ConnectivityManager.getConnectionOwnerUid].
     * API 26-28: fall back to parsing /proc/net/tcp[6] / /proc/net/udp[6].
     *
     * Note: getConnectionOwnerUid requires ACCESS_NETWORK_STATE; on some
     * vendor builds it may additionally require a signature permission and
     * will throw. The catch block silently falls through to proc parsing.
     */
    private fun resolveUid(packet: ParsedPacket): Int {
        if (packet.srcPort == 0) return Process.INVALID_UID

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val cm   = getSystemService(ConnectivityManager::class.java)
                val proto = if (packet.protocol == Protocol.TCP)
                    OsConstants.IPPROTO_TCP else OsConstants.IPPROTO_UDP
                val src = InetSocketAddress(InetAddress.getByName(packet.srcIp), packet.srcPort)
                val dst = InetSocketAddress(InetAddress.getByName(packet.dstIp), packet.dstPort)
                val uid = cm.getConnectionOwnerUid(proto, src, dst)
                if (uid != Process.INVALID_UID) return uid
            } catch (e: Exception) {
                Log.v(TAG, "getConnectionOwnerUid unavailable, falling back to /proc: ${e.message}")
            }
        }

        return ProcNetParser.findUid(packet)
    }

    // ── Foreground monitor ────────────────────────────────────────────────────

    /**
     * Polls UsageStatsManager every 2 seconds to keep [foregroundPackages] fresh.
     * Requires PACKAGE_USAGE_STATS permission (already in manifest).
     */
    private fun startForegroundMonitor() {
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                foregroundPackages = queryForegroundPackages()
                delay(2_000)
            }
        }
    }

    /**
     * Returns the set of packages whose last lifecycle event was MOVE_TO_FOREGROUND
     * (i.e. they are currently visible to the user).
     * Returns empty set when the screen is off — everything is background then.
     */
    @Suppress("DEPRECATION")
    private fun queryForegroundPackages(): Set<String> {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isInteractive) return emptySet()
        return try {
            val usm   = getSystemService(UsageStatsManager::class.java)
            val end   = System.currentTimeMillis()
            val start = end - 30 * 60_000L          // 30-minute look-back window
            val eventsResult = usm.queryEvents(start, end)
            val lastFg = mutableMapOf<String, Long>()
            val lastBg = mutableMapOf<String, Long>()
            val ev = UsageEvents.Event()
            while (eventsResult.hasNextEvent()) {
                eventsResult.getNextEvent(ev)
                when (ev.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND ->
                        if (ev.timeStamp > (lastFg[ev.packageName] ?: 0L))
                            lastFg[ev.packageName] = ev.timeStamp
                    UsageEvents.Event.MOVE_TO_BACKGROUND ->
                        if (ev.timeStamp > (lastBg[ev.packageName] ?: 0L))
                            lastBg[ev.packageName] = ev.timeStamp
                }
            }
            // Keep only packages whose last fg timestamp is newer than their last bg timestamp
            lastFg.keys.filter { pkg ->
                (lastFg[pkg] ?: 0L) > (lastBg[pkg] ?: 0L)
            }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "queryForegroundPackages: ${e.message}")
            emptySet()
        }
    }

    // ── UDP relay (bidirectional) ─────────────────────────────────────────────

    /**
     * Forwards a UDP packet to the real network and, on the first packet for a
     * given flow, launches a coroutine that reads responses and writes them back
     * into the TUN fd as correctly-framed IP+UDP packets.
     *
     * Without the read-back path, DNS replies and QUIC (HTTP/3) responses are
     * silently discarded, which causes map tiles and similar UDP-heavy content to
     * never arrive.
     */
    private fun forwardUdp(packet: ParsedPacket, raw: ByteArray) {
        val out  = tunOut ?: return
        val key  = "${packet.srcIp}:${packet.srcPort}->${packet.dstIp}:${packet.dstPort}"

        val flow = udpFlows.computeIfAbsent(key) {
            val sock = DatagramSocket()
            protect(sock)
            sock.connect(InetAddress.getByName(packet.dstIp), packet.dstPort)

            // Capture flow identity for the response builder closure
            val fSrcIp   = packet.srcIp;  val fSrcPort = packet.srcPort
            val fDstIp   = packet.dstIp;  val fDstPort = packet.dstPort
            val fIpVer   = packet.ipVersion

            val job = scope.launch(Dispatchers.IO) {
                val buf = ByteArray(MTU)
                val dp  = DatagramPacket(buf, buf.size)
                while (true) {
                    try {
                        sock.receive(dp)                      // blocks until data or socket closed
                        if (dp.length == 0) continue
                        val pkt = PacketBuilder.buildUdpPacket(
                            ipVersion = fIpVer,
                            srcIp     = fDstIp,  srcPort = fDstPort,   // reversed for return path
                            dstIp     = fSrcIp,  dstPort = fSrcPort,
                            payload   = buf.copyOf(dp.length),
                        )
                        synchronized(out) { out.write(pkt) }
                    } catch (e: Exception) {
                        break                                 // socket closed or error — exit loop
                    }
                }
                udpFlows.remove(key)
                runCatching { sock.close() }
            }
            UdpFlow(sock, job)
        }

        val payloadStart = packet.ipHeaderLen + packet.transportHeaderLen
        val payloadLen   = raw.size - payloadStart
        if (payloadLen <= 0) return

        try {
            flow.socket.send(DatagramPacket(raw, payloadStart, payloadLen))
        } catch (e: Exception) {
            Log.w(TAG, "UDP send failed [$key]: ${e.message}")
            udpFlows.remove(key)?.also {
                runCatching { it.socket.close() }
                runCatching { it.job.cancel() }
            }
        }
    }

    // ── TCP relay ─────────────────────────────────────────────────────────────

    /**
     * Dispatches an incoming TCP packet to its [TcpSession].
     * On SYN, a new session is created which completes the 3-way handshake and
     * splices data between the TUN fd and a protected upstream [java.net.Socket].
     */
    private fun forwardTcp(packet: ParsedPacket, raw: ByteArray) {
        val out = tunOut ?: return
        val isSyn = (packet.tcpFlags and 0x02) != 0
        val key   = "${packet.srcIp}:${packet.srcPort}->${packet.dstIp}:${packet.dstPort}"

        if (isSyn) {
            val session = TcpSession(
                ipVersion  = packet.ipVersion,
                srcIp      = packet.srcIp,
                srcPort    = packet.srcPort,
                dstIp      = packet.dstIp,
                dstPort    = packet.dstPort,
                clientIsn  = packet.tcpSeq,
                tunOut     = out,
                vpnService = this,
                scope      = scope,
                onClosed   = { tcpSessions.remove(key) },
            )
            tcpSessions.put(key, session)?.close()  // close any stale session for this key
            session.start()
        } else {
            tcpSessions[key]?.onPacket(packet, raw)
        }
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    private fun stopVpn() {
        _isRunning.value = false
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        udpFlows.values.forEach { runCatching { it.socket.close(); it.job.cancel() } }
        udpFlows.clear()
        tcpSessions.values.forEach { runCatching { it.close() } }
        tcpSessions.clear()
        tunOut?.close()
        tunOut = null
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun ensureNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN Monitor", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Active while EinHanamer is monitoring traffic" }
            )
        }
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, LocalVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Privacy Monitor running")
                .setContentText("Intercepting all outbound traffic")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
                .setOngoing(true)
                .build()
        )
    }
}
