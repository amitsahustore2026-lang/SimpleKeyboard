package com.smartkeyboard.ai

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FormatterRuleAdapter(
    private val onEdit: (FormatRule) -> Unit,
    private val onToggleEnabled: (FormatRule) -> Unit,
    private val onDelete: (FormatRule) -> Unit
) : RecyclerView.Adapter<FormatterRuleAdapter.ViewHolder>() {

    private val items = mutableListOf<FormatRule>()

    fun submitList(newItems: List<FormatRule>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.formatter_rule_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val rule = items[position]
        val sample = "+91 98765 43210"
        val preview = FormatterEngine.previewSingleRule(sample, rule.type, rule.config)

        holder.name.text = rule.name
        holder.meta.text = "${rule.type}  •  priority ${rule.priority}" +
            if (rule.phoneOnly) "  •  phone only" else ""
        holder.preview.text = "$sample  →  $preview"
        holder.enabledToggle.text = if (rule.enabled) "ON" else "OFF"
        holder.enabledToggle.setBackgroundResource(
            if (rule.enabled) R.drawable.accent_key_bg else R.drawable.special_key_bg
        )

        holder.itemView.setOnClickListener { onEdit(rule) }
        holder.enabledToggle.setOnClickListener { onToggleEnabled(rule) }
        holder.delete.setOnClickListener { onDelete(rule) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.rule_item_name)
        val meta: TextView = itemView.findViewById(R.id.rule_item_meta)
        val preview: TextView = itemView.findViewById(R.id.rule_item_preview)
        val enabledToggle: TextView = itemView.findViewById(R.id.rule_item_enabled)
        val delete: TextView = itemView.findViewById(R.id.rule_item_delete)
    }
}
