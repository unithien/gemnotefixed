package com.geminianytype.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.geminianytype.service.ClipboardService
import com.geminianytype.ui.theme.GemNoteTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            ClipboardService.start(this)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            GemNoteTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: MainViewModel = hiltViewModel()
                    val uiState by viewModel.uiState.collectAsState()
                    
                    MainScreen(
                        uiState = uiState,
                        onApiKeyChange = viewModel::setApiKey,
                        onBaseUrlChange = viewModel::setBaseUrl,
                        onConnect = viewModel::connectToAnytype,
                        onSelectSpace = viewModel::selectSpace,
                        onSendToAnytype = viewModel::sendToAnytype,
                        onDeleteEntry = viewModel::deleteEntry,
                        onClearAll = viewModel::clearAllEntries,
                        onStartService = { startClipboardService() },
                        onStopService = { stopClipboardService() },
                        onClearError = viewModel::clearError,
                        onClearSuccess = viewModel::clearSuccessMessage
                    )
                }
            }
        }
    }
    
    private fun startClipboardService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    ClipboardService.start(this)
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            ClipboardService.start(this)
        }
    }
    
    private fun stopClipboardService() {
        ClipboardService.stop(this)
    }
}
