package com.streambd.iptv.data.activation

data class PlaylistData(
    val name: String,
    val m3uUrl: String,
    val epgUrl: String
)

sealed interface ActivationState {
    data object NotActivated : ActivationState

    data class Active(
        val code: String,
        val expiresAt: Long,
        val playlists: List<PlaylistData>
    ) : ActivationState
}
