package com.production.slippery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.production.slippery.ui.theme.SlipperyandroidTheme
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
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
                    CategoryListScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun CategoryListScreen(modifier: Modifier = Modifier) {
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val result = SupabaseClientInstance.client.postgrest["categories"]
                .select(columns = Columns.list("id", "code", "name"))
                .decodeList<Category>()
            categories = result
        } catch (e: Exception) {
            error = e.message
        }
    }

    Column(modifier = modifier.padding(16.dp)) {
        Text(text = "Slippery Android")
        if (error != null) {
            Text(text = "Error: $error")
        } else if (categories.isEmpty()) {
            Text(text = "Loading categories...")
        } else {
            categories.forEach { cat ->
                Text(text = "${cat.code} - ${cat.name}")
            }
        }
    }
}
