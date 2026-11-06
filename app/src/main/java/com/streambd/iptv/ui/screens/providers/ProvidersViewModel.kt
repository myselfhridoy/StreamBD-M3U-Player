package com.streambd.iptv.ui.screens.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambd.iptv.data.db.ProviderDao
import com.streambd.iptv.data.db.ProviderEntity
import com.streambd.iptv.data.ftp.FtpBrowser
import com.streambd.iptv.data.repo.ChannelRepository
import com.streambd.iptv.data.repo.PlaylistSync
import com.streambd.iptv.data.repo.SyncException
import com.streambd.iptv.data.xtream.XtreamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    private val channelRepo: ChannelRepository,
    private val ftpBrowser: FtpBrowser,
    private val xtream: XtreamRepository,
) : ViewModel() {

    val providers: StateFlow<List<ProviderEntity>> = providerDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val defaultBusy = "Downloading channels..."
    private val _busyMessage = MutableStateFlow(defaultBusy)
    val busyMessage: StateFlow<String> = _busyMessage.asStateFlow()

    /** FTP/h5ai সার্ভার সেভ: আগে ঠিকানা খোলে কিনা যাচাই, তারপর সেভ। কোনো চ্যানেল ডাউনলোড হয় না। */
    fun addFtpServer(name: String, address: String, onResult: (Boolean, String?) -> Unit) {
        saveFtp(null, name, address, onResult)
    }

    fun updateFtpServer(provider: ProviderEntity, name: String, address: String, onResult: (Boolean, String?) -> Unit) {
        saveFtp(provider, name, address, onResult)
    }

    private fun saveFtp(existing: ProviderEntity?, name: String, address: String, onResult: (Boolean, String?) -> Unit) {
        if (name.isBlank() || address.isBlank()) {
            onResult(false, "Name and address are required")
            return
        }
        val url = ftpBrowser.normalizeAddress(address)
        if (url == null) {
            onResult(false, "Enter a valid server address")
            return
        }
        viewModelScope.launch {
            _busyMessage.value = "Connecting to server..."
            _isSyncing.value = true
            try {
                ftpBrowser.list(url, force = true) // পৌঁছানো যায় কিনা দেখা
                if (existing == null) {
                    providerDao.insert(ProviderEntity(name = name.trim(), url = url, type = "FTP"))
                    onResult(true, "FTP server added.")
                } else {
                    providerDao.update(existing.copy(name = name.trim(), url = url))
                    onResult(true, "FTP server updated.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(false, e.message ?: "Could not connect")
            } finally {
                _isSyncing.value = false
                _busyMessage.value = defaultBusy
            }
        }
    }

    // ───────────────────────── Xtream ─────────────────────────

    fun addXtream(
        name: String,
        url: String,
        username: String,
        password: String,
        userAgent: String? = null,
        onResult: (Boolean, String?) -> Unit,
    ) {
        if (name.isBlank() || url.isBlank() || username.isBlank() || password.isBlank()) {
            onResult(false, "Name, URL, username and password are required")
            return
        }
        viewModelScope.launch {
            _busyMessage.value = "Connecting to Xtream..."
            _isSyncing.value = true
            try {
                val summary = xtream.add(name, url, username, password, userAgent)
                onResult(true, summary.message())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(false, e.message ?: "Could not add Xtream server")
            } finally {
                _isSyncing.value = false
                _busyMessage.value = defaultBusy
            }
        }
    }

    fun updateXtream(
        provider: ProviderEntity,
        name: String,
        url: String,
        username: String,
        password: String,
        userAgent: String? = null,
        onResult: (Boolean, String?) -> Unit,
    ) {
        if (name.isBlank() || url.isBlank() || username.isBlank() || password.isBlank()) {
            onResult(false, "Name, URL, username and password are required")
            return
        }
        viewModelScope.launch {
            _busyMessage.value = "Updating Xtream..."
            _isSyncing.value = true
            try {
                val summary = xtream.update(provider, name, url, username, password, userAgent)
                onResult(true, summary?.message() ?: "Xtream provider updated.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(false, e.message ?: "Could not update Xtream server")
            } finally {
                _isSyncing.value = false
                _busyMessage.value = defaultBusy
            }
        }
    }

    // ───────────────────────── M3U ─────────────────────────

    // নতুন প্লেলিস্ট অ্যাড করার ফাংশন
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
                val synced = sync.syncPlaylist(url.trim(), providerId = providerId)

                // নতুন: EPG ডাউনলোড এবং মেসেজ তৈরি
                val msg = if (synced.epgUrls.isEmpty()) {
                    "Added ${synced.channels} channels. No EPG link in playlist."
                } else try {
                    val n = sync.syncEpg(synced.epgUrls.joinToString("\n"))
                    "Added ${synced.channels} channels, $n guide entries."
                } catch (e: SyncException) {
                    "Added ${synced.channels} channels. TV guide: ${e.message}"
                }

                onResult(true, msg)
            } catch (e: CancellationException) {
                throw e
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
        onResult: (Boolean, String?) -> Unit,
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
                    val synced = sync.syncPlaylist(newUrl.trim(), providerId = provider.id)

                    val msg = if (synced.epgUrls.isEmpty()) {
                        "Updated ${synced.channels} channels. No EPG link found."
                    } else try {
                        val n = sync.syncEpg(synced.epgUrls.joinToString("\n"))
                        "Updated ${synced.channels} channels, $n guide entries."
                    } catch (e: SyncException) {
                        "Updated ${synced.channels} channels. TV guide: ${e.message}"
                    }
                    onResult(true, msg)
                } else {
                    onResult(true, "Provider name updated.")
                }
            } catch (e: CancellationException) {
                throw e
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
