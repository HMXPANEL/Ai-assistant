package com.voicecontrol.app.model

private var nextId = 0L

data class Message(
    val id: Long = ++nextId,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
