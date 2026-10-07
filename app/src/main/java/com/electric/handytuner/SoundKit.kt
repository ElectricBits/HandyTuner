// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Onboarding music and sounds, synthesized here rather than shipped: chiptune
 * waves (pulse, triangle) rendered to small WAVs in the cache on first use. No
 * asset licences, a few KB of code. Media stream, modest level, one mute switch.
 */
class SoundKit(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
    var muted: Boolean
        get() = prefs.getBoolean("muted", false)
        set(v) { prefs.edit().putBoolean("muted", v).apply(); if (v) music?.pause() else music?.start() }

    private val pool = SoundPool.Builder().setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val ids = mutableMapOf<String, Int>()
    private var music: MediaPlayer? = null

    fun load() {
        SFX.forEach { (name, notes) -> ids[name] = pool.load(wav(name, render(notes)).path, 1) }
    }

    fun play(name: String, volume: Float = 0.5f) {
        if (muted) return
        ids[name]?.let { pool.play(it, volume, volume, 1, 0, 1f) }
    }

    /** Talking blip, pitch wobbling a little per letter, like a game character. */
    fun blip() { if (!muted) ids["blip"]?.let { pool.play(it, 0.25f, 0.25f, 0, 0, 0.9f + Math.random().toFloat() * 0.25f) } }

    fun startMusic() {
        if (music != null) { if (!muted) music?.start(); return }
        music = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setDataSource(wav("music", renderMusic()).path)
            isLooping = true; setVolume(0.22f, 0.22f)
            prepare()
            if (!muted) start()
        }
    }
    fun pauseMusic() = music?.takeIf { it.isPlaying }?.pause()
    fun release() { music?.release(); music = null; pool.release() }

    // --- synthesis ------------------------------------------------------------

    private class Note(val hz: Double, val ms: Int, val wave: Char = 'p', val amp: Double = 0.4)

    /** Pulse (25% duty, bright), triangle (soft), with a short attack and decay so nothing clicks. */
    private fun sample(wave: Char, hz: Double, i: Int): Double {
        if (hz <= 0) return 0.0
        val phase = (i * hz / RATE) % 1.0
        return when (wave) {
            'p' -> if (phase < 0.25) 1.0 else -1.0
            't' -> 4 * kotlin.math.abs(phase - 0.5) - 1
            else -> sin(2 * PI * phase)
        }
    }

    private fun render(notes: List<Note>): ShortArray {
        val out = ArrayList<Short>()
        notes.forEach { n ->
            val len = RATE * n.ms / 1000
            for (i in 0 until len) {
                val env = minOf(1.0, i / (RATE * 0.004), (len - i) / (RATE * 0.02))
                out += (sample(n.wave, n.hz, i) * n.amp * env * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out.toShortArray()
    }

    /**
     * A calm music-box loop at 80 bpm: C - Am - F - G, twice. Bell-like sine notes with a soft
     * decay pick out the chord in quarter notes, over a quiet sine bass on the bar. The first
     * version used buzzy pulse waves at 104 bpm, and the owner found it annoying.
     */
    private fun renderMusic(): ShortArray {
        val quarter = 60.0 / 80
        val chords = listOf(
            listOf(523.25, 659.25, 783.99), listOf(440.0, 523.25, 659.25),
            listOf(349.23, 440.0, 523.25), listOf(392.0, 493.88, 587.33),
        )
        val bars = chords + chords
        val total = (bars.size * 4 * quarter * RATE).toInt()
        val mix = DoubleArray(total)
        fun bell(start: Int, hz: Double, amp: Double, len: Int) {
            for (i in 0 until len) {
                if (start + i >= total) return
                val tt = i.toDouble() / RATE
                val env = minOf(1.0, i / (RATE * 0.004)) * kotlin.math.exp(-tt * 3.2)
                // A sine plus a quiet octave: a music-box "ting" rather than a buzz.
                mix[start + i] += (sin(2 * PI * hz * tt) + 0.25 * sin(4 * PI * hz * tt)) * amp * env
            }
        }
        bars.forEachIndexed { b, chord ->
            val barStart = (b * 4 * quarter * RATE).toInt()
            bell(barStart, chord[0] / 4, 0.16, (4 * quarter * RATE).toInt())            // bass, once a bar
            val pattern = if (b % 2 == 0) intArrayOf(0, 1, 2, 1) else intArrayOf(2, 1, 0, 1)
            for (q in 0 until 4) bell(barStart + (q * quarter * RATE).toInt(), chord[pattern[q]], 0.07, (1.6 * RATE).toInt())
        }
        return ShortArray(total) { (mix[it].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort() }
    }

    private fun wav(name: String, pcm: ShortArray): File {
        val f = File(ctx.cacheDir, "sfx_$name.wav")
        if (f.exists() && f.length() == 44L + pcm.size * 2) return f
        val b = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(pcm.size * 2)
        pcm.forEach { b.putShort(it) }
        f.writeBytes(b.array())
        return f
    }

    private companion object {
        const val RATE = 22050
        val SFX = mapOf(
            "blip" to listOf(Note(587.0, 26, 't', 0.3)),                    // soft triangle, not a buzz
            "select" to listOf(Note(659.0, 50), Note(988.0, 70)),
            "back" to listOf(Note(659.0, 50), Note(440.0, 70)),
            "success" to listOf(Note(523.0, 80, 't', 0.5), Note(659.0, 80, 't', 0.5), Note(784.0, 80, 't', 0.5), Note(1047.0, 220, 't', 0.5)),
            "hotkey" to listOf(Note(1047.0, 50), Note(1319.0, 50), Note(1568.0, 50), Note(2093.0, 120)),
            "nudge" to listOf(Note(392.0, 90, 't', 0.45), Note(349.0, 140, 't', 0.45)),
        )
    }
}
