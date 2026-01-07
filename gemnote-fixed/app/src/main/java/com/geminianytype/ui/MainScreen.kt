package com.geminianytype.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geminianytype.data.ClipboardEntry
import com.geminianytype.data.Space
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    onApiKeyChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onConnect: () -> Unit,
    onSelectSpace: (Space) -> Unit,
    onSendToAnytype: (ClipboardEntry) -> Unit,
    onDeleteEntry: (ClipboardEntry) -> Unit,
    onClearAll: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onClearError: () -> Unit,
    onClearSuccess: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    
    LaunchedEffect(uiState.error) { uiState.error?.let { snackbarHostState.showSnackbar(it); onClearError() } }
    LaunchedEffect(uiState.successMessage) { uiState.successMessage?.let { snackbarHostState.showSnackbar(it); onClearSuccess() } }
    
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("GemNote") },
                actions = {
                    IconButton(onClick = { if (uiState.isServiceRunning) onStopService() else onStartService() }) {
                        Icon(Icons.Default.PlayArrow, null, tint = if (uiState.isServiceRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ConnectionBar(uiState, onSelectSpace, onConnect)
            if (uiState.clipboardEntries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📋", style = MaterialTheme.typography.displayLarge)
                        Spacer(Modifier.height(16.dp))
                        Text("No clipboard entries", style = MaterialTheme.typography.titleMedium)
                        Text("Copy text to capture it here", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(uiState.clipboardEntries) { entry ->
                        EntryCard(entry, uiState.isConnected, { onSendToAnytype(entry) }, { onDeleteEntry(entry) })
                    }
                }
            }
        }
    }
    
    if (showSettings) {
        SettingsDialog(uiState.apiKey, uiState.baseUrl, onApiKeyChange, onBaseUrlChange, onConnect) { showSettings = false }
    }
}

@Composable
fun ConnectionBar(uiState: MainUiState, onSelectSpace: (Space) -> Unit, onConnect: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), color = if (uiState.isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (uiState.isConnected) Icons.Default.CheckCircle else Icons.Default.Warning, null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(if (uiState.isConnected) "Connected" else "Not Connected")
                    if (uiState.selectedSpace != null) Text("Space: ${uiState.selectedSpace.name}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!uiState.isConnected) Button(onClick = onConnect) { Text("Connect") }
            else if (uiState.spaces.size > 1) TextButton(onClick = { expanded = true }) { Text("Change") }
        }
        DropdownMenu(expanded, { expanded = false }) {
            uiState.spaces.forEach { space ->
                DropdownMenuItem(text = { Text(space.name) }, onClick = { onSelectSpace(space); expanded = false })
            }
        }
    }
}

@Composable
fun EntryCard(entry: ClipboardEntry, isConnected: Boolean, onSend: () -> Unit, onDelete: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(dateFormat.format(Date(entry.timestamp)), style = MaterialTheme.typography.labelSmall)
                if (entry.isSynced) Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("✓ Synced", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(entry.preview, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete) { Text("Delete") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSend, enabled = isConnected && !entry.isSynced) { Text(if (entry.isSynced) "Sent" else "Send") }
            }
        }
    }
}

@Composable
fun SettingsDialog(apiKey: String, baseUrl: String, onApiKeyChange: (String) -> Unit, onBaseUrlChange: (String) -> Unit, onConnect: () -> Unit, onDismiss: () -> Unit) {
    var localApiKey by remember { mutableStateOf(apiKey) }
    var localBaseUrl by remember { mutableStateOf(baseUrl) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(localApiKey, { localApiKey = it }, label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(localBaseUrl, { localBaseUrl = it }, label = { Text("Anytype URL") }, placeholder = { Text("http://192.168.1.100:31009") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Enter your PC's IP where Anytype runs", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onApiKeyChange(localApiKey); onBaseUrlChange(localBaseUrl); onConnect(); onDismiss() }) { Text("Save & Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
