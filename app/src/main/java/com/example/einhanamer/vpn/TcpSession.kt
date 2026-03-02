package com.example.einhanamer.vpn

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "TcpSession"

/**
 * Manages a single TCP flow through the local VPN.
 *
 * On SYN: sends SYN-ACK to the app via the TUN fd, opens a protected socket
 * to the real destination, and splices data in both directions.
 *
 * Sequence-number accounting uses unsigned 32-bit arithmetic (Long & 0xFFFFFFFFL).
 * All mutable state is guarded by [this] to avoid races between the packet-in
 * handler ([onPacket]) and the upstream-read coroutine ([relayUpstreamToTun]).
 */
class TcpSession(
    private val ipVersion: Int,
    private val srcIp: String,
    private val srcPort: Int,
    private val dstIp: String,
    private val dstPort: Int,
    clientIsn: Long,
    private val tunOut: OutputStream,
    private val vpnService: VpnService,
    private val scope: CoroutineScope,
    private val onClosed: () -> Unit,
) {
    // Next seq we expect from the client (SYN consumed the ISN so we start at ISN+1)
    private var clientSeq = inc32(clientIsn)

    // Our current send seq (the SYN-ACK will use this value, then we increment)
    private var serverSeq = 0x01020304L

    private val socket    = Socket()
    private val pending   = ArrayDeque<ByteArray>()  // data buffered before upstream connects
    private var pendingBytes = 0

    private enum class State { HALF_OPEN, ESTABLISHED, CLOSING, CLOSED }
    private var state = State.HALF_OPEN

    // ── Entry points ──────────────────────────────────────────────────────────

    /** Called once after the session is registered. Sends SYN-ACK and starts upstream connect. */
    fun start() {
        vpnService.protect(socket)
        synchronized(this) { sendSynAck() }
        scope.launch(Dispatchers.IO) { connectUpstream() }
    }

    /**
     * Called for every subsequent TCP packet belonging to this flow.
     * Must be non-blocking — heavy work is dispatched to IO coroutines.
     */
    fun onPacket(packet: ParsedPacket, raw: ByteArray) {
        val flags = packet.tcpFlags
        val isRst = (flags and 0x04) != 0
        val isFin = (flags and 0x01) != 0

        if (isRst) { close(); return }

        val payloadStart = packet.ipHeaderLen + packet.transportHeaderLen
        val payloadLen   = packet.payloadLen
        val payload = if (payloadLen > 0)
            raw.copyOfRange(payloadStart, payloadStart + payloadLen)
        else
            ByteArray(0)

        synchronized(this) {
            when (state) {
                State.HALF_OPEN -> {
                    // Buffer data and ACK it so the client doesn't stall waiting for an ACK.
                    if (payload.isNotEmpty() && pendingBytes < MAX_PENDING_BYTES) {
                        pending.add(payload)
                        pendingBytes += payload.size
                    }
                    clientSeq = seq32Add(clientSeq, payloadLen.toLong())
                    if (payload.isNotEmpty()) sendAck()
                    if (isFin) {
                        clientSeq = inc32(clientSeq)
                        state = State.CLOSING
                    }
                }

                State.ESTABLISHED -> {
                    if (payload.isNotEmpty()) {
                        clientSeq = seq32Add(clientSeq, payloadLen.toLong())
                        val data = payload  // captured for coroutine
                        scope.launch(Dispatchers.IO) { writeUpstream(data) }
                        sendAck()
                    }
                    if (isFin) {
                        clientSeq = inc32(clientSeq)
                        state = State.CLOSING
                        scope.launch(Dispatchers.IO) {
                            runCatching { socket.shutdownOutput() }
                            synchronized(this@TcpSession) { sendFinAck() }
                        }
                    }
                }

                State.CLOSING, State.CLOSED -> Unit
            }
        }
    }

    fun close() {
        synchronized(this) {
            if (state == State.CLOSED) return
            state = State.CLOSED
        }
        runCatching { socket.close() }
        onClosed()
    }

    // ── Upstream ──────────────────────────────────────────────────────────────

    private suspend fun connectUpstream() {
        try {
            socket.connect(InetSocketAddress(InetAddress.getByName(dstIp), dstPort), 15_000)

            // Flush anything buffered during HALF_OPEN, then start relay.
            val toFlush: List<ByteArray>
            synchronized(this) {
                state = State.ESTABLISHED
                toFlush = pending.toList()
                pending.clear()
                pendingBytes = 0
            }
            for (data in toFlush) writeUpstream(data)

            relayUpstreamToTun()
        } catch (e: Exception) {
            Log.w(TAG, "Upstream connect failed $dstIp:$dstPort — ${e.message}")
            synchronized(this) { sendRst() }
            close()
        }
    }

    private fun writeUpstream(data: ByteArray) {
        try {
            socket.outputStream.write(data)
            socket.outputStream.flush()
        } catch (e: Exception) {
            if (state != State.CLOSING && state != State.CLOSED) {
                Log.d(TAG, "Upstream write error: ${e.message}")
                synchronized(this) { sendRst() }
                close()
            }
        }
    }

    private suspend fun relayUpstreamToTun() = withContext(Dispatchers.IO) {
        val buf = ByteArray(8192)
        try {
            while (isActive && state != State.CLOSED) {
                val n = socket.inputStream.read(buf)
                if (n < 0) break
                if (n > 0) {
                    // Split into MSS-sized segments so no single packet exceeds the TUN MTU.
                    // Without this, large server responses (e.g. TLS certificates) create
                    // oversized IP packets that the kernel drops because DF=1 is set.
                    var offset = 0
                    while (offset < n) {
                        val chunk = buf.copyOfRange(offset, minOf(offset + MSS, n))
                        synchronized(this@TcpSession) { sendData(chunk) }
                        offset += chunk.size
                    }
                }
            }
        } catch (e: Exception) {
            if (state != State.CLOSING && state != State.CLOSED)
                Log.d(TAG, "Upstream read ended ($dstIp:$dstPort): ${e.message}")
        }

        synchronized(this@TcpSession) {
            if (state == State.ESTABLISHED) {
                state = State.CLOSING
                sendFinAck()
            }
        }
        close()
    }

    // ── Packet senders (must be called from within synchronized(this)) ────────

    private fun sendSynAck() {
        sendPkt(0x12, ByteArray(0))      // SYN|ACK
        serverSeq = inc32(serverSeq)     // SYN consumes one sequence number
    }

    private fun sendAck() = sendPkt(0x10, ByteArray(0))

    private fun sendData(data: ByteArray) {
        sendPkt(0x18, data)              // PSH|ACK
        serverSeq = seq32Add(serverSeq, data.size.toLong())
    }

    private fun sendFinAck() {
        sendPkt(0x11, ByteArray(0))      // FIN|ACK
        serverSeq = inc32(serverSeq)     // FIN consumes one sequence number
    }

    private fun sendRst() = sendPkt(0x04, ByteArray(0))

    private fun sendPkt(flags: Int, payload: ByteArray) {
        try {
            val pkt = PacketBuilder.buildTcpPacket(
                ipVersion = ipVersion,
                srcIp     = dstIp,  srcPort = dstPort,  // reversed: reply goes back to app
                dstIp     = srcIp,  dstPort = srcPort,
                seq       = serverSeq,
                ack       = clientSeq,
                flags     = flags,
                payload   = payload,
            )
            // tunOut is shared across all sessions; synchronize only the write itself.
            synchronized(tunOut) { tunOut.write(pkt) }
        } catch (e: Exception) {
            Log.w(TAG, "TUN write failed: ${e.message}")
        }
    }

    // ── Seq number helpers ────────────────────────────────────────────────────

    private fun inc32(n: Long)                 = (n + 1L)     and 0xFFFFFFFFL
    private fun seq32Add(n: Long, delta: Long) = (n + delta)  and 0xFFFFFFFFL

    companion object {
        private const val MAX_PENDING_BYTES = 65_536
        // Maximum TCP payload per segment: MTU(1500) - IPv6 header(40) - TCP header(20).
        // Using the IPv6 value (1440) is safe for both IPv4 and IPv6.
        private const val MSS = 1440
    }
}
