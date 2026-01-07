package com.geminianytype.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geminianytype.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val clipboardEntries: List<ClipboardEntry> = emptyList(),
    val spaces: List<Space> = emptyList(),
    val selectedSpace: Space? = null,
    val isLoading: Boolean = false,
    val isConnected: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
    val apiKey: String = "",
    val baseUrl: String = "",
    val isServiceRunning: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: ClipboardRepository,
    private val settingsManager: SettingsManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    init {
        viewModelScope.launch {
            repository.getAllClipboardEntries().collect { entries ->
                _uiState.update { it.copy(clipboardEntries = entries) }
            }
        }
        viewModelScope.launch {
            combine(settingsManager.apiKey, settingsManager.baseUrl, settingsManager.isServiceRunning) { key, url, running ->
                Triple(key, url, running)
            }.collect { (key, url, running) ->
                _uiState.update { it.copy(apiKey = key, baseUrl = url, isServiceRunning = running) }
            }
        }
    }
    
    fun setApiKey(key: String) = viewModelScope.launch { settingsManager.setApiKey(key) }
    fun setBaseUrl(url: String) = viewModelScope.launch { settingsManager.setBaseUrl(url) }
    
    fun connectToAnytype() = viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true, error = null) }
        repository.getSpaces().fold(
            onSuccess = { spaces ->
                _uiState.update { it.copy(spaces = spaces, isConnected = true, isLoading = false) }
                if (spaces.isNotEmpty() && _uiState.value.selectedSpace == null) selectSpace(spaces.first())
            },
            onFailure = { e -> _uiState.update { it.copy(isConnected = false, isLoading = false, error = "Connection failed: ${e.message}") } }
        )
    }
    
    fun selectSpace(space: Space) = viewModelScope.launch {
        settingsManager.setSelectedSpace(space.id)
        _uiState.update { it.copy(selectedSpace = space) }
    }
    
    fun sendToAnytype(entry: ClipboardEntry) {
        val spaceId = _uiState.value.selectedSpace?.id ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val title = entry.content.lines().firstOrNull()?.take(50)?.trimStart('#', ' ')?.ifEmpty { "Note" } ?: "Note"
            repository.createNote(spaceId, title, entry.content).fold(
                onSuccess = { obj ->
                    repository.markAsSynced(entry.id, obj.id)
                    _uiState.update { it.copy(isLoading = false, successMessage = "Saved: $title") }
                },
                onFailure = { e -> _uiState.update { it.copy(isLoading = false, error = "Failed: ${e.message}") } }
            )
        }
    }
    
    fun deleteEntry(entry: ClipboardEntry) = viewModelScope.launch { repository.deleteEntry(entry) }
    fun clearAllEntries() = viewModelScope.launch { repository.clearAllEntries() }
    fun clearError() = _uiState.update { it.copy(error = null) }
    fun clearSuccessMessage() = _uiState.update { it.copy(successMessage = null) }
}
