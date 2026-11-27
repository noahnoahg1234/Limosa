package com.limosa.kdehome

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Plays Linux's sound on Android. PulseAudio inside Linux streams everything that plays as raw
 * 48 kHz 16-bit stereo to 127.0.0.1:[port]; this writes it to an AudioTrack. When nothing plays,
 * PulseAudio stops sending and the track is paused, so silence costs no battery.
 */
class AudioBridge(private val port: Int) {
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "linux-audio", isDaemon = true) {
            while (running) {
                try {
                    Socket().use { s ->
                        socket = s
                        s.connect(InetSocketAddress("127.0.0.1", port), 1000)
                        s.soTimeout = 400
                        play(s.getInputStream())
                    }
                } catch (_: Exception) {
                }
                socket = null
                if (running) Thread.sleep(1500)   // PulseAudio not up yet, or restarted
            }
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
    }

    private fun play(input: InputStream) {
        val rate = 48000
        val format = AudioFormat.Builder()
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min * 2, rate * 4 / 10))   // ~100 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val buf = ByteArray(16 * 1024)
        var have = 0          // bytes in buf not yet written (only whole 4-byte frames are written)
        var playing = false
        try {
            while (running) {
                val n = try {
                    input.read(buf, have, buf.size - have)
                } catch (_: SocketTimeoutException) {
                    if (playing) {
                        track.pause()
                        track.flush()
                        playing = false
                    }
                    continue
                }
                if (n < 0) break
                have += n
                val whole = have - have % 4
                if (whole == 0) continue
                if (!playing) {
                    track.play()
                    playing = true
                }
                track.write(buf, 0, whole)
                System.arraycopy(buf, whole, buf, 0, have - whole)
                have -= whole
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}
