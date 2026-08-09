package com.production.slippery

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage

/**
 * Was a hardcoded singleton with the Supabase URL/key baked in at
 * compile time — a direct violation of PROVISIONING.md's own rule ("no
 * hardcoded project-specific values anywhere in code"). Fixed 2026-08-09
 * as part of building QR onboarding: now configured at runtime from
 * whatever workspace the buyer's QR scan resolved to (see WorkspaceStore,
 * QrScanScreen). No client exists until [initialize] is called.
 */
object SupabaseClientInstance {
    lateinit var client: io.github.jan.supabase.SupabaseClient
        private set

    val isInitialized: Boolean get() = ::client.isInitialized

    fun initialize(workspaceUrl: String, workspaceAnonKey: String) {
        client = createSupabaseClient(
            supabaseUrl = workspaceUrl,
            supabaseKey = workspaceAnonKey
        ) {
            install(Auth)
            install(Postgrest)
            install(Storage)
        }
    }
}
