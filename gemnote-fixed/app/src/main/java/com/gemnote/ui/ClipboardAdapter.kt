package com.gemnote.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gemnote.R
import com.gemnote.data.ClipboardEntry
import java.text.SimpleDateFormat
import java.util.*

class ClipboardAdapter(
    private val onSendClick: (ClipboardEntry) -> Unit,
    private val onDeleteClick: (ClipboardEntry) -> Unit
) : ListAdapter<ClipboardEntry, ClipboardAdapter.ViewHolder>(DiffCallback()) {
    
    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_clipboard, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val timestampText: TextView = itemView.findViewById(R.id.timestampText)
        private val previewText: TextView = itemView.findViewById(R.id.previewText)
        private val syncedBadge: TextView = itemView.findViewById(R.id.syncedBadge)
        private val sendButton: Button = itemView.findViewById(R.id.sendButton)
        private val deleteButton: Button = itemView.findViewById(R.id.deleteButton)
        
        fun bind(entry: ClipboardEntry) {
            timestampText.text = dateFormat.format(Date(entry.timestamp))
            previewText.text = entry.preview
            
            if (entry.isSynced) {
                syncedBadge.visibility = View.VISIBLE
                sendButton.text = "Sent"
                sendButton.isEnabled = false
            } else {
                syncedBadge.visibility = View.GONE
                sendButton.text = "Send"
                sendButton.isEnabled = true
            }
            
            sendButton.setOnClickListener { onSendClick(entry) }
            deleteButton.setOnClickListener { onDeleteClick(entry) }
        }
    }
    
    class DiffCallback : DiffUtil.ItemCallback<ClipboardEntry>() {
        override fun areItemsTheSame(oldItem: ClipboardEntry, newItem: ClipboardEntry): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: ClipboardEntry, newItem: ClipboardEntry): Boolean {
            return oldItem == newItem
        }
    }
}
