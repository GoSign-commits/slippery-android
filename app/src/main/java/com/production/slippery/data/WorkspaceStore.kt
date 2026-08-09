package com.production.slippery.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists the scanned workspace config (Supabase URL + anon key) so the
 * app knows which show it's talking to across restarts, without a
 * central lookup/router service — matches PROVISIONING.md's "Buyer
 * invite QR" design exactly: the QR is the full config, nothing else to
 * keep running.
 *
 * Anon key is public-by-design (protected by RLS, not a secret) — the
 * encryption here is about tamper-resistance and keeping this in the
 * same Keystore-backed pattern PROVISIONING.md specifies, not because
 * this specific value is sensitive on its own.
 */
class WorkspaceStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "workspace_config",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(workspaceUrl: String, workspaceAnonKey: String, workspaceLabel: String?) {
        prefs.edit()
            .putString(KEY_URL, workspaceUrl)
            .putString(KEY_ANON_KEY, workspaceAnonKey)
            .putString(KEY_LABEL, workspaceLabel)
            .apply()
    }

    fun getUrl(): String? = prefs.getString(KEY_URL, null)
    fun getAnonKey(): String? = prefs.getString(KEY_ANON_KEY, null)
    fun getLabel(): String? = prefs.getString(KEY_LABEL, null)

    fun isConfigured(): Boolean = getUrl() != null && getAnonKey() != null

    companion object {
        private const val KEY_URL = "workspace_url"
        private const val KEY_ANON_KEY = "workspace_anon_key"
        private const val KEY_LABEL = "workspace_label"
    }
}
