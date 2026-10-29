package com.streambd.iptv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.player.AspectMode
import com.streambd.iptv.player.PlayerUiState
import com.streambd.iptv.player.TrackChoice
import com.streambd.iptv.ui.components.ChannelActionsDialog
import com.streambd.iptv.ui.components.PinDialog

/** প্লেয়ার স্ক্রিনের ডায়ালগ খোলা/বন্ধের অবস্থা। Live ও Movies/Series একই জিনিস ব্যবহার করে। */
@Stable
class PlayerDialogsState {
    var tracksOpen by mutableStateOf(false)
    var aspectOpen by mutableStateOf(false)
    var actionsItemId by mutableStateOf<Long?>(null)

    fun openTracks() { tracksOpen = true }
    fun openAspect() { aspectOpen = true }
    fun openActions(id: Long) { actionsItemId = id }
}

@Composable
fun rememberPlayerDialogs(): PlayerDialogsState = remember { PlayerDialogsState() }

/**
 * Track, Aspect, PIN ও ChannelActions ডায়ালগ। স্ক্রিনের শেষে একবার ডাকলেই হয়।
 *
 * @param items "Long-press → Actions"-এর জন্য চ্যানেল খোঁজার তালিকা
 * @param pendingUnlock PIN-এর অপেক্ষায় থাকা লক করা আইটেম (null হলে PIN ডায়ালগ নেই)
 */
@Composable
fun PlayerDialogs(
    dialogs: PlayerDialogsState,
    state: PlayerUiState,
    items: List<ChannelEntity>,
    pendingUnlock: ChannelEntity?,
    verifyPin: suspend (String) -> Boolean,
    onUnlocked: () -> Unit,
    onDismissUnlock: () -> Unit,
    onApplyTracks: (video: TrackChoice, audio: TrackChoice) -> Unit,
    onAspectChange: (AspectMode) -> Unit,
    onToggleFavorite: (ChannelEntity) -> Unit,
    onToggleLock: (ChannelEntity) -> Unit,
) {
    if (dialogs.tracksOpen) {
        TrackSelectionDialog(
            info = state.tracks,
            onConfirm = { video, audio ->
                onApplyTracks(video, audio)
                dialogs.tracksOpen = false
            },
            onDismiss = { dialogs.tracksOpen = false },
        )
    }

    if (dialogs.aspectOpen) {
        PlayerSettingsDialog(
            currentAspect = state.aspectMode,
            onAspectChange = onAspectChange,
            onDismiss = { dialogs.aspectOpen = false },
        )
    }

    if (pendingUnlock != null) {
        PinDialog(
            title = "Enter PIN",
            verify = verifyPin,
            onSuccess = onUnlocked,
            onDismiss = onDismissUnlock,
        )
    }

    val actionsItem = dialogs.actionsItemId?.let { id -> items.firstOrNull { it.id == id } }
    if (actionsItem != null) {
        ChannelActionsDialog(
            channel = actionsItem,
            onToggleFavorite = { onToggleFavorite(actionsItem) },
            onToggleLock = { onToggleLock(actionsItem) },
            onDismiss = { dialogs.actionsItemId = null },
        )
    }
}
