package com.production.slippery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.production.slippery.data.WorkspaceStore
import com.production.slippery.ui.theme.SlipperyandroidTheme
import io.github.jan.supabase.auth.auth
import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val id: String,
    val code: String,
    val name: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val workspaceStore = WorkspaceStore(applicationContext)
        // Configure the client eagerly if we already have a workspace on
        // file — supabase-kt's own session persistence then handles
        // whether that also means an active login, not just a known
        // backend to talk to.
        if (workspaceStore.isConfigured()) {
            SupabaseClientInstance.initialize(
                workspaceStore.getUrl()!!,
                workspaceStore.getAnonKey()!!
            )
        }

        setContent {
            SlipperyandroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // supabase-kt loads the session from persisted storage
                    // asynchronously — currentSessionOrNull() can race ahead
                    // of that load on cold start and return null even when
                    // the buyer IS signed in. awaitInitialization() blocks
                    // until the session manager has finished loading, then
                    // the check is safe.
                    var loggedIn by remember { mutableStateOf<Boolean?>(null) }

                    LaunchedEffect(Unit) {
                        loggedIn = if (SupabaseClientInstance.isInitialized) {
                            SupabaseClientInstance.client.auth.awaitInitialization()
                            SupabaseClientInstance.client.auth.currentSessionOrNull() != null
                        } else {
                            false
                        }
                    }

                    when (loggedIn) {
                        null -> Box(
                            modifier = Modifier.fillMaxSize().padding(innerPadding),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                        true -> CaptureScreen(modifier = Modifier.padding(innerPadding))
                        false -> QrScanScreen(onLoginSuccess = { loggedIn = true })
                    }
                }
            }
        }
    }
}
