package com.gemnote.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class StorageManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("gemnote_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_SPACE_ID = "space_id"
        private const val KEY_SPACE_NAME = "space_name"
        private const val KEY_CLIPBOARD_ENTRIES = "clipboard_entries"
        const val DEFAULT_BASE_URL = "http://192.168.1.100:31009"
    }
    
    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()
    
    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()
    
    var spaceId: String
        get() = prefs.getString(KEY_SPACE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPACE_ID, value).apply()
    
    var spaceName: String
        get() = prefs.getString(KEY_SPACE_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPACE_NAME, value).apply()
    
    fun getClipboardEntries(): MutableList<ClipboardEntry> {
        val json = prefs.getString(KEY_CLIPBOARD_ENTRIES, "[]")
        val type = object : TypeToken<MutableList<ClipboardEntry>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }
    
    fun saveClipboardEntries(entries: List<ClipboardEntry>) {
        val json = gson.toJson(entries)
        prefs.edit().putString(KEY_CLIPBOARD_ENTRIES, json).apply()
    }
    
    fun addClipboardEntry(content: String) {
        val entries = getClipboardEntries()
        val preview = content.take(100).replace("\n", " ")
        entries.add(0, ClipboardEntry(content = content, preview = preview))
        // Keep only last 50 entries
        if (entries.size > 50) {
            entries.removeAt(entries.lastIndex)
        }
        saveClipboardEntries(entries)
    }
    
    fun markAsSynced(entryId: Long) {
        val entries = getClipboardEntries()
        entries.find { it.id == entryId }?.isSynced = true
        saveClipboardEntries(entries)
    }
    
    fun deleteEntry(entryId: Long) {
        val entries = getClipboardEntries()
        entries.removeAll { it.id == entryId }
        saveClipboardEntries(entries)
    }
    
    fun clearAllEntries() {
        saveClipboardEntries(emptyList())
    }
}
