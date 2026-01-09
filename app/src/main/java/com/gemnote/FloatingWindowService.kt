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
import android.view.ViewGroup
import android.view.WindowManager
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
    
    private var statusText: TextView? = null
    private var entriesContainer: LinearLayout? = null
    
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

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
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
        val btnBg = Color.parseColor("#E8E0F0")
        val white = Color.WHITE
        
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(white, 16f)
            elevation = 12f
        }
        
        // ===== HEADER with buttons =====
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(purple)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(8), dpToPx(8), dpToPx(8))
        }
        
        statusText = TextView(this).apply {
            text = "GemNote"
            setTextColor(white)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(statusText)
        
        // Add +, C, T buttons in header (where we know clicks work)
        val pasteBtn = TextView(this).apply {
            text = "+"
            setTextColor(white)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = createRoundedDrawable(Color.parseColor("#8B6ECA"), 6f)
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(36))
            setOnClickListener { pasteFromClipboard() }
        }
        header.addView(pasteBtn)
        
        val connectBtn = TextView(this).apply {
            text = "C"
            setTextColor(white)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = createRoundedDrawable(Color.parseColor("#8B6ECA"), 6f)
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(36)).apply {
                marginStart = dpToPx(6)
            }
            setOnClickListener { onConnectClick() }
        }
        header.addView(connectBtn)
        
        val typeBtn = TextView(this).apply {
            text = "T"
            setTextColor(white)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = createRoundedDrawable(Color.parseColor("#8B6ECA"), 6f)
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(36)).apply {
                marginStart = dpToPx(6)
            }
            setOnClickListener { showToast("Type: $selectedTypeName") }
        }
        header.addView(typeBtn)
        
        val closeBtn = TextView(this).apply {
            text = "X"
            setTextColor(white)
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(36)).apply {
                marginStart = dpToPx(6)
            }
            setOnClickListener { closeFloatingWindow() }
        }
        header.addView(closeBtn)
        
        rootLayout.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        
        // ===== CONTENT - Simple LinearLayout, NO ScrollView =====
        entriesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(lightPurple)
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
        }
        
        rootLayout.addView(entriesContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        
        // ===== RESIZE HANDLE =====
        val resizeHandle = TextView(this).apply {
            text = "="
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9080C0"))
            setBackgroundColor(Color.parseColor("#E8E0F0"))
        }
        rootLayout.addView(resizeHandle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(24)
        ))
        
        floatingView = rootLayout
        
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        layoutParams = WindowManager.LayoutParams(
            dpToPx(300),
            dpToPx(400),
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 150
        }
        
        windowManager?.addView(floatingView, layoutParams)
        
        setupDrag(header)
        setupResize(resizeHandle)
        
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
        var isDragging = false
        
        dragView.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            val wm = windowManager ?: return@setOnTouchListener false
            val view = floatingView ?: return@setOnTouchListener false
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        wm.updateViewLayout(view, params)
                    }
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
                    
                    params.width = (params.width + deltaX).coerceIn(dpToPx(250), dpToPx(450))
                    params.height = (params.height + deltaY).coerceIn(dpToPx(300), dpToPx(600))
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
            }
            isConnected -> {
                statusText?.text = "✓ Connected"
            }
            else -> {
                statusText?.text = "GemNote"
            }
        }
    }
    
    private fun updateEntriesUI() {
        val container = entriesContainer ?: return
        container.removeAllViews()
        
        if (entries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No entries yet\n\nCopy text, then tap + to paste"
                setTextColor(Color.parseColor("#888888"))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dpToPx(16), dpToPx(40), dpToPx(16), dpToPx(40))
            })
            return
        }
        
        val purple = Color.parseColor("#6B4EAA")
        val btnBg = Color.parseColor("#E8E0F0")
        
        // Show only first 5 entries to avoid scrolling issues
        for (entry in entries.take(5)) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = createRoundedDrawable(Color.WHITE, 8f)
                setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
                elevation = 2f
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpToPx(6) }
            }
            
            // Preview text
            val previewText = TextView(this).apply {
                text = entry.preview.take(40) + if (entry.preview.length > 40) "..." else ""
                setTextColor(Color.parseColor("#333333"))
                textSize = 12f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            card.addView(previewText)
            
            // Synced indicator
            if (entry.isSynced) {
                val syncIcon = TextView(this).apply {
                    text = "✓"
                    setTextColor(Color.parseColor("#4CAF50"))
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dpToPx(6) }
                }
                card.addView(syncIcon)
            }
            
            // Send button
            val sendBtn = TextView(this).apply {
                text = "S"
                setTextColor(purple)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = createRoundedDrawable(btnBg, 6f)
                layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(32))
                setOnClickListener { sendToAnytype(entry) }
            }
            card.addView(sendBtn)
            
            // Delete button
            val deleteBtn = TextView(this).apply {
                text = "D"
                setTextColor(purple)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = createRoundedDrawable(btnBg, 6f)
                layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(32)).apply {
                    marginStart = dpToPx(4)
                }
                setOnClickListener { deleteEntry(entry) }
            }
            card.addView(deleteBtn)
            
            container.addView(card)
        }
        
        // Show count if more entries
        if (entries.size > 5) {
            container.addView(TextView(this).apply {
                text = "+ ${entries.size - 5} more entries"
                setTextColor(Color.parseColor("#999999"))
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(4), 0, 0)
            })
        }
    }
    
    private fun onConnectClick() {
        if (isConnected) {
            showToast("Connected to: $selectedSpaceName")
        } else {
            if (getApiKey().isNotEmpty()) {
                showToast("Scanning network...")
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
                showToast("Clipboard is empty")
            }
        } else {
            showToast("Clipboard is empty")
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
        showToast("Entry added!")
    }
    
    private fun deleteEntry(entry: ClipEntry) {
        entries.removeAll { it.id == entry.id }
        saveEntries()
        updateEntriesUI()
        showToast("Deleted")
    }
    
    private fun closeFloatingWindow() {
        sendBroadcast(Intent("com.gemnote.FLOATING_CLOSED"))
        stopSelf()
    }
    
    // ========== Network ==========
    
    private fun getLocalSubnet(): String? {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifiManager.connectionInfo.ipAddress
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
                if (tryConnect(savedUrl)) {
                    handler.post { updateStatus() }
                }
            }
        }
    }
    
    private fun autoScanNetwork() {
        val apiKey = getApiKey()
        if (apiKey.isEmpty()) return
        
        val subnet = getLocalSubnet() ?: run {
            showToast("Not on WiFi")
            return
        }
        
        serviceScope.launch {
            var foundUrl: String? = null
            val ips = (1..254).map { "$subnet.$it" }
            
            withContext(Dispatchers.IO) {
                ips.chunked(50).forEach { batch ->
                    if (foundUrl != null) return@forEach
                    foundUrl = batch.map { ip ->
                        async {
                            val url = "http://$ip:$PROXY_PORT"
                            if (checkAnytypeAt(url, apiKey)) url else null
                        }
                    }.awaitAll().filterNotNull().firstOrNull()
                }
            }
            
            if (foundUrl != null) {
                prefs.edit().putString("base_url", foundUrl).apply()
                if (tryConnect(foundUrl!!)) {
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
                createApi(url, apiKey).getSpaces().isSuccessful
            } ?: false
        } catch (e: Exception) { false }
    }
    
    private suspend fun tryConnect(url: String): Boolean {
        return try {
            val response = withContext(Dispatchers.IO) { createApi(url, getApiKey()).getSpaces() }
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
        
        showToast("Sending...")
        
        val lines = entry.content.lines()
        val title = lines.firstOrNull()?.take(100)?.trimStart('#', ' ') ?: "Note"
        val body = if (lines.size > 1) lines.drop(1).joinToString("\n").trim() else null
        
        serviceScope.launch {
            try {
                val request = FloatingCreateObjectRequest(
                    name = title,
                    typeKey = selectedTypeKey,
                    body = body,
                    icon = FloatingObjectIcon(emoji = "📝", format = "emoji")
                )
                val response = withContext(Dispatchers.IO) {
                    createApi(getBaseUrl(), getApiKey()).createObject(selectedSpaceId, request)
                }
                
                if (response.isSuccessful) {
                    entry.isSynced = true
                    saveEntries()
                    handler.post { updateEntriesUI() }
                    showToast("Sent!")
                } else {
                    showToast("Failed")
                }
            } catch (e: Exception) {
                showToast("Error: ${e.message}")
            }
        }
    }
    
    private fun createApi(baseUrl: String, apiKey: String): FloatingAnytypeApi {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .build())
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
        floatingView?.let { 
            try { windowManager?.removeView(it) } catch (e: Exception) {}
        }
    }
}

data class FloatingSpace(val id: String, val name: String)
data class FloatingApiResponse<T>(val data: T?)
data class FloatingObjectIcon(val emoji: String? = null, val format: String? = null)
data class FloatingCreateObjectRequest(
    val name: String,
    @SerializedName("type_key") val typeKey: String,
    val body: String? = null,
    val icon: FloatingObjectIcon? = null
)

interface FloatingAnytypeApi {
    @GET("v1/spaces")
    suspend fun getSpaces(): retrofit2.Response<FloatingApiResponse<List<FloatingSpace>>>
    
    @POST("v1/spaces/{spaceId}/objects")
    suspend fun createObject(@Path("spaceId") spaceId: String, @Body request: FloatingCreateObjectRequest): retrofit2.Response<Any>
}
