package com.production.slippery

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage

object SupabaseClientInstance {
    val client = createSupabaseClient(
        supabaseUrl = "https://xwzydprpdzhiitexzbtn.supabase.co",
        supabaseKey = "sb_publishable_1eJ7H8BCMc77X2x9DBQjAQ_uE7mD2uI"
    ) {
        install(Auth)
        install(Postgrest)
        install(Storage)
    }
}
