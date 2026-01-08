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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
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
    
    private val entries = mutableListOf<ClipEntry>()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    
    private var spaces = listOf<FloatingSpace>()
    private var selectedSpaceId = ""
    private var selectedSpaceName = ""
    private var selectedTypeKey = "note"
    private var selectedTypeName = "Note"
    private var isConnected = false
    
    // Views to update
    private var statusText: TextView? = null
    private var entriesContainer: LinearLayout? = null
    private var emptyText: TextView? = null
    private var connectBtn: Button? = null
    private var typeBtn: Button? = null
    
    // For dragging
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    
    // For resizing
    private var currentWidth = 300
    private var currentHeight = 450

    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification())
            
            prefs = getSharedPreferences("gemnote", Context.MODE_PRIVATE)
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            
            loadSettings()
            loadEntries()
            
            handler.postDelayed({
                createFloatingWindow()
                if (getApiKey().isNotEmpty()) {
                    autoConnect()
                }
            }, 300)
            
        } catch (e: Exception) {
            showToast("Error: ${e.message}")
            stopSelf()
        }
    }
    
    private fun showToast(msg: String) {
        handler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
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
        val purple = Color.parseColor("#6B4EAA")
        val lightPurple = Color.parseColor("#F5F0FF")
        val white = Color.WHITE
        
        // Root layout with rounded corners
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(white, 16f)
            elevation = 12f
            clipToOutline = true
        }
        
        // ===== HEADER (drag handle) =====
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(purple)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(8), dpToPx(8), dpToPx(8))
        }
        
        statusText = TextView(this).apply {
            text = "GemNote"
            setTextColor(white)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(statusText)
        
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(white)
            textSize = 18f
            setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4))
            setOnClickListener { closeFloatingWindow() }
        }
        header.addView(closeBtn)
        
        rootLayout.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // ===== CONTENT AREA (scrollable entries) =====
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(lightPurple)
        }
        
        entriesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
        }
        
        emptyText = TextView(this).apply {
            text = "No entries yet\n\nCopy text, then tap + to paste"
            setTextColor(Color.parseColor("#888888"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dpToPx(16), dpToPx(40), dpToPx(16), dpToPx(40))
        }
        entriesContainer?.addView(emptyText)
        
        scrollView.addView(entriesContainer)
        rootLayout.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        
        // ===== BOTTOM BAR =====
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(white)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
        }
        
        // Paste button (circular) - use TextView for better click handling
        val pasteBtn = TextView(this).apply {
            text = "+"
            setTextColor(purple)
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = createRoundedDrawable(Color.parseColor("#E8E0F0"), 50f)
            layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44))
            setOnClickListener { 
                showToast("Pasting...")
                pasteFromClipboard() 
            }
        }
        bottomBar.addView(pasteBtn)
        
        // Connect button - use TextView for better click handling
        connectBtn = Button(this).apply {
            text = "CONNECT"
            setTextColor(white)
            textSize = 11f
            isAllCaps = true
            background = createRoundedDrawable(purple, 8f)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(40), 1f).apply {
                marginStart = dpToPx(6)
                marginEnd = dpToPx(4)
            }
            setOnClickListener { 
                showToast("Connect tapped")
                onConnectClick() 
            }
        }
        bottomBar.addView(connectBtn)
        
        // Type button
        typeBtn = Button(this).apply {
            text = selectedTypeName.uppercase()
            setTextColor(white)
            textSize = 11f
            isAllCaps = true
            background = createRoundedDrawable(purple, 8f)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(40), 1f).apply {
                marginStart = dpToPx(4)
            }
            setOnClickListener { 
                showToast("Type: $selectedTypeName") 
            }
        }
        bottomBar.addView(typeBtn)
        
        rootLayout.addView(bottomBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // ===== RESIZE HANDLE =====
        val resizeHandle = View(this).apply {
            setBackgroundColor(Color.parseColor("#D8D0F0"))
        }
        rootLayout.addView(resizeHandle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(12)
        ))
        
        floatingView = rootLayout
        
        // Window params - REMOVE FLAG_NOT_FOCUSABLE so buttons work
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        layoutParams = WindowManager.LayoutParams(
            dpToPx(currentWidth),
            dpToPx(currentHeight),
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 150
        }
        
        windowManager?.addView(floatingView, layoutParams)
        
        // Setup interactions
        setupDrag(header)
        setupResize(resizeHandle)
        
        // Render entries
        updateEntriesUI()
        updateStatus()
    }
    
    private fun createRoundedDrawable(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpToPx(radius.toInt()).toFloat()
        }
    }
    
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
    
    private fun setupDrag(dragView: View) {
        dragView.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            val wm = windowManager ?: return@setOnTouchListener false
            val view = floatingView ?: return@setOnTouchListener false
            
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
    }
    
    private fun setupResize(resizeView: View) {
        resizeView.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            val wm = windowManager ?: return@setOnTouchListener false
            val view = floatingView ?: return@setOnTouchListener false
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    
                    val newWidth = (params.width + deltaX).coerceIn(dpToPx(250), dpToPx(450))
                    val newHeight = (params.height + deltaY).coerceIn(dpToPx(350), dpToPx(650))
                    
                    params.width = newWidth
                    params.height = newHeight
                    wm.updateViewLayout(view, params)
                    
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                else -> false
            }
        }
    }
    
    private fun updateStatus() {
        when {
            isConnected && selectedSpaceName.isNotEmpty() -> {
                statusText?.text = "✓ $selectedSpaceName"
                connectBtn?.text = "SPACE"
            }
            isConnected -> {
                statusText?.text = "✓ Connected"
                connectBtn?.text = "SPACE"
            }
            else -> {
                statusText?.text = "GemNote"
                connectBtn?.text = "CONNECT"
            }
        }
        typeBtn?.text = selectedTypeName.uppercase()
    }
    
    private fun updateEntriesUI() {
        val container = entriesContainer ?: return
        container.removeAllViews()
        
        if (entries.isEmpty()) {
            emptyText = TextView(this).apply {
                text = "No entries yet\n\nCopy text, then tap + to paste"
                setTextColor(Color.parseColor("#888888"))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dpToPx(16), dpToPx(40), dpToPx(16), dpToPx(40))
            }
            container.addView(emptyText)
            return
        }
        
        for (entry in entries) {
            container.addView(createEntryCard(entry))
        }
    }
    
    private fun createEntryCard(entry: ClipEntry): View {
        val purple = Color.parseColor("#6B4EAA")
        
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(Color.WHITE, 8f)
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            elevation = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(6)
            }
        }
        
        // Time + synced status
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        
        val timeText = TextView(this).apply {
            text = dateFormat.format(Date(entry.timestamp))
            setTextColor(Color.parseColor("#999999"))
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(timeText)
        
        if (entry.isSynced) {
            val syncedText = TextView(this).apply {
                text = "Synced"
                setTextColor(Color.parseColor("#4CAF50"))
                textSize = 10f
                setBackgroundColor(Color.parseColor("#E8F5E9"))
                setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
            }
            headerRow.addView(syncedText)
        }
        
        card.addView(headerRow)
        
        // Preview text
        val previewText = TextView(this).apply {
            text = entry.preview
            setTextColor(Color.parseColor("#333333"))
            textSize = 12f
            maxLines = 2
            setPadding(0, dpToPx(4), 0, dpToPx(6))
        }
        card.addView(previewText)
        
        // Buttons row
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        
        val sendBtn = Button(this).apply {
            text = if (entry.isSynced) "SENT" else "SEND"
            setTextColor(Color.WHITE)
            textSize = 10f
            isAllCaps = true
            background = createRoundedDrawable(purple, 6f)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(32), 1f).apply {
                marginEnd = dpToPx(6)
            }
            setOnClickListener { sendToAnytype(entry) }
        }
        buttonsRow.addView(sendBtn)
        
        val deleteBtn = Button(this).apply {
            text = "DELETE"
            setTextColor(Color.parseColor("#666666"))
            textSize = 10f
            isAllCaps = true
            background = createRoundedDrawable(Color.parseColor("#E0E0E0"), 6f)
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(32), 1f)
            setOnClickListener { deleteEntry(entry) }
        }
        buttonsRow.addView(deleteBtn)
        
        card.addView(buttonsRow)
        
        return card
    }
    
    private fun onConnectClick() {
        if (isConnected) {
            showToast("Connected to: $selectedSpaceName")
        } else {
            if (getApiKey().isNotEmpty()) {
                showToast("Scanning...")
                autoScanNetwork()
            } else {
                showToast("Set API key in main app first")
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
                showToast("Clipboard empty")
            }
        } else {
            showToast("Clipboard empty")
        }
    }
    
    private fun addEntry(content: String) {
        if (content.isBlank()) return
        if (entries.any { it.content == content }) {
            showToast("Already exists")
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
        updateEntriesUI()
        showToast("Added!")
    }
    
    private fun deleteEntry(entry: ClipEntry) {
        entries.removeAll { it.id == entry.id }
        saveEntries()
        updateEntriesUI()
    }
    
    private fun closeFloatingWindow() {
        sendBroadcast(Intent("com.gemnote.FLOATING_CLOSED"))
        stopSelf()
    }
    
    // ========== Network ==========
    
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
                if (success) {
                    handler.post { updateStatus() }
                }
            }
        }
    }
    
    private fun autoScanNetwork() {
        val apiKey = getApiKey()
        if (apiKey.isEmpty()) return
        
        val subnet = getLocalSubnet()
        if (subnet == null) {
            showToast("Not on WiFi")
            return
        }
        
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
            
            if (foundUrl != null) {
                prefs.edit().putString("base_url", foundUrl).apply()
                val success = tryConnect(foundUrl!!)
                if (success) {
                    showToast("Connected!")
                    handler.post { updateStatus() }
                }
            } else {
                showToast("Not found")
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
                true
            } else false
        } catch (e: Exception) { false }
    }
    
    private fun sendToAnytype(entry: ClipEntry) {
        if (!isConnected || selectedSpaceId.isEmpty()) {
            showToast("Not connected")
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
                    handler.post { updateEntriesUI() }
                    showToast("Sent! 🎉")
                } else {
                    showToast("Failed")
                }
            } catch (e: Exception) {
                showToast("Error")
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
        serviceScope.cancel()
        floatingView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {}
        }
        floatingView = null
    }
}

// Data classes
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
