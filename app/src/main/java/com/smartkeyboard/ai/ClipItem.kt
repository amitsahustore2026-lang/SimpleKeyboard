package com.smartkeyboard.ai

/**
 * A single clipboard history entry.
 */
data class ClipItem(
    val id: Long,
    val content: String,
    val timestamp: Long,
    val pinned: Boolean,
    val favorite: Boolean,
    val category: String
)
