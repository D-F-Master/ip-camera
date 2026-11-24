package com.example.ipcamera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.ipcamera.util.Preferences

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val prefs = Preferences(this)
        val etPort = findViewById<EditText>(R.id.etPort)
        val etUsername = findViewById<EditText>(R.id.etUsername)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val spCamera = findViewById<Spinner>(R.id.spCamera)
        val spResolution = findViewById<Spinner>(R.id.spResolution)
        val spFps = findViewById<Spinner>(R.id.spFps)
        val spBitrate = findViewById<Spinner>(R.id.spBitrate)
        val spKeyframe = findViewById<Spinner>(R.id.spKeyframeInterval)
        val cbAutoSkipBlack = findViewById<CheckBox>(R.id.cbAutoSkipBlack)

        etPort.setText(prefs.port.toString())
        etUsername.setText(prefs.username)
        etPassword.setText(prefs.password)
        cbAutoSkipBlack.isChecked = prefs.autoSkipBlackCamera

        val resolutions = resources.getStringArray(R.array.resolution_entries)
        val fpsValues = resources.getStringArray(R.array.fps_values)
        val bitrateValues = resources.getStringArray(R.array.bitrate_values)
        val keyframeValues = resources.getStringArray(R.array.keyframe_interval_values)

        spResolution.adapter = simpleAdapter(resolutions)
        spFps.adapter = simpleAdapter(resources.getStringArray(R.array.fps_entries))
        spBitrate.adapter = simpleAdapter(resources.getStringArray(R.array.bitrate_entries))
        spKeyframe.adapter = simpleAdapter(resources.getStringArray(R.array.keyframe_interval_entries))

        spResolution.setSelection(indexOfValue(resolutions, "${prefs.captureWidth}x${prefs.captureHeight}"))
        spFps.setSelection(indexOfValue(fpsValues, prefs.fps.toString()))
        spBitrate.setSelection(indexOfValue(bitrateValues, (prefs.bitrateBps / 1_000_000).toString()))
        spKeyframe.setSelection(indexOfValue(keyframeValues, prefs.iFrameIntervalSec.toString()))

        val cameraIds = loadCameraIds()
        val cameraLabels = cameraIds.map { id -> cameraLabel(id) }
        spCamera.adapter = simpleAdapter(cameraLabels.toTypedArray())
        val savedCameraIndex = cameraIds.indexOf(prefs.cameraId)
        if (savedCameraIndex >= 0 && cameraIds.isNotEmpty()) {
            spCamera.setSelection(savedCameraIndex)
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val portText = etPort.text.toString().trim()
            val port = portText.toIntOrNull()
            if (port == null || port !in 1024..65535) {
                Toast.makeText(this, "端口号必须在 1024-65535 之间", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selectedCameraId = cameraIds.getOrNull(spCamera.selectedItemPosition) ?: prefs.cameraId
            val resolution = resolutions.getOrNull(spResolution.selectedItemPosition)
                ?.split("x")?.mapNotNull { it.toIntOrNull() }
            val width = resolution?.getOrNull(0) ?: prefs.captureWidth
            val height = resolution?.getOrNull(1) ?: prefs.captureHeight
            val fps = fpsValues.getOrNull(spFps.selectedItemPosition)?.toIntOrNull() ?: prefs.fps
            val bitrateMbps = bitrateValues.getOrNull(spBitrate.selectedItemPosition)?.toIntOrNull()
                ?: (prefs.bitrateBps / 1_000_000)
            val keyframe = keyframeValues.getOrNull(spKeyframe.selectedItemPosition)?.toIntOrNull()
                ?: prefs.iFrameIntervalSec

            prefs.saveAll(
                port = port,
                username = etUsername.text.toString(),
                password = etPassword.text.toString(),
                cameraId = selectedCameraId,
                width = width,
                height = height,
                fps = fps,
                bitrateBps = bitrateMbps * 1_000_000,
                iFrameIntervalSec = keyframe,
                autoSkipBlackCamera = cbAutoSkipBlack.isChecked
            )

            setResult(RESULT_OK)
            Toast.makeText(this, R.string.saved_restart_hint, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun simpleAdapter(items: Array<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    private fun indexOfValue(values: Array<String>, current: String): Int {
        val index = values.indexOf(current)
        return if (index >= 0) index else 0
    }

    private fun loadCameraIds(): Array<String> = try {
        (getSystemService(CAMERA_SERVICE) as CameraManager).cameraIdList
    } catch (e: Exception) {
        emptyArray()
    }

    private fun cameraLabel(id: String): String = try {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(id)
        val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "前置"
            CameraCharacteristics.LENS_FACING_BACK -> "后置"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "外接"
            else -> "未知"
        }
        "$id ($facing)"
    } catch (e: Exception) {
        id
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
