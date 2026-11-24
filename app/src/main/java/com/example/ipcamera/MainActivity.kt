package com.example.ipcamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ipcamera.camera.CameraController
import com.example.ipcamera.rtsp.RtspServer
import com.example.ipcamera.util.Preferences
import java.net.InetAddress
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var textureView: android.view.TextureView
    private lateinit var btnStart: Button
    private lateinit var btnSwitch: Button
    private lateinit var btnSettings: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvAddress: TextView

    private var cameraController: CameraController? = null
    private var rtspServer: RtspServer? = null
    private var isRunning = false
    private var pendingStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.preview)
        btnStart = findViewById(R.id.btnStart)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnSettings = findViewById(R.id.btnSettings)
        tvStatus = findViewById(R.id.tvStatus)
        tvAddress = findViewById(R.id.tvAddress)

        textureView.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                if (pendingStart) {
                    pendingStart = false
                    startService()
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                pendingStart = false
                if (isRunning) stopService()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
        }

        btnStart.setOnClickListener {
            if (isRunning) stopService() else startService()
        }
        btnSwitch.setOnClickListener {
            if (isRunning) {
                val newId = cameraController?.switchCamera()
                if (newId.isNullOrBlank()) {
                    Toast.makeText(this, "只有一个摄像头", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "请先开启服务", Toast.LENGTH_SHORT).show()
            }
        }
        btnSettings.setOnClickListener {
            startActivityForResult(android.content.Intent(this, SettingsActivity::class.java), 2001)
        }

        updateUI()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK) {
            if (isRunning) {
                stopService()
                startService()
            } else {
                updateUI()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isRunning) updateUI()
    }

    private fun ensureCameraPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted && pendingStart) {
                pendingStart = false
                startService()
            } else if (!granted) {
                pendingStart = false
            }
        }
    }

    private fun startService() {
        if (isRunning) return
        if (!ensureCameraPermission()) {
            pendingStart = true
            Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_SHORT).show()
            return
        }
        val surfaceTexture = textureView.surfaceTexture
        if (surfaceTexture == null) {
            pendingStart = true
            return
        }
        val surface = android.view.Surface(surfaceTexture)
        try {
            val prefs = Preferences(this)
            val server = RtspServer(prefs.port, prefs.username, prefs.password)
            server.start()
            rtspServer = server

            val controller = CameraController(
                this,
                config = prefs.cameraConfig(),
                onEncodedFrame = { data, ts -> rtspServer?.onH265Frame(data, ts) },
                onParameterSets = { v, s, p -> rtspServer?.setParameterSets(v, s, p) },
                onCameraSwitched = { id, userInitiated ->
                    Preferences(this).cameraId = id
                    if (userInitiated) {
                        runOnUiThread {
                            Toast.makeText(this, "已切换到摄像头 $id 并保存", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
            cameraController = controller
            controller.start(surface)

            isRunning = true
            updateUI()
        } catch (e: Exception) {
            cameraController?.stop()
            cameraController = null
            rtspServer?.stop()
            rtspServer = null
            isRunning = false
            btnStart.setText(R.string.start_service)
            tvAddress.text = ""
            tvStatus.text = getString(R.string.status_error, e.message ?: "unknown error")
        }
    }

    private fun stopService() {
        pendingStart = false
        cameraController?.stop()
        cameraController = null
        rtspServer?.stop()
        rtspServer = null
        isRunning = false
        updateUI()
    }

    private fun updateUI() {
        btnStart.setText(if (isRunning) R.string.stop_service else R.string.start_service)
        tvStatus.setText(if (isRunning) R.string.status_running else R.string.status_idle)
        if (isRunning) {
            val prefs = Preferences(this)
            val port = prefs.port
            tvAddress.text = getString(R.string.rtsp_address, "rtsp://获取中...:$port/")
            Thread {
                val ip = getLocalIp()
                runOnUiThread {
                    if (isRunning) {
                        tvAddress.text = getString(R.string.rtsp_address, "rtsp://$ip:$port/")
                    }
                }
            }.start()
        } else {
            tvAddress.text = ""
        }
    }

    private fun getLocalIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is InetAddress) {
                        val host = addr.hostAddress ?: ""
                        if (host.contains(".") && !host.startsWith("169.254")) return host
                    }
                }
            }
        } catch (e: Exception) {
        }
        return "127.0.0.1"
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning) stopService()
    }
}
