package com.voicecontrol.app.wake

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bridge between WakeListenerService (a Service, separate lifecycle from
 * ChatViewModel) and the UI/ViewModel layer. Mirrors the existing
 * requestSmsPermission SharedFlow pattern already in ChatViewModel.
 */
object WakeEventBus {
    private val _wakeDetected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val wakeDetected: SharedFlow<Unit> = _wakeDetected.asSharedFlow()

    private val _state = MutableSharedFlow<WakeState>(replay = 1, extraBufferCapacity = 1)
    val state: SharedFlow<WakeState> = _state.asSharedFlow()

    fun emitWake() {
        _wakeDetected.tryEmit(Unit)
    }

    fun emitState(newState: WakeState) {
        _state.tryEmit(newState)
    }
}
