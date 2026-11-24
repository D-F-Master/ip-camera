package com.example.ipcamera.camera

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.graphics.ImageFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import com.example.ipcamera.util.CameraConfig
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class CameraController(
    private val context: Context,
    private val config: CameraConfig,
    private val onEncodedFrame: (ByteArray, Long) -> Unit,
    private val onParameterSets: (ByteArray?, ByteArray?, ByteArray?) -> Unit,
    private val onCameraSwitched: (String, Boolean) -> Unit = { _, _ -> }
) {
    private val tag = "CameraController"
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraIds: Array<String> = emptyArray()
    @Volatile private var currentIndex = 0
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaCodec: MediaCodec? = null
    private var encodeThread: HandlerThread? = null
    private var encodeHandler: Handler? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var isRunning = false
    private var encodeSurface: Surface? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    @Volatile private var consecutiveBlackFrames = 0
    private val autoSkipped = ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var cameraGeneration = 0
    private val cameraLock = Any()
    private var width: Int = config.width
    private var height: Int = config.height

    init {
        try {
            cameraIds = cameraManager.cameraIdList
        } catch (e: Exception) {
            Log.e(tag, "get camera list failed", e)
        }
        val savedIndex = cameraIds.indexOf(config.cameraId)
        if (savedIndex >= 0) currentIndex = savedIndex
    }

    val cameraCount: Int get() = cameraIds.size

    fun hasH265Encoder(): Boolean {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        return list.codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }
        }
    }

    fun start(surface: Surface) {
        if (isRunning) return
        if (cameraIds.isEmpty()) throw RuntimeException("No camera found")
        if (!hasH265Encoder()) throw RuntimeException("H265 encoder not supported on this device")
        if (currentIndex !in cameraIds.indices) currentIndex = 0
        autoSkipped.clear()

        previewSurface = surface
        isRunning = true
        cameraGeneration++
        determineSize()

        cameraThread = HandlerThread("camera-thread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
        encodeThread = HandlerThread("encode-thread").apply { start() }
        encodeHandler = Handler(encodeThread!!.looper)

        imageReader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buf = plane.buffer
                buf.rewind()
                var sum = 0L
                var count = 0
                val step = maxOf(1, buf.remaining() / 2000)
                while (buf.remaining() > 0 && count < 2000) {
                    val v = buf.get().toInt() and 0xff
                    sum += v
                    for (i in 0 until step - 1) if (buf.remaining() > 0) buf.get()
                    count++
                }
                val mean = if (count > 0) sum / count.toLong() else 0L
                // 自动跳过无图像的摄像头（某些设备上报了摄像头但没有接传感器）
                if (mean < 12) consecutiveBlackFrames++ else consecutiveBlackFrames = 0
                synchronized(cameraLock) {
                    if (config.autoSkipBlackCamera && consecutiveBlackFrames >= 15 &&
                        cameraIds.size > 1 && currentIndex !in autoSkipped) {
                        autoSkipped.add(currentIndex)
                        val next = (1 until cameraIds.size)
                            .map { (currentIndex + it) % cameraIds.size }
                            .firstOrNull { it !in autoSkipped }
                        if (next != null) {
                            Log.w(tag, "camera ${cameraIds[currentIndex]} appears black, auto switch to ${cameraIds[next]}")
                            consecutiveBlackFrames = 0
                            switchToCamera(next, userInitiated = false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "luma sample failed", e)
            } finally {
                image.close()
            }
        }, cameraHandler)

        setupEncoder()
        openCamera()
    }

    fun switchCamera(): String? {
        if (!isRunning || cameraIds.size < 2) return null
        return synchronized(cameraLock) {
            switchToCamera((currentIndex + 1) % cameraIds.size, clearSkipped = true, userInitiated = true)
            cameraIds.getOrNull(currentIndex)
        }
    }

    private fun switchToCamera(index: Int, clearSkipped: Boolean = false, userInitiated: Boolean = false) {
        synchronized(cameraLock) {
            currentIndex = index % cameraIds.size
            consecutiveBlackFrames = 0
            if (clearSkipped) autoSkipped.clear()
            cameraGeneration++
            cameraIds.getOrNull(currentIndex)?.let { onCameraSwitched(it, userInitiated) }
            closeCamera()
            openCamera()
        }
    }

    fun currentCameraId(): String? = cameraIds.getOrNull(currentIndex)

    fun stop() {
        isRunning = false
        cameraGeneration++
        closeCamera()
        mediaCodec?.let {
            try { it.stop() } catch (e: Exception) { Log.w(tag, "codec stop: $e") }
            try { it.release() } catch (e: Exception) { Log.w(tag, "codec release: $e") }
        }
        mediaCodec = null
        encodeSurface?.release()
        encodeSurface = null
        imageReader?.close()
        imageReader = null
        encodeThread?.quitSafely()
        encodeThread = null
        cameraThread?.quitSafely()
        cameraThread = null
        previewSurface = null
    }

    private fun determineSize() {
        try {
            val target = Size(config.width, config.height)
            val sizeSets = cameraIds.mapNotNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(SurfaceHolder::class.java)?.toSet()
            }
            // 选所有摄像头都支持、且最接近配置值的尺寸，避免切换摄像头后 session 配置失败。
            val common = if (sizeSets.isEmpty()) null else sizeSets.reduce { a, b -> a intersect b }
            val candidates = common ?: sizeSets.firstOrNull()
            val chosen = candidates?.minByOrNull { abs(it.width - target.width) + abs(it.height - target.height) }
            if (chosen != null) {
                width = chosen.width
                height = chosen.height
            }
            Log.d(tag, "encode size: ${width}x${height}")
        } catch (e: Exception) {
            Log.w(tag, "determineSize failed, use default", e)
        }
    }

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameIntervalSec)

        val codecInfo = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }
        } ?: throw RuntimeException("No H265 encoder")

        val codec = MediaCodec.createByCodecName(codecInfo.name)
        mediaCodec = codec
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encodeSurface = codec.createInputSurface()
            codec.start()
            startEncodeLoop()
        } catch (e: Exception) {
            try { encodeSurface?.release() } catch (_: Exception) {}
            encodeSurface = null
            try { codec.release() } catch (_: Exception) {}
            mediaCodec = null
            throw e
        }
    }

    private fun startEncodeLoop() {
        val codec = mediaCodec ?: return
        val info = MediaCodec.BufferInfo()
        encodeHandler?.post(object : Runnable {
            override fun run() {
                if (!isRunning) return
                try {
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            extractParameterSets(codec.outputFormat)
                        }
                        index >= 0 -> {
                            val bb = codec.getOutputBuffer(index)
                            if (bb != null && info.size > 0) {
                                bb.position(info.offset)
                                bb.limit(info.offset + info.size)
                                val data = ByteArray(info.size)
                                bb.get(data)
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    parseConfigData(data)
                                } else {
                                    onEncodedFrame(data, info.presentationTimeUs)
                                }
                            }
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "encode loop error", e)
                }
                encodeHandler?.post(this)
            }
        })
    }

    private fun extractParameterSets(format: MediaFormat) {
        val all = mutableListOf<ByteArray>()
        for (key in arrayOf("csd-0", "csd-1", "csd-2")) {
            getByteArray(format, key)?.let { all.addAll(parseParameterSetBuffer(it)) }
        }
        Log.d(tag, "format csd parsed nals=${all.size}")
        dispatchParameterSets(all)
    }

    private fun parseConfigData(data: ByteArray) {
        dispatchParameterSets(parseParameterSetBuffer(data))
    }

    /** 兼容两种格式：4字节长度前缀/Annex-B NAL，以及 hvcC 配置记录。 */
    private fun parseParameterSetBuffer(buf: ByteArray): List<ByteArray> {
        val hvc = parseHvcC(buf)
        if (hvc.isNotEmpty()) return hvc
        return splitLengthPrefixed(buf)
    }

    /** ISO/IEC 14496-15 HEVCDecoderConfigurationRecord。 */
    private fun parseHvcC(buf: ByteArray): List<ByteArray> {
        var off = 0
        fun isHvcC(i: Int) = buf.size >= i + 4 &&
                buf[i] == 0x68.toByte() && buf[i + 1] == 0x76.toByte() &&
                buf[i + 2] == 0x63.toByte() && buf[i + 3] == 0x43.toByte()
        if (isHvcC(0)) off = 4 else if (isHvcC(4)) off = 8
        if (buf.size < off + 23 || (buf[off].toInt() and 0xff) != 1) return emptyList()
        val result = mutableListOf<ByteArray>()
        val numArrays = buf[off + 22].toInt() and 0xff
        var p = off + 23
        for (a in 0 until numArrays) {
            if (p + 3 > buf.size) break
            p++ // completeness / NAL type
            val count = ((buf[p].toInt() and 0xff) shl 8) or (buf[p + 1].toInt() and 0xff)
            p += 2
            repeat(count) {
                if (p + 2 > buf.size) return@repeat
                val len = ((buf[p].toInt() and 0xff) shl 8) or (buf[p + 1].toInt() and 0xff)
                p += 2
                if (len <= 0 || p + len > buf.size) return@repeat
                result.add(buf.copyOfRange(p, p + len))
                p += len
            }
        }
        return result
    }

    private fun dispatchParameterSets(nals: List<ByteArray>) {
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in nals) {
            if (nal.size < 2) continue
            when ((nal[0].toInt() shr 1) and 0x3f) {
                32 -> vps = nal
                33 -> sps = nal
                34 -> pps = nal
            }
        }
        if (vps != null || sps != null || pps != null) {
            Log.d(tag, "parameter sets ready: vps=${vps?.size},sps=${sps?.size},pps=${pps?.size}")
            onParameterSets(vps, sps, pps)
        } else {
            Log.w(tag, "no VPS/SPS/PPS extracted from nals=${nals.size}")
        }
    }

    private fun getByteArray(format: MediaFormat, key: String): ByteArray? {
        val bb = format.getByteBuffer(key) ?: return null
        val copy = ByteArray(bb.remaining())
        bb.duplicate().get(copy)
        return copy
    }

    /** MediaCodec H265 输出为 4 字节长度前缀格式；同时兼容 Annex-B。 */
    private fun splitLengthPrefixed(buf: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        if (buf.size >= 3 && buf[0] == 0.toByte() && buf[1] == 0.toByte() &&
            (buf[2] == 1.toByte() || (buf.size >= 4 && buf[2] == 0.toByte() && buf[3] == 1.toByte()))
        ) {
            return splitAnnexB(buf)
        }
        var i = 0
        while (i + 4 <= buf.size) {
            val len = ((buf[i].toInt() and 0xff) shl 24) or
                    ((buf[i + 1].toInt() and 0xff) shl 16) or
                    ((buf[i + 2].toInt() and 0xff) shl 8) or
                    (buf[i + 3].toInt() and 0xff)
            if (len <= 0 || i + 4 + len > buf.size) break
            result.add(buf.copyOfRange(i + 4, i + 4 + len))
            i += 4 + len
        }
        if (result.isEmpty() && buf.isNotEmpty()) result.add(buf)
        return result
    }

    private fun splitAnnexB(buf: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 2 < buf.size) {
            if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte() && buf[i + 2] == 1.toByte()) {
                starts.add(i)
                i += 3
            } else {
                i++
            }
        }
        val result = mutableListOf<ByteArray>()
        for (j in starts.indices) {
            val start = starts[j] + 3
            val end = if (j + 1 < starts.size) starts[j + 1] else buf.size
            if (end > start) {
                var realEnd = end
                while (realEnd > start && buf[realEnd - 1] == 0.toByte()) realEnd--
                result.add(buf.copyOfRange(start, realEnd))
            }
        }
        return result
    }

    private fun openCamera() {
        if (!isRunning || cameraIds.isEmpty()) return
        val cameraId = cameraIds[currentIndex % cameraIds.size]
        val generation = cameraGeneration
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (generation != cameraGeneration || !isRunning) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    createSession(camera, generation)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    if (generation != cameraGeneration) {
                        camera.close()
                        return
                    }
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(tag, "camera error: $error")
                    if (generation != cameraGeneration) {
                        camera.close()
                        return
                    }
                    camera.close()
                    cameraDevice = null
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e(tag, "openCamera failed", e)
        } catch (e: SecurityException) {
            Log.e(tag, "camera permission denied", e)
        }
    }

    private fun createSession(camera: CameraDevice, generation: Int, includeLuma: Boolean = true) {
        val encSurface = encodeSurface
        val prevSurface = previewSurface
        val lumaSurface = if (includeLuma) imageReader?.surface else null
        if (encSurface == null || prevSurface == null) return
        try {
            camera.createCaptureSession(
                listOf(prevSurface, encSurface, lumaSurface).filterNotNull(),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (generation != cameraGeneration || !isRunning) {
                            session.close()
                            return
                        }
                        captureSession = session
                        try {
                            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            builder.addTarget(prevSurface)
                            builder.addTarget(encSurface)
                            lumaSurface?.let { builder.addTarget(it) }
                            val afModes = try {
                                cameraManager.getCameraCharacteristics(camera.id)
                                    .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                            } catch (e: Exception) { null }
                            if (afModes?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true) {
                                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }
                            session.setRepeatingRequest(builder.build(), null, cameraHandler)
                        } catch (e: Exception) {
                            Log.e(tag, "setRepeatingRequest failed", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (includeLuma && imageReader != null) {
                            Log.w(tag, "capture session with luma failed, retry without luma")
                            cameraHandler?.post { createSession(camera, generation, includeLuma = false) }
                        } else {
                            Log.e(tag, "capture session configure failed")
                        }
                    }
                },
                cameraHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(tag, "createCaptureSession failed", e)
        }
    }

    private fun closeCamera() {
        try { captureSession?.close() } catch (e: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (e: Exception) {}
        cameraDevice = null
    }
}
