package com.hocmemini.raid

import android.app.Application
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hocmemini.raid.audio.Recorder
import com.hocmemini.raid.camera.captureToFile
import com.hocmemini.raid.game.GameState
import com.hocmemini.raid.game.Monster
import com.hocmemini.raid.game.Msg
import com.hocmemini.raid.game.Phase
import com.hocmemini.raid.game.Player
import com.hocmemini.raid.game.headingDelta
import com.hocmemini.raid.llm.Gemma
import com.hocmemini.raid.net.NearbyLink
import com.hocmemini.raid.sensors.Compass
import com.hocmemini.raid.sensors.Locator
import com.hocmemini.raid.sensors.distanceMeters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class GameViewModel(app: Application) : AndroidViewModel(app) {
    enum class Role { NONE, HOST, CLIENT }

    val role = MutableStateFlow(Role.NONE)
    val status = MutableStateFlow("")
    val gemmaStatus = MutableStateFlow("model not loaded")
    val gemmaReady = MutableStateFlow(false)
    val state = MutableStateFlow(GameState())
    val peers = MutableStateFlow<List<String>>(emptyList())

    val selfId: String = UUID.randomUUID().toString().take(8)
    private var selfName = "Player"

    val compass = Compass(app)
    val locator = Locator(app)
    private var link: NearbyLink? = null
    private var gemma: Gemma? = null
    private val recorder = Recorder()
    val recording = MutableStateFlow(false)
    /** Host: audio headers waiting for their STREAM payload, by endpoint. */
    private val pendingAudio = mutableMapOf<String, Msg.AudioHeader>()

    val modelFile: File get() = File(getApplication<Application>().filesDir, MODEL_NAME)
    val modelPresent = MutableStateFlow(modelFile.exists())

    fun startSensors() { compass.start(); locator.start() }
    fun stopSensors() { compass.stop(); locator.stop() }

    // ---------- model ----------

    fun loadModel(useGpu: Boolean) = viewModelScope.launch {
        runCatching {
            val g = Gemma(modelFile.absolutePath, useGpu)
            g.load { gemmaStatus.value = it }
            gemma = g
            gemmaReady.value = true
        }.onFailure { gemmaStatus.value = "load failed: ${it.message}" }
    }

    /** Copy a user-picked .litertlm (e.g. downloaded in Chrome) into app storage. */
    fun importModel(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val resolver = getApplication<Application>().contentResolver
            val tmp = File(modelFile.path + ".part")
            resolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); total += n
                        if (total % (64L shl 20) < (1 shl 20)) gemmaStatus.value = "copying model… ${total shr 20} MB"
                    }
                }
            }
            tmp.renameTo(modelFile)
            modelPresent.value = true
            gemmaStatus.value = "model copied (${modelFile.length() shr 20} MB)"
        }.onFailure { gemmaStatus.value = "copy failed: ${it.message}" }
    }

    // ---------- networking ----------

    fun host(name: String) {
        selfName = name.ifBlank { "Host" }
        role.value = Role.HOST
        link = NearbyLink(getApplication(), selfName, ::onMessage, ::onBinary, { peers.value = it }, { status.value = it }).also { it.host() }
        state.update { it.copy(phase = Phase.EXPLORE, players = it.players + (selfId to selfPlayer())).withLog("$selfName opened the raid") }
        broadcast()
    }

    fun join(name: String) {
        selfName = name.ifBlank { "Player" }
        role.value = Role.CLIENT
        link = NearbyLink(
            getApplication(), selfName, ::onMessage, ::onBinary,
            onPeersChanged = { ps -> peers.value = ps; if (ps.isNotEmpty()) link?.send(Msg.Join(selfPlayer())) },
            onStatus = { status.value = it },
        ).also { it.join() }
    }

    private fun selfPlayer(): Player {
        val loc = locator.location.value
        return Player(selfId, selfName, headingDeg = compass.heading.value, lat = loc?.latitude ?: 0.0, lon = loc?.longitude ?: 0.0)
    }

    fun sendPose() {
        if (role.value != Role.CLIENT) return
        val loc = locator.location.value
        link?.send(Msg.Pose(selfId, compass.heading.value, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0))
    }

    private fun onMessage(endpointId: String, msg: Msg) {
        when (msg) {
            is Msg.Join -> if (role.value == Role.HOST) {
                state.update { it.copy(players = it.players + (msg.player.id to msg.player)).withLog("${msg.player.name} joined the raid") }
                broadcast()
            }
            is Msg.Pose -> if (role.value == Role.HOST) {
                state.update { s ->
                    val p = s.players[msg.playerId] ?: return@update s
                    s.copy(players = s.players + (p.id to p.copy(headingDeg = msg.headingDeg, lat = msg.lat, lon = msg.lon)))
                }
            }
            is Msg.Action -> if (role.value == Role.HOST) {
                viewModelScope.launch { resolve(msg.playerId, msg.text, null, msg.headingDeg, msg.lat, msg.lon) }
            }
            is Msg.AudioHeader -> if (role.value == Role.HOST) pendingAudio[endpointId] = msg
            is Msg.State -> if (role.value == Role.CLIENT) state.value = msg.state
        }
    }

    /** Host: a STREAM payload arrived — it is the WAV clip announced by the last AudioHeader from that endpoint. */
    private fun onBinary(endpointId: String, bytes: ByteArray) {
        if (role.value != Role.HOST) return
        val h = pendingAudio.remove(endpointId) ?: run { status.value = "audio without header"; return }
        viewModelScope.launch { resolve(h.playerId, null, bytes, h.headingDeg, h.lat, h.lon) }
    }

    private fun broadcast() { link?.send(Msg.State(state.value)) }

    // ---------- game loop (host only) ----------

    fun spawnFromCamera(imageCapture: ImageCapture) = viewModelScope.launch {
        val g = gemma ?: run { status.value = "load Gemma first"; return@launch }
        if (state.value.busy) return@launch
        state.update { it.copy(busy = true).withLog("The dungeon master studies the scene…") }; broadcast()
        val path = runCatching { imageCapture.captureToFile(getApplication()) }
            .getOrElse { state.update { s -> s.copy(busy = false).withLog("camera failed: ${it.message}") }; broadcast(); return@launch }
        val bearing = compass.heading.value
        val loc = locator.location.value
        val (spawned, text) = runCatching { g.spawn(path) }
            .getOrElse { state.update { s -> s.copy(busy = false).withLog("Gemma error: ${it.message}") }; broadcast(); return@launch }
        if (spawned == null) {
            state.update { it.copy(busy = false).withLog("The DM hesitates: ${text.take(200)}") }
        } else {
            val m = Monster(spawned.name, spawned.hp, spawned.hp, spawned.attacks, spawned.weakness, spawned.flavor, bearing, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0)
            state.update { it.copy(phase = Phase.BATTLE, monster = m, busy = false, turn = 0).withLog("${m.name} appears! ${m.flavor}").let { s -> if (text.isNotBlank()) s.withLog(text.take(200)) else s } }
        }
        broadcast()
    }

    fun act(text: String) {
        val loc = locator.location.value
        val h = compass.heading.value
        if (role.value == Role.HOST) viewModelScope.launch { resolve(selfId, text, null, h, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0) }
        else link?.send(Msg.Action(selfId, text, h, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0))
    }

    fun voiceStart() {
        if (state.value.phase != Phase.BATTLE || state.value.busy) return
        recorder.start(); recording.value = true
    }

    fun voiceStop() {
        if (!recording.value) return
        recording.value = false
        val wav = recorder.stop() ?: run { status.value = "too short — hold the button while you speak"; return }
        val loc = locator.location.value
        val h = compass.heading.value
        val lat = loc?.latitude ?: 0.0; val lon = loc?.longitude ?: 0.0
        if (role.value == Role.HOST) viewModelScope.launch { resolve(selfId, null, wav, h, lat, lon) }
        else { link?.send(Msg.AudioHeader(selfId, h, lat, lon, wav.size)); link?.sendStream(wav) }
    }

    private suspend fun resolve(playerId: String, text: String?, audioWav: ByteArray?, headingDeg: Float, lat: Double, lon: Double) {
        val g = gemma ?: return
        val s0 = state.value
        val m = s0.monster ?: return
        val p0 = s0.players[playerId] ?: return
        if (s0.phase != Phase.BATTLE || s0.busy) return
        val p = p0.copy(headingDeg = headingDeg, lat = lat, lon = lon)

        val facing = abs(headingDelta(headingDeg, bearingToMonster(m, p))) <= FACING_TOLERANCE_DEG
        val flanking = s0.players.values.any { o -> o.id != p.id && o.hp > 0 && abs(headingDelta(o.headingDeg, headingDeg)) > FLANK_MIN_DEG }

        val shown = text ?: "🎤 (${(audioWav!!.size - 44) / 32000}s of shouting)"
        state.update { it.copy(busy = true, players = it.players + (p.id to p)).withLog("${p.name}: $shown") }; broadcast()
        val (r, reply) = runCatching { g.resolve(p.name, text, audioWav, m.name, m.hp, m.weakness, facing, flanking) }
            .getOrElse { state.update { s -> s.copy(busy = false).withLog("Gemma error: ${it.message}") }; broadcast(); return }
        if (r == null) {
            state.update { it.copy(busy = false).withLog("The DM mumbles: ${reply.take(200)}") }; broadcast(); return
        }
        var dmg = r.damageToMonster
        if (!facing) dmg /= 2
        if (flanking) dmg = (dmg * 1.5f).roundToInt()
        val newMonsterHp = (m.hp - dmg).coerceAtLeast(0)
        val newPlayerHp = (p.hp - r.damageToPlayer).coerceAtLeast(0)
        val tag = buildString {
            append("−$dmg ${m.name}")
            if (flanking) append(" (flank!)")
            if (!facing) append(" (not facing it)")
            if (r.damageToPlayer > 0) append(", −${r.damageToPlayer} ${p.name}")
        }
        state.update { s ->
            val players = s.players + (p.id to p.copy(hp = newPlayerHp))
            var next = s.copy(busy = false, turn = s.turn + 1, monster = m.copy(hp = newMonsterHp), players = players)
                .withLog(r.narration).withLog(tag)
            if (newMonsterHp == 0) next = next.copy(phase = Phase.VICTORY).withLog("${m.name} is defeated! Loot it, then find the next one.")
            else if (players.values.all { it.hp <= 0 }) next = next.copy(phase = Phase.DEFEAT).withLog("The party falls. ${m.name} gloats.")
            next
        }
        broadcast()
    }

    fun nextEncounter() {
        if (role.value != Role.HOST) return
        state.update { s -> s.copy(phase = Phase.EXPLORE, monster = null, players = s.players.mapValues { (_, p) -> p.copy(hp = p.maxHp) }).withLog("The party regroups. Point at something new.") }
        broadcast()
    }

    /** Bearing a player should face to see the monster. Uses GPS geometry only when far enough apart for GPS to mean anything. */
    fun bearingToMonster(m: Monster, p: Player?): Float {
        if (p == null || p.lat == 0.0 || m.lat == 0.0) return m.bearingDeg
        if (distanceMeters(p.lat, p.lon, m.lat, m.lon) < GPS_MIN_METERS) return m.bearingDeg
        val dLon = Math.toRadians(m.lon - p.lon)
        val lat1 = Math.toRadians(p.lat); val lat2 = Math.toRadians(m.lat)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    override fun onCleared() {
        if (recording.value) recorder.stop()
        link?.stop(); gemma?.close(); stopSensors()
    }

    companion object {
        const val MODEL_NAME = "gemma-4-E2B-it.litertlm"
        const val FACING_TOLERANCE_DEG = 40f
        const val FLANK_MIN_DEG = 60f
        const val GPS_MIN_METERS = 15f
    }
}
