package com.gemnote.data

data class ClipboardEntry(
    val id: Long = System.currentTimeMillis(),
    val content: String,
    val preview: String,
    val timestamp: Long = System.currentTimeMillis(),
    var isSynced: Boolean = false
)
