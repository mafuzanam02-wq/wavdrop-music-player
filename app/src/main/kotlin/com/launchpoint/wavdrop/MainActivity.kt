package com.launchpoint.wavdrop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.launchpoint.wavdrop.ui.navigation.WavdropNavGraph
import com.launchpoint.wavdrop.ui.theme.WavdropTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WavdropTheme {
                WavdropNavGraph()
            }
        }
    }
}
