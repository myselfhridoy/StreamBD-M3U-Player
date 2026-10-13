package com.streambd.iptv.player

/** App-level aspect mode, mapped to PlayerView resize modes. */
enum class AspectMode(val label: String, val description: String) {
    ORIGINAL("Original", "Keep original aspect ratio"),
    STRETCH ("Stretch",  "Stretch video to fit display"),
    ZOOM    ("Zoom",     "Increase width or height to match aspect ratio"),
}
