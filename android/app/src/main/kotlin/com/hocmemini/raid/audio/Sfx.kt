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

/** Chiptune effects (synthesized, see scripts/gen-sfx.py) plus text-to-speech voices for monsters, NPCs and the DM. */
class Sfx(context: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(8)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids: Map<String, Int> = mapOf(
        "spawn" to R.raw.sfx_spawn, "slash" to R.raw.sfx_slash, "hit" to R.raw.sfx_hit, "attack" to R.raw.sfx_attack,
        "parry" to R.raw.sfx_parry, "potion" to R.raw.sfx_potion, "legendary" to R.raw.sfx_legendary, "kill" to R.raw.sfx_kill,
        "victory" to R.raw.sfx_victory, "levelup" to R.raw.sfx_levelup, "wind" to R.raw.sfx_wind, "omen" to R.raw.sfx_omen,
        "miss" to R.raw.sfx_miss, "shutter" to R.raw.sfx_shutter, "blip" to R.raw.sfx_blip, "npc" to R.raw.sfx_npc,
        "coins" to R.raw.sfx_coins, "hurt" to R.raw.sfx_hurt, "whoosh" to R.raw.sfx_whoosh, "steps" to R.raw.sfx_steps,
        "ping" to R.raw.sfx_ping, "join" to R.raw.sfx_join, "windup" to R.raw.sfx_windup,
        "ambient_battle" to R.raw.sfx_ambient_battle, "ambient_explore" to R.raw.sfx_ambient_explore,
    ).mapValues { (_, res) -> pool.load(context, res, 1) }
    private val main = Handler(Looper.getMainLooper())
    private var ttsReady = false
    private var tts: TextToSpeech? = null
    private var loopStream = 0
    private var loopName = ""
    @Volatile var muted = false

    init {
        tts = TextToSpeech(context) { status: Int ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) runCatching { tts?.language = Locale.US }
            Log.i("Sfx", "tts ready=$ttsReady")
        }
    }

    fun play(name: String, delayMs: Long = 0, volume: Float = 1f, rate: Float = 1f) {
        if (muted) return
        val id = ids[name] ?: return
        if (delayMs > 0) main.postDelayed({ pool.play(id, volume, volume, 1, 0, rate) }, delayMs)
        else pool.play(id, volume, volume, 1, 0, rate)
    }

    /** Ambient bed; switching to the same loop is a no-op. */
    fun loop(name: String) {
        if (name == loopName) return
        stopLoop()
        val id = ids[name] ?: return
        loopName = name
        if (muted) return
        loopStream = pool.play(id, 0.5f, 0.5f, 0, -1, 1f)
        if (loopStream == 0) main.postDelayed({ if (loopName == name) loopStream = pool.play(id, 0.5f, 0.5f, 0, -1, 1f) }, 800)
    }

    fun stopLoop() { if (loopStream != 0) pool.stop(loopStream); loopStream = 0; loopName = "" }

    /** Any character speaks: pitch < 1 is a monster, > 1 a friendly NPC. */
    fun speak(text: String, pitch: Float, rate: Float = 1f, delayMs: Long = 0) {
        if (muted || !ttsReady || text.isBlank()) return
        main.postDelayed({
            tts?.setPitch(pitch); tts?.setSpeechRate(rate)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice")
        }, delayMs)
    }

    fun growl(text: String, delayMs: Long = 0) = speak(text, 0.55f, 0.95f, delayMs)

    /** The dungeon master narrates: normal pitch, queued after whatever is playing. */
    fun narrate(text: String, delayMs: Long = 0) {
        if (muted || !ttsReady || text.isBlank()) return
        main.postDelayed({
            tts?.setPitch(1.0f); tts?.setSpeechRate(1.05f)
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "narration")
        }, delayMs)
    }

    fun release() { stopLoop(); pool.release(); runCatching { tts?.shutdown() } }
}
