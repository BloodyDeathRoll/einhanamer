package com.example.einhanamer.data

data class MicAccessEvent(
    val timestampMs: Long = System.currentTimeMillis(),
    val packageName: String,
    val uid: Int,
    val isMeta: Boolean,
    /** true = mic became active, false = mic became idle */
    val isActive: Boolean,
)
