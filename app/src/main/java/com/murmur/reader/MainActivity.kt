package com.murmur.reader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.murmur.reader.navigation.MurmurNavGraph
import com.murmur.reader.ui.theme.MurmurTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle share intents
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val sharedUri = if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_VIEW) {
            intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } else null

        setContent {
            MurmurTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MurmurNavGraph(
                        sharedText = sharedText,
                        sharedUri = sharedUri
                    )
                }
            }
        }
    }
}
