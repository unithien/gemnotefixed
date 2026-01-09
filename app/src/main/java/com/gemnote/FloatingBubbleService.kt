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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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

class FloatingBubbleService : Service() {

    companion object {
        const val CHANNEL_ID = "floating_bubble"
        const val NOTIFICATION_ID = 2001
        const val PROXY_PORT = 31010
        var isRunning = false
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var bubbleView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private lateinit var prefs: SharedPreferences

    private val entries = mutableListOf<ClipEntry>()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    private var spaces = listOf<FloatSpace>()
    private var types = listOf<FloatObjectType>()
    private var selectedSpaceId = ""
    private var selectedSpaceName = ""
    private var selectedTypeKey = "note"
    private var selectedTypeName = "Note"
    private var isConnected = false
    private var isMinimized = false

    private var statusText: TextView? = null
    private var entriesContainer: LinearLayout? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        
        prefs = getSharedPreferences("gemnote", Context.MODE_PRIVATE)
        loadSettings()
        loadEntries()
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        handler.postDelayed({
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            createFloatingWindow()
            createBubble()
            if (getApiKey().isNotEmpty()) {
                autoConnect()
            }
        }, 300)
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
            .setContentText("Floating window active")
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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun createRoundedDrawable(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpToPx(radius.toInt()).toFloat()
        }
    }

    private fun createBubble() {
        val bubble = ImageView(this).apply {
            setImageDrawable(createRoundedDrawable(Color.parseColor("#6B4EAA"), 30f))
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            dpToPx(50),
            dpToPx(50),
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }

        bubbleView = bubble
        windowManager?.addView(bubbleView, bubbleParams)
        bubbleView?.visibility = View.GONE

        setupBubbleTouch()
    }

    private fun setupBubbleTouch() {
        var isDragging = false
        var clickStartTime = 0L

        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    clickStartTime = System.currentTimeMillis()
                    initialX = bubbleParams?.x ?: 0
                    initialY = bubbleParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                    }
                    if (isDragging) {
                        bubbleParams?.x = initialX + dx.toInt()
                        bubbleParams?.y = initialY + dy.toInt()
                        windowManager?.updateViewLayout(bubbleView, bubbleParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val clickDuration = System.currentTimeMillis() - clickStartTime
                    if (!isDragging && clickDuration < 200) {
                        expandFromBubble()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun createFloatingWindow() {
        val purple = Color.parseColor("#6B4EAA")
        val lightPurple = Color.parseColor("#F5F0FF")
        val white = Color.WHITE

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(white, 16f)
            elevation = 12f
        }

        // Header
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
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(statusText)

        // Header buttons: +, C, T, -, X
        val btnPaste = createHeaderButton("+") { pasteFromClipboard() }
        header.addView(btnPaste)

        val btnConnect = createHeaderButton("C") { showSpaceSelector() }
        (btnConnect.layoutParams as LinearLayout.LayoutParams).marginStart = dpToPx(4)
        header.addView(btnConnect)

        val btnType = createHeaderButton("T") { showTypeSelector() }
        (btnType.layoutParams as LinearLayout.LayoutParams).marginStart = dpToPx(4)
        header.addView(btnType)

        val btnMinimize = createHeaderButton("-") { minimizeToBubble() }
        (btnMinimize.layoutParams as LinearLayout.LayoutParams).marginStart = dpToPx(4)
        header.addView(btnMinimize)

        val btnClose = createHeaderButton("X") { stopSelf() }
        (btnClose.layoutParams as LinearLayout.LayoutParams).marginStart = dpToPx(4)
        header.addView(btnClose)

        rootLayout.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Content ScrollView
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(lightPurple)
        }

        entriesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
        }
        scrollView.addView(entriesContainer)

        rootLayout.addView(scrollView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        floatingView = rootLayout

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            dpToPx(280),
            dpToPx(350),
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 150
        }

        windowManager?.addView(floatingView, params)

        setupDrag(header)
        updateEntriesUI()
        updateStatus()
    }

