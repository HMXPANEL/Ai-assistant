package com.voicecontrol.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    showBackButton: Boolean = false,
    onBack: () -> Unit = {},
    onClearHistory: () -> Unit = {},
    isLocalAiEnabled: Boolean = false,
    onToggleLocalAi: () -> Unit = {},
    isModelAvailable: Boolean = false,
    onUnloadModel: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1976D2),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    onClearHistory()
                    scope.launch {
                        snackbarHostState.showSnackbar("Conversation history cleared")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1976D2)
                )
            ) {
                Text("Clear Conversation History")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "On-Device AI (Experimental)",
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Enable Gemma 2B (On-Device)")
                Switch(
                    checked = isLocalAiEnabled,
                    onCheckedChange = { onToggleLocalAi() }
                )
            }

            if (isLocalAiEnabled) {
                Text(
                    text = "⚠️ Requires 4GB+ RAM and a 1.4GB model file placed at: " +
                            "/storage/emulated/0/Download/gemma-2-2b-it-lQ4_XS.gguf. Responses may be slow on low-RAM devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text(
                text = "Model file: ${if (isModelAvailable) "Found ✓" else "Not found ✗"}" +
                        "\nPath: /storage/emulated/0/Download/gemma-2-2b-it-lQ4_XS.gguf",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isModelAvailable) Color(0xFF2E7D32) else Color(0xFFC62828)
            )

            Button(
                onClick = onUnloadModel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFC62828)
                )
            ) {
                Text("Unload Model from Memory")
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Permissions", style = MaterialTheme.typography.titleMedium)

            val ctx = LocalContext.current
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { _ -> }
            val permItems = listOf(
                Manifest.permission.READ_CONTACTS to "Read Contacts",
                Manifest.permission.READ_SMS to "Read SMS",
                Manifest.permission.SEND_SMS to "Send SMS",
                Manifest.permission.READ_CALENDAR to "Read Calendar",
                Manifest.permission.WRITE_CALENDAR to "Write Calendar",
                Manifest.permission.CAMERA to "Camera"
            )
            permItems.forEach { (perm, label) ->
                val granted = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (granted) "Granted ✓" else "Denied ✗",
                        color = if (granted) Color(0xFF2E7D32) else Color(0xFFC62828),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    permissionLauncher.launch(arrayOf(
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.READ_SMS,
                        Manifest.permission.SEND_SMS,
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR,
                        Manifest.permission.CAMERA
                    ))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
            ) {
                Text("Grant All Permissions")
            }
        }
    }
}
