package com.voicecontrol.app.model

import java.util.concurrent.atomic.AtomicLong

private val messageIdCounter = AtomicLong(0)

data class Message(
    val id: Long = messageIdCounter.incrementAndGet(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
