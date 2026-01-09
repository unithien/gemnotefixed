package com.gemnote

data class ClipEntry(
    val id: Long,
    val content: String,
    val preview: String,
    val timestamp: Long,
    var isSynced: Boolean = false
)
