package com.example.einhanamer.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-memory singleton event bus. Replace with Room + Flow in Phase 2.
 * Thread-safe: SharedFlow emission is concurrent-safe; the deque accesses
 * are guarded by @Synchronized.
 */
object EventLog {

    private const val TRAFFIC_CACHE  = 500
    private const val MIC_CACHE      = 100
    private const val CAMERA_CACHE   = 100
    private const val LOCATION_CACHE = 100

    // ── Traffic ──────────────────────────────────────────────────────────────
    private val _traffic = MutableSharedFlow<TrafficEvent>(extraBufferCapacity = 256)
    val traffic = _traffic.asSharedFlow()

    private val trafficCache = ArrayDeque<TrafficEvent>(TRAFFIC_CACHE)

    @Synchronized
    fun logTraffic(event: TrafficEvent) {
        if (trafficCache.size >= TRAFFIC_CACHE) trafficCache.removeFirst()
        trafficCache.addLast(event)
        _traffic.tryEmit(event)
    }

    @Synchronized
    fun recentTraffic(): List<TrafficEvent> = trafficCache.toList()

    // ── Mic ──────────────────────────────────────────────────────────────────
    private val _mic = MutableSharedFlow<MicAccessEvent>(extraBufferCapacity = 64)
    val mic = _mic.asSharedFlow()

    private val micCache = ArrayDeque<MicAccessEvent>(MIC_CACHE)

    @Synchronized
    fun logMic(event: MicAccessEvent) {
        if (micCache.size >= MIC_CACHE) micCache.removeFirst()
        micCache.addLast(event)
        _mic.tryEmit(event)
    }

    @Synchronized
    fun recentMic(): List<MicAccessEvent> = micCache.toList()

    // ── Camera ───────────────────────────────────────────────────────────────
    private val _camera = MutableSharedFlow<CameraAccessEvent>(extraBufferCapacity = 64)
    val camera = _camera.asSharedFlow()

    private val cameraCache = ArrayDeque<CameraAccessEvent>(CAMERA_CACHE)

    @Synchronized
    fun logCamera(event: CameraAccessEvent) {
        if (cameraCache.size >= CAMERA_CACHE) cameraCache.removeFirst()
        cameraCache.addLast(event)
        _camera.tryEmit(event)
    }

    @Synchronized
    fun recentCamera(): List<CameraAccessEvent> = cameraCache.toList()

    // ── Location ─────────────────────────────────────────────────────────────
    private val _location = MutableSharedFlow<LocationAccessEvent>(extraBufferCapacity = 64)
    val location = _location.asSharedFlow()

    private val locationCache = ArrayDeque<LocationAccessEvent>(LOCATION_CACHE)

    @Synchronized
    fun logLocation(event: LocationAccessEvent) {
        if (locationCache.size >= LOCATION_CACHE) locationCache.removeFirst()
        locationCache.addLast(event)
        _location.tryEmit(event)
    }

    @Synchronized
    fun recentLocation(): List<LocationAccessEvent> = locationCache.toList()
}
