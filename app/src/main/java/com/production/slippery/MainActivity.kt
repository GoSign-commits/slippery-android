package com.production.slippery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.production.slippery.ui.theme.SlipperyandroidTheme
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
        setContent {
            SlipperyandroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CaptureScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}
