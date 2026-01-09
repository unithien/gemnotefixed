package com.gemnote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
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

class MainActivity : AppCompatActivity() {

    companion object {
        const val PROXY_PORT = 31010
        const val CHANNEL_ID = "gemnote_bubble"
        const val BUBBLE_NOTIFICATION_ID = 2001
        const val SHORTCUT_ID = "gemnote_bubble_shortcut"
    }

    private lateinit var prefs: SharedPreferences
    private val entries = mutableListOf<ClipEntry>()
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    private var spaces = listOf<Space>()
    private var types = listOf<ObjectType>()
    private var selectedSpaceId = ""
    private var selectedSpaceName = ""
    private var selectedTypeKey = "note"
    private var selectedTypeName = "Note"
    private var isConnected = false

    private lateinit var statusText: TextView
    private lateinit var entriesContainer: LinearLayout
    private lateinit var floatingSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("gemnote", Context.MODE_PRIVATE)
        loadSettings()
        loadEntries()

        createNotificationChannel()
        createBubbleShortcut()

        setupViews()
        updateUI()

        handleShareIntent(intent)

        if (getApiKey().isNotEmpty()) {
            autoConnect()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                addEntry(text)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GemNote Bubble",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Floating bubble for quick access"
            setAllowBubbles(true)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createBubbleShortcut() {
        val shortcut = ShortcutInfoCompat.Builder(this, SHORTCUT_ID)
            .setShortLabel("GemNote")
            .setLongLabel("GemNote Bubble")
            .setIcon(IconCompat.createWithResource(this, android.R.drawable.ic_dialog_info))
            .setIntent(Intent(this, BubbleActivity::class.java).apply {
                action = Intent.ACTION_VIEW
            })
            .setLongLived(true)
            .build()

        ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
    }

    private fun setupViews() {
        statusText = findViewById(R.id.statusText)
        entriesContainer = findViewById(R.id.entriesContainer)
        floatingSwitch = findViewById(R.id.floatingSwitch)

        findViewById<Button>(R.id.btnPaste).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.btnConnect).setOnClickListener { onConnectClick() }
        findViewById<Button>(R.id.btnType).setOnClickListener { showTypeSelector() }

        floatingSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                showBubble()
            } else {
                hideBubble()
            }
        }
    }

    private fun showBubble() {
        val target = Intent(this, BubbleActivity::class.java)
        val bubbleIntent = PendingIntent.getActivity(
            this, 0, target,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val person = Person.Builder()
            .setName("GemNote")
            .setImportant(true)
            .build()

        val bubbleData = NotificationCompat.BubbleMetadata.Builder(
            bubbleIntent,
            IconCompat.createWithResource(this, android.R.drawable.ic_dialog_info)
        )
            .setDesiredHeight(500)
            .setAutoExpandBubble(true)
            .setSuppressNotification(true)
            .build()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("GemNote")
            .setContentText("Tap to open")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setShortcutId(SHORTCUT_ID)
            .addPerson(person)
            .setBubbleMetadata(bubbleData)
            .build()

        getSystemService(NotificationManager::class.java).notify(BUBBLE_NOTIFICATION_ID, notification)
        moveTaskToBack(true)
    }

    private fun hideBubble() {
        getSystemService(NotificationManager::class.java).cancel(BUBBLE_NOTIFICATION_ID)
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

    private fun updateUI() {
        statusText.text = when {
            isConnected && selectedSpaceName.isNotEmpty() -> "OK $selectedSpaceName"
            isConnected -> "OK Connected"
            else -> "Not connected"
        }

        findViewById<Button>(R.id.btnType).text = "Type: $selectedTypeName"

        entriesContainer.removeAllViews()

        if (entries.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "No entries yet.\n\nCopy text or tap the + button!"
                setTextColor(0xFF888888.toInt())
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(32, 100, 32, 100)
            }
            entriesContainer.addView(emptyText)
            return
        }

        val inflater = LayoutInflater.from(this)

        for (entry in entries) {
            val cardView = inflater.inflate(R.layout.item_entry, entriesContainer, false)

            cardView.findViewById<TextView>(R.id.timestampText).text = dateFormat.format(Date(entry.timestamp))
            cardView.findViewById<TextView>(R.id.previewText).text = entry.preview

            val syncedText = cardView.findViewById<TextView>(R.id.syncedText)
            syncedText.visibility = if (entry.isSynced) View.VISIBLE else View.GONE

            cardView.findViewById<Button>(R.id.btnSend).setOnClickListener {
                sendToAnytype(entry)
            }

            cardView.findViewById<Button>(R.id.btnDelete).setOnClickListener {
                deleteEntry(entry)
            }

            entriesContainer.addView(cardView)
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
        updateUI()
        showToast("Entry added!")
    }

    private fun deleteEntry(entry: ClipEntry) {
        entries.removeAll { it.id == entry.id }
        saveEntries()
        updateUI()
        showToast("Deleted")
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun onConnectClick() {
        if (isConnected) {
            showSpaceSelector()
        } else {
            showApiKeyDialog()
        }
    }

    private fun showApiKeyDialog() {
        val input = EditText(this).apply {
            hint = "Enter API key"
            setText(getApiKey())
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Anytype API Key")
            .setMessage("Get your API key from Anytype settings")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val apiKey = input.text.toString().trim()
                if (apiKey.isNotEmpty()) {
                    prefs.edit().putString("api_key", apiKey).apply()
                    autoScanNetwork(apiKey)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSpaceSelector() {
        if (spaces.isEmpty()) {
            showToast("No spaces available")
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
                loadTypesForSpace()
                updateUI()
            }
            .show()
    }

    private fun showTypeSelector() {
        if (types.isEmpty()) {
            showToast("Connect first to load types")
            return
        }

        val names = types.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Type")
            .setItems(names) { _, which ->
                val type = types[which]
                selectedTypeKey = type.key
                selectedTypeName = type.name
                prefs.edit()
                    .putString("type_key", selectedTypeKey)
                    .putString("type_name", selectedTypeName)
                    .apply()
                updateUI()
            }
            .show()
    }

    private fun getLocalSubnet(): String? {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip == 0) null
            else String.format("%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff)
        } catch (e: Exception) {
            null
        }
    }

    private fun autoConnect() {
        val savedUrl = getBaseUrl()
        if (savedUrl.isNotEmpty()) {
            activityScope.launch {
                if (tryConnect(savedUrl)) {
                    runOnUiThread { updateUI() }
                }
            }
        }
    }

    private fun autoScanNetwork(apiKey: String) {
        val subnet = getLocalSubnet() ?: run {
            showToast("Not on WiFi")
            return
        }

        showToast("Scanning network...")

        activityScope.launch {
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
                    runOnUiThread { updateUI() }
                }
            } else {
                showToast("Anytype not found")
            }
        }
    }

    private suspend fun checkAnytypeAt(url: String, apiKey: String): Boolean {
        return try {
            withTimeoutOrNull(2000) {
                createApi(url, apiKey).getSpaces().isSuccessful
            } ?: false
        } catch (e: Exception) {
            false
        }
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
        } catch (e: Exception) {
            false
        }
    }

    private fun loadTypesForSpace() {
        if (selectedSpaceId.isEmpty()) return

        activityScope.launch {
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

        activityScope.launch {
            try {
                val request = CreateObjectRequest(
                    name = title,
                    typeKey = selectedTypeKey,
                    body = body,
                    icon = ObjectIcon(emoji = "N", format = "emoji")
                )
                val response = withContext(Dispatchers.IO) {
                    createApi(getBaseUrl(), getApiKey()).createObject(selectedSpaceId, request)
                }

                if (response.isSuccessful) {
                    entry.isSynced = true
                    saveEntries()
                    runOnUiThread { updateUI() }
                    showToast("Sent!")
                } else {
                    showToast("Failed")
                }
            } catch (e: Exception) {
                showToast("Error: ${e.message}")
            }
        }
    }

    private fun createApi(baseUrl: String, apiKey: String): AnytypeApi {
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
            .create(AnytypeApi::class.java)
    }

    override fun onResume() {
        super.onResume()
        loadEntries()
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}

data class Space(val id: String, val name: String)
data class ObjectType(
    @SerializedName("unique_key") val key: String,
    val name: String
)
data class ApiResponse<T>(val data: T?)
data class ObjectIcon(val emoji: String? = null, val format: String? = null)
data class CreateObjectRequest(
    val name: String,
    @SerializedName("type_key") val typeKey: String,
    val body: String? = null,
    val icon: ObjectIcon? = null
)

interface AnytypeApi {
    @GET("v1/spaces")
    suspend fun getSpaces(): retrofit2.Response<ApiResponse<List<Space>>>

    @GET("v1/spaces/{spaceId}/types")
    suspend fun getTypes(@Path("spaceId") spaceId: String): retrofit2.Response<ApiResponse<List<ObjectType>>>

    @POST("v1/spaces/{spaceId}/objects")
    suspend fun createObject(@Path("spaceId") spaceId: String, @Body request: CreateObjectRequest): retrofit2.Response<Any>
}
