package com.example.einhanamer.monitor

object MetaApps {

    /** All known package names shipped by Meta Platforms. */
    val PACKAGES: Set<String> = setOf(
        // Core social
        "com.facebook.katana",           // Facebook
        "com.facebook.lite",             // Facebook Lite
        "com.facebook.orca",             // Messenger
        "com.messenger.lite",            // Messenger Lite
        "com.facebook.mlite",            // Messenger Lite (alt id)
        "com.instagram.android",         // Instagram
        "com.instagram.barcelona",       // Threads
        "com.whatsapp",                  // WhatsApp
        "com.whatsapp.w4b",              // WhatsApp Business
        // Utility / background
        "com.facebook.appmanager",       // Meta App Manager (system helper)
        "com.facebook.services",         // Meta Services / App Installer
        "com.facebook.system",           // Meta System Helper
        "com.facebook.katana.stub",      // Facebook stub
        // Business / creator
        "com.facebook.pages.app",        // Meta Business Suite
        "com.facebook.groups",           // Facebook Groups
        "com.facebook.work",             // Workplace from Meta
        "com.facebook.ads.controllercenter", // Audience Network
        // Shopping
        "com.facebook.marketplace",      // Facebook Marketplace
        // VR / AR
        "com.oculus.browser",            // Meta Quest Browser
        "com.oculus.vrshell",            // Meta Quest shell
        "com.oculus.mobile.fetch",       // Oculus data fetch
        "com.meta.spatial.conductor",    // Meta Spatial
        "com.oculus.companion.app",      // Quest companion
        "com.oculus.platform",           // Oculus platform service
    )

    /**
     * Known Meta-owned hostnames (for cross-referencing against DNS queries
     * parsed from UDP port 53 or DoH traffic in Phase 2).
     */
    val DOMAINS: Set<String> = setOf(
        "facebook.com",
        "fbcdn.net",
        "fbsbx.com",
        "fb.com",
        "fb.me",
        "instagram.com",
        "cdninstagram.com",
        "whatsapp.com",
        "whatsapp.net",
        "oculus.com",
        "meta.com",
        "metacdn.com",
        "graph.facebook.com",
        "edge-mqtt.facebook.com",
        "connect.facebook.net",
        "atdmt.com",       // Atlas ad tracker
    )

    fun isDomain(host: String): Boolean =
        DOMAINS.any { host == it || host.endsWith(".$it") }
}
