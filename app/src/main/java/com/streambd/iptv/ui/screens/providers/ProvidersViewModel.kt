package com.streambd.iptv.ui.screens.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streambd.iptv.data.addon.AddonCatalogService
import com.streambd.iptv.data.addon.CatalogRow
import com.streambd.iptv.data.addon.AddonException
import com.streambd.iptv.data.addon.AddonManifest
import com.streambd.iptv.data.addon.AddonPreview
import com.streambd.iptv.data.addon.AddonRepository
import com.streambd.iptv.data.db.AddonEntity
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
    private val addonRepo: AddonRepository,
    private val catalog: AddonCatalogService,
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
            if (provider.type == "ADDON") {
                addonRepo.remove(provider.id)
            } else {
                providerDao.deleteById(provider.id)
                channelRepo.clearProviderData(provider.id)
            }
        }
    }

    // ───────────────────────── Add-ons ─────────────────────────

    private fun addonMessage(e: Exception, fallback: String): String =
        (e as? AddonException)?.message ?: (e as? SyncException)?.message ?: e.message ?: fallback

    /** URL বা ফাইলের টেক্সট থেকে Add-on-এর তথ্য (ইনস্টলের আগে দেখানোর জন্য)। */
    fun previewAddon(url: String?, scriptText: String?, onResult: (AddonPreview?, String?) -> Unit) {
        viewModelScope.launch {
            _busyMessage.value = "Loading add-on..."
            _isSyncing.value = true
            try {
                val script = scriptText ?: addonRepo.download(url.orEmpty())
                onResult(addonRepo.preview(script, if (scriptText == null) url?.trim() else null), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(null, addonMessage(e, "Could not load the add-on"))
            } finally {
                _isSyncing.value = false
                _busyMessage.value = defaultBusy
            }
        }
    }

    fun installAddon(preview: AddonPreview, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _busyMessage.value = "Installing add-on..."
            _isSyncing.value = true
            try {
                val providerId = addonRepo.install(preview)
                _busyMessage.value = "Running add-on..."
                val summary = try {
                    addonRepo.refresh(providerId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Installed, but the first run failed: ${addonMessage(e, "error")}"
                }
                onResult(true, summary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(false, addonMessage(e, "Could not install the add-on"))
            } finally {
                _isSyncing.value = false
                _busyMessage.value = defaultBusy
            }
        }
    }

    /** অফিশিয়াল ক্যাটালগের তালিকা (কিল-সুইচও প্রয়োগ হয়)। */
    fun loadCatalog(onResult: (List<CatalogRow>?, String?) -> Unit) {
        viewModelScope.launch {
            _busyMessage.value = "Loading catalog..."
            _isSyncing.value = true
            try {
                onResult(catalog.rows(), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(null, addonMessage(e, "Could not load the catalog"))
            } finally {
                _isSyncing.value = false
                _busyMessage.value = defaultBusy
            }
        }
    }

    /** ইউজারের নিজে Add-on বন্ধ/চালু: বন্ধ করলে চ্যানেল তালিকা থেকে সরে, অটো-রিফ্রেশ থামে; চালু করলে আবার আসে। */
    fun setAddonEnabled(providerId: Long, on: Boolean, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _busyMessage.value = if (on) "Turning on..." else "Turning off..."
            _isSyncing.value = true
            try {
                onResult(true, addonRepo.setEnabled(providerId, on))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(false, addonMessage(e, "Could not change the add-on"))
            } finally {
                _isSyncing.value = false
                _busyMessage.value = defaultBusy
            }
        }
    }

    /** ক্যাটালগের Add-on ডাউনলোড + সিগনেচার যাচাই; সফল হলে ইনস্টল-প্রিভিউ। */
    fun previewCatalogAddon(id: String, onResult: (AddonPreview?, String?) -> Unit) {
        viewModelScope.launch {
            _busyMessage.value = "Verifying add-on..."
            _isSyncing.value = true
            try {
                onResult(catalog.preview(id), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(null, addonMessage(e, "Could not load the add-on"))
            } finally {
                _isSyncing.value = false
                _busyMessage.value = defaultBusy
            }
        }
    }

    fun loadAddon(provider: ProviderEntity, onLoaded: (AddonEntity?) -> Unit) {
        viewModelScope.launch { onLoaded(addonRepo.forProvider(provider.id)) }
    }

    /** সেভ করা সেটিংস + ম্যানিফেস্টের ডিফল্ট মিলিয়ে ফর্মের মান। */
    fun addonSettingValues(addon: AddonEntity, manifest: AddonManifest): Map<String, String> {
        val saved = addonRepo.jsonToMap(addon.settings)
        return manifest.settings.associate { it.key to (saved[it.key] ?: it.default) }
    }

    fun saveAddonSettings(addon: AddonEntity, values: Map<String, String>, onDone: () -> Unit) {
        viewModelScope.launch {
            addonRepo.saveSettings(addon, values)
            onDone()
        }
    }

    fun refreshAddon(provider: ProviderEntity, settings: Map<String, String>?, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _busyMessage.value = "Running add-on..."
            _isSyncing.value = true
            try {
                if (settings != null) addonRepo.forProvider(provider.id)?.let { addonRepo.saveSettings(it, settings) }
                // কিল-সুইচে বন্ধ থাকলে আগে ক্যাটালগে দেখে নেওয়া: সার্ভারে আবার চালু হয়ে থাকলে এখনই চালু হবে
                val cur = addonRepo.forProvider(provider.id)
                if (cur != null && !cur.enabled && cur.origin == "CATALOG") {
                    runCatching { catalog.syncInBackground() }
                    if (addonRepo.forProvider(provider.id)?.enabled != true) {
                        throw AddonException("This add-on was disabled by StreamBD and is not available right now.")
                    }
                }
                onResult(true, addonRepo.refresh(provider.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(false, addonMessage(e, "The add-on failed"))
            } finally {
                _isSyncing.value = false
                _busyMessage.value = defaultBusy
            }
        }
    }

    /** ইনস্টলের URL থেকে নতুন ভার্সন আনে। পারমিশন বদলালে আপডেট হয় না (ইউজারকে আবার ইনস্টল করতে হবে)। */
    fun updateAddon(provider: ProviderEntity, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _busyMessage.value = "Checking for update..."
            _isSyncing.value = true
            try {
                val a = addonRepo.forProvider(provider.id) ?: throw AddonException("Add-on not found.")
                val official = a.origin == "CATALOG"
                val src = a.sourceUrl
                if (!official && src == null) throw AddonException("This add-on was installed from a file. Install the new file to update it.")
                val p = if (official) {
                    addonRepo.previewCatalog(catalog.fetch(a.addonId))
                } else {
                    addonRepo.preview(addonRepo.download(src.orEmpty()), src)
                }
                val script = p.script
                if (p.manifest.id != a.addonId) throw AddonException("That link is a different add-on.")
                val old = AddonManifest.parse(a.manifest)
                if (old.domains.toSet() != p.manifest.domains.toSet() || old.allowLocal != p.manifest.allowLocal) {
                    throw AddonException("Permissions changed. Remove and reinstall this add-on to review them.")
                }
                if (script == a.script) {
                    onResult(true, "Already up to date.")
                    return@launch
                }
                addonRepo.install(p)
                _busyMessage.value = "Updating channels..."
                onResult(true, "Updated to ${p.manifest.version}. " + addonRepo.refresh(provider.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(false, addonMessage(e, "Could not update the add-on"))
            } finally {
                _isSyncing.value = false
                _busyMessage.value = defaultBusy
            }
        }
    }
}