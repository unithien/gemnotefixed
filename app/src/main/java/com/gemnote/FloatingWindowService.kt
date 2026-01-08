package com.gemnote

import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: FloatingEntryAdapter
    
    private val entries = mutableListOf<ClipEntry>()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    
    private var selectedSpaceId = ""
    private var selectedTypeKey = "note"
    private var selectedTypeName = "Note"
    
    // For dragging
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    
    // For resizing
    private var initialWidth = 280
    private var initialHeight = 400
    private var minWidth = 200
    private var minHeight = 300
    private var maxWidth = 500
    private var maxHeight = 700

    private lateinit var layoutParams: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        
        prefs = getSharedPreferences("gemnote", Context.MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        loadSettings()
        loadEntries()
        createFloatingWindow()
    }
    
    private fun loadSettings() {
        selectedSpaceId = prefs.getString("space_id", "") ?: ""
        selectedTypeKey = prefs.getString("type_key", "note") ?: "note"
        selectedTypeName = prefs.getString("type_name", "Note") ?: "Note"
    }
    
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
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.layout_floating, null)
        
        // Density for dp to px conversion
        val density = resources.displayMetrics.density
        val widthPx = (initialWidth * density).toInt()
        val heightPx = (initialHeight * density).toInt()
        
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
            x = 100
            y = 200
        }
        
        windowManager.addView(floatingView, layoutParams)
        
        setupViews()
        setupDragAndResize()
    }
    
    private fun setupViews() {
        val recyclerView = floatingView.findViewById<RecyclerView>(R.id.floatingRecyclerView)
        val emptyView = floatingView.findViewById<TextView>(R.id.floatingEmptyView)
        val statusText = floatingView.findViewById<TextView>(R.id.floatingStatus)
        val btnClose = floatingView.findViewById<ImageButton>(R.id.btnClose)
        val btnPaste = floatingView.findViewById<ImageButton>(R.id.floatingFabPaste)
        val btnType = floatingView.findViewById<Button>(R.id.floatingBtnType)
        
        // Update status
        val spaceName = prefs.getString("space_name", "") ?: ""
        statusText.text = if (spaceName.isNotEmpty()) "✓ $spaceName" else "GemNote"
        
        // Update type button
        btnType.text = selectedTypeName
        
        // Setup adapter
        adapter = FloatingEntryAdapter(
            entries,
            onSendClick = { entry -> sendToAnytype(entry) },
            onDeleteClick = { entry -> deleteEntry(entry) }
        )
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        
        // Update visibility
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        
        // Close button
        btnClose.setOnClickListener {
            stopSelf()
            // Notify MainActivity to turn off toggle
            sendBroadcast(Intent("com.gemnote.FLOATING_CLOSED"))
        }
        
        // Paste button
        btnPaste.setOnClickListener {
            pasteFromClipboard()
        }
        
        // Type button - just show current type (full selection needs activity)
        btnType.setOnClickListener {
            Toast.makeText(this, "Type: $selectedTypeName", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun setupDragAndResize() {
        val header = floatingView.findViewById<View>(R.id.floatingHeader)
        val resizeHandle = floatingView.findViewById<View>(R.id.resizeHandle)
        val card = floatingView.findViewById<CardView>(R.id.floatingCard)
        
        // Drag by header
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    true
                }
                else -> false
            }
        }
        
        // Resize by bottom handle
        resizeHandle.setOnTouchListener { _, event ->
            val density = resources.displayMetrics.density
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialWidth = (layoutParams.width / density).toInt()
                    initialHeight = (layoutParams.height / density).toInt()
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = ((event.rawX - initialTouchX) / density).toInt()
                    val deltaY = ((event.rawY - initialTouchY) / density).toInt()
                    
                    var newWidth = initialWidth + deltaX
                    var newHeight = initialHeight + deltaY
                    
                    // Constrain to min/max
                    newWidth = newWidth.coerceIn(minWidth, maxWidth)
                    newHeight = newHeight.coerceIn(minHeight, maxHeight)
                    
                    layoutParams.width = (newWidth * density).toInt()
                    layoutParams.height = (newHeight * density).toInt()
                    
                    windowManager.updateViewLayout(floatingView, layoutParams)
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
                Toast.makeText(this, "Clipboard empty", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Clipboard empty", Toast.LENGTH_SHORT).show()
        }
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
        Toast.makeText(this, "Added!", Toast.LENGTH_SHORT).show()
    }
    
    private fun deleteEntry(entry: ClipEntry) {
        entries.removeAll { it.id == entry.id }
        saveEntries()
        updateUI()
    }
    
    private fun updateUI() {
        val recyclerView = floatingView.findViewById<RecyclerView>(R.id.floatingRecyclerView)
        val emptyView = floatingView.findViewById<TextView>(R.id.floatingEmptyView)
        
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }
    
    private fun sendToAnytype(entry: ClipEntry) {
        val baseUrl = prefs.getString("base_url", "") ?: ""
        val apiKey = prefs.getString("api_key", "") ?: ""
        
        if (baseUrl.isEmpty() || apiKey.isEmpty() || selectedSpaceId.isEmpty()) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            return
        }
        
        val lines = entry.content.lines()
        val title = lines.firstOrNull()?.take(100)?.trimStart('#', ' ') ?: "Note"
        val body = if (lines.size > 1) lines.drop(1).joinToString("\n").trim() else null
        
        serviceScope.launch {
            try {
                val api = createApi(baseUrl, apiKey)
                val request = FloatingCreateObjectRequest(
                    name = title,
                    typeKey = selectedTypeKey,
                    body = body,
                    icon = FloatingObjectIcon(emoji = "\uD83D\uDCDD", format = "emoji")
                )
                val response = withContext(Dispatchers.IO) {
                    api.createObject(selectedSpaceId, request)
                }
                
                if (response.isSuccessful) {
                    entry.isSynced = true
                    saveEntries()
                    updateUI()
                    Toast.makeText(this@FloatingWindowService, "success \uD83C\uDF89", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@FloatingWindowService, "Failed: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@FloatingWindowService, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
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
        serviceJob.cancel()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
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
            .inflate(R.layout.item_entry_floating, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.tvTime.text = dateFormat.format(Date(entry.timestamp))
        holder.tvPreview.text = entry.preview
        holder.tvSynced.visibility = if (entry.isSynced) View.VISIBLE else View.GONE
        holder.btnSend.isEnabled = true
        holder.btnSend.text = if (entry.isSynced) "Sent" else "Send"
        holder.btnSend.setOnClickListener { onSendClick(entry) }
        holder.btnDelete.setOnClickListener { onDeleteClick(entry) }
    }
    
    override fun getItemCount() = entries.size
}

// API interface for floating service
data class FloatingObjectIcon(
    val emoji: String? = null,
    val format: String? = null
)

data class FloatingCreateObjectRequest(
    val name: String,
    @SerializedName("type_key")
    val typeKey: String,
    val body: String? = null,
    val icon: FloatingObjectIcon? = null
)

interface FloatingAnytypeApi {
    @POST("v1/spaces/{spaceId}/objects")
    suspend fun createObject(
        @Path("spaceId") spaceId: String,
        @Body request: FloatingCreateObjectRequest
    ): retrofit2.Response<Any>
}
