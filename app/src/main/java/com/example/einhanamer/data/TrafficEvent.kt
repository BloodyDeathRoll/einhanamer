package com.example.einhanamer.data

data class TrafficEvent(
    val timestampMs: Long = System.currentTimeMillis(),
    val uid: Int,
    val packageName: String?,       // null if UID could not be resolved
    val protocol: Protocol,
    val srcIp: String,
    val dstIp: String,
    val srcPort: Int,
    val dstPort: Int,
    val byteCount: Int,
    val isMeta: Boolean,
    val isBackground: Boolean = false,  // true when the app was not in the foreground at packet time
)

enum class Protocol { TCP, UDP, OTHER }
