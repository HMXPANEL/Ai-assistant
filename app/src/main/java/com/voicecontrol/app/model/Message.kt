package com.voicecontrol.app.model

import java.util.concurrent.atomic.AtomicLong

private val nextId = AtomicLong(0)

data class Message(
    val id: Long = nextId.incrementAndGet(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
