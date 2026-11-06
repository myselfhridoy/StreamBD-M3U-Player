package com.streambd.iptv.ui.screens.resume

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import com.streambd.iptv.data.activation.ActivationRepository
import com.streambd.iptv.data.activation.ActivationState
import com.streambd.iptv.data.db.ChannelDao
import com.streambd.iptv.data.db.ChannelEntity
import com.streambd.iptv.data.db.WatchProgressEntity
import com.streambd.iptv.data.ftp.FtpBrowser
import com.streambd.iptv.data.ftp.FtpEntry
import com.streambd.iptv.data.repo.WatchProgressRepository
import com.streambd.iptv.data.settings.SettingsStore
import com.streambd.iptv.player.ExternalSubtitle
import com.streambd.iptv.player.LockController
import com.streambd.iptv.player.PlayerManager
import com.streambd.iptv.player.PlayerUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** [channel] না থাকলে এটা FTP ফাইল (ডেটাবেসে চ্যানেল হিসেবে নেই)। */
data class ContinueItem(val entry: WatchProgressEntity, val channel: ChannelEntity?) {
    val isFtp: Boolean get() = channel == null
}

@HiltViewModel
class ContinueViewModel @Inject constructor(
    private val progress: WatchProgressRepository,
    private val channels: ChannelDao,
    private val browser: FtpBrowser,
    private val playerManager: PlayerManager,
    private val lockController: LockController,
    private val settingsStore: SettingsStore,
    private val activation: ActivationRepository,
) : ViewModel() {

    /** null = এখনো লোড হচ্ছে। */
    val items: StateFlow<List<ContinueItem>?> = progress.observe()
        .map { list -> list.map { ContinueItem(it, channels.getById(it.id)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _playing = MutableStateFlow<ContinueItem?>(null)
    val playing: StateFlow<ContinueItem?> = _playing.asStateFlow()

    val playerState: StateFlow<PlayerUiState> = playerManager.state
    val sessionUnlocked: StateFlow<Boolean> = lockController.unlocked
    val player get() = playerManager

    fun play(item: ContinueItem, fromStart: Boolean = false) {
        viewModelScope.launch {
            val s = activation.current()
            if (!(s is ActivationState.Active && !activation.isExpired(s))) return@launch

            val channel = item.channel ?: ftpChannel(item.entry)
            val resume: Long? = if (fromStart) {
                progress.remove(item.entry.id)
                0L
            } else {
                progress.resumePositionFor(item.entry.id)
            }
            val subs = if (item.isFtp) sidecarSubtitles(item.entry.url) else emptyList()

            _playing.value = item
            playerManager.play(channel, resumeAtMs = resume ?: C.TIME_UNSET, subtitles = subs)
        }
    }

    fun unlockAndPlay(item: ContinueItem) {
        lockController.unlock()
        play(item)
    }

    fun remove(item: ContinueItem) {
        viewModelScope.launch { progress.remove(item.entry.id) }
    }

    fun closePlayer() {
        playerManager.stopAndClear()
        _playing.value = null
    }

    suspend fun verifyPin(pin: String): Boolean = settingsStore.verifyPin(pin)

    /** FtpViewModel.toChannel-এর মতোই (id একই থাকে, তাই অগ্রগতি মেলে)। */
    private fun ftpChannel(e: WatchProgressEntity) = ChannelEntity(
        id = e.id,
        number = 0,
        name = e.title,
        logoUrl = e.logoUrl,
        streamUrl = e.url,
        groupId = 0L,
        tvgId = null,
        kind = "MOVIE",
        providerId = e.serverId,
    )

    /** ভিডিওর ফোল্ডার খুলে পাশের সাবটাইটেল খোঁজে (সর্বোচ্চ ৫ সেকেন্ড অপেক্ষা)। */
    private suspend fun sidecarSubtitles(videoUrl: String): List<ExternalSubtitle> {
        val folder = videoUrl.substringBeforeLast('/', "")
        if (folder.isEmpty()) return emptyList()
        val all: List<FtpEntry> = try {
            withTimeoutOrNull(5_000L) { browser.list("$folder/", false) } ?: return emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyList()
        }
        val subs = all.filter { it.isSubtitle }
        if (subs.isEmpty()) return emptyList()
        val video = all.firstOrNull { it.isVideo && it.url == videoUrl } ?: return emptyList()

        val base = video.name.substringBeforeLast('.')
        val otherBases = all.filter { it.isVideo && it.url != video.url }
            .map { it.name.substringBeforeLast('.') }
            .filter { it.length > base.length && it.startsWith(base, ignoreCase = true) }
        return subs.mapNotNull { s ->
            val sb = s.name.substringBeforeLast('.')
            if (!sb.startsWith(base, ignoreCase = true)) return@mapNotNull null
            val rest = sb.substring(base.length)
            if (rest.isNotEmpty() && !rest.startsWith(".")) return@mapNotNull null
            if (otherBases.any { sb.startsWith(it, ignoreCase = true) }) return@mapNotNull null
            val tag = rest.trim('.', ' ', '_', '-')
            ExternalSubtitle(
                url = s.url,
                label = tag.ifBlank { "Subtitle" },
                language = tag.takeIf { it.length in 2..3 && it.all(Char::isLetter) }?.lowercase(),
            )
        }.take(6)
    }

    override fun onCleared() {
        if (_playing.value != null) playerManager.stopAndClear()
        super.onCleared()
    }
}
