package com.example.ipcamera.util

import android.content.Context
import androidx.core.content.edit

data class CameraConfig(
    val cameraId: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val iFrameIntervalSec: Int,
    val autoSkipBlackCamera: Boolean
)

class Preferences(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("ipcamera_prefs", Context.MODE_PRIVATE)

    var port: Int
        get() = sp.getInt("port", 8554)
        set(value) = sp.edit { putInt("port", value) }

    var username: String
        get() = sp.getString("username", "admin") ?: "admin"
        set(value) = sp.edit { putString("username", value) }

    var password: String
        get() = sp.getString("password", "123456") ?: "123456"
        set(value) = sp.edit { putString("password", value) }

    var cameraId: String
        get() = sp.getString("cameraId", "") ?: ""
        set(value) = sp.edit { putString("cameraId", value) }

    var captureWidth: Int
        get() = sp.getInt("captureWidth", 1280)
        set(value) = sp.edit { putInt("captureWidth", value) }

    var captureHeight: Int
        get() = sp.getInt("captureHeight", 720)
        set(value) = sp.edit { putInt("captureHeight", value) }

    var fps: Int
        get() = sp.getInt("fps", 25)
        set(value) = sp.edit { putInt("fps", value) }

    var bitrateBps: Int
        get() = sp.getInt("bitrateBps", 2_000_000)
        set(value) = sp.edit { putInt("bitrateBps", value) }

    var iFrameIntervalSec: Int
        get() = sp.getInt("iFrameIntervalSec", 2)
        set(value) = sp.edit { putInt("iFrameIntervalSec", value) }

    var autoSkipBlackCamera: Boolean
        get() = sp.getBoolean("autoSkipBlackCamera", true)
        set(value) = sp.edit { putBoolean("autoSkipBlackCamera", value) }

    fun cameraConfig(): CameraConfig = CameraConfig(
        cameraId = cameraId,
        width = captureWidth,
        height = captureHeight,
        fps = fps,
        bitrateBps = bitrateBps,
        iFrameIntervalSec = iFrameIntervalSec,
        autoSkipBlackCamera = autoSkipBlackCamera
    )

    fun saveAll(
        port: Int,
        username: String,
        password: String,
        cameraId: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int,
        iFrameIntervalSec: Int,
        autoSkipBlackCamera: Boolean
    ) {
        sp.edit {
            putInt("port", port)
            putString("username", username)
            putString("password", password)
            putString("cameraId", cameraId)
            putInt("captureWidth", width)
            putInt("captureHeight", height)
            putInt("fps", fps)
            putInt("bitrateBps", bitrateBps)
            putInt("iFrameIntervalSec", iFrameIntervalSec)
            putBoolean("autoSkipBlackCamera", autoSkipBlackCamera)
        }
    }
}
