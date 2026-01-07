package com.geminianytype.service

import android.app.*
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.geminianytype.R
import com.geminianytype.data.ClipboardRepository
import com.geminianytype.data.SettingsManager
import com.geminianytype.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class ClipboardService : Service() {
    
    @Inject lateinit var repository: ClipboardRepository
    @Inject lateinit var settingsManager: SettingsManager
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var clipboardManager: ClipboardManager? = null
    private var lastClipContent = ""
    
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        serviceScope.launch {
            try {
                val clip = clipboardManager?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString()
                    if (!text.isNullOrBlank() && text != lastClipContent) {
                        lastClipContent = text
                        repository.saveClipboardEntry(text)
                        updateNotification("Captured: ${text.take(30)}...")
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1001, createNotification("Monitoring clipboard..."))
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
        serviceScope.launch { settingsManager.setServiceRunning(true) }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        serviceScope.launch { settingsManager.setServiceRunning(false) }
        serviceScope.cancel()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("clipboard_channel", "Clipboard Monitor", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "clipboard_channel")
            .setContentTitle("GemNote")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_clipboard)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(1001, createNotification(content))
    }
    
    companion object {
        fun start(context: Context) {
            val intent = Intent(context, ClipboardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
        fun stop(context: Context) = context.stopService(Intent(context, ClipboardService::class.java))
    }
}
