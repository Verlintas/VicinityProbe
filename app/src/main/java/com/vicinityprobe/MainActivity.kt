package com.vicinityprobe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vicinityprobe.ui.navigation.AppNav
import com.vicinityprobe.ui.theme.VicinityProbeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VicinityProbeTheme {
                AppNav()
            }
        }
    }
}
