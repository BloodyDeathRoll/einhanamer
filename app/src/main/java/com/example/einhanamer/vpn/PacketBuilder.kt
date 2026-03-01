package com.example.einhanamer.vpn

import java.net.InetAddress

/**
 * Builds raw IP+TCP packets to write back into the TUN file descriptor.
 * Supports both IPv4 and IPv6.
 * TCP header is always 20 bytes (no options).
 */
object PacketBuilder {

    fun buildUdpPacket(
        ipVersion: Int,
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        payload: ByteArray,
    ): ByteArray = when (ipVersion) {
        4    -> buildUdp4(srcIp, srcPort, dstIp, dstPort, payload)
        6    -> buildUdp6(srcIp, srcPort, dstIp, dstPort, payload)
        else -> throw IllegalArgumentException("Unsupported IP version: $ipVersion")
    }

    private fun buildUdp4(
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val ipHdr  = 20
        val udpHdr = 8
        val total  = ipHdr + udpHdr + payload.size
        val buf    = ByteArray(total)
        val src    = InetAddress.getByName(srcIp).address
        val dst    = InetAddress.getByName(dstIp).address

        buf[0] = 0x45.toByte(); buf[1] = 0
        buf[2] = (total ushr 8).toByte(); buf[3] = (total and 0xFF).toByte()
        buf[4] = 0; buf[5] = 0
        buf[6] = 0x40.toByte(); buf[7] = 0   // don't fragment
        buf[8] = 64; buf[9] = 17             // TTL=64, protocol=UDP
        src.copyInto(buf, 12); dst.copyInto(buf, 16)
        val ipCk = checksum(buf, 0, ipHdr)
        buf[10] = (ipCk ushr 8).toByte(); buf[11] = (ipCk and 0xFF).toByte()

        writeUdpHeader(buf, ipHdr, srcPort, dstPort, payload)
        val udpLen = udpHdr + payload.size
        val udpCk  = udpChecksumV4(src, dst, buf, ipHdr, udpLen)
        buf[ipHdr + 6] = (udpCk ushr 8).toByte()
        buf[ipHdr + 7] = (udpCk and 0xFF).toByte()
        return buf
    }

    private fun buildUdp6(
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val ipHdr      = 40
        val udpHdr     = 8
        val udpPayload = udpHdr + payload.size
        val total      = ipHdr + udpPayload
        val buf        = ByteArray(total)
        val src        = InetAddress.getByName(srcIp).address
        val dst        = InetAddress.getByName(dstIp).address

        buf[0] = 0x60.toByte(); buf[1] = 0; buf[2] = 0; buf[3] = 0
        buf[4] = (udpPayload ushr 8).toByte(); buf[5] = (udpPayload and 0xFF).toByte()
        buf[6] = 17; buf[7] = 64             // next header=UDP, hop limit=64
        src.copyInto(buf, 8); dst.copyInto(buf, 24)

        writeUdpHeader(buf, ipHdr, srcPort, dstPort, payload)
        val udpCk = udpChecksumV6(src, dst, buf, ipHdr, udpPayload)
        buf[ipHdr + 6] = (udpCk ushr 8).toByte()
        buf[ipHdr + 7] = (udpCk and 0xFF).toByte()
        return buf
    }

    private fun writeUdpHeader(buf: ByteArray, off: Int, srcPort: Int, dstPort: Int, payload: ByteArray) {
        val len = 8 + payload.size
        buf[off + 0] = (srcPort ushr 8).toByte(); buf[off + 1] = (srcPort and 0xFF).toByte()
        buf[off + 2] = (dstPort ushr 8).toByte(); buf[off + 3] = (dstPort and 0xFF).toByte()
        buf[off + 4] = (len ushr 8).toByte();     buf[off + 5] = (len and 0xFF).toByte()
        buf[off + 6] = 0; buf[off + 7] = 0        // checksum placeholder
        if (payload.isNotEmpty()) payload.copyInto(buf, off + 8)
    }

