package com.smartkeyboard.ai

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ClipboardAdapter(
    private val onPaste: (ClipItem) -> Unit,
    private val onTogglePin: (ClipItem) -> Unit,
    private val onToggleFavorite: (ClipItem) -> Unit,
    private val onDelete: (ClipItem) -> Unit
) : RecyclerView.Adapter<ClipboardAdapter.ViewHolder>() {

    private val items = mutableListOf<ClipItem>()

    fun submitList(newItems: List<ClipItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.clipboard_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.category.text = item.category
        holder.content.text = item.content
        holder.timestamp.text = DateUtils.getRelativeTimeSpanString(
            item.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        holder.pin.text = if (item.pinned) "📌" else "📍"
        holder.favorite.text = if (item.favorite) "⭐" else "☆"

        holder.itemView.setOnClickListener { onPaste(item) }
        holder.pin.setOnClickListener { onTogglePin(item) }
        holder.favorite.setOnClickListener { onToggleFavorite(item) }
        holder.delete.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val category: TextView = itemView.findViewById(R.id.clip_item_category)
        val content: TextView = itemView.findViewById(R.id.clip_item_content)
        val timestamp: TextView = itemView.findViewById(R.id.clip_item_timestamp)
        val pin: TextView = itemView.findViewById(R.id.clip_item_pin)
        val favorite: TextView = itemView.findViewById(R.id.clip_item_favorite)
        val delete: TextView = itemView.findViewById(R.id.clip_item_delete)
    }
}
