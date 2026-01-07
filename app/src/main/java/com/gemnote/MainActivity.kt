package com.gemnote

import android.Manifest
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var statusText: TextView
    private lateinit var adapter: EntryAdapter
    
    private val entries = mutableListOf<ClipEntry>()
    private var spaces = listOf<Space>()
    private var selectedSpaceId = ""
    private var selectedSpaceName = ""
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        prefs = getSharedPreferences("gemnote", Context.MODE_PRIVATE)
        
        setupViews()
        loadEntries()
        loadSettings()
        
        handleShareIntent(intent)
        
        if (getApiKey().isNotEmpty()) {
            connectToAnytype()
        }
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleShareIntent(it) }
    }
    
    private fun handleShareIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                addEntry(text)
            }
        }
    }
    
    private fun setupViews() {
        recyclerView = findViewById(R.id.recyclerView)
        emptyView = findViewById(R.id.emptyView)
        statusText = findViewById(R.id.statusText)
        
        adapter = EntryAdapter(
            entries,
            onSendClick = { entry -> sendToAnytype(entry) },
            onDeleteClick = { entry -> deleteEntry(entry) }
        )
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        
        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            if (isConnected) {
                showSpaceSelector()
            } else {
                showSettingsDialog()
            }
        }
        
        findViewById<Button>(R.id.btnService).setOnClickListener {
            toggleService()
        }
        
        findViewById<FloatingActionButton>(R.id.fabPaste).setOnClickListener {
            pasteFromClipboard()
        }
    }
    
    private fun loadSettings() {
        selectedSpaceId = prefs.getString("space_id", "") ?: ""
        selectedSpaceName = prefs.getString("space_name", "") ?: ""
        updateStatus()
    }
    
    private fun getApiKey() = prefs.getString("api_key", "") ?: ""
    private fun getBaseUrl() = prefs.getString("base_url", "http://192.168.1.100:31009") ?: "http://192.168.1.100:31009"
    
    private fun loadEntries() {
        val json = prefs.getString("entries", "[]")
        val type = object : TypeToken<MutableList<ClipEntry>>() {}.type
        entries.clear()
        entries.addAll(Gson().fromJson(json, type) ?: mutableListOf())
        updateUI()
    }
    
    private fun saveEntries() {
        prefs.edit().putString("entries", Gson().toJson(entries)).apply()
    }
    
    private fun addEntry(content: String) {
        if (content.isBlank()) return
        if (entries.any { it.content == content }) return
        
        val preview = content.take(100).replace("\n", " ")
        entries.add(0, ClipEntry(
            id = System.currentTimeMillis(),
            content = content,
            preview = preview,
            timestamp = System.currentTimeMillis()
        ))
        
        if (entries.size > 50) entries.removeLast()
        
        saveEntries()
        updateUI()
        Toast.makeText(this, "Entry added!", Toast.LENGTH_SHORT).show()
    }
    
    private fun deleteEntry(entry: ClipEntry) {
        entries.removeAll { it.id == entry.id }
        saveEntries()
        updateUI()
    }
    
    private fun updateUI() {
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }
    
    private fun updateStatus() {
        if (isConnected && selectedSpaceName.isNotEmpty()) {
            statusText.text = "Connected to: $selectedSpaceName"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else if (isConnected) {
            statusText.text = "Connected - Select a space"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
        } else {
            statusText.text = "Not connected"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }
    
    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()
            if (!text.isNullOrBlank()) {
                addEntry(text)
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val apiKeyInput = view.findViewById<EditText>(R.id.etApiKey)
        val baseUrlInput = view.findViewById<EditText>(R.id.etBaseUrl)
        
        apiKeyInput.setText(getApiKey())
        baseUrlInput.setText(getBaseUrl())
        
        AlertDialog.Builder(this)
            .setTitle("Anytype Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("api_key", apiKeyInput.text.toString().trim())
                    .putString("base_url", baseUrlInput.text.toString().trim())
                    .apply()
                connectToAnytype()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showSpaceSelector() {
        if (spaces.isEmpty()) {
            Toast.makeText(this, "No spaces found", Toast.LENGTH_SHORT).show()
            return
        }
        
        val names = spaces.map { it.name }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Select Space")
            .setItems(names) { _, which ->
                val space = spaces[which]
                selectedSpaceId = space.id
                selectedSpaceName = space.name
                prefs.edit()
                    .putString("space_id", selectedSpaceId)
                    .putString("space_name", selectedSpaceName)
                    .apply()
                updateStatus()
                Toast.makeText(this, "Selected: ${space.name}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
    
    private fun connectToAnytype() {
        val apiKey = getApiKey()
        val baseUrl = getBaseUrl()
        
        if (apiKey.isBlank() || baseUrl.isBlank()) {
            Toast.makeText(this, "Please configure API settings", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                val api = createApi(baseUrl, apiKey)
                val response = withContext(Dispatchers.IO) { api.getSpaces() }
                
                if (response.isSuccessful) {
                    spaces = response.body()?.data ?: emptyList()
                    isConnected = true
                    
                    if (selectedSpaceId.isEmpty() && spaces.isNotEmpty()) {
                        selectedSpaceId = spaces[0].id
                        selectedSpaceName = spaces[0].name
                        prefs.edit()
                            .putString("space_id", selectedSpaceId)
                            .putString("space_name", selectedSpaceName)
                            .apply()
                    }
                    
                    updateStatus()
                    Toast.makeText(this@MainActivity, "Connected! ${spaces.size} spaces", Toast.LENGTH_SHORT).show()
                } else {
                    isConnected = false
                    updateStatus()
                    Toast.makeText(this@MainActivity, "Failed: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                isConnected = false
                updateStatus()
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun sendToAnytype(entry: ClipEntry) {
        if (!isConnected || selectedSpaceId.isEmpty()) {
            Toast.makeText(this, "Please connect first", Toast.LENGTH_SHORT).show()
            return
        }
        
        val title = entry.content.lines().firstOrNull()?.take(50)?.trimStart('#', ' ') ?: "Note from GemNote"
        
        lifecycleScope.launch {
            try {
                val api = createApi(getBaseUrl(), getApiKey())
                val request = CreateObjectRequest(
                    name = title,
                    objectTypeUniqueKey = "ot-note",
                    description = entry.content
                )
                val response = withContext(Dispatchers.IO) { 
                    api.createObject(selectedSpaceId, request) 
                }
                
                if (response.isSuccessful) {
                    entry.isSynced = true
                    saveEntries()
                    updateUI()
                    Toast.makeText(this@MainActivity, "Sent: $title", Toast.LENGTH_SHORT).show()
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Unknown error"
                    Toast.makeText(this@MainActivity, "Failed ${response.code()}: $errorBody", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun toggleService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, 
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
                return
            }
        }
        
        val intent = Intent(this, ClipboardService::class.java)
        if (ClipboardService.isRunning) {
            stopService(intent)
            Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show()
        }
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
            R.id.action_clear -> {
                entries.clear()
                saveEntries()
                updateUI()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun createApi(baseUrl: String, apiKey: String): AnytypeApi {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(HttpLoggingInterceptor().apply { 
                level = HttpLoggingInterceptor.Level.BODY 
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        
        return Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AnytypeApi::class.java)
    }
}

data class ClipEntry(
    val id: Long,
    val content: String,
    val preview: String,
    val timestamp: Long,
    var isSynced: Boolean = false
)

data class Space(val id: String, val name: String)
data class ApiResponse<T>(val data: T?)

data class CreateObjectRequest(
    val name: String,
    @SerializedName("object_type_unique_key")
    val objectTypeUniqueKey: String,
    val description: String? = null,
    val body: String? = null,
    val icon: String? = null
)

interface AnytypeApi {
    @GET("v1/spaces")
    suspend fun getSpaces(): retrofit2.Response<ApiResponse<List<Space>>>
    
    @POST("v1/spaces/{spaceId}/objects")
    suspend fun createObject(
        @Path("spaceId") spaceId: String,
        @Body request: CreateObjectRequest
    ): retrofit2.Response<Any>
}

class EntryAdapter(
    private val entries: List<ClipEntry>,
    private val onSendClick: (ClipEntry) -> Unit,
    private val onDeleteClick: (ClipEntry) -> Unit
) : RecyclerView.Adapter<EntryAdapter.ViewHolder>() {
    
    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvPreview: TextView = view.findViewById(R.id.tvPreview)
        val tvSynced: TextView = view.findViewById(R.id.tvSynced)
        val btnSend: Button = view.findViewById(R.id.btnSend)
        val btnDelete: Button = view.findViewById(R.id.btnDelete)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_entry, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.tvTime.text = dateFormat.format(Date(entry.timestamp))
        holder.tvPreview.text = entry.preview
        holder.tvSynced.visibility = if (entry.isSynced) View.VISIBLE else View.GONE
        holder.btnSend.isEnabled = !entry.isSynced
        holder.btnSend.text = if (entry.isSynced) "Sent" else "Send"
        holder.btnSend.setOnClickListener { onSendClick(entry) }
        holder.btnDelete.setOnClickListener { onDeleteClick(entry) }
    }
    
    override fun getItemCount() = entries.size
}
