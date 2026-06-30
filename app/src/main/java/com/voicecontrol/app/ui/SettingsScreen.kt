package com.voicecontrol.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voicecontrol.app.ChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    showBackButton: Boolean = false,
    onBack: () -> Unit = {},
    onClearHistory: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val copyProgress by viewModel.modelCopyProgress.collectAsState()
    val copyStatus by viewModel.modelCopyStatus.collectAsState()
    val isLocalAiEnabled by viewModel.isLocalAiEnabled.collectAsState()
    val isGeminiEnabled by viewModel.isGeminiEnabled.collectAsState()
    val savedApiKey by viewModel.geminiApiKey.collectAsState()

    val context = LocalContext.current
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // some providers don't support persistable permission, ignore
            }
            viewModel.copyModelFromUri(uri)
        }
    }

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

            // Gemini AI section
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Gemini API (Cloud AI)", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Enable Gemini AI")
                        Switch(
                            checked = isGeminiEnabled,
                            onCheckedChange = { viewModel.toggleGemini() }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    var apiKeyInput by remember { mutableStateOf(savedApiKey) }
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("Gemini API Key") },
                        placeholder = { Text("Paste your API key from aistudio.google.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = { viewModel.saveGeminiApiKey(apiKeyInput.trim()) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = apiKeyInput.isNotBlank()
                    ) {
                        Text("Save API Key")
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Model: gemini-2.5-flash \u2022 Get a free key at aistudio.google.com",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }

// On-device LLM (llama-android) — kept for future rule-based on-device tasks
//            Spacer(modifier = Modifier.height(16.dp))
//            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
//                Column(modifier = Modifier.padding(16.dp)) {
//                    Text("On-Device AI (Legacy)", style = MaterialTheme.typography.titleMedium)
//                    ...
//                }
//            }

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
                        if (granted) "Granted \u2713" else "Denied \u2717",
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
