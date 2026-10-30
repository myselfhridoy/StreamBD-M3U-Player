package com.streambd.iptv.ui.screens.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambd.iptv.data.db.ProviderDao
import com.streambd.iptv.data.db.ProviderEntity
import com.streambd.iptv.data.repo.ChannelRepository
import com.streambd.iptv.data.repo.PlaylistSync
import com.streambd.iptv.data.repo.SyncException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProvidersViewModel @Inject constructor(
    private val providerDao: ProviderDao,
    private val sync: PlaylistSync,
    private val channelRepo: ChannelRepository
) : ViewModel() {

    val providers: StateFlow<List<ProviderEntity>> = providerDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    // নতুন: URL ভ্যালিডেশন এবং সিঙ্ক ফেইল করলে ডেটাবেস থেকে ডিলিট করার লজিক
    fun addProvider(name: String, url: String, onResult: (Boolean, String?) -> Unit) {
        if (name.isBlank() || url.isBlank()) { 
            onResult(false, "Name and URL are required")
            return 
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            onResult(false, "Enter a valid http(s) link")
            return
        }
        
        viewModelScope.launch {
            _isSyncing.value = true
            var providerId = 0L
            try {
                providerId = providerDao.insert(ProviderEntity(name = name.trim(), url = url.trim()))
                sync.syncPlaylist(url.trim(), providerId = providerId)
                onResult(true, null)
            } catch (e: Exception) {
                // সিঙ্ক ফেইল করলে ডেটাবেস থেকে ভাঙা প্রোভাইডারটি মুছে ফেলা হবে
                if (providerId > 0L) {
                    providerDao.deleteById(providerId)
                    channelRepo.clearProviderData(providerId)
                }
                onResult(false, (e as? SyncException)?.message ?: e.message ?: "Unknown error")
            } finally {
                _isSyncing.value = false
            }
        }
    }

    // এডিট/আপডেট করার ফাংশন
    fun updateProvider(
        provider: ProviderEntity, 
        newName: String, 
        newUrl: String, 
        onResult: (Boolean, String?) -> Unit
    ) {
        if (newName.isBlank() || newUrl.isBlank()) {
            onResult(false, "Name and URL are required")
            return
        }
        if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
            onResult(false, "Enter a valid http(s) link")
            return
        }
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                val updated = provider.copy(name = newName.trim(), url = newUrl.trim())
                providerDao.update(updated)
                
                if (provider.url != newUrl.trim()) {
                    channelRepo.clearProviderData(provider.id)
                    sync.syncPlaylist(newUrl.trim(), providerId = provider.id)
                }
                
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, (e as? SyncException)?.message ?: e.message ?: "Unknown error")
            } finally {
                _isSyncing.value = false
            }
        }
    }

    // প্লেলিস্ট ডিলিট করার ফাংশন
    fun deleteProvider(provider: ProviderEntity) {
        viewModelScope.launch {
            providerDao.deleteById(provider.id)
            channelRepo.clearProviderData(provider.id)
        }
    }
}
