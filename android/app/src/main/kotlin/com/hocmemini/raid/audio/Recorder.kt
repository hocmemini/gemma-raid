package com.hocmemini.raid.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Records 16 kHz mono PCM16 from the mic and returns WAV bytes — the format Gemma 4 E2B takes natively. */
class Recorder {
    private val recording = AtomicBoolean(false)
    private var worker: Thread? = null
    private val buf = ByteArrayOutputStream()
    val isRecording: Boolean get() = recording.get()

    @SuppressLint("MissingPermission")
    fun start() {
        if (!recording.compareAndSet(false, true)) return
        synchronized(buf) { buf.reset() }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192) * 2)
        worker = thread(name = "raid-rec") {
            val chunk = ByteArray(4096)
            rec.startRecording()
            while (recording.get()) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) synchronized(buf) { buf.write(chunk, 0, n); if (buf.size() > MAX_PCM_BYTES) recording.set(false) }
            }
            runCatching { rec.stop() }; rec.release()
        }
    }

    /** Stops recording; returns WAV bytes, or null if the clip is shorter than half a second. */
    fun stop(): ByteArray? {
        recording.set(false)
        worker?.join(1500); worker = null
        val pcm = synchronized(buf) { buf.toByteArray() }
        if (pcm.size < SAMPLE_RATE) return null
        return wav(pcm)
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_PCM_BYTES = SAMPLE_RATE * 2 * 20 // 20 s cap

        fun wav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            header.put("data".toByteArray()).putInt(pcm.size)
            return header.array() + pcm
        }
    }
}
