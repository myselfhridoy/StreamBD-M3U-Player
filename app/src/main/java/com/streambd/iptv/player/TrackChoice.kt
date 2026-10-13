package com.streambd.iptv.player

import androidx.media3.common.Tracks

/** A choice in the track-selection dialog. */
sealed interface TrackChoice {
    data object None : TrackChoice
    data object Auto : TrackChoice
    data class Specific(val group: Tracks.Group, val index: Int) : TrackChoice
}

/** Snapshot of the tracks currently available on the player. */
data class TrackInfo(
    val videoGroups: List<Tracks.Group>,
    val audioGroups: List<Tracks.Group>,
) {
    companion object {
        fun empty() = TrackInfo(emptyList(), emptyList())
    }
}
