package com.gemnote.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gemnote.R
import com.gemnote.data.*
import com.gemnote.service.ClipboardService
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var storage: StorageManager
    private lateinit var apiClient: ApiClient
    private lateinit var adapter: ClipboardAdapter
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: LinearLayout
    private lateinit var statusBar: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var spaceText: TextView
    private lateinit var connectButton: Button
    private lateinit var serviceToggle: ToggleButton
    
    private var spaces: List<Space> = emptyList()
    private var isConnected = false
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startClipboardService()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        storage = StorageManager(this)
        apiClient = ApiClient(storage)
        
        setupViews()
        setupRecyclerView()
        loadEntries()
        
        // Auto-connect if we have API key
        if (storage.apiKey.isNotEmpty()) {
            connect()
        }
    }
    
    override fun onResume() {
        super.onResume()
        loadEntries()
    }
    
    private fun setupViews() {
        recyclerView = findViewById(R.id.recyclerView)
        emptyView = findViewById(R.id.emptyView)
        statusBar = findViewById(R.id.statusBar)
        statusText = findViewById(R.id.statusText)
        spaceText = findViewById(R.id.spaceText)
        connectButton = findViewById(R.id.connectButton)
        serviceToggle = findViewById(R.id.serviceToggle)
        
        connectButton.setOnClickListener {
            if (isConnected) {
                showSpaceSelector()
            } else {
                showSettingsDialog()
            }
        }
        
        serviceToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startClipboardService()
            } else {
                ClipboardService.stop(this)
            }
        }
        
        findViewById<FloatingActionButton>(R.id.fabClear).setOnClickListener {
            storage.clearAllEntries()
            loadEntries()
        }
        
        updateStatusBar()
    }
    
    private fun setupRecyclerView() {
        adapter = ClipboardAdapter(
            onSendClick = { entry -> sendToAnytype(entry) },
            onDeleteClick = { entry ->
                storage.deleteEntry(entry.id)
                loadEntries()
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }
    
    private fun loadEntries() {
        val entries = storage.getClipboardEntries()
        adapter.submitList(entries)
        
        if (entries.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }
    }
    
    private fun updateStatusBar() {
        if (isConnected) {
            statusBar.setBackgroundColor(ContextCompat.getColor(this, R.color.connected_bg))
            statusText.text = "✓ Connected"
            connectButton.text = "Change Space"
            spaceText.text = "Space: ${storage.spaceName}"
            spaceText.visibility = View.VISIBLE
        } else {
            statusBar.setBackgroundColor(ContextCompat.getColor(this, R.color.disconnected_bg))
            statusText.text = "Not Connected"
            connectButton.text = "Connect"
            spaceText.visibility = View.GONE
        }
    }
    
    private fun connect() {
        lifecycleScope.launch {
            try {
                apiClient.getSpaces().fold(
                    onSuccess = { spaceList ->
                        spaces = spaceList
                        isConnected = true
                        
                        // Auto-select saved space or first one
                        if (storage.spaceId.isEmpty() && spaces.isNotEmpty()) {
                            storage.spaceId = spaces[0].id
                            storage.spaceName = spaces[0].name
                        }
                        
                        updateStatusBar()
                        showSnackbar("Connected! ${spaces.size} space(s) found")
                    },
                    onFailure = { e ->
                        isConnected = false
                        updateStatusBar()
                        showSnackbar("Connection failed: ${e.message}")
                    }
                )
            } catch (e: Exception) {
                isConnected = false
                updateStatusBar()
                showSnackbar("Error: ${e.message}")
            }
        }
    }
    
    private fun sendToAnytype(entry: ClipboardEntry) {
        if (!isConnected || storage.spaceId.isEmpty()) {
            showSnackbar("Please connect to Anytype first")
            return
        }
        
        val title = entry.content.lines().firstOrNull()?.take(50)?.trimStart('#', ' ') ?: "Note"
        
        lifecycleScope.launch {
            apiClient.createNote(storage.spaceId, title, entry.content).fold(
                onSuccess = {
                    storage.markAsSynced(entry.id)
                    loadEntries()
                    showSnackbar("Saved: $title")
                },
                onFailure = { e ->
                    showSnackbar("Failed: ${e.message}")
                }
            )
        }
    }
    
    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val apiKeyInput = dialogView.findViewById<EditText>(R.id.apiKeyInput)
        val baseUrlInput = dialogView.findViewById<EditText>(R.id.baseUrlInput)
        
        apiKeyInput.setText(storage.apiKey)
        baseUrlInput.setText(storage.baseUrl)
        
        AlertDialog.Builder(this)
            .setTitle("Anytype Settings")
            .setView(dialogView)
            .setPositiveButton("Save & Connect") { _, _ ->
                storage.apiKey = apiKeyInput.text.toString().trim()
                storage.baseUrl = baseUrlInput.text.toString().trim()
                connect()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showSpaceSelector() {
        if (spaces.isEmpty()) {
            showSnackbar("No spaces available")
            return
        }
        
        val names = spaces.map { it.name }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Select Space")
            .setItems(names) { _, which ->
                val space = spaces[which]
                storage.spaceId = space.id
                storage.spaceName = space.name
                updateStatusBar()
                showSnackbar("Selected: ${space.name}")
            }
            .show()
    }
    
    private fun startClipboardService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                    == PackageManager.PERMISSION_GRANTED -> {
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
    
    private fun showSnackbar(message: String) {
        Snackbar.make(recyclerView, message, Snackbar.LENGTH_SHORT).show()
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showSettingsDialog()
                true
            }
            R.id.action_refresh -> {
                loadEntries()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
