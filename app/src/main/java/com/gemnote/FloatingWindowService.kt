package com.gemnote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
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

class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingService"
        const val PROXY_PORT = 31010
        const val CHANNEL_ID = "floating_channel"
        const val NOTIFICATION_ID = 1001
        
        val EXCLUDED_TYPE_KEYS = setOf(
            "audio", "video", "file", "image", 
            "participant", "spaceview", "template",
            "ot-audio", "ot-video", "ot-file", "ot-image",
            "ot-participant", "ot-template"
        )
        
        val EXCLUDED_TYPE_NAMES = setOf(
            "audio", "video", "file", "image", 
            "space member", "template", "participant"
        )
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private lateinit var prefs: SharedPreferences
    private var adapter: FloatingEntryAdapter? = null
    
    private val entries = mutableListOf<ClipEntry>()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var spaces = listOf<FloatingSpace>()
    private var objectTypes = listOf<FloatingObjectType>()
    private var selectedSpaceId = ""
    private var selectedSpaceName = ""
    private var selectedTypeKey = "note"
    private var selectedTypeName = "Note"
    private var isConnected = false
    private var isScanning = false
    
    // For dragging
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    
    // For resizing
    private var currentWidth = 300
    private var currentHeight = 450
    private val minWidth = 250
    private val minHeight = 350
    private val maxWidth = 450
    private val maxHeight = 650

    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification())
            Log.d(TAG, "Foreground started")
            
            prefs = getSharedPreferences("gemnote", Context.MODE_PRIVATE)
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            
            loadSettings()
            loadEntries()
            createFloatingWindow()
            
            if (getApiKey().isNotEmpty()) {
                autoConnect()
            }
            
            Log.d(TAG, "Service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
            stopSelf()
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GemNote Floating",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Floating window mode"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GemNote")
            .setContentText("Floating mode active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun loadSettings() {
        selectedSpaceId = prefs.getString("space_id", "") ?: ""
        selectedSpaceName = prefs.getString("space_name", "") ?: ""
        selectedTypeKey = prefs.getString("type_key", "note") ?: "note"
        selectedTypeName = prefs.getString("type_name", "Note") ?: "Note"
    }
    
    private fun getApiKey() = prefs.getString("api_key", "") ?: ""
    private fun getBaseUrl() = prefs.getString("base_url", "") ?: ""
    
    private fun loadEntries() {
        val json = prefs.getString("entries", "[]")
        val type = object : TypeToken<MutableList<ClipEntry>>() {}.type
        entries.clear()
        entries.addAll(Gson().fromJson(json, type) ?: mutableListOf())
    }
    
    private fun saveEntries() {
        prefs.edit().putString("entries", Gson().toJson(entries)).apply()
    }

    private fun createFloatingWindow() {
        Log.d(TAG, "Creating floating window")
        
        try {
            val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
            floatingView = inflater.inflate(R.layout.layout_floating_simple, null)
            
            val density = resources.displayMetrics.density
            val widthPx = (currentWidth * density).toInt()
            val heightPx = (currentHeight * density).toInt()
            
            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            layoutParams = WindowManager.LayoutParams(
                widthPx,
                heightPx,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 50
                y = 100
            }
            
            windowManager?.addView(floatingView, layoutParams)
            Log.d(TAG, "View added to WindowManager")
            
            setupViews()
            setupDragAndResize()
            updateStatus()
            updateTypeButton()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating floating window: ${e.message}", e)
            throw e
        }
    }
    
    private fun setupViews() {
        val view = floatingView ?: return
        
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerView)
        val emptyView = view.findViewById<TextView>(R.id.emptyView)
        val statusText = view.findViewById<TextView>(R.id.statusText)
        val btnConnect = view.findViewById<Button>(R.id.btnConnect)
        val btnType = view.findViewById<Button>(R.id.btnType)
        val btnClose = view.findViewById<ImageButton>(R.id.btnClose)
        val fabPaste = view.findViewById<Button>(R.id.fabPaste)
        
        // Setup adapter
        adapter = FloatingEntryAdapter(
            entries,
            onSendClick = { entry -> sendToAnytype(entry) },
            onDeleteClick = { entry -> deleteEntry(entry) }
        )
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        
        // Update empty view
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        
        // Close button
        btnClose.setOnClickListener {
            closeFloatingWindow()
        }
        
        // Paste button
        fabPaste.setOnClickListener {
            pasteFromClipboard()
        }
        
        // Connect button
        btnConnect.setOnClickListener {
            if (isConnected) {
                Toast.makeText(this, "Connected to: $selectedSpaceName", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Tap to scan...", Toast.LENGTH_SHORT).show()
                if (getApiKey().isNotEmpty()) {
                    autoScanNetwork()
                } else {
                    Toast.makeText(this, "Set API key in main app first", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        // Type button
        btnType.setOnClickListener {
            Toast.makeText(this, "Type: $selectedTypeName", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupDragAndResize() {
        val view = floatingView ?: return
        val params = layoutParams ?: return
        val wm = windowManager ?: return
        
        val dragHandle = view.findViewById<View>(R.id.dragHandle)
        val resizeHandle = view.findViewById<View>(R.id.resizeHandle)
        
        // Drag by header
        dragHandle?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    wm.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
        
        // Resize by bottom handle
        resizeHandle?.setOnTouchListener { _, event ->
            val density = resources.displayMetrics.density
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = ((event.rawX - initialTouchX) / density).toInt()
                    val deltaY = ((event.rawY - initialTouchY) / density).toInt()
                    
                    var newWidth = currentWidth + deltaX
                    var newHeight = currentHeight + deltaY
                    
                    newWidth = newWidth.coerceIn(minWidth, maxWidth)
                    newHeight = newHeight.coerceIn(minHeight, maxHeight)
                    
                    params.width = (newWidth * density).toInt()
                    params.height = (newHeight * density).toInt()
                    
                    wm.updateViewLayout(view, params)
                    
                    currentWidth = newWidth
                    currentHeight = newHeight
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                else -> false
            }
        }
    }
    
    private fun updateUI() {
        adapter?.notifyDataSetChanged()
        floatingView?.let { view ->
            view.findViewById<TextView>(R.id.emptyView)?.visibility = 
                if (entries.isEmpty()) View.VISIBLE else View.GONE
            view.findViewById<RecyclerView>(R.id.recyclerView)?.visibility = 
                if (entries.isEmpty()) View.GONE else View.VISIBLE
        }
    }
    
    private fun updateStatus() {
        floatingView?.let { view ->
            val statusText = view.findViewById<TextView>(R.id.statusText)
            val btnConnect = view.findViewById<Button>(R.id.btnConnect)
            
            when {
                isScanning -> {
                    statusText?.text = "Scanning..."
                    btnConnect?.text = "Scanning"
                }
                isConnected && selectedSpaceName.isNotEmpty() -> {
                    statusText?.text = "✓ $selectedSpaceName"
                    btnConnect?.text = "Space"
                }
                isConnected -> {
                    statusText?.text = "✓ Connected"
                    btnConnect?.text = "Space"
                }
                else -> {
                    statusText?.text = "GemNote"
                    btnConnect?.text = "Connect"
                }
            }
        }
    }
    
    private fun updateTypeButton() {
        floatingView?.findViewById<Button>(R.id.btnType)?.text = selectedTypeName
    }
    
    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()
            if (!text.isNullOrBlank()) {
                addEntry(text)
            } else {
                Toast.makeText(this, "Clipboard empty", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Clipboard empty", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun addEntry(content: String) {
        if (content.isBlank()) return
        if (entries.any { it.content == content }) {
            Toast.makeText(this, "Already exists", Toast.LENGTH_SHORT).show()
            return
        }
        
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
        Toast.makeText(this, "Added!", Toast.LENGTH_SHORT).show()
    }
    
    private fun deleteEntry(entry: ClipEntry) {
        entries.removeAll { it.id == entry.id }
        saveEntries()
        updateUI()
    }
    
    private fun closeFloatingWindow() {
        sendBroadcast(Intent("com.gemnote.FLOATING_CLOSED"))
        stopSelf()
    }
    
    // ========== Connection Logic ==========
    
    private fun getLocalSubnet(): String? {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ip = wifiInfo.ipAddress
            if (ip == 0) return null
            return String.format("%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff)
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun autoConnect() {
        val savedUrl = getBaseUrl()
        if (savedUrl.isNotEmpty()) {
            serviceScope.launch {
                val success = tryConnect(savedUrl)
                if (!success) {
                    updateStatus()
                }
            }
        }
    }
    
    private fun autoScanNetwork() {
        val apiKey = getApiKey()
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Set API key first", Toast.LENGTH_SHORT).show()
            return
        }
        
        val subnet = getLocalSubnet()
        if (subnet == null) {
            Toast.makeText(this, "Not on WiFi", Toast.LENGTH_SHORT).show()
            return
        }
        
        isScanning = true
        updateStatus()
        
        serviceScope.launch {
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
                prefs.edit().putString("base_url", foundUrl).apply()
                connectToAnytype(foundUrl!!)
            } else {
                updateStatus()
                Toast.makeText(this@FloatingWindowService, "Not found", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private suspend fun checkAnytypeAt(url: String, apiKey: String): Boolean {
        return try {
            withTimeoutOrNull(2000) {
                val api = createApi(url, apiKey)
                api.getSpaces().isSuccessful
            } ?: false
        } catch (e: Exception) { false }
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
            } else false
        } catch (e: Exception) { false }
    }
    
    private fun connectToAnytype(baseUrl: String) {
        serviceScope.launch {
            try {
                val api = createApi(baseUrl, getApiKey())
                val response = withContext(Dispatchers.IO) { api.getSpaces() }
                
                if (response.isSuccessful) {
                    spaces = response.body()?.data ?: emptyList()
                    isConnected = true
                    prefs.edit().putString("base_url", baseUrl).apply()
                    
                    if (selectedSpaceId.isEmpty() && spaces.isNotEmpty()) {
                        selectedSpaceId = spaces[0].id
                        selectedSpaceName = spaces[0].name
                        prefs.edit()
                            .putString("space_id", selectedSpaceId)
                            .putString("space_name", selectedSpaceName)
                            .apply()
                    }
                    
                    updateStatus()
                    Toast.makeText(this@FloatingWindowService, "Connected!", Toast.LENGTH_SHORT).show()
                } else {
                    isConnected = false
                    updateStatus()
                }
            } catch (e: Exception) {
                isConnected = false
                updateStatus()
            }
        }
    }
    
    private fun sendToAnytype(entry: ClipEntry) {
        if (!isConnected || selectedSpaceId.isEmpty()) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            return
        }
        
        val lines = entry.content.lines()
        val title = lines.firstOrNull()?.take(100)?.trimStart('#', ' ') ?: "Note"
        val body = if (lines.size > 1) lines.drop(1).joinToString("\n").trim() else null
        
        serviceScope.launch {
            try {
                val api = createApi(getBaseUrl(), getApiKey())
                val request = FloatingCreateObjectRequest(
                    name = title,
                    typeKey = selectedTypeKey,
                    body = body,
                    icon = FloatingObjectIcon(emoji = "📝", format = "emoji")
                )
                val response = withContext(Dispatchers.IO) {
                    api.createObject(selectedSpaceId, request)
                }
                
                if (response.isSuccessful) {
                    entry.isSynced = true
                    saveEntries()
                    updateUI()
                    Toast.makeText(this@FloatingWindowService, "Sent! 🎉", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@FloatingWindowService, "Failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@FloatingWindowService, "Error", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun createApi(baseUrl: String, apiKey: String): FloatingAnytypeApi {
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
            .create(FloatingAnytypeApi::class.java)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        serviceScope.cancel()
        floatingView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing view: ${e.message}")
            }
        }
        floatingView = null
    }
}

// Adapter for floating window - reuse item_entry.xml
class FloatingEntryAdapter(
    private val entries: List<ClipEntry>,
    private val onSendClick: (ClipEntry) -> Unit,
    private val onDeleteClick: (ClipEntry) -> Unit
) : RecyclerView.Adapter<FloatingEntryAdapter.ViewHolder>() {
    
    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvPreview: TextView = view.findViewById(R.id.tvPreview)
        val tvSynced: TextView = view.findViewById(R.id.tvSynced)
        val btnSend: Button = view.findViewById(R.id.btnSend)
        val btnDelete: Button = view.findViewById(R.id.btnDelete)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // Use the regular item_entry layout
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_entry, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.tvTime.text = dateFormat.format(Date(entry.timestamp))
        holder.tvPreview.text = entry.preview
        holder.tvSynced.visibility = if (entry.isSynced) View.VISIBLE else View.GONE
        holder.btnSend.text = if (entry.isSynced) "Sent" else "Send"
        holder.btnSend.setOnClickListener { onSendClick(entry) }
        holder.btnDelete.setOnClickListener { onDeleteClick(entry) }
    }
    
    override fun getItemCount() = entries.size
}

// Data classes for floating service
data class FloatingSpace(val id: String, val name: String)
data class FloatingApiResponse<T>(val data: T?)
data class FloatingObjectIcon(val emoji: String? = null, val format: String? = null)
data class FloatingObjectType(val id: String, val key: String, val name: String, val icon: FloatingObjectIcon? = null)
data class FloatingCreateObjectRequest(
    val name: String,
    @SerializedName("type_key") val typeKey: String,
    val body: String? = null,
    val icon: FloatingObjectIcon? = null
)

interface FloatingAnytypeApi {
    @GET("v1/spaces")
    suspend fun getSpaces(): retrofit2.Response<FloatingApiResponse<List<FloatingSpace>>>
    
    @GET("v1/spaces/{spaceId}/types")
    suspend fun getTypes(@Path("spaceId") spaceId: String): retrofit2.Response<FloatingApiResponse<List<FloatingObjectType>>>
    
    @POST("v1/spaces/{spaceId}/objects")
    suspend fun createObject(@Path("spaceId") spaceId: String, @Body request: FloatingCreateObjectRequest): retrofit2.Response<Any>
}
