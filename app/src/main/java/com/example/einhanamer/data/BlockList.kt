package com.example.einhanamer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory runtime block list. Cleared when the process exits.
 * The VPN service reads [blocked] and [blockedStreams] on every packet.
 *
 * App-level block:    block("com.instagram.android")          → drops ALL traffic for that app
 * Stream-level block: blockStream("com.instagram.android", 53) → drops only DNS traffic for that app
 */
object BlockList {

    // ── App-level blocking ────────────────────────────────────────────────────

    private val _blocked = MutableStateFlow<Set<String>>(emptySet())
    val blocked: StateFlow<Set<String>> = _blocked.asStateFlow()

    fun block(packageName: String) {
        _blocked.value = _blocked.value + packageName
    }

    fun unblock(packageName: String) {
        _blocked.value = _blocked.value - packageName
    }

    fun isBlocked(packageName: String): Boolean = packageName in _blocked.value

    // ── Stream-level blocking  (key = "packageName:port") ────────────────────

    private val _blockedStreams = MutableStateFlow<Set<String>>(emptySet())
    val blockedStreams: StateFlow<Set<String>> = _blockedStreams.asStateFlow()

    fun blockStream(packageName: String, port: Int) {
        _blockedStreams.value = _blockedStreams.value + streamKey(packageName, port)
    }

    fun unblockStream(packageName: String, port: Int) {
        _blockedStreams.value = _blockedStreams.value - streamKey(packageName, port)
    }

    fun isStreamBlocked(packageName: String, port: Int): Boolean =
        streamKey(packageName, port) in _blockedStreams.value

    private fun streamKey(packageName: String, port: Int) = "$packageName:$port"
}
