package com.voicecontrol.app.model

import java.util.*

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Date = Date()
)