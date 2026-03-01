package com.example.einhanamer.data

data class CameraAccessEvent(
    val timestampMs: Long,
    val packageName: String?,   // null when attribution is unavailable
    val uid:         Int,
    val isActive:    Boolean,   // true = camera opened, false = camera released
)
