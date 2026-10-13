package com.streambd.iptv.data.activation

sealed interface ActivationState {
    data object NotActivated : ActivationState

    data class Active(
        val code: String,
        val expiresAt: Long,
        val playlistUrl: String,
        val epgUrl: String,
    ) : ActivationState
}