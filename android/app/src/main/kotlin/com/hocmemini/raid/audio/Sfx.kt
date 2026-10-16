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

    /** Three distinct voices, best local quality first: dungeon master, monster, NPC. */
    private var voiceDm: android.speech.tts.Voice? = null
    private var voiceMonster: android.speech.tts.Voice? = null
    private var voiceNpc: android.speech.tts.Voice? = null

    init {
        tts = TextToSpeech(context) { status: Int ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) runCatching {
                tts?.language = Locale.US
                val all = tts?.voices.orEmpty().filter { it.locale.language == "en" && !it.isNetworkConnectionRequired && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                val ranked = all.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenByDescending { it.locale.country == "US" }.thenBy { it.name })
                Log.i("Sfx", "voices: " + ranked.joinToString { "${it.name}(q${it.quality}${if (it.isNetworkConnectionRequired) ",net" else ""})" })
                // Google's newer local voices carry "x-..." ids; prefer distinct ones so the three characters differ.
                val us = ranked.filter { it.locale.country == "US" }.ifEmpty { ranked }
                voiceDm = us.getOrNull(0)
                voiceMonster = us.firstOrNull { it.name.contains("iom", true) || it.name.contains("iog", true) || it.name.contains("tpd", true) } ?: us.getOrNull(1) ?: voiceDm
                voiceNpc = us.firstOrNull { it != voiceDm && it != voiceMonster && (it.name.contains("iob", true) || it.name.contains("tpc", true) || it.name.contains("sfg", true)) } ?: us.getOrNull(2) ?: voiceDm
                Log.i("Sfx", "picked dm=${voiceDm?.name} monster=${voiceMonster?.name} npc=${voiceNpc?.name}")
            }
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
            // monsters: a different voice body, modest pitch drop (extreme pitch shifts are what sounds robotic)
            val v = if (pitch < 1f) voiceMonster else voiceNpc
            runCatching { if (v != null) tts?.voice = v }
            tts?.setPitch(if (pitch < 1f) (0.72f + (pitch - 0.4f) * 0.4f).coerceIn(0.7f, 0.9f) else pitch.coerceIn(1.0f, 1.25f))
            tts?.setSpeechRate(rate)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice")
        }, delayMs)
    }

    fun growl(text: String, delayMs: Long = 0) = speak(text, 0.55f, 0.95f, delayMs)

    /** The dungeon master narrates: normal pitch, queued after whatever is playing. */
    fun narrate(text: String, delayMs: Long = 0) {
        if (muted || !ttsReady || text.isBlank()) return
        main.postDelayed({
            runCatching { voiceDm?.let { tts?.voice = it } }
            tts?.setPitch(1.0f); tts?.setSpeechRate(1.05f)
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "narration")
        }, delayMs)
    }

    fun release() { stopLoop(); pool.release(); runCatching { tts?.shutdown() } }
}
