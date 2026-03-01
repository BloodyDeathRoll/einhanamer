package com.example.einhanamer.vpn

import com.example.einhanamer.data.Protocol
import java.net.InetAddress
import java.nio.ByteBuffer

data class ParsedPacket(
    val ipVersion: Int,         // 4 or 6
    val protocol: Protocol,
    val srcIp: String,
    val dstIp: String,
    val srcPort: Int,           // 0 for Protocol.OTHER
    val dstPort: Int,           // 0 for Protocol.OTHER
    val ipHeaderLen: Int,       // byte offset where transport header starts
    val transportHeaderLen: Int,// byte offset where payload starts (from ipHeaderLen)
    val payloadLen: Int,        // bytes of application data (may be 0)
    val totalLen: Int,          // total packet length as seen in the IP header
    val tcpSeq: Long = 0L,      // TCP only: sequence number (unsigned 32-bit stored in Long)
    val tcpFlags: Int = 0,      // TCP only: flags byte (FIN=0x01 SYN=0x02 RST=0x04 ACK=0x10)
)

/**
 * Stateless parser for IPv4/IPv6 packets arriving from the TUN file descriptor.
 * Only parses headers — never copies payload bytes — to stay allocation-light
 * in the hot capture loop.
 */
object PacketParser {

    fun parse(buf: ByteArray, len: Int): ParsedPacket? {
        if (len < 20) return null
        return when (val version = (buf[0].toInt() and 0xFF) ushr 4) {
            4 -> parseV4(buf, len)
            6 -> parseV6(buf, len)
            else -> null.also { /* unknown IP version */ }
        }
    }

    // ── IPv4 ─────────────────────────────────────────────────────────────────

    private fun parseV4(buf: ByteArray, len: Int): ParsedPacket? {
        if (len < 20) return null
        val ihl      = (buf[0].toInt() and 0x0F) * 4          // IP header length
        val totalLen = buf.readUShort(2)
        val proto    = buf[9].toInt() and 0xFF
        val srcIp    = buf.readIp4(12)
        val dstIp    = buf.readIp4(16)
        if (len < ihl) return null
        return parseTcpUdp(buf, len, 4, proto, srcIp, dstIp, ihl, totalLen)
    }

    // ── IPv6 ─────────────────────────────────────────────────────────────────

    private fun parseV6(buf: ByteArray, len: Int): ParsedPacket? {
        if (len < 40) return null
        val payloadLen = buf.readUShort(4)
        val nextHdr    = buf[6].toInt() and 0xFF
        val srcIp      = buf.readIp6(8)
        val dstIp      = buf.readIp6(24)
        val ihl        = 40 // fixed IPv6 header; extension headers are ignored
        val totalLen   = ihl + payloadLen
        return parseTcpUdp(buf, len, 6, nextHdr, srcIp, dstIp, ihl, totalLen)
    }

    // ── Transport ─────────────────────────────────────────────────────────────

    private fun parseTcpUdp(
        buf: ByteArray, len: Int,
        ipVer: Int, proto: Int,
        srcIp: String, dstIp: String,
        ihl: Int, totalLen: Int,
    ): ParsedPacket {
        if (len < ihl + 4) {
            return ParsedPacket(ipVer, Protocol.OTHER, srcIp, dstIp, 0, 0, ihl, 0, 0, totalLen)
        }
        val srcPort = buf.readUShort(ihl)
        val dstPort = buf.readUShort(ihl + 2)

        return when (proto) {
            6 -> {  // TCP
                val dataOffset   = if (len >= ihl + 13) ((buf[ihl + 12].toInt() and 0xFF) ushr 4) * 4 else 20
                val payloadStart = ihl + dataOffset
                val tcpSeq       = if (len >= ihl + 8)  buf.readUInt32(ihl + 4) else 0L
                val tcpFlags     = if (len >= ihl + 14) buf[ihl + 13].toInt() and 0xFF else 0
                ParsedPacket(ipVer, Protocol.TCP, srcIp, dstIp, srcPort, dstPort,
                    ihl, dataOffset, maxOf(0, totalLen - payloadStart), totalLen,
                    tcpSeq = tcpSeq, tcpFlags = tcpFlags)
            }
            17 -> { // UDP: 8-byte fixed header
                ParsedPacket(ipVer, Protocol.UDP, srcIp, dstIp, srcPort, dstPort,
                    ihl, 8, maxOf(0, totalLen - ihl - 8), totalLen)
            }
            else -> {
                ParsedPacket(ipVer, Protocol.OTHER, srcIp, dstIp, srcPort, dstPort,
                    ihl, 0, 0, totalLen)
            }
        }
    }

    // ── ByteArray helpers ─────────────────────────────────────────────────────

    private fun ByteArray.readUShort(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

    private fun ByteArray.readUInt32(offset: Int): Long =
        ((this[offset].toLong()     and 0xFF) shl 24) or
        ((this[offset + 1].toLong() and 0xFF) shl 16) or
        ((this[offset + 2].toLong() and 0xFF) shl 8)  or
         (this[offset + 3].toLong() and 0xFF)

    private fun ByteArray.readIp4(offset: Int): String =
        InetAddress.getByAddress(copyOfRange(offset, offset + 4)).hostAddress ?: "0.0.0.0"

    private fun ByteArray.readIp6(offset: Int): String =
        InetAddress.getByAddress(copyOfRange(offset, offset + 16)).hostAddress ?: "::"
}
