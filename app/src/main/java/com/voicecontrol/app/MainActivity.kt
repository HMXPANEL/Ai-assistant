package com.voicecontrol.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.voicecontrol.app.ui.ChatScreen
import com.voicecontrol.app.ui.SettingsScreen
import com.voicecontrol.app.ui.theme.Theme.VoiceControl

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            VoiceControl {
                var showSettings by remember { mutableStateOf(false) }

                if (showSettings) {
                    SettingsScreen(
                        viewModel = viewModel,
                        showBackButton = true,
                        onBack = { showSettings = false }
                    )
                } else {
                    ChatScreen(
                        viewModel = viewModel,
                        onOpenSettings = { showSettings = true }
                    )
                }
            }
        }
    }

    private fun enableEdgeToEdge() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
    }
}
