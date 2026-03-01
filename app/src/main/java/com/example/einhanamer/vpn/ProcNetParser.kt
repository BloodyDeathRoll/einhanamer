package com.example.einhanamer.vpn

import android.os.Process
import android.util.Log
import com.example.einhanamer.data.Protocol
import java.io.File

/**
 * Fallback UID resolver for API 26-28 (before ConnectivityManager.getConnectionOwnerUid).
 *
 * Reads /proc/net/tcp[6] and /proc/net/udp[6], matches on local port, and
 * returns the owning UID.  These files are readable by VPN-service apps
 * without root on all current Android versions.
 *
 * The file format (space-separated columns) is:
 *   sl  local_addr:port  rem_addr:port  state  ...  uid  ...
 * Addresses are in little-endian hex for IPv4, big-endian hex for IPv6.
 */
object ProcNetParser {

    private const val TAG = "ProcNetParser"

    fun findUid(packet: ParsedPacket): Int {
        val files: List<String> = when (packet.protocol) {
            Protocol.TCP -> listOf("/proc/net/tcp", "/proc/net/tcp6")
            Protocol.UDP -> listOf("/proc/net/udp", "/proc/net/udp6")
            else -> return Process.INVALID_UID
        }
        for (path in files) {
            val uid = searchFile(path, packet.srcPort)
            if (uid != Process.INVALID_UID) return uid
        }
        return Process.INVALID_UID
    }

    private fun searchFile(path: String, srcPort: Int): Int {
        val file = File(path)
        if (!file.canRead()) return Process.INVALID_UID
        return try {
            file.bufferedReader().useLines { lines ->
                lines.drop(1) // skip header row
                    .mapNotNull { parseLine(it, srcPort) }
                    .firstOrNull()
                    ?: Process.INVALID_UID
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read $path: ${e.message}")
            Process.INVALID_UID
        }
    }

    /**
     * Example line from /proc/net/tcp:
     *   0: 0F6CA8C0:C4B2 D8EFF945:01BB 01 ... 10073 ...
     *                ↑ local port (big-endian hex)         ↑ uid column [7]
     */
    private fun parseLine(line: String, srcPort: Int): Int? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 8) return null
        val localField = parts[1]              // "XXXXXXXX:PPPP"
        val portHex    = localField.substringAfter(':', "")
        val port       = portHex.toIntOrNull(16) ?: return null
        if (port != srcPort) return null
        return parts[7].toIntOrNull()
    }
}
