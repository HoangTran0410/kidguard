package dev.kidguard

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * AAC ADTS stream (what [Listen] records) -> M4A, without re-encoding: Telegram plays M4A inline as a voice
 * message, while a raw .aac only arrives as a file to download. Parsing is pure (unit-tested); [toM4a] uses MediaMuxer.
 */
object Adts {
    /** One AAC frame inside [src]: payload offset/length, without the ADTS header. */
    data class Frame(val off: Int, val len: Int)
    data class Info(val profile: Int, val srIdx: Int, val channels: Int, val frames: List<Frame>) {
        val rate get() = RATES.getOrElse(srIdx) { 16_000 }
        val durationMs get() = frames.size * 1024L * 1000 / rate
    }
    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** Walks the ADTS headers; stops at the first broken / cut frame. Null when there is no frame at all. */
    fun parse(b: ByteArray): Info? {
        var i = 0; var head: IntArray? = null; val out = ArrayList<Frame>()
        while (i + 7 <= b.size) {
            if (b[i].toInt() and 0xFF != 0xFF || b[i + 1].toInt() and 0xF0 != 0xF0) break
            val noCrc = b[i + 1].toInt() and 1 == 1
            val profile = (b[i + 2].toInt() shr 6) and 3
            val sr = (b[i + 2].toInt() shr 2) and 0xF
            val ch = ((b[i + 2].toInt() and 1) shl 2) or ((b[i + 3].toInt() shr 6) and 3)
            val len = ((b[i + 3].toInt() and 3) shl 11) or ((b[i + 4].toInt() and 0xFF) shl 3) or ((b[i + 5].toInt() and 0xFF) shr 5)
            val hl = if (noCrc) 7 else 9
            if (len <= hl || i + len > b.size) break
            if (head == null) head = intArrayOf(profile, sr, ch)
            out += Frame(i + hl, len - hl); i += len
        }
        val h = head ?: return null
        return Info(h[0] + 1, h[1], h[2].coerceAtLeast(1), out)
    }

    /** AudioSpecificConfig (csd-0): 5 bits object type, 4 bits rate index, 4 bits channels. */
    fun asc(info: Info) = byteArrayOf(((info.profile shl 3) or (info.srIdx shr 1)).toByte(), (((info.srIdx and 1) shl 7) or (info.channels shl 3)).toByte())

    /** M4A bytes, or null (then the caller sends the raw .aac). */
    fun toM4a(src: ByteArray, tmpDir: File): Pair<ByteArray, Long>? {
        val info = parse(src)?.takeIf { it.frames.isNotEmpty() } ?: return null
        val f = File.createTempFile("rec", ".m4a", tmpDir)
        return try {
            val m = MediaMuxer(f.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, info.rate, info.channels)
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(asc(info)))
            val track = m.addTrack(fmt); m.start()
            val bi = MediaCodec.BufferInfo()
            info.frames.forEachIndexed { n, fr ->
                bi.set(0, fr.len, n * 1024L * 1_000_000 / info.rate, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                m.writeSampleData(track, ByteBuffer.wrap(src, fr.off, fr.len).slice(), bi)
            }
            m.stop(); m.release()
            f.readBytes() to info.durationMs
        } catch (e: Exception) { null } finally { f.delete() }
    }
}
