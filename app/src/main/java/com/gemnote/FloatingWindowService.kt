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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
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
    private val handler = Handler(Looper.getMainLooper())
    
    private var spaces = listOf<FloatingSpace>()
    private var objectTypes = listOf<FloatingObjectType>()
    private var selectedSpaceId = ""
    private var selectedSpaceName = ""
    private var selectedTypeKey = "note"
    private var selectedTypeName = "Note"
    private var isConnected = false
    
    // For dragging
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        
        showToast("Service onCreate")
        
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, createNotification())
            showToast("Foreground started")
            
            prefs = getSharedPreferences("gemnote", Context.MODE_PRIVATE)
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            
            loadSettings()
            loadEntries()
            
            // Delay window creation slightly
            handler.postDelayed({
                try {
                    createFloatingWindow()
                    showToast("Window created!")
                } catch (e: Exception) {
                    showToast("Window error: ${e.message}")
                }
            }, 500)
            
            if (getApiKey().isNotEmpty()) {
                autoConnect()
            }
            
        } catch (e: Exception) {
            showToast("onCreate error: ${e.message}")
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
        showToast("Creating window...")
        
        // Create a simple view programmatically instead of inflating XML
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = 10f
        }
        
        // Header (purple bar with drag + close)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#6B4EAA"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 10, 20, 10)
        }
        
        val titleText = TextView(this).apply {
            text = if (selectedSpaceName.isNotEmpty()) "✓ $selectedSpaceName" else "GemNote"
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(titleText)
        
        val closeBtn = Button(this).apply {
            text = "X"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                closeFloatingWindow()
            }
        }
        header.addView(closeBtn)
        
        rootLayout.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        
        // Content area
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F0FF"))
            setPadding(20, 20, 20, 20)
            gravity = Gravity.CENTER
        }
        
        val infoText = TextView(this).apply {
            text = "Floating Window Works!\n\nEntries: ${entries.size}\n\nDrag header to move"
            setTextColor(Color.parseColor("#333333"))
            textSize = 14f
            gravity = Gravity.CENTER
        }
        content.addView(infoText)
        
        // Paste button
        val pasteBtn = Button(this).apply {
            text = "+ Paste"
            setBackgroundColor(Color.parseColor("#6B4EAA"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                pasteFromClipboard()
            }
        }
        content.addView(pasteBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 20 })
        
        rootLayout.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        
        floatingView = rootLayout
        
        // Window params
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        layoutParams = WindowManager.LayoutParams(
            dpToPx(280),
            dpToPx(350),
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }
        
        // Add view to window manager
        try {
            windowManager?.addView(floatingView, layoutParams)
            showToast("View added!")
        } catch (e: Exception) {
            showToast("addView error: ${e.message}")
            throw e
        }
        
        // Setup drag
        setupDrag(header)
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
        showToast("Added! (${entries.size} entries)")
    }
    
    private fun closeFloatingWindow() {
        sendBroadcast(Intent("com.gemnote.FLOATING_CLOSED"))
        stopSelf()
    }
    
    private fun autoConnect() {
        val savedUrl = getBaseUrl()
        if (savedUrl.isNotEmpty()) {
            serviceScope.launch {
                tryConnect(savedUrl)
            }
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
                true
            } else false
        } catch (e: Exception) { false }
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
            } catch (e: Exception) {
                // ignore
            }
        }
        floatingView = null
    }
}

// Adapter for floating window
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