    private fun udpChecksumV4(
        srcIp: ByteArray, dstIp: ByteArray,
        buf: ByteArray, udpOff: Int, udpLen: Int,
    ): Int {
        var sum = 0
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += 17; sum += udpLen
        sum += checksumSum(buf, udpOff, udpLen)
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    private fun udpChecksumV6(
        srcIp: ByteArray, dstIp: ByteArray,
        buf: ByteArray, udpOff: Int, udpLen: Int,
    ): Int {
        var sum = 0
        for (i in 0 until 16 step 2)
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
        for (i in 0 until 16 step 2)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        sum += (udpLen ushr 16) and 0xFFFF; sum += udpLen and 0xFFFF
        sum += 17
        sum += checksumSum(buf, udpOff, udpLen)
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    fun buildTcpPacket(
        ipVersion: Int,
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        seq: Long, ack: Long,
        flags: Int,
        payload: ByteArray,
    ): ByteArray = when (ipVersion) {
        4    -> buildTcp4(srcIp, srcPort, dstIp, dstPort, seq, ack, flags, payload)
        6    -> buildTcp6(srcIp, srcPort, dstIp, dstPort, seq, ack, flags, payload)
        else -> throw IllegalArgumentException("Unsupported IP version: $ipVersion")
    }

    // ── IPv4 ─────────────────────────────────────────────────────────────────

    private fun buildTcp4(
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        seq: Long, ack: Long,
        flags: Int, payload: ByteArray,
    ): ByteArray {
        val ipHdr = 20
        val tcpHdr = 20
        val total = ipHdr + tcpHdr + payload.size
        val buf = ByteArray(total)
        val src = InetAddress.getByName(srcIp).address
        val dst = InetAddress.getByName(dstIp).address

        buf[0]  = 0x45.toByte()                 // version=4, IHL=5
        buf[1]  = 0
        buf[2]  = (total ushr 8).toByte()
        buf[3]  = (total and 0xFF).toByte()
        buf[4]  = 0; buf[5] = 0                 // identification
        buf[6]  = 0x40.toByte()                 // don't fragment
        buf[7]  = 0
        buf[8]  = 64                            // TTL
        buf[9]  = 6                             // protocol = TCP
        // checksum [10-11] filled below
        src.copyInto(buf, 12)
        dst.copyInto(buf, 16)
        val ipCk = checksum(buf, 0, ipHdr)
        buf[10] = (ipCk ushr 8).toByte()
        buf[11] = (ipCk and 0xFF).toByte()

        writeTcpHeader(buf, ipHdr, srcPort, dstPort, seq, ack, flags, payload)
        val tcpLen = tcpHdr + payload.size
        val tcpCk  = tcpChecksumV4(src, dst, buf, ipHdr, tcpLen)
        buf[ipHdr + 16] = (tcpCk ushr 8).toByte()
        buf[ipHdr + 17] = (tcpCk and 0xFF).toByte()
        return buf
    }

    // ── IPv6 ─────────────────────────────────────────────────────────────────

    private fun buildTcp6(
        srcIp: String, srcPort: Int,
        dstIp: String, dstPort: Int,
        seq: Long, ack: Long,
        flags: Int, payload: ByteArray,
    ): ByteArray {
        val ipHdr = 40
        val tcpHdr = 20
        val tcpPayloadLen = tcpHdr + payload.size
        val total = ipHdr + tcpPayloadLen
        val buf = ByteArray(total)
        val src = InetAddress.getByName(srcIp).address
        val dst = InetAddress.getByName(dstIp).address

        buf[0] = 0x60.toByte()                  // version=6, traffic class hi
        buf[1] = 0; buf[2] = 0; buf[3] = 0     // traffic class lo + flow label
        buf[4] = (tcpPayloadLen ushr 8).toByte()
        buf[5] = (tcpPayloadLen and 0xFF).toByte()
        buf[6] = 6                              // next header = TCP
        buf[7] = 64                             // hop limit
        src.copyInto(buf, 8)
        dst.copyInto(buf, 24)

        writeTcpHeader(buf, ipHdr, srcPort, dstPort, seq, ack, flags, payload)
        val tcpCk = tcpChecksumV6(src, dst, buf, ipHdr, tcpPayloadLen)
        buf[ipHdr + 16] = (tcpCk ushr 8).toByte()
        buf[ipHdr + 17] = (tcpCk and 0xFF).toByte()
        return buf
    }

    // ── TCP header ────────────────────────────────────────────────────────────

    private fun writeTcpHeader(
        buf: ByteArray, off: Int,
        srcPort: Int, dstPort: Int,
        seq: Long, ack: Long,
        flags: Int, payload: ByteArray,
    ) {
        buf[off + 0]  = (srcPort ushr 8).toByte()
        buf[off + 1]  = (srcPort and 0xFF).toByte()
        buf[off + 2]  = (dstPort ushr 8).toByte()
        buf[off + 3]  = (dstPort and 0xFF).toByte()
        buf[off + 4]  = (seq ushr 24 and 0xFF).toByte()
        buf[off + 5]  = (seq ushr 16 and 0xFF).toByte()
        buf[off + 6]  = (seq ushr  8 and 0xFF).toByte()
        buf[off + 7]  = (seq         and 0xFF).toByte()
        buf[off + 8]  = (ack ushr 24 and 0xFF).toByte()
        buf[off + 9]  = (ack ushr 16 and 0xFF).toByte()
        buf[off + 10] = (ack ushr  8 and 0xFF).toByte()
        buf[off + 11] = (ack         and 0xFF).toByte()
        buf[off + 12] = 0x50.toByte()           // data offset = 5 (20 bytes), no options
        buf[off + 13] = flags.toByte()
        buf[off + 14] = 0xFF.toByte()           // window = 65535
        buf[off + 15] = 0xFF.toByte()
        buf[off + 16] = 0                       // checksum placeholder
        buf[off + 17] = 0
        buf[off + 18] = 0                       // urgent pointer
        buf[off + 19] = 0
        if (payload.isNotEmpty()) payload.copyInto(buf, off + 20)
    }

    // ── Checksum helpers ──────────────────────────────────────────────────────

    /** One's-complement 16-bit checksum. */
    private fun checksum(buf: ByteArray, offset: Int, len: Int): Int {
        var sum = checksumSum(buf, offset, len)
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    private fun tcpChecksumV4(
        srcIp: ByteArray, dstIp: ByteArray,
        buf: ByteArray, tcpOff: Int, tcpLen: Int,
    ): Int {
        var sum = 0
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += 6        // protocol = TCP
        sum += tcpLen
        sum += checksumSum(buf, tcpOff, tcpLen)
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    private fun tcpChecksumV6(
        srcIp: ByteArray, dstIp: ByteArray,
        buf: ByteArray, tcpOff: Int, tcpLen: Int,
    ): Int {
        var sum = 0
        for (i in 0 until 16 step 2)
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
        for (i in 0 until 16 step 2)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        sum += (tcpLen ushr 16) and 0xFFFF
        sum += tcpLen and 0xFFFF
        sum += 6        // next header = TCP
        sum += checksumSum(buf, tcpOff, tcpLen)
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    private fun checksumSum(buf: ByteArray, offset: Int, len: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + len - 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (len and 1 != 0) sum += (buf[offset + len - 1].toInt() and 0xFF) shl 8
        return sum
    }
}
