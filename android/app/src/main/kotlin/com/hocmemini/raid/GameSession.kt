package com.hocmemini.raid

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.camera.core.ImageCapture
import com.hocmemini.raid.audio.Recorder
import com.hocmemini.raid.camera.captureToFile
import com.hocmemini.raid.game.GameState
import com.hocmemini.raid.game.Monster
import com.hocmemini.raid.game.Msg
import com.hocmemini.raid.game.Phase
import com.hocmemini.raid.game.Player
import com.hocmemini.raid.game.headingDelta
import com.hocmemini.raid.game.levelFor
import com.hocmemini.raid.game.maxHpFor
import com.hocmemini.raid.llm.Gemma
import com.hocmemini.raid.game.Omen
import com.hocmemini.raid.net.NearbyLink
import com.hocmemini.raid.net.SignalScanner
import com.hocmemini.raid.sensors.Compass
import com.hocmemini.raid.sensors.DoubleTwist
import com.hocmemini.raid.sensors.Environment
import com.hocmemini.raid.sensors.SwingDetector
import com.hocmemini.raid.sensors.wavLoudness
import com.hocmemini.raid.sensors.Locator
import com.hocmemini.raid.sensors.StepCounter
import com.hocmemini.raid.sensors.distanceMeters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * All game logic. Lives for the whole process (owned by [RaidApplication]), not a ViewModel,
 * so switching apps mid-battle doesn't unload the 2.6 GB model or drop the party.
 */
class GameSession(private val app: Application) {
    enum class Role { NONE, HOST, CLIENT }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("raid", Context.MODE_PRIVATE)

    val role = MutableStateFlow(Role.NONE)
    val status = MutableStateFlow("")
    val gemmaStatus = MutableStateFlow("model not loaded")
    val gemmaReady = MutableStateFlow(false)
    val gemmaLoading = MutableStateFlow(false)
    val state = MutableStateFlow(GameState())
    val peers = MutableStateFlow<List<String>>(emptyList())
    val recording = MutableStateFlow(false)
    val savedName: String get() = prefs.getString("name", "") ?: ""

    val selfId: String = UUID.randomUUID().toString().take(8)
    private var selfName = "Player"

    val compass = Compass(app)
    val locator = Locator(app)
    val steps = StepCounter(app)
    val env = Environment(app)
    val lastSwing = MutableStateFlow(0f)
    val lastEvent = MutableStateFlow("")
    private val swing = SwingDetector(app) { peak -> onSwing(peak) }
    private val twist = DoubleTwist(app) { onTwist() }
    private var link: NearbyLink? = null
    private var gemma: Gemma? = null
    private val recorder = Recorder()
    private val scanner = SignalScanner(app)
    private val vibrator = runCatching { app.getSystemService(VibratorManager::class.java)?.defaultVibrator }.getOrNull()
    /** Host: audio headers waiting for their STREAM payload, by endpoint. */
    private val pendingAudio = mutableMapOf<String, Msg.AudioHeader>()
    private var sensorsRunning = false

    val modelFile: File get() = File(app.filesDir, MODEL_NAME)
    val modelPresent = MutableStateFlow(modelFile.exists())

    fun startSensors() { if (!sensorsRunning) { compass.start(); locator.start(); steps.start(); env.start(); swing.start(); twist.start(); sensorsRunning = true } }
    fun stopSensors() { if (sensorsRunning) { compass.stop(); locator.stop(); steps.stop(); env.stop(); swing.stop(); twist.stop(); sensorsRunning = false } }

    // ---------- model ----------

    fun loadModel(useGpu: Boolean) {
        if (gemmaReady.value || gemmaLoading.value || !modelFile.exists()) return
        gemmaLoading.value = true
        scope.launch {
            runCatching {
                val g = Gemma(modelFile.absolutePath, useGpu)
                g.load { gemmaStatus.value = it }
                gemma = g
                gemmaReady.value = true
            }.onFailure { gemmaStatus.value = "load failed: ${it.message}" }
            gemmaLoading.value = false
        }
    }

