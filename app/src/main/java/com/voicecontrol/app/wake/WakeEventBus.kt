package com.voicecontrol.app.wake

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object WakeEventBus {
    private val _wakeDetected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val wakeDetected: SharedFlow<Unit> = _wakeDetected.asSharedFlow()

    fun emitWake() {
        _wakeDetected.tryEmit(Unit)
    }
}
