package com.gemnote

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

class BubbleActivity : AppCompatActivity() {

    companion object {
        const val PROXY_PORT = 31010
    }

    private lateinit var prefs: SharedPreferences
    private val entries = mutableListOf<ClipEntry>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    private var spaces = listOf<BSpace>()
    private var selectedSpaceId = ""
    private var selectedSpaceName = ""
    private var selectedTypeKey = "note"
    private var selectedTypeName = "Note"
    private var isConnected = false

    private lateinit var statusText: TextView
    private lateinit var entriesContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bubble)

        prefs = getSharedPreferences("gemnote", Context.MODE_PRIVATE)
        loadSettings()
        loadEntries()

        statusText = findViewById(R.id.statusText)
        entriesContainer = findViewById(R.id.entriesContainer)

        // Setup button clicks
        findViewById<Button>(R.id.btnPaste).setOnClickListener {
            pasteFromClipboard()
        }

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            if (isConnected) {
                showToast("Connected: $selectedSpaceName")
            } else {
                if (getApiKey().isNotEmpty()) {
                    showToast("Scanning...")
                    autoScanNetwork()
                } else {
                    showToast("Set API key in main app")
                }
            }
        }

        findViewById<Button>(R.id.btnType).setOnClickListener {
            showToast("Type: $selectedTypeName")
        }

        updateUI()

        if (getApiKey().isNotEmpty()) {
            autoConnect()
        }
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
            isConnected && selectedSpaceName.isNotEmpty() -> "✓ $selectedSpaceName"
            isConnected -> "✓ Connected"
            else -> "GemNote"
        }

        entriesContainer.removeAllViews()

        if (entries.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "No entries\n\nTap + to paste from clipboard"
                setTextColor(0xFF888888.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(32, 60, 32, 60)
            }
            entriesContainer.addView(emptyText)
            return
        }

        val inflater = LayoutInflater.from(this)

        for (entry in entries) {
            val card = inflater.inflate(R.layout.item_bubble_entry, entriesContainer, false)

            card.findViewById<TextView>(R.id.timestampText).text = dateFormat.format(Date(entry.timestamp))
            card.findViewById<TextView>(R.id.previewText).text = entry.preview
            card.findViewById<TextView>(R.id.syncedText).visibility = if (entry.isSynced) View.VISIBLE else View.GONE

            card.findViewById<Button>(R.id.btnSend).setOnClickListener {
                sendToAnytype(entry)
            }

            card.findViewById<Button>(R.id.btnDelete).setOnClickListener {
                entries.removeAll { it.id == entry.id }
                saveEntries()
                updateUI()
                showToast("Deleted")
            }

            entriesContainer.addView(card)
        }
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()
            if (!text.isNullOrBlank()) {
                if (entries.any { it.content == text }) {
                    showToast("Already exists")
                    return
                }
                val preview = text.take(100).replace("\n", " ")
                entries.add(0, ClipEntry(
                    id = System.currentTimeMillis(),
                    content = text,
                    preview = preview,
                    timestamp = System.currentTimeMillis()
                ))
                if (entries.size > 50) entries.removeLast()
                saveEntries()
                updateUI()
                showToast("Added!")
            } else {
                showToast("Clipboard empty")
            }
        } else {
            showToast("Clipboard empty")
        }
    }

    // Network methods
    private fun getLocalSubnet(): String? {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip == 0) null
            else String.format("%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff)
        } catch (e: Exception) { null }
    }

    private fun autoConnect() {
        val url = getBaseUrl()
        if (url.isNotEmpty()) {
            scope.launch {
                if (tryConnect(url)) {
                    runOnUiThread { updateUI() }
                }
            }
        }
    }

    private fun autoScanNetwork() {
        val apiKey = getApiKey()
        val subnet = getLocalSubnet() ?: run {
            showToast("Not on WiFi")
            return
        }

        scope.launch {
            var found: String? = null
            withContext(Dispatchers.IO) {
                (1..254).map { "$subnet.$it" }.chunked(50).forEach { batch ->
                    if (found != null) return@forEach
                    found = batch.map { ip ->
                        async {
                            val url = "http://$ip:$PROXY_PORT"
                            try {
                                if (withTimeoutOrNull(2000) { createApi(url, apiKey).getSpaces().isSuccessful } == true) url else null
                            } catch (e: Exception) { null }
                        }
                    }.awaitAll().filterNotNull().firstOrNull()
                }
            }

            if (found != null) {
                prefs.edit().putString("base_url", found).apply()
                if (tryConnect(found!!)) {
                    showToast("Connected!")
                    runOnUiThread { updateUI() }
                }
            } else {
                showToast("Not found")
            }
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

        scope.launch {
            try {
                val request = BCreateRequest(name = title, typeKey = selectedTypeKey, body = body)
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
                showToast("Error")
            }
        }
    }

    private fun createApi(baseUrl: String, apiKey: String): BApi {
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
            .create(BApi::class.java)
    }

    override fun onResume() {
        super.onResume()
        loadEntries()
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

data class BSpace(val id: String, val name: String)
data class BResponse<T>(val data: T?)
data class BCreateRequest(
    val name: String,
    @SerializedName("type_key") val typeKey: String,
    val body: String? = null
)

interface BApi {
    @GET("v1/spaces")
    suspend fun getSpaces(): retrofit2.Response<BResponse<List<BSpace>>>

    @POST("v1/spaces/{spaceId}/objects")
    suspend fun createObject(@Path("spaceId") spaceId: String, @Body request: BCreateRequest): retrofit2.Response<Any>
}