    /** Copy a user-picked .litertlm (e.g. downloaded in Chrome) into app storage. */
    fun importModel(uri: Uri) = scope.launch(Dispatchers.IO) {
        runCatching {
            val tmp = File(modelFile.path + ".part")
            app.contentResolver.openInputStream(uri)!!.use { input ->
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
        prefs.edit().putString("name", selfName).apply()
        role.value = Role.HOST
        link = NearbyLink(app, selfName, ::onMessage, ::onBinary, { peers.value = it }, { status.value = it }).also { it.host() }
        steps.reset(); env.markBaseline()
        state.update { it.copy(phase = Phase.EXPLORE, players = it.players + (selfId to selfPlayer())).withLog("$selfName opened the raid. Walk around, then point at something.") }
        broadcast()
    }

    /** A vigorous swing = a melee attack without typing. Harder swings hit harder. */
    private fun onSwing(peak: Float) {
        val s = state.value
        if (s.phase != Phase.BATTLE || s.busy || (s.players[selfId]?.hp ?: 0) <= 0) return
        lastSwing.value = peak
        buzz(30)
        val text = when {
            peak > 30f -> "I swing with everything I have, a wild all-out strike!"
            peak > 22f -> "I swing hard at it!"
            else -> "I take a swing at it."
        }
        act(text)
    }

    /** Double twist = parry: the monster's next hit on this player is blocked. */
    private fun onTwist() {
        val s = state.value
        if (s.phase != Phase.BATTLE) return
        buzz(20, 40, 20)
        if (role.value == Role.HOST) armParry(selfId) else link?.send(Msg.Parry(selfId))
    }

    private fun armParry(playerId: String) {
        state.update { s ->
            if (playerId in s.parries) s
            else s.copy(parries = s.parries + playerId).withLog("🛡 ${s.players[playerId]?.name ?: "?"} twists into a guard stance — next hit is parried")
        }
        broadcast()
    }

    fun join(name: String) {
        selfName = name.ifBlank { "Player" }
        prefs.edit().putString("name", selfName).apply()
        role.value = Role.CLIENT
        link = NearbyLink(
            app, selfName, ::onMessage, ::onBinary,
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
                scope.launch { resolve(msg.playerId, msg.text, null, msg.headingDeg, msg.lat, msg.lon) }
            }
            is Msg.AudioHeader -> if (role.value == Role.HOST) pendingAudio[endpointId] = msg
            is Msg.Parry -> if (role.value == Role.HOST) armParry(msg.playerId)
            is Msg.State -> if (role.value == Role.CLIENT) {
                val before = state.value.players[selfId]?.hp
                state.value = msg.state
                val after = msg.state.players[selfId]?.hp
                if (before != null && after != null && after < before) buzz(120)
                if (msg.state.phase == Phase.VICTORY && state.value.phase != Phase.VICTORY) buzz(40, 60, 40, 60, 200)
            }
        }
    }

    /** Host: a STREAM payload arrived — it is the WAV clip announced by the last AudioHeader from that endpoint. */
    private fun onBinary(endpointId: String, bytes: ByteArray) {
        if (role.value != Role.HOST) return
        val h = pendingAudio.remove(endpointId) ?: run { status.value = "audio without header"; return }
        scope.launch { resolve(h.playerId, null, bytes, h.headingDeg, h.lat, h.lon) }
    }

    private fun broadcast() { link?.send(Msg.State(state.value)) }

    // ---------- game loop (host only) ----------

    fun spawnFromCamera(imageCapture: ImageCapture) = scope.launch {
        val g = gemma ?: run { status.value = "load Gemma first"; return@launch }
        if (state.value.busy) return@launch
        val walked = steps.sinceReset.value
        state.update { it.copy(busy = true, steps = walked).withLog("The dungeon master studies the scene…") }; broadcast()
        val path = runCatching { imageCapture.captureToFile(app) }
            .getOrElse { state.update { s -> s.copy(busy = false).withLog("camera failed: ${it.message}") }; broadcast(); return@launch }
        val bearing = compass.heading.value
        val loc = locator.location.value
        val conditions = env.describe()
        val lair = state.value.bossLair
        val (spawned, text) = runCatching { g.spawn(path, walked, conditions, lair?.let { "${it.name} (${it.lore})" }) }
            .getOrElse { state.update { s -> s.copy(busy = false).withLog("Gemma error: ${it.message}") }; broadcast(); return@launch }
        if (spawned == null) {
            state.update { it.copy(busy = false).withLog("The DM hesitates: ${text.take(200)}") }
        } else {
            // Exploring pays: every 4 steps walked since the last fight adds 1 HP (cap +50).
            val bonus = min(walked / 4, 50)
            val hp = (spawned.hp + bonus) * (if (lair != null) 3 else 2) / 2
            val m = Monster(if (lair != null) "👑 ${spawned.name}" else spawned.name, hp, hp, spawned.attacks, spawned.weakness, spawned.flavor, bearing, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0, spawned.emoji)
            steps.reset()
            state.update {
                var s = it.copy(phase = Phase.BATTLE, monster = m, busy = false, turn = 0, steps = 0, env = env.summary.value, parries = emptyList(), bossLair = null)
                    .withLog("${m.emoji} ${m.name} appears! ${m.flavor}")
                if (lair != null) s = s.withLog("👑 BOSS FIGHT — champion of ${lair.name}. Double XP.")
                if (conditions.isNotBlank()) s = s.withLog("(${conditions.replaceFirstChar { c -> c.uppercase() }}.)")
                if (bonus > 0) s = s.withLog("It grew +$bonus HP from the $walked steps you walked.")
                if (text.isNotBlank()) s = s.withLog(text.take(200))
                s
            }
            buzz(60)
        }
        broadcast()
    }

    /** Host: scan Wi-Fi + BLE, let Gemma turn the names into lore; a menacing strong signal becomes a boss lair. */
    fun scanOmens() = scope.launch {
        val g = gemma ?: return@launch
        if (role.value != Role.HOST || state.value.busy) return@launch
        state.update { it.copy(busy = true).withLog("📡 You listen to the airwaves…") }; broadcast()
        val signals = runCatching { scanner.scan() }.getOrElse { emptyList() }.take(6)
        if (signals.isEmpty()) {
            state.update { it.copy(busy = false).withLog("📡 Silence. No spirits haunt this place — wild country.") }; broadcast(); return@launch
        }
        val (texts, line) = runCatching { g.omens(signals.map { it.name }) }.getOrElse { null to "" }
        if (texts == null) {
            state.update { it.copy(busy = false).withLog("📡 ${signals.size} signals, but the DM can't read them: ${line.take(120)}") }; broadcast(); return@launch
        }
        val omens = signals.zip(texts).map { (sig, t) -> Omen(sig.name, t.name, t.lore, t.threat, sig.dbm) }
        val lair = omens.filter { it.threat >= 4 }.maxByOrNull { it.dbm }
        state.update { s ->
            var next = s.copy(busy = false, omens = omens, bossLair = lair)
            omens.forEach { o -> next = next.withLog("${"☠".repeat(o.threat)} ${o.name} (\"${o.signal}\", ${o.dbm} dBm): ${o.lore}") }
            if (lair != null) next = next.withLog("👑 This is the lair of ${lair.name}. The next monster will be a BOSS.")
            if (line.isNotBlank()) next = next.withLog(line.take(160))
            next
        }
        buzz(40, 40, 40)
        broadcast()
    }

    fun act(text: String) {
        val loc = locator.location.value
        val h = compass.heading.value
        if (role.value == Role.HOST) scope.launch { resolve(selfId, text, null, h, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0) }
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
        if (role.value == Role.HOST) scope.launch { resolve(selfId, null, wav, h, lat, lon) }
        else { link?.send(Msg.AudioHeader(selfId, h, lat, lon, wav.size)); link?.sendStream(wav) }
    }

    private suspend fun resolve(playerId: String, text: String?, audioWav: ByteArray?, headingDeg: Float, lat: Double, lon: Double) {
        val g = gemma ?: return
        val s0 = state.value
        val m = s0.monster ?: return
        val p0 = s0.players[playerId] ?: return
        if (s0.phase != Phase.BATTLE || s0.busy || p0.hp <= 0) return
        val p = p0.copy(headingDeg = headingDeg, lat = lat, lon = lon)

        val facing = abs(headingDelta(headingDeg, bearingToMonster(m, p))) <= FACING_TOLERANCE_DEG
        val flanking = s0.players.values.any { o -> o.id != p.id && o.hp > 0 && abs(headingDelta(o.headingDeg, headingDeg)) > FLANK_MIN_DEG }

        val shown = text ?: "🎤 (${(audioWav!!.size - 44) / 32000}s of shouting)"
        state.update { it.copy(busy = true, players = it.players + (p.id to p)).withLog("${p.name}: $shown") }; broadcast()
        val (r, reply) = runCatching { g.resolve(p.name, text, audioWav, m.name, m.hp, m.weakness, facing, flanking, p.items) }
            .getOrElse { state.update { s -> s.copy(busy = false).withLog("Gemma error: ${it.message}") }; broadcast(); return }
        if (r == null) {
            state.update { it.copy(busy = false).withLog("The DM mumbles: ${reply.take(200)}") }; broadcast(); return
        }
        var dmg = r.damageToMonster
        if (!facing) dmg /= 2
        if (flanking) dmg = (dmg * 1.5f).roundToInt()
        val cry = if (audioWav != null) (wavLoudness(audioWav) * 5f).roundToInt() else 0
        if (dmg > 0) dmg += cry
        val parried = playerId in s0.parries
        val hurt = if (parried) 0 else r.damageToPlayer
        val newMonsterHp = (m.hp - dmg).coerceAtLeast(0)
        val newPlayerHp = (p.hp - hurt).coerceAtLeast(0)
        val killed = newMonsterHp == 0
        val boss = m.name.startsWith("👑")
        val xpGain = ((if (dmg > 0) 10 else 0) + (if (killed) 50 else 0)) * (if (boss) 2 else 1)
        val tag = buildString {
            append("−$dmg ${m.name}")
            if (flanking) append(" (flank!)")
            if (!facing) append(" (not facing it)")
            if (cry > 0) append(" (battle cry +$cry)")
            if (parried && r.damageToPlayer > 0) append(", 🛡 parried ${r.damageToPlayer}")
            else if (hurt > 0) append(", −$hurt ${p.name}")
            if (xpGain > 0) append(", +$xpGain xp")
        }
        if (playerId == selfId && hurt > 0) buzz(120)
        state.update { s ->
            var me = p.copy(hp = newPlayerHp, xp = p.xp + xpGain)
            val newLevel = levelFor(me.xp)
            var next = s.copy(busy = false, turn = s.turn + 1, monster = m.copy(hp = newMonsterHp), parries = s.parries - playerId).withLog(r.narration).withLog(tag)
            if (newLevel > me.level) {
                me = me.copy(level = newLevel, maxHp = maxHpFor(newLevel), hp = maxHpFor(newLevel))
                next = next.withLog("⬆ ${me.name} reached level $newLevel! Max HP ${me.maxHp}, fully healed.")
            }
            val players = next.players + (me.id to me)
            next = next.copy(players = players)
            if (killed) next = next.copy(phase = Phase.VICTORY, kills = next.kills + 1).withLog("${m.emoji} ${m.name} is defeated!")
            else if (players.values.all { it.hp <= 0 }) next = next.copy(phase = Phase.DEFEAT).withLog("The party falls. ${m.name} gloats.")
            next
        }
        broadcast()
        if (killed) {
            if (playerId == selfId) buzz(40, 60, 40, 60, 200)
            val (loot, line) = runCatching { g.loot(m.name, p.name) }.getOrElse { null to "" }
            state.update { s ->
                val me = s.players[p.id] ?: return@update s
                var next = s
                if (loot != null) {
                    next = next.copy(players = next.players + (me.id to me.copy(items = (me.items + loot.name).takeLast(6))))
                        .withLog("🎁 ${me.name} loots ${loot.name}: ${loot.effect}")
                }
                if (line.isNotBlank()) next = next.withLog(line.take(200))
                next
            }
            broadcast()
        }
    }

    fun nextEncounter() {
        if (role.value != Role.HOST) return
        steps.reset()
        state.update { s -> s.copy(phase = Phase.EXPLORE, monster = null, steps = 0, players = s.players.mapValues { (_, p) -> p.copy(hp = maxOf(p.hp, p.maxHp / 2)) }).withLog("The party regroups. Walk around, then point at something new.") }
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

    private fun buzz(vararg pattern: Long) {
        val v = vibrator ?: return
        runCatching {
            if (pattern.size == 1) v.vibrate(VibrationEffect.createOneShot(pattern[0], VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, *pattern), -1))
        }
    }

    companion object {
        const val MODEL_NAME = "gemma-4-E2B-it.litertlm"
        const val FACING_TOLERANCE_DEG = 40f
        const val FLANK_MIN_DEG = 60f
        const val GPS_MIN_METERS = 15f
    }
}
