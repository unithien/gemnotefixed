package com.gemnote

import android.Manifest
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
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

    companion object {
        const val PROXY_PORT = 31010
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var statusText: TextView
    private lateinit var btnConnect: Button
    private lateinit var adapter: EntryAdapter
    
    private val entries = mutableListOf<ClipEntry>()
    private var spaces = listOf<Space>()
    private var selectedSpaceId = ""
    private var selectedSpaceName = ""
    private var isConnected = false
    private var isScanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        prefs = getSharedPreferences("gemnote", Context.MODE_PRIVATE)
        
        setupViews()
        loadEntries()
        loadSettings()
        
        handleShareIntent(intent)
        
        if (getApiKey().isNotEmpty()) {
            autoConnect()
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
        btnConnect = findViewById(R.id.btnConnect)
        
        adapter = EntryAdapter(
            entries,
            onSendClick = { entry -> sendToAnytype(entry) },
            onDeleteClick = { entry -> deleteEntry(entry) }
        )
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        
        btnConnect.setOnClickListener {
            when {
                isScanning -> Toast.makeText(this, "Scanning...", Toast.LENGTH_SHORT).show()
                isConnected -> showSpaceSelector()
                else -> showConnectionOptions()
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
    private fun getBaseUrl() = prefs.getString("base_url", "") ?: ""
    
    private fun saveBaseUrl(url: String) {
        prefs.edit().putString("base_url", url).apply()
    }
    
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
        when {
            isScanning -> {
                statusText.text = "🔍 Scanning network..."
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
                btnConnect.text = "Scanning..."
            }
            isConnected && selectedSpaceName.isNotEmpty() -> {
                statusText.text = "✓ Connected to: $selectedSpaceName"
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                btnConnect.text = "Change Space"
            }
            isConnected -> {
                statusText.text = "✓ Connected - Select a space"
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                btnConnect.text = "Select Space"
            }
            else -> {
                statusText.text = "Not connected"
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
                btnConnect.text = "Connect"
            }
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
    
    private fun showConnectionOptions() {
        val options = arrayOf("🔍 Auto-scan network", "⚙️ Manual settings")
        
        AlertDialog.Builder(this)
            .setTitle("Connect to Anytype")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        if (getApiKey().isEmpty()) {
                            showApiKeyDialog { autoScanNetwork() }
                        } else {
                            autoScanNetwork()
                        }
                    }
                    1 -> showSettingsDialog()
                }
            }
            .show()
    }
    
    private fun showApiKeyDialog(onSuccess: () -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_apikey, null)
        val apiKeyInput = view.findViewById<EditText>(R.id.etApiKey)
        apiKeyInput.setText(getApiKey())
        
        AlertDialog.Builder(this)
            .setTitle("Enter API Key")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val key = apiKeyInput.text.toString().trim()
                if (key.isNotEmpty()) {
                    prefs.edit().putString("api_key", key).apply()
                    onSuccess()
                } else {
                    Toast.makeText(this, "API key is required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val apiKeyInput = view.findViewById<EditText>(R.id.etApiKey)
        val baseUrlInput = view.findViewById<EditText>(R.id.etBaseUrl)
        
        apiKeyInput.setText(getApiKey())
        baseUrlInput.setText(getBaseUrl().ifEmpty { "http://192.168.1.100:$PROXY_PORT" })
        
        AlertDialog.Builder(this)
            .setTitle("Anytype Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("api_key", apiKeyInput.text.toString().trim())
                    .putString("base_url", baseUrlInput.text.toString().trim())
                    .apply()
                connectToAnytype(baseUrlInput.text.toString().trim())
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
    
    private fun getLocalSubnet(): String? {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ip = wifiInfo.ipAddress
            
            if (ip == 0) return null
            
            return String.format(
                "%d.%d.%d",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff
            )
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun autoConnect() {
        val savedUrl = getBaseUrl()
        if (savedUrl.isNotEmpty()) {
            lifecycleScope.launch {
                val success = tryConnect(savedUrl)
                if (!success) {
                    autoScanNetwork()
                }
            }
        } else {
            autoScanNetwork()
        }
    }
    
    private fun autoScanNetwork() {
        val apiKey = getApiKey()
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Please set API key first", Toast.LENGTH_SHORT).show()
            return
        }
        
        val subnet = getLocalSubnet()
        if (subnet == null) {
            Toast.makeText(this, "Not connected to WiFi", Toast.LENGTH_SHORT).show()
            return
        }
        
        isScanning = true
        updateStatus()
        
        lifecycleScope.launch {
            var foundUrl: String? = null
            
            val ips = (1..254).map { "$subnet.$it" }
            
            withContext(Dispatchers.IO) {
                ips.chunked(50).forEach { batch ->
                    if (foundUrl != null) return@forEach
                    
                    val results = batch.map { ip ->
                        async {
                            val url = "http://$ip:$PROXY_PORT"
                            if (checkAnytypeAt(url, apiKey)) url else null
                        }
                    }.awaitAll()
                    
                    foundUrl = results.filterNotNull().firstOrNull()
                }
            }
            
            isScanning = false
            
            if (foundUrl != null) {
                saveBaseUrl(foundUrl!!)
                connectToAnytype(foundUrl!!)
                Toast.makeText(this@MainActivity, "Found: $foundUrl", Toast.LENGTH_SHORT).show()
            } else {
                updateStatus()
                Toast.makeText(this@MainActivity, "Proxy not found. Run START_PROXY.bat on PC!", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private suspend fun checkAnytypeAt(url: String, apiKey: String): Boolean {
        return try {
            withTimeoutOrNull(2000) {
                val api = createApi(url, apiKey)
                val response = api.getSpaces()
                response.isSuccessful
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun tryConnect(url: String): Boolean {
        return try {
            val api = createApi(url, getApiKey())
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
                
                withContext(Dispatchers.Main) { updateStatus() }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun connectToAnytype(baseUrl: String) {
        val apiKey = getApiKey()
        
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
                    saveBaseUrl(baseUrl)
                    
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
        
        // Split content into title (first line) and body (rest)
        val lines = entry.content.lines()
        val title = lines.firstOrNull()?.take(100)?.trimStart('#', ' ') ?: "Note from GemNote"
        
        // Body is everything after the first line (if any)
        val body = if (lines.size > 1) {
            lines.drop(1).joinToString("\n").trim()
        } else {
            null  // No body if only one line
        }
        
        lifecycleScope.launch {
            try {
                val api = createApi(getBaseUrl(), getApiKey())
                val request = CreateObjectRequest(
                    name = title,
                    typeKey = "note",
                    body = body,
                    icon = ObjectIcon(emoji = "📝", format = "emoji")
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
                    val errorBody = response.errorBody()?.string() ?: ""
                    Toast.makeText(this@MainActivity, "Failed ${response.code()}: $errorBody", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
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
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
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

data class ObjectIcon(
    val emoji: String,
    val format: String
)

data class CreateObjectRequest(
    val name: String,
    @SerializedName("type_key")
    val typeKey: String,
    val body: String? = null,
    val icon: ObjectIcon? = null
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
