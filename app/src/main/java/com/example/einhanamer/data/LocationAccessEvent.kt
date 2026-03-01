package com.example.einhanamer.data

data class LocationAccessEvent(
    val timestampMs: Long,
    val packageName: String?,   // null when attribution is unavailable
    val uid:         Int,
    val isActive:    Boolean,   // true = location access started, false = stopped
)
