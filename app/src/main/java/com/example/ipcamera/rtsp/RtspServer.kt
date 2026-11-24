package com.example.ipcamera.rtsp

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import android.util.Base64
import android.util.Log

/**
 * 极简 RTSP 服务器：支持 Basic/Digest 认证、TCP interleaved 与 UDP 传输、H265 RTP 推流。
 */
class RtspServer(
    private val port: Int,
    private val username: String,
    private val password: String
) {
    private val tag = "RtspServer"
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val sessions = CopyOnWriteArrayList<ClientSession>()
    private val packetizer = RtpPacketizer()
    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var lastTimestamp = 0L
    private val realm = "IPCamera"
    private val nonce = java.lang.Long.toHexString(System.nanoTime())

    private data class Request(val method: String, val uri: String, val headers: Map<String, String>)

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        acceptThread = Thread {
            while (!ss.isClosed) {
                try {
                    val client = ss.accept()
                    val session = ClientSession(client)
                    sessions.add(session)
                    session.isDaemon = true
                    session.start()
                } catch (e: Exception) {
                    continue
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        try { serverSocket?.close() } catch (e: Exception) {}
        for (s in sessions) s.shutdown()
        sessions.clear()
    }

    fun setParameterSets(vps: ByteArray?, sps: ByteArray?, pps: ByteArray?) {
        if (vps != null) this.vps = vps
        if (sps != null) this.sps = sps
        if (pps != null) this.pps = pps
    }

    fun hasClients(): Boolean = sessions.any { it.isPlaying() }

    fun onH265Frame(data: ByteArray, timestampUs: Long) {
        if (sessions.isEmpty()) return
        lastTimestamp = timestampUs * 9 / 100
        val packets = packetizer.packetize(withParameterSets(data), lastTimestamp)
        for (s in sessions) {
            if (s.isPlaying()) {
                for (p in packets) s.enqueue(p)
            }
        }
    }

    /** 关键帧前插入 VPS/SPS/PPS，保证迟到客户端和中途加入的客户端能解码。 */
    private fun withParameterSets(data: ByteArray): ByteArray {
        val sets = listOfNotNull(vps, sps, pps)
        if (sets.isEmpty() || !hasIdr(data)) return data
        // MediaCodec 输出的是 Annex-B（00 00 00 01 start code），参数集必须用同样的格式，
        // 否则 RtpPacketizer 会按 4 字节长度前缀解析，把关键帧 NAL 切错并丢弃 IDR。
        val prefix = ByteArray(sets.sumOf { it.size + 4 })
        var pos = 0
        for (s in sets) {
            prefix[pos++] = 0
            prefix[pos++] = 0
            prefix[pos++] = 0
            prefix[pos++] = 1
            System.arraycopy(s, 0, prefix, pos, s.size)
            pos += s.size
        }
        return prefix + data
    }

    private fun hasIdr(data: ByteArray): Boolean {
        var i = 0
        while (i + 5 <= data.size) {
            val len = ((data[i].toInt() and 0xff) shl 24) or
                    ((data[i + 1].toInt() and 0xff) shl 16) or
                    ((data[i + 2].toInt() and 0xff) shl 8) or
                    (data[i + 3].toInt() and 0xff)
            if (len <= 0 || i + 4 + len > data.size) break
            val t = (data[i + 4].toInt() shr 1) and 0x3f
            if (t == 19 || t == 20 || t == 21) return true
            i += 4 + len
        }
        for (j in 0 until data.size - 4) {
            if (data[j] == 0.toByte() && data[j + 1] == 0.toByte() && data[j + 2] == 1.toByte()) {
                val t = (data[j + 3].toInt() shr 1) and 0x3f
                if (t == 19 || t == 20 || t == 21) return true
            }
        }
        return false
    }

    inner class ClientSession(private val socket: Socket) : Thread() {
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
        private val output = BufferedOutputStream(socket.getOutputStream())
        private val sessionId = java.lang.Integer.toHexString((Math.random() * Int.MAX_VALUE).toInt())
        private val queue = ArrayBlockingQueue<ByteArray>(90)
        private var udpServerPort = 5000
        private var playing = false
        private var transportTcp = true
        private var rtpChannel = 0
        private var udpSocket: DatagramSocket? = null
        private var udpAddress: InetAddress? = null
        private var udpPort = 0
        private var sendThread: Thread? = null
        private var closed = false

        fun isPlaying(): Boolean = playing && !closed

        fun enqueue(packet: ByteArray) {
            try {
                queue.offer(packet, 30, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        override fun run() {
            try {
                while (!closed && !socket.isClosed) {
                    val req = readRequest() ?: break
                    handleRequest(req)
                }
            } catch (e: Exception) {
                // client disconnected
            } finally {
                shutdown()
            }
        }

        private fun readRequest(): Request? {
            val line = reader.readLine() ?: return null
            if (line.isBlank()) return null
            val parts = line.split(" ")
            if (parts.size < 3) return null
            val headers = mutableMapOf<String, String>()
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
                val idx = h.indexOf(':')
                if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len > 0) {
                val buf = CharArray(len)
                var read = 0
                while (read < len) {
                    val n = reader.read(buf, read, len - read)
                    if (n < 0) break
                    read += n
                }
            }
            return Request(parts[0].uppercase(), parts[1], headers)
        }

        private fun handleRequest(req: Request) {
            val cSeq = req.headers["cseq"] ?: "0"
            when (req.method) {
                "OPTIONS" -> sendResponse(
                    "200 OK",
                    mapOf("CSeq" to cSeq, "Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER")
                )

                "DESCRIBE" -> {
                    if (!checkAuth(req)) { sendUnauthorized(cSeq); return }
                    val sdp = buildSdp(req.uri)
                    val body = sdp.toByteArray(StandardCharsets.ISO_8859_1)
                    sendResponse(
                        "200 OK",
                        mapOf(
                            "CSeq" to cSeq,
                            "Content-Base" to req.uri,
                            "Content-Type" to "application/sdp",
                            "Content-Length" to body.size.toString()
                        ),
                        body
                    )
                }

                "SETUP" -> {
                    if (!checkAuth(req)) { sendUnauthorized(cSeq); return }
                    parseTransport(req.headers["transport"] ?: "")
                    val transportHeader = if (transportTcp) {
                        "RTP/AVP/TCP;unicast;interleaved=$rtpChannel-${rtpChannel + 1}"
                    } else {
                        "RTP/AVP/UDP;unicast;client_port=$udpPort-${udpPort + 1};server_port=$udpServerPort-${udpServerPort + 1}"
                    }
                    sendResponse(
                        "200 OK",
                        mapOf(
                            "CSeq" to cSeq,
                            "Transport" to transportHeader,
                            "Session" to "$sessionId;timeout=60"
                        )
                    )
                }

                "PLAY" -> {
                    if (!checkAuth(req)) { sendUnauthorized(cSeq); return }
                    if (!checkSession(req)) { sendResponse("454 Session Not Found", mapOf("CSeq" to cSeq)); return }
                    playing = true
                    startSendThread()
                    sendResponse(
                        "200 OK",
                        mapOf(
                            "CSeq" to cSeq,
                            "Session" to "$sessionId;timeout=60",
                            "RTP-Info" to "url=${req.uri};seq=${packetizer.currentSeq};rtptime=$lastTimestamp"
                        )
                    )
                }

                "PAUSE" -> {
                    if (!checkAuth(req)) { sendUnauthorized(cSeq); return }
                    if (!checkSession(req)) { sendResponse("454 Session Not Found", mapOf("CSeq" to cSeq)); return }
                    playing = false
                    sendResponse("200 OK", mapOf("CSeq" to cSeq, "Session" to sessionId))
                }

                "TEARDOWN" -> {
                    if (!checkAuth(req)) { sendUnauthorized(cSeq); return }
                    if (!checkSession(req)) { sendResponse("454 Session Not Found", mapOf("CSeq" to cSeq)); return }
                    playing = false
                    sendResponse("200 OK", mapOf("CSeq" to cSeq, "Session" to sessionId))
                    shutdown()
                }

                "GET_PARAMETER" -> {
                    if (!checkAuth(req)) { sendUnauthorized(cSeq); return }
                    sendResponse("200 OK", mapOf("CSeq" to cSeq, "Content-Length" to "0"))
                }

                else -> sendResponse("455 Method Not Valid In This State", mapOf("CSeq" to cSeq))
            }
        }

        private fun parseTransport(header: String) {
            if (header.contains("RTP/AVP/TCP", true) || header.contains("interleaved", true)) {
                transportTcp = true
                val m = Regex("interleaved=(\\d+)-(\\d+)").find(header)
                rtpChannel = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
            } else {
                transportTcp = false
                val m = Regex("client_port=(\\d+)-(\\d+)").find(header)
                udpPort = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                udpAddress = socket.inetAddress
                try {
                    udpSocket = DatagramSocket()
                    udpServerPort = udpSocket?.localPort ?: 5000
                } catch (e: Exception) {}
            }
        }

        private fun startSendThread() {
            if (sendThread != null) return
            sendThread = Thread {
                while (isPlaying() && !socket.isClosed) {
                    try {
                        val pkt = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        if (transportTcp) {
                            val interleaved = byteArrayOf(
                                0x24,
                                rtpChannel.toByte(),
                                (pkt.size shr 8).toByte(),
                                (pkt.size and 0xff).toByte()
                            )
                            synchronized(output) {
                                output.write(interleaved)
                                output.write(pkt)
                                output.flush()
                            }
                        } else {
                            val addr = udpAddress
                            val ds = udpSocket
                            if (addr != null && ds != null && udpPort > 0) {
                                ds.send(DatagramPacket(pkt, pkt.size, addr, udpPort))
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "send error: $e")
                        shutdown()
                        break
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        private fun sendResponse(status: String, headers: Map<String, String>, body: ByteArray? = null) {
            val sb = StringBuilder()
            sb.append("RTSP/1.0 ").append(status).append("\r\n")
            headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
            sb.append("\r\n")
            synchronized(output) {
                output.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
                if (body != null) output.write(body)
                output.flush()
            }
        }

        private fun sendUnauthorized(cSeq: String) {
            val digest = "Digest realm=\"$realm\", nonce=\"$nonce\", qop=\"auth\""
            synchronized(output) {
                val sb = StringBuilder()
                sb.append("RTSP/1.0 401 Unauthorized\r\n")
                sb.append("CSeq: $cSeq\r\n")
                sb.append("WWW-Authenticate: $digest\r\n")
                sb.append("WWW-Authenticate: Basic realm=\"$realm\"\r\n")
                sb.append("Content-Length: 0\r\n\r\n")
                output.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
                output.flush()
            }
        }

        private fun checkAuth(req: Request): Boolean {
            val auth = req.headers["authorization"] ?: return false
            return try {
                when {
                    auth.startsWith("Basic ", true) -> {
                        val decoded = String(
                            Base64.decode(auth.substring(6).trim(), Base64.DEFAULT),
                            StandardCharsets.UTF_8
                        )
                        val idx = decoded.indexOf(':')
                        idx >= 0 && decoded.substring(0, idx) == username && decoded.substring(idx + 1) == password
                    }

                    auth.startsWith("Digest ", true) -> {
                        val params = parseAuthParams(auth.substring(7))
                        val n = params["nonce"] ?: return false
                        if (n != nonce) return false
                        val digestUri = params["uri"] ?: req.uri
                        val ha1 = md5("$username:$realm:$password")
                        val ha2 = md5("${req.method}:$digestUri")
                        // 兼容两种 Digest：VLC 常发无 qop 的 RFC 2069；标准客户端发 qop=auth 的 RFC 2617
                        val qop = params["qop"]
                        val expected = if (qop.isNullOrEmpty()) {
                            md5("$ha1:$n:$ha2")
                        } else {
                            if (qop != "auth") return false
                            val nc = params["nc"] ?: return false
                            val cnonce = params["cnonce"] ?: return false
                            md5("$ha1:$n:$nc:$cnonce:$qop:$ha2")
                        }
                        expected.equals(params["response"], true)
                    }

                    else -> false
                }
            } catch (e: Exception) {
                false
            }
        }

        private fun checkSession(req: Request): Boolean {
            val s = req.headers["session"] ?: return true
            return s.substringBefore(';').trim() == sessionId
        }

        private fun parseAuthParams(s: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            val regex = Regex("(\\w+)=\"([^\"]*)\"|(\\w+)=([^,\\s]+)")
            for (m in regex.findAll(s)) {
                val key = m.groupValues[1].ifEmpty { m.groupValues[3] }
                val value = m.groupValues[2].ifEmpty { m.groupValues[4] }
                result[key.lowercase()] = value
            }
            return result
        }

        private fun md5(s: String): String {
            val bytes = MessageDigest.getInstance("MD5").digest(s.toByteArray(StandardCharsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun buildSdp(uri: String): String {
            fun b64(b: ByteArray?) = if (b == null || b.isEmpty()) "" else Base64.encodeToString(b, Base64.NO_WRAP)
            val fmtp = buildString {
                append("profile-id=1;tier-flag=0;")
                val v = b64(vps); val s = b64(sps); val p = b64(pps)
                if (v.isNotEmpty()) append("sprop-vps=$v;")
                if (s.isNotEmpty()) append("sprop-sps=$s;")
                if (p.isNotEmpty()) append("sprop-pps=$p;")
            }.trimEnd(';')

            val host = try { socket.localAddress.hostAddress } catch (e: Exception) { null } ?: "127.0.0.1"
            val control = if (uri.endsWith("/")) "${uri}track1" else "$uri/track1"
            return buildString {
                append("v=0\r\n")
                append("o=- 0 0 IN IP4 $host\r\n")
                append("s=H265 Camera Stream\r\n")
                append("c=IN IP4 0.0.0.0\r\n")
                append("t=0 0\r\n")
                append("a=tool:IPCamera RTSP Server\r\n")
                append("m=video 0 RTP/AVP 96\r\n")
                append("a=rtpmap:96 H265/90000\r\n")
                append("a=fmtp:96 $fmtp\r\n")
                append("a=control:$control\r\n")
            }
        }

        fun shutdown() {
            if (closed) return
            closed = true
            playing = false
            try { sendThread?.interrupt() } catch (e: Exception) {}
            try { udpSocket?.close() } catch (e: Exception) {}
            try { reader.close() } catch (e: Exception) {}
            try { output.close() } catch (e: Exception) {}
            try { socket.close() } catch (e: Exception) {}
            sessions.remove(this)
        }
    }
}
