package com.example.ipcamera.rtsp

import kotlin.math.min

/**
 * 把 MediaCodec 输出的 H265 数据（4 字节长度前缀或 Annex-B）打包成 RTP 包。
 * 遵循 RFC 7798：Single NAL Unit Packet 与 FU (Fragmentation Unit)。
 */
class RtpPacketizer(private val mtu: Int = 1200) {
    companion object {
        private const val PT_H265 = 96
    }

    private var seq = (Math.random() * 65536).toInt() and 0xffff
    private val ssrc = (Math.random() * 4294967295L).toLong()

    val currentSeq: Int get() = seq

    fun packetize(encodedData: ByteArray, timestamp: Long): List<ByteArray> {
        val nals = splitNals(encodedData)
        val packets = mutableListOf<ByteArray>()
        for (nal in nals) {
            if (nal.size <= 2) continue
            packets.addAll(packetizeNal(nal, timestamp))
        }
        if (packets.isNotEmpty()) {
            val last = packets.lastIndex
            val p = packets[last].copyOf()
            p[1] = (p[1].toInt() or 0x80).toByte()
            packets[last] = p
        }
        return packets
    }

    private fun packetizeNal(nal: ByteArray, ts: Long): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        if (nal.size <= mtu) {
            result.add(buildRtp(nal, ts))
        } else {
            val nalType = (nal[0].toInt() shr 1) and 0x3f
            val payloadHdr0 = ((nal[0].toInt() and 0x81) or (49 shl 1)).toByte()
            val payloadHdr1 = nal[1]
            val maxFrag = mtu - 3
            var offset = 2
            var first = true
            while (offset < nal.size) {
                val len = min(maxFrag, nal.size - offset)
                val frag = nal.copyOfRange(offset, offset + len)
                val lastFrag = (offset + len >= nal.size)
                val fuHeader = (((if (first) 1 else 0) shl 7) or
                        ((if (lastFrag) 1 else 0) shl 6) or nalType).toByte()
                val payload = byteArrayOf(payloadHdr0, payloadHdr1, fuHeader) + frag
                result.add(buildRtp(payload, ts))
                offset += len
                first = false
            }
        }
        return result
    }

    private fun buildRtp(payload: ByteArray, ts: Long): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header[1] = PT_H265.toByte()
        seq = (seq + 1) and 0xffff
        header[2] = (seq shr 8).toByte()
        header[3] = seq.toByte()
        header[4] = (ts shr 24).toByte()
        header[5] = (ts shr 16).toByte()
        header[6] = (ts shr 8).toByte()
        header[7] = ts.toByte()
        header[8] = (ssrc shr 24).toByte()
        header[9] = (ssrc shr 16).toByte()
        header[10] = (ssrc shr 8).toByte()
        header[11] = ssrc.toByte()
        return header + payload
    }

    private fun splitNals(data: ByteArray): List<ByteArray> {
        if (data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            (data[2] == 1.toByte() || (data.size >= 4 && data[2] == 0.toByte() && data[3] == 1.toByte()))
        ) {
            return splitAnnexB(data)
        }
        val result = mutableListOf<ByteArray>()
        var i = 0
        while (i + 4 <= data.size) {
            val len = ((data[i].toInt() and 0xff) shl 24) or
                    ((data[i + 1].toInt() and 0xff) shl 16) or
                    ((data[i + 2].toInt() and 0xff) shl 8) or
                    (data[i + 3].toInt() and 0xff)
            if (len <= 0 || i + 4 + len > data.size) break
            result.add(data.copyOfRange(i + 4, i + 4 + len))
            i += 4 + len
        }
        if (result.isEmpty() && data.isNotEmpty()) result.add(data)
        return result
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                starts.add(i)
                i += 3
            } else {
                i++
            }
        }
        val result = mutableListOf<ByteArray>()
        for (j in starts.indices) {
            val start = starts[j] + 3
            val end = if (j + 1 < starts.size) starts[j + 1] else data.size
            if (end > start) {
                var realEnd = end
                while (realEnd > start && data[realEnd - 1] == 0.toByte()) realEnd--
                result.add(data.copyOfRange(start, realEnd))
            }
        }
        return result
    }
}