    private fun createHeaderButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = createRoundedDrawable(Color.parseColor("#8B6ECA"), 6f)
            layoutParams = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32))
            setOnClickListener { onClick() }
        }
    }

    private fun minimizeToBubble() {
        isMinimized = true
        floatingView?.visibility = View.GONE
        bubbleView?.visibility = View.VISIBLE
        // Position bubble where floating window was
        bubbleParams?.x = params?.x ?: 50
        bubbleParams?.y = params?.y ?: 150
        windowManager?.updateViewLayout(bubbleView, bubbleParams)
    }

    private fun expandFromBubble() {
        isMinimized = false
        bubbleView?.visibility = View.GONE
        floatingView?.visibility = View.VISIBLE
        // Position floating window where bubble was
        params?.x = bubbleParams?.x ?: 50
        params?.y = bubbleParams?.y ?: 150
        windowManager?.updateViewLayout(floatingView, params)
        // Refresh entries
        loadEntries()
        updateEntriesUI()
    }

    private fun setupDrag(dragView: View) {
        var isDragging = false

        dragView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
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
                        params?.x = initialX + dx.toInt()
                        params?.y = initialY + dy.toInt()
                        windowManager?.updateViewLayout(floatingView, params)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updateStatus() {
        statusText?.text = when {
            isConnected && selectedSpaceName.isNotEmpty() -> selectedSpaceName
            isConnected -> "Connected"
            else -> "GemNote"
        }
    }

    private fun updateEntriesUI() {
        val container = entriesContainer ?: return
        container.removeAllViews()

        if (entries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No entries\n\nTap + to paste"
                setTextColor(Color.parseColor("#888888"))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dpToPx(16), dpToPx(40), dpToPx(16), dpToPx(40))
            })
            return
        }

        for (entry in entries) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = createRoundedDrawable(Color.WHITE, 8f)
                setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
                elevation = 2f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpToPx(6) }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            topRow.addView(TextView(this).apply {
                text = dateFormat.format(Date(entry.timestamp))
                setTextColor(Color.parseColor("#999999"))
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (entry.isSynced) {
                topRow.addView(TextView(this).apply {
                    text = "Synced"
                    setTextColor(Color.parseColor("#4CAF50"))
                    textSize = 10f
                })
            }
            card.addView(topRow)

            card.addView(TextView(this).apply {
                text = entry.preview.take(60) + if (entry.preview.length > 60) "..." else ""
                setTextColor(Color.parseColor("#333333"))
                textSize = 12f
                maxLines = 2
                setPadding(0, dpToPx(4), 0, dpToPx(6))
            })

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val btnSend = TextView(this).apply {
                text = "SEND"
                setTextColor(Color.WHITE)
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = createRoundedDrawable(Color.parseColor("#6B4EAA"), 6f)
                layoutParams = LinearLayout.LayoutParams(dpToPx(60), dpToPx(28))
                setOnClickListener { sendToAnytype(entry) }
            }
            btnRow.addView(btnSend)

            val btnDelete = TextView(this).apply {
                text = "DEL"
                setTextColor(Color.WHITE)
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = createRoundedDrawable(Color.parseColor("#E57373"), 6f)
                layoutParams = LinearLayout.LayoutParams(dpToPx(50), dpToPx(28)).apply {
                    marginStart = dpToPx(6)
                }
                setOnClickListener { deleteEntry(entry) }
            }
            btnRow.addView(btnDelete)

            card.addView(btnRow)
            container.addView(card)
        }
    }

    private fun showToast(msg: String) {
        handler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSpaceSelector() {
        if (spaces.isEmpty()) {
            if (getApiKey().isNotEmpty()) {
                showToast("Scanning...")
                autoScanNetwork()
            } else {
                showToast("Set API key in main app")
            }
            return
        }

        // Show popup menu for space selection
        val names = spaces.map { it.name }
        showSelectionPopup("Select Space", names) { index ->
            val space = spaces[index]
            selectedSpaceId = space.id
            selectedSpaceName = space.name
            prefs.edit()
                .putString("space_id", selectedSpaceId)
                .putString("space_name", selectedSpaceName)
                .apply()
            loadTypesForSpace()
            updateStatus()
            showToast("Space: $selectedSpaceName")
        }
    }

    private fun showTypeSelector() {
        if (types.isEmpty()) {
            showToast("Connect first")
            return
        }

        val names = types.map { it.name }
        showSelectionPopup("Select Type", names) { index ->
            val type = types[index]
            selectedTypeKey = type.key
            selectedTypeName = type.name
            prefs.edit()
                .putString("type_key", selectedTypeKey)
                .putString("type_name", selectedTypeName)
                .apply()
            showToast("Type: $selectedTypeName")
        }
    }

    private fun showSelectionPopup(title: String, items: List<String>, onSelect: (Int) -> Unit) {
        // Create a popup window for selection
        val popupLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(Color.WHITE, 12f)
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            elevation = 20f
        }

        // Title
        popupLayout.addView(TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#6B4EAA"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(8))
        })

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val popupParams = WindowManager.LayoutParams(
            dpToPx(200),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (params?.x ?: 50) + dpToPx(40)
            y = (params?.y ?: 150) + dpToPx(50)
        }

        windowManager?.addView(popupLayout, popupParams)

        // Add items
        items.forEachIndexed { index, name ->
            popupLayout.addView(TextView(this).apply {
                text = name
                setTextColor(Color.parseColor("#333333"))
                textSize = 13f
                setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
                background = createRoundedDrawable(Color.parseColor("#F5F0FF"), 6f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpToPx(4) }
                setOnClickListener {
                    windowManager?.removeView(popupLayout)
                    onSelect(index)
                }
            })
        }

        // Close button
        popupLayout.addView(TextView(this).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = createRoundedDrawable(Color.parseColor("#999999"), 6f)
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(8) }
            setOnClickListener {
                windowManager?.removeView(popupLayout)
            }
        })
    }

    private fun pasteFromClipboard() {
        // Temporarily make window focusable to access clipboard (Android 10+ restriction)
        params?.flags = params?.flags?.and(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) ?: 0
        windowManager?.updateViewLayout(floatingView, params)
        
        handler.postDelayed({
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString()
                    if (!text.isNullOrBlank()) {
                        addEntry(text)
                    } else {
                        showToast("Empty clipboard")
                    }
                } else {
                    showToast("Empty clipboard")
                }
            } finally {
                // Revert to not focusable
                params?.flags = params?.flags?.or(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) ?: WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                windowManager?.updateViewLayout(floatingView, params)
            }
        }, 100)
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
        showToast("Deleted")
    }

    // Network
    private fun getLocalSubnet(): String? {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip == 0) null
            else String.format("%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff)
        } catch (e: Exception) { null }
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
                loadTypesForSpace()
                true
            } else false
        } catch (e: Exception) { false }
    }

    private fun loadTypesForSpace() {
        if (selectedSpaceId.isEmpty()) return

        serviceScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    createApi(getBaseUrl(), getApiKey()).getTypes(selectedSpaceId)
                }
                if (response.isSuccessful) {
                    types = response.body()?.data ?: emptyList()
                }
            } catch (e: Exception) { }
        }
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
                val request = FloatCreateObjectRequest(
                    name = title,
                    typeKey = selectedTypeKey,
                    body = body
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
                showToast("Error")
            }
        }
    }

    private fun createApi(baseUrl: String, apiKey: String): FloatAnytypeApi {
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
            .create(FloatAnytypeApi::class.java)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
        floatingView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { }
        }
        bubbleView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { }
        }
    }
}

data class FloatSpace(val id: String, val name: String)
data class FloatObjectType(
    @SerializedName("unique_key") val key: String,
    val name: String
)
data class FloatApiResponse<T>(val data: T?)
data class FloatCreateObjectRequest(
    val name: String,
    @SerializedName("type_key") val typeKey: String,
    val body: String? = null
)

interface FloatAnytypeApi {
    @GET("v1/spaces")
    suspend fun getSpaces(): retrofit2.Response<FloatApiResponse<List<FloatSpace>>>

    @GET("v1/spaces/{spaceId}/types")
    suspend fun getTypes(@Path("spaceId") spaceId: String): retrofit2.Response<FloatApiResponse<List<FloatObjectType>>>

    @POST("v1/spaces/{spaceId}/objects")
    suspend fun createObject(@Path("spaceId") spaceId: String, @Body request: FloatCreateObjectRequest): retrofit2.Response<Any>
}
