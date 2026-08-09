package com.production.slippery

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.production.slippery.data.WorkspaceStore
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Field names match generate-buyer-invite's JSON response exactly (see
 * infra/PROVISIONING.md "Buyer invite QR") — no @SerialName mapping
 * needed since both sides already use camelCase.
 *
 * emailOtp, not hashedToken — supabase-kt has no token_hash-based verify
 * method, see the Edge Function's own comments for the full story.
 */
@Serializable
data class BuyerInvitePayload(
    val email: String,
    val emailOtp: String,
    val workspaceLabel: String?,
    val workspaceUrl: String,
    val workspaceAnonKey: String
)

@Composable
fun QrScanScreen(onLoginSuccess: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val workspaceStore = remember { WorkspaceStore(context) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val scanner = remember { GmsBarcodeScanning.getClient(activity) }

    fun startScan() {
        errorMessage = null
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue
                if (raw == null) {
                    errorMessage = "Couldn't read that code. Try again."
                    return@addOnSuccessListener
                }
                isLoading = true
                scope.launch {
                    try {
                        val payload = Json.decodeFromString<BuyerInvitePayload>(raw)

                        workspaceStore.save(
                            payload.workspaceUrl,
                            payload.workspaceAnonKey,
                            payload.workspaceLabel
                        )
                        SupabaseClientInstance.initialize(payload.workspaceUrl, payload.workspaceAnonKey)

                        SupabaseClientInstance.client.auth.verifyEmailOtp(
                            type = OtpType.Email.MAGIC_LINK,
                            email = payload.email,
                            token = payload.emailOtp
                        )

                        isLoading = false
                        onLoginSuccess()
                    } catch (e: Exception) {
                        isLoading = false
                        android.util.Log.e("QrScan", "Redemption failed", e)
                        // Deliberately generic in the UI — a wrong/expired/already-used
                        // code shouldn't leak details about why, same
                        // zero-trust posture as the presign worker. The real
                        // reason is logged above, not hidden from debugging.
                        errorMessage = "That code didn't work. Ask accounts for a new one."
                    }
                }
            }
            .addOnCanceledListener {
                // User backed out of the scan — not an error, just do nothing.
            }
            .addOnFailureListener {
                errorMessage = "Scanner unavailable. Check Google Play Services and try again."
            }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Slippery", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))
            Text(
                "Scan the invite code accounts gave you to get started.",
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            if (isLoading) {
                CircularProgressIndicator()
            } else {
                Button(onClick = { startScan() }) {
                    Text("Scan invite")
                }
            }
            errorMessage?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
