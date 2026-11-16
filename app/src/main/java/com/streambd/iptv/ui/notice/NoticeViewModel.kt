package com.streambd.iptv.ui.notice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambd.iptv.data.notice.AppConfigRepository
import com.streambd.iptv.data.notice.AppUpdater
import com.streambd.iptv.data.notice.NoticeState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NoticeViewModel @Inject constructor(
    private val repo: AppConfigRepository,
    private val updater: AppUpdater,
) : ViewModel() {
    val state: StateFlow<NoticeState> = repo.state

    val download: StateFlow<AppUpdater.State> = updater.state
    fun canUpdateInApp(url: String) = updater.canUpdateInApp(url)
    fun startUpdate(url: String) = updater.start(url)

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    fun dismissAnnouncement(id: Long) = repo.dismissAnnouncement(id)
    fun dismissUpdate() = repo.dismissUpdate()

    /** আটকে থাকা অ্যাপে "Check again": অ্যাডমিন ন্যূনতম ভার্সন কমালে ছাড়া পাওয়ার পথ। */
    fun recheck() {
        viewModelScope.launch {
            _checking.value = true
            try { repo.refresh() } finally { _checking.value = false }
        }
    }
}
