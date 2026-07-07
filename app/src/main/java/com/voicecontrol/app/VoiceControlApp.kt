package com.voicecontrol.app

import android.app.Application

class VoiceControlApp : Application() {
    val sharedTtsManager: com.voicecontrol.app.agent.AgentTtsManager by lazy {
        com.voicecontrol.app.agent.AgentTtsManager(this)
    }
}
