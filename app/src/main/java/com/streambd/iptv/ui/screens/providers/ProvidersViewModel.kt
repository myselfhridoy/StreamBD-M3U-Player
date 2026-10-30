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

    // নতুন প্লেলিস্ট অ্যাড করার ফাংশন
    fun addProvider(name: String, url: String, onResult: (Boolean, String?) -> Unit) {
        if (name.isBlank() || url.isBlank()) {
            onResult(false, "Name and URL are required")
            return
        }
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                // ১. ডেটাবেসে সেভ করা (এটি একটি নতুন providerId রিটার্ন করবে)
                val providerId = providerDao.insert(ProviderEntity(name = name, url = url))
                // ২. চ্যানেল সিঙ্ক করা (isPremium = false যুক্ত হয়েছে PlaylistSync-এ)
                sync.syncPlaylist(url, providerId = providerId)
                _isSyncing.value = false
                onResult(true, null)
            } catch (e: SyncException) {
                _isSyncing.value = false
                onResult(false, e.message)
            } catch (e: Exception) {
                _isSyncing.value = false
                onResult(false, e.message ?: "Unknown error")
            }
        }
    }

    // নতুন: এডিট/আপডেট করার ফাংশন
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
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                // ১. ডেটাবেসে নাম ও লিংক আপডেট করা
                val updated = provider.copy(name = newName, url = newUrl)
                providerDao.update(updated)
                
                // ২. যদি লিংক (URL) পরিবর্তন হয়ে থাকে, তবেই নতুন করে চ্যানেল ডাউনলোড হবে
                // অন্যথায় শুধু নাম পরিবর্তনে বারবার ডাউনলোড হবে না।
                if (provider.url != newUrl) {
                    channelRepo.clearProviderData(provider.id) // আগের চ্যানেল ডিলিট
                    sync.syncPlaylist(newUrl, providerId = provider.id) // নতুন লিংক সিঙ্ক
                }
                
                _isSyncing.value = false
                onResult(true, null)
            } catch (e: SyncException) {
                _isSyncing.value = false
                onResult(false, e.message)
            } catch (e: Exception) {
                _isSyncing.value = false
                onResult(false, e.message ?: "Unknown error")
            }
        }
    }

    // প্লেলিস্ট ডিলিট করার ফাংশন
    fun deleteProvider(provider: ProviderEntity) {
        viewModelScope.launch {
            providerDao.deleteById(provider.id)
            // চ্যানেলগুলোও ডিটাবেস থেকে মুছে ফেলা হবে
            channelRepo.clearProviderData(provider.id)
        }
    }
}
