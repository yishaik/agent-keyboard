package helium314.keyboard.agent.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * PCM16 mono at 24 kHz in both directions: the default format of OpenAI Realtime
 * and Grok Voice. Capture runs on its own thread and hands ~40 ms frames to [onFrame].
 */
class AudioIO(private val sampleRate: Int = 24_000) {
    @Volatile private var recording = false
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    @Volatile var level: Float = 0f; private set

    @SuppressLint("MissingPermission") // checked by AgentController before starting
    fun startCapture(onFrame: (ByteArray) -> Unit) {
        if (recording) return
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val frameBytes = sampleRate / 25 * 2 // 40 ms
        // VOICE_COMMUNICATION turns on echo cancellation, so the agent does not hear itself.
        val src = MediaRecorder.AudioSource.VOICE_COMMUNICATION
        val r = AudioRecord(src, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frameBytes * 4))
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); throw IllegalStateException("Microphone unavailable") }
        record = r
        recording = true
        r.startRecording()
        thread(name = "agent-mic", isDaemon = true) {
            val buf = ByteArray(frameBytes)
            while (recording) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) {
                    level = rms(buf, n)
                    onFrame(buf.copyOf(n))
                }
            }
        }
    }

    fun stopCapture() {
        recording = false
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
        level = 0f
    }

    val isCapturing get() = recording

    fun play(pcm16: ByteArray) {
        val t = track ?: createTrack().also { track = it }
        t.write(pcm16, 0, pcm16.size)
    }

    /** Drop queued speech, e.g. when the user barges in. */
    fun flushPlayback() {
        track?.let { runCatching { it.pause(); it.flush(); it.play() } }
    }

    fun release() {
        stopCapture()
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }

    private fun createTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = if (Build.VERSION.SDK_INT >= 23) {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, sampleRate)) // ~0.5 s
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, sampleRate), AudioTrack.MODE_STREAM)
        }
        t.play()
        return t
    }

    private fun rms(b: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)).toShort().toDouble()
            sum += s * s; i += 2
        }
        return (sqrt(sum / (n / 2).coerceAtLeast(1)) / 32768.0).toFloat()
    }
}
