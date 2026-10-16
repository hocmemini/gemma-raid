package com.hocmemini.raid.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import com.hocmemini.raid.R
import java.util.Locale

/** Chiptune effects (synthesized, see scripts/gen-sfx.py) plus a text-to-speech monster voice for taunts. */
class Sfx(context: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(6)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = mapOf(
        "spawn" to pool.load(context, R.raw.sfx_spawn, 1),
        "slash" to pool.load(context, R.raw.sfx_slash, 1),
        "hit" to pool.load(context, R.raw.sfx_hit, 1),
        "attack" to pool.load(context, R.raw.sfx_attack, 1),
        "parry" to pool.load(context, R.raw.sfx_parry, 1),
        "potion" to pool.load(context, R.raw.sfx_potion, 1),
        "legendary" to pool.load(context, R.raw.sfx_legendary, 1),
        "kill" to pool.load(context, R.raw.sfx_kill, 1),
        "victory" to pool.load(context, R.raw.sfx_victory, 1),
        "levelup" to pool.load(context, R.raw.sfx_levelup, 1),
        "wind" to pool.load(context, R.raw.sfx_wind, 1),
        "omen" to pool.load(context, R.raw.sfx_omen, 1),
        "miss" to pool.load(context, R.raw.sfx_miss, 1),
    )
    private val main = Handler(Looper.getMainLooper())
    private var ttsReady = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context) { status: Int ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) runCatching { tts?.language = Locale.US }
            Log.i("Sfx", "tts ready=$ttsReady")
        }
    }
    @Volatile var muted = false

    fun play(name: String, delayMs: Long = 0, volume: Float = 1f, rate: Float = 1f) {
        if (muted) return
        val id = ids[name] ?: return
        if (delayMs > 0) main.postDelayed({ pool.play(id, volume, volume, 1, 0, rate) }, delayMs)
        else pool.play(id, volume, volume, 1, 0, rate)
    }

    /** The monster speaks: low pitch, a touch slow. */
    fun growl(text: String, delayMs: Long = 0) {
        if (muted || !ttsReady || text.isBlank()) return
        main.postDelayed({
            tts?.setPitch(0.55f); tts?.setSpeechRate(0.95f)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "taunt")
        }, delayMs)
    }

    /** The dungeon master narrates: normal pitch. */
    fun narrate(text: String, delayMs: Long = 0) {
        if (muted || !ttsReady || text.isBlank()) return
        main.postDelayed({
            tts?.setPitch(1.0f); tts?.setSpeechRate(1.05f)
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "narration")
        }, delayMs)
    }

    fun release() { pool.release(); runCatching { tts?.shutdown() } }
}
