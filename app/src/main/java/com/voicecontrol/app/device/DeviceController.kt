package com.voicecontrol.app.device

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings

object DeviceController {

    fun toggleFlashlight(context: Context, on: Boolean): String {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return "No camera found on this device."
            cameraManager.setTorchMode(cameraId, on)
            return "Flashlight turned ${if (on) "on" else "off"}."
        } catch (e: SecurityException) {
            return "Camera permission not granted. Grant camera access in Settings > Apps > AI Assistant > Permissions."
        } catch (e: Exception) {
            return "Failed to toggle flashlight: ${e.message}"
        }
    }

    fun setVolume(context: Context, level: Int): String {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max) / 100, 0)
            return "Volume set to $level%."
        } catch (e: Exception) {
            return "Failed to set volume: ${e.message}"
        }
    }

    fun mutePhone(context: Context): String {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            return "Phone muted."
        } catch (e: Exception) {
            return "Failed to mute: ${e.message}"
        }
    }

    fun unmutePhone(context: Context): String {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            return "Phone unmuted."
        } catch (e: Exception) {
            return "Failed to unmute: ${e.message}"
        }
    }

    fun setBrightness(context: Context, level: Int): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
            return "To control brightness, go to Settings > Apps > AI Assistant > Modify System Settings and enable it."
        }
        try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (level * 255) / 100)
            return "Brightness set to $level%."
        } catch (e: Exception) {
            return "Failed to set brightness: ${e.message}"
        }
    }

    fun toggleWifi(context: Context, enable: Boolean): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.startActivity(
                Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return "Opening WiFi settings. Please toggle WiFi manually."
        }
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.isWifiEnabled = enable
        return "WiFi turned ${if (enable) "on" else "off"}."
    }

    fun toggleBluetooth(context: Context, enable: Boolean): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.startActivity(
                Intent(Settings.Panel.ACTION_BLUETOOTH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return "Opening Bluetooth settings. Please toggle Bluetooth manually."
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) return "Bluetooth not available."
        return if (enable) {
            adapter.enable()
            "Bluetooth turned on."
        } else {
            adapter.disable()
            "Bluetooth turned off."
        }
    }
}
