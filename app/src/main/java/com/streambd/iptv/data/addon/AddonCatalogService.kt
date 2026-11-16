package com.streambd.iptv.data.addon

import android.util.Base64
import android.util.Log
import com.streambd.iptv.BuildConfig
import com.streambd.iptv.data.activation.ActivationRepository
import com.streambd.iptv.data.activation.ActivationState
import com.streambd.iptv.data.db.AddonDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** ক্যাটালগ তালিকার একটি সারি + ইনস্টল অবস্থা। */
data class CatalogRow(
    val entry: CatalogEntry,
    val installedVersion: String?,
    val updateAvailable: Boolean,
    /** এই অ্যাপ ভার্সনে চলবে না (minAppVersion বেশি)। */
    val needsNewerApp: Boolean,
)

/**
 * অফিশিয়াল Add-on ক্যাটালগ: তালিকা, ইনস্টল-প্রিভিউ, কিল-সুইচ ও অটো-আপডেট।
 * অ্যাক্টিভেশন কোড ও ডিভাইস আইডি ActivationRepository থেকে আসে।
 */
@Singleton
class AddonCatalogService @Inject constructor(
    private val activation: ActivationRepository,
    private val client: AddonCatalogClient,
    private val repo: AddonRepository,
    private val addons: AddonDao,
) {
    private companion object {
        const val TAG = "AddonCatalog"
    }

    private fun baseUrl(): String = try {
        String(Base64.decode(BuildConfig.ENCODED_API, Base64.DEFAULT), Charsets.UTF_8).trimEnd('/')
    } catch (e: Exception) {
        ""
    }

    private suspend fun creds(): Pair<String, String> {
        val active = activation.current() as? ActivationState.Active
            ?: throw AddonException("Activate the app to use the add-on catalog.")
        if (active.code == ActivationRepository.LOCAL_CODE || active.code == "DEV") {
            throw AddonException("The catalog is not available in local mode.")
        }
        val base = baseUrl()
        if (base.isBlank()) throw AddonException("Catalog server is not configured.")
        return active.code to activation.deviceId()
    }

    /** তালিকা আনে, কিল-সুইচ প্রয়োগ করে, ইনস্টল অবস্থা মিলিয়ে সারি বানায়। */
    suspend fun rows(): List<CatalogRow> {
        val (code, device) = creds()
        val list = client.list(baseUrl(), code, device)
        repo.applyKillSwitch(list.blocked)
        repo.restoreUnblocked(list.entries.map { it.id }, list.blocked)
        val installed = addons.observeAll().first().associateBy { it.addonId }
        return list.entries.map { e ->
            val a = installed[e.id]
            CatalogRow(
                entry = e,
                installedVersion = a?.version,
                updateAvailable = a != null && a.origin == "CATALOG" && a.version != e.version,
                needsNewerApp = e.minAppVersion > BuildConfig.VERSION_CODE,
            )
        }
    }

    suspend fun fetch(id: String): CatalogAddon {
        val (code, device) = creds()
        return client.get(baseUrl(), code, device, id)
    }

    /** ইনস্টলের আগে যাচাই (সিগনেচার ইত্যাদি) করা প্রিভিউ। */
    suspend fun preview(id: String): AddonPreview = repo.previewCatalog(fetch(id))

    /**
     * ব্যাকগ্রাউন্ড: কিল-সুইচ + ইনস্টল করা অফিশিয়াল Add-on-এর অটো-আপডেট।
     * পারমিশন বদলালে অটো-আপডেট হয় না (ইউজারকে আবার ইনস্টল করে দেখতে হবে)।
     * @return আপডেট হওয়া Add-on সংখ্যা
     */
    suspend fun syncInBackground(): Int {
        val (code, device) = creds()
        val list = client.list(baseUrl(), code, device)
        repo.applyKillSwitch(list.blocked)
        repo.restoreUnblocked(list.entries.map { it.id }, list.blocked)

        var updated = 0
        for (a in addons.observeAll().first()) {
            if (a.origin != "CATALOG" || !a.enabled) continue
            val e = list.entries.firstOrNull { it.id == a.addonId } ?: continue
            if (e.version == a.version) continue
            try {
                val p = repo.previewCatalog(client.get(baseUrl(), code, device, e.id))
                val old = AddonManifest.parse(a.manifest)
                if (old.domains.toSet() != p.manifest.domains.toSet() || old.allowLocal != p.manifest.allowLocal) {
                    Log.i(TAG, "${e.id}: permissions changed, skipping auto-update")
                    continue
                }
                repo.install(p)
                runCatching { repo.refresh(a.providerId) }
                updated++
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Log.w(TAG, "${e.id}: auto-update failed: ${ex.message}")
            }
        }
        return updated
    }
}
