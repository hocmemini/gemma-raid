package com.hocmemini.raid

import android.app.Application
import android.util.Log
import android.content.Context
import android.net.Uri
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.camera.core.ImageCapture
import com.hocmemini.raid.audio.Recorder
import com.hocmemini.raid.audio.Sfx
import com.hocmemini.raid.camera.SpriteMaker
import com.hocmemini.raid.camera.captureToFile
import com.hocmemini.raid.game.Ally
import com.hocmemini.raid.game.Encounter
import com.hocmemini.raid.game.Fx
import com.hocmemini.raid.game.GameState
import com.hocmemini.raid.game.Item
import com.hocmemini.raid.game.BAG_SLOTS
import com.hocmemini.raid.game.Monster
import com.hocmemini.raid.game.Msg
import com.hocmemini.raid.game.Omen
import com.hocmemini.raid.game.Phase
import com.hocmemini.raid.game.Player
import com.hocmemini.raid.game.Victory
import com.hocmemini.raid.game.StatsStore
import com.hocmemini.raid.game.companionCapacity
import com.hocmemini.raid.game.headingDelta
import com.hocmemini.raid.game.levelFor
import com.hocmemini.raid.game.maxHpFor
import com.hocmemini.raid.game.powerUnlockedAt
import com.hocmemini.raid.game.powersFor
import com.hocmemini.raid.llm.Gemma
import com.hocmemini.raid.llm.clean
import com.hocmemini.raid.llm.ModelManager
import com.hocmemini.raid.net.NearbyLink
import com.hocmemini.raid.net.SignalScanner
import com.hocmemini.raid.sensors.Compass
import com.hocmemini.raid.sensors.DoubleTwist
import com.hocmemini.raid.sensors.Environment
import com.hocmemini.raid.sensors.Locator
import com.hocmemini.raid.sensors.StepCounter
import com.hocmemini.raid.sensors.SwingDetector
import com.hocmemini.raid.sensors.distanceMeters
import com.hocmemini.raid.sensors.wavLoudness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * All game logic. Lives for the whole process (owned by [RaidApplication]), not a ViewModel,
 * so switching apps mid-battle doesn't unload the 2.6 GB model or drop the party.
 * Only the host runs Gemma; clients send intents and receive state.
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
    val debugHud = MutableStateFlow(false)
    /** Full history of everything the DM, NPCs and players said; survives restarts. */
    val journal = MutableStateFlow<List<String>>(emptyList())
    private var seenLogTotal = 0
    val textScale = MutableStateFlow(1f)
    val narrateAloud = MutableStateFlow(false)
    val savedName: String get() = prefs.getString("name", "") ?: ""

    val selfId: String = UUID.randomUUID().toString().take(8)
    private var selfName = "Player"

    val compass = Compass(app)
    val locator = Locator(app)
    val steps = StepCounter(app)
    val env = Environment(app)
    val lastSwing = MutableStateFlow(0f)
    private val swing = SwingDetector(app) { peak -> onSwing(peak) }
    private val twist = DoubleTwist(app) { onTwist() }
    private var link: NearbyLink? = null
    private var gemma: Gemma? = null
    private val recorder = Recorder()
    val sfx = Sfx(app)
    private val scanner = SignalScanner(app)
    val statsStore = StatsStore(app)
    private val vibrator = runCatching { app.getSystemService(VibratorManager::class.java)?.defaultVibrator }.getOrNull()

    private val pendingAudio = mutableMapOf<String, Msg.AudioHeader>()
    private var pendingSprite: Msg.SpriteHeader? = null
    val spriteVersion = MutableStateFlow(0)
    private var sensorsRunning = false
    private var raidStartedAt = 0L
    private var fightStartedAt = 0L
    private var lastFxSeq = 0
    private var windupJob: Job? = null
    /** Host: the last slain monster, bindable as a companion. */
    private var lastSlain: Monster? = null

    val models = ModelManager(app)
    /** User's explicit pick ("E2B"/"E4B"), else the recommendation. */
    val selectedModel = MutableStateFlow<String?>(prefs.getString("model", null))
    val modelChoice = MutableStateFlow(models.best(preferVariant = prefs.getString("model", null)))
    val modelPresent = MutableStateFlow(modelChoice.value != null)
    val modelInfo: String get() = models.why(modelChoice.value)
    val modelFile: File get() = modelChoice.value?.file ?: File(app.filesDir, MODEL_NAME)
    private fun rescanModels() { modelChoice.value = models.best(preferVariant = selectedModel.value); modelPresent.value = modelChoice.value != null }

    fun selectModel(variant: String) {
        selectedModel.value = variant; prefs.edit().putString("model", variant).apply()
        rescanModels()
        if (gemmaReady.value) { gemma?.close(); gemma = null; gemmaReady.value = false }
        loadModel()
    }

    fun deleteModel(variant: String) {
        models.candidates().filter { it.name.contains(variant, true) }.forEach { it.delete() }
        if (selectedModel.value == variant) { selectedModel.value = null; prefs.edit().remove("model").apply() }
        rescanModels()
    }
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Your character (xp, level, items, potions, epithet, companions) survives between raids. */
    private fun savedCharacter(): Player? = runCatching { prefs.getString("character", null)?.let { json.decodeFromString(Player.serializer(), it) } }.getOrNull()
    private fun saveCharacter(p: Player) { prefs.edit().putString("character", json.encodeToString(Player.serializer(), p)).apply() }
    fun resetCharacter() { prefs.edit().remove("character").apply() }

    init {
        // A stray download of a model we already have (earlier builds allowed it) is just wasted data.
        runCatching { if (models.has("E2B") || models.has("E4B")) { val n = models.cancelDownloads(); if (n > 0) gemmaStatus.value = "cancelled $n stray download(s)" } }
        textScale.value = prefs.getFloat("textScale", 1f)
        journal.value = runCatching { prefs.getString("journal", null)?.let { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), it) } }.getOrNull() ?: emptyList()
    }

    fun cycleTextScale() {
        val next = when (textScale.value) { 1f -> 1.2f; 1.2f -> 1.45f; else -> 1f }
        textScale.value = next; prefs.edit().putFloat("textScale", next).apply()
    }

    fun clearJournal() { journal.value = emptyList(); prefs.edit().remove("journal").apply() }

    private fun absorbLog(s: GameState) {
        val fresh = s.logTotal - seenLogTotal
        if (fresh <= 0) { if (fresh < 0) seenLogTotal = s.logTotal; return }
        val lines = s.log.takeLast(minOf(fresh, s.log.size))
        seenLogTotal = s.logTotal
        val stamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
        val tagged = lines.map { "$stamp  $it" }
        val next = (journal.value + tagged).takeLast(600)
        journal.value = next
        prefs.edit().putString("journal", json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), next)).apply()
    }

    private fun journalMark(title: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
        journal.value = (journal.value + "$stamp  ━━ $title ━━").takeLast(600)
    }
    fun characterSummary(): String? = savedCharacter()?.let { c ->
        "Your character: L${c.level}${if (c.epithet.isNotBlank()) " ${c.epithet}" else ""} · ${c.xp} xp · ${c.items.size} items · ${c.companions.size} companions · powers: ${powersFor(c.level).ifEmpty { listOf("none yet") }.joinToString(", ")}"
    }

    fun startSensors() { if (!sensorsRunning) { compass.start(); locator.start(); steps.start(); env.start(); swing.start(); twist.start(); sensorsRunning = true } }
    fun stopSensors() { if (sensorsRunning) { compass.stop(); locator.stop(); steps.stop(); env.stop(); swing.stop(); twist.stop(); sensorsRunning = false } }

    // ---------- model ----------

    /** Loads the best model on the best backend; if the GPU/NPU init fails, retries on CPU without asking. */
    fun loadModel(forceCpu: Boolean = false) {
        if (gemmaReady.value || gemmaLoading.value) return
        rescanModels()
        val choice = models.best(forceCpu, selectedModel.value) ?: run { gemmaStatus.value = "no model file on this phone"; return }
        gemmaLoading.value = true
        scope.launch {
            val ok = runCatching {
                val g = Gemma(choice.file.absolutePath, choice.backend, choice.backendName)
                g.load { gemmaStatus.value = it }
                gemma = g; gemmaReady.value = true
            }.isSuccess
            if (!ok && !forceCpu) {
                gemmaStatus.value = "${choice.backendName} init failed — falling back to CPU"
                runCatching {
                    val cpu = models.best(forceCpu = true, preferVariant = selectedModel.value)!!
                    val g = Gemma(cpu.file.absolutePath, cpu.backend, "CPU")
                    g.load { gemmaStatus.value = it }
                    gemma = g; gemmaReady.value = true
                }.onFailure { gemmaStatus.value = "load failed: ${it.message?.take(120)}" }
            } else if (!ok) gemmaStatus.value = "load failed on CPU"
            gemmaLoading.value = false
        }
    }

    /** Fetch a model straight from Hugging Face on the phone (internet needed once). */
    val downloading = MutableStateFlow(false)

    fun cancelDownload() {
        val n = models.cancelDownloads()
        downloading.value = false; gemmaLoading.value = false
        gemmaStatus.value = if (n > 0) "download cancelled" else "nothing to cancel"
        rescanModels()
    }

    fun downloadModel(variant: String) = scope.launch {
        if (gemmaLoading.value) return@launch
        if (models.has(variant)) { gemmaStatus.value = "Gemma 4 $variant is already on this phone"; return@launch }
        downloading.value = true
        gemmaLoading.value = true
        gemmaStatus.value = "starting download of Gemma 4 $variant…"
        val ok = runCatching {
            val id = models.startDownload(variant)
            models.awaitDownload(id) { gemmaStatus.value = it }
        }.getOrDefault(false)
        gemmaLoading.value = false; downloading.value = false
        if (ok) { gemmaStatus.value = "downloaded Gemma 4 $variant"; rescanModels(); loadModel() }
    }

    fun importModel(uri: Uri) = scope.launch(Dispatchers.IO) {
        runCatching {
            val picked = runCatching { app.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null } }.getOrNull()
            val tmp = File(app.filesDir, (picked?.takeIf { it.endsWith(".litertlm") } ?: MODEL_NAME) + ".part")
            app.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20); var total = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); total += n
                        if (total % (64L shl 20) < (1 shl 20)) gemmaStatus.value = "copying model… ${total shr 20} MB"
                    }
                }
            }
            tmp.renameTo(File(app.filesDir, tmp.name.removeSuffix(".part")))
            rescanModels()
            gemmaStatus.value = "model copied"
            loadModel()
        }.onFailure { gemmaStatus.value = "copy failed: ${it.message}" }
    }

    // ---------- lobby / networking ----------

    fun host(name: String) {
        selfName = name.ifBlank { "Host" }
        prefs.edit().putString("name", selfName).apply()
        role.value = Role.HOST
        link = NearbyLink(app, selfName, ::onMessage, ::onBinary, { peers.value = it }, { status.value = it }).also { it.host() }
        steps.reset(); env.markBaseline()
        raidStartedAt = System.currentTimeMillis()
        statsStore.update { it.copy(raids = it.raids + 1) }
        seenLogTotal = 0; journalMark("Raid ${statsStore.stats.value.raids} · ${java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.US).format(java.util.Date())}")
        state.update { it.copy(phase = Phase.EXPLORE, players = it.players + (selfId to selfPlayer())).withLog("$selfName opened the raid. Walk around, then point at something.") }
        broadcast()
        sfx.loop("ambient_explore")
    }

    fun join(name: String) {
        selfName = name.ifBlank { "Player" }
        prefs.edit().putString("name", selfName).apply()
        role.value = Role.CLIENT
        seenLogTotal = 0; journalMark("Joined a raid")
        raidStartedAt = System.currentTimeMillis()
        statsStore.update { it.copy(raids = it.raids + 1) }
        link = NearbyLink(
            app, selfName, ::onMessage, ::onBinary,
            onPeersChanged = { ps -> peers.value = ps; if (ps.isNotEmpty()) { link?.send(Msg.Join(selfPlayer())); sfx.play("join") } },
            onStatus = { status.value = it },
        ).also { it.join() }
    }

    fun leave() {
        if (role.value == Role.NONE) return
        flushPlaytime()
        if (recording.value) recorder.stop()
        recording.value = false
        windupJob?.cancel(); windupJob = null
        runCatching { link?.stop() }; link = null
        peers.value = emptyList(); pendingAudio.clear(); pendingSprite = null
        role.value = Role.NONE; status.value = ""
        state.value = GameState()
        sfx.stopLoop()
    }

    private fun flushPlaytime() {
        if (raidStartedAt > 0) {
            val secs = ((System.currentTimeMillis() - raidStartedAt) / 1000).toInt()
            statsStore.update { it.copy(playSeconds = it.playSeconds + secs) }
            raidStartedAt = 0
        }
    }

    private fun selfPlayer(): Player {
        val loc = locator.location.value
        val saved = savedCharacter()
        val fresh = Player(selfId, selfName, headingDeg = compass.heading.value, lat = loc?.latitude ?: 0.0, lon = loc?.longitude ?: 0.0, acc = loc?.accuracy ?: 999f)
        return if (saved == null) fresh else fresh.copy(
            xp = saved.xp, level = saved.level, maxHp = maxHpFor(saved.level), hp = maxHpFor(saved.level),
            items = saved.items, potions = maxOf(saved.potions, 1), epithet = saved.epithet, companions = saved.companions,
        )
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
                sfx.play("join"); broadcast()
                state.value.monster?.sprite?.takeIf { it.isNotBlank() }?.let { sendSprite(it, listOf(endpointId)) }
            }
            is Msg.Pose -> if (role.value == Role.HOST) state.update { s ->
                val p = s.players[msg.playerId] ?: return@update s
                s.copy(players = s.players + (p.id to p.copy(headingDeg = msg.headingDeg, lat = msg.lat, lon = msg.lon)))
            }
            is Msg.Action -> if (role.value == Role.HOST) scope.launch { submit(msg.playerId, msg.text, null, msg.headingDeg, msg.lat, msg.lon) }
            is Msg.AudioHeader -> if (role.value == Role.HOST) pendingAudio[endpointId] = msg
            is Msg.SpriteHeader -> if (role.value == Role.CLIENT) pendingSprite = msg
            is Msg.Parry -> if (role.value == Role.HOST) armParry(msg.playerId)
            is Msg.Potion -> if (role.value == Role.HOST) applyPotion(msg.playerId)
            is Msg.State -> if (role.value == Role.CLIENT) {
                val before = state.value
                state.value = msg.state
                trackFx(msg.state); absorbLog(msg.state)
                msg.state.players[selfId]?.let { saveCharacter(it) }
                val hpBefore = before.players[selfId]?.hp; val hpAfter = msg.state.players[selfId]?.hp
                if (hpBefore != null && hpAfter != null && hpAfter < hpBefore) buzz(120)
                if (msg.state.phase != before.phase) onPhaseChanged(msg.state.phase)
            }
        }
    }

    private fun sendSprite(name: String, to: Collection<String>? = null) {
        val f = File(app.cacheDir, name); if (!f.exists()) return
        val bytes = f.readBytes()
        if (to == null) { link?.send(Msg.SpriteHeader(name, bytes.size)); link?.sendStream(bytes) }
        else { link?.send(Msg.SpriteHeader(name, bytes.size), to); link?.sendStream(bytes, to) }
    }

    private fun onBinary(endpointId: String, bytes: ByteArray) {
        if (role.value == Role.CLIENT) {
            val h = pendingSprite ?: return; pendingSprite = null
            runCatching { File(app.cacheDir, h.name).writeBytes(bytes); spriteVersion.value++ }
            return
        }
        if (role.value != Role.HOST) return
        val h = pendingAudio.remove(endpointId) ?: run { status.value = "audio without header"; return }
        scope.launch { submit(h.playerId, null, bytes, h.headingDeg, h.lat, h.lon) }
    }

    private fun broadcast() { trackFx(state.value); absorbLog(state.value); state.value.players[selfId]?.let { saveCharacter(it) }; link?.send(Msg.State(state.value)) }

    private fun onPhaseChanged(phase: Phase) {
        when (phase) {
            Phase.BATTLE -> sfx.loop("ambient_battle")
            Phase.EXPLORE, Phase.NPC, Phase.VICTORY, Phase.DEFEAT -> sfx.loop("ambient_explore")
            else -> sfx.stopLoop()
        }
    }

    // ---------- stats & sound from the synced Fx stream (both roles) ----------

    private fun trackFx(s: GameState) {
        val f = s.fx ?: return
        if (f.seq == lastFxSeq) return
        lastFxSeq = f.seq
        playFx(f, s)
        val mine = f.playerId == selfId
        statsStore.update { st ->
            var n = st
            if (mine && f.dealt > 0) n = n.copy(damageDealt = n.damageDealt + f.dealt, bestHit = maxOf(n.bestHit, f.dealt))
            if (mine && f.taken > 0) n = n.copy(damageTaken = n.damageTaken + f.taken)
            if (mine && f.parried) n = n.copy(parries = n.parries + 1)
            if (mine && f.label == "LEGENDARY!") n = n.copy(legendaryHits = n.legendaryHits + 1)
            if (mine && f.label == "stylish") n = n.copy(stylishHits = n.stylishHits + 1)
            if (mine && (f.label.startsWith("🧪") || f.potion > 0)) n = n.copy(potions = n.potions + 1)
            if (f.killed) n = n.copy(kills = n.kills + 1, bosses = n.bosses + (if (s.monster?.name?.startsWith("👑") == true) 1 else 0), longestFightTurns = maxOf(n.longestFightTurns, s.turn))
            if (f.label == "spawn" && s.monster?.friendly == false) n = n.copy(encounters = n.encounters + 1)
            n
        }
    }

    private fun voicePitchFor(m: Monster?): Float {
        if (m == null) return 0.55f
        val h = abs(m.name.hashCode() % 100) / 100f
        return if (m.friendly) 1.05f + h * 0.35f else 0.4f + h * 0.3f
    }

    private fun playFx(f: Fx, s: GameState) {
        val pitch = voicePitchFor(s.monster)
        when (f.label) {
            "spawn" -> { if (s.monster?.friendly == true) { sfx.play("npc"); sfx.speak(f.taunt, pitch, 1.0f, 500) } else { sfx.play("spawn"); sfx.speak(f.taunt, pitch, 0.95f, 700) } }
            "omens" -> sfx.play("omen")
            "chase" -> { sfx.play("steps"); sfx.speak(f.taunt, pitch, 0.95f, 400) }
            "parley" -> { sfx.play("npc", 200); sfx.speak(f.taunt, pitch, 1.0f, 600) }
            "say" -> {
                if (f.xpSpent > 0) sfx.play("coins")
                if (f.potion > 0) sfx.play("potion")
                if (f.item.isNotBlank() && f.xpSpent == 0) sfx.play("legendary", volume = 0.5f)
                if (s.phase == Phase.NPC) sfx.speak(f.say, pitch, 1.0f, 150) else sfx.narrate(f.say, 150)
            }
            "epithet" -> { sfx.play("levelup"); sfx.narrate(f.say, 400) }
            "windup" -> sfx.play("windup")
            "pin" -> sfx.play("ping")
            "stir" -> sfx.play("omen", volume = 0.35f, rate = 1.6f)
            "flee" -> { sfx.play("whoosh"); sfx.play("steps", 150) }
            "bind" -> { sfx.play("npc"); sfx.speak(f.taunt, pitch, 1.0f, 300) }
            else -> {
                if (f.power.isNotBlank()) sfx.play("legendary", 0, 0.6f, 1.2f)
                if (f.dealt > 0) { sfx.play("slash"); sfx.play("hit", 130) }
                else if (f.playerId.isNotBlank() && f.potion == 0 && f.taken == 0 && !f.killed) { sfx.play("miss"); sfx.play("whoosh", 120) }
                if (f.companionHits.isNotEmpty()) { sfx.play("hit", 420, 0.7f, 1.3f); sfx.speak("${f.companionHits.first()}!", 0.7f, 1.1f, 350) }
                if (f.label == "LEGENDARY!") sfx.play("legendary", 200)
                if (f.potion > 0) sfx.play("potion", 150)
                if (f.parried) sfx.play("parry", 320)
                else if (f.taken > 0) { sfx.play("attack", 260); if (f.playerId == selfId) sfx.play("hurt", 520) }
                if (f.killed) { sfx.play("kill", 350); sfx.play("victory", 1100) }
                else if (f.taunt.isNotBlank()) sfx.speak(f.taunt, pitch, 0.95f, if (f.taken > 0) 900 else 500)
                if (f.levelUp) sfx.play("levelup", 1300)
                if (f.wind) sfx.play("wind", 600)
                if (narrateAloud.value && !f.killed) s.log.dropLast(1).lastOrNull()?.let { sfx.narrate(it, 1400) }
            }
        }
    }

    private fun recordEncounter(s: GameState, outcome: String, loot: String = "") {
        val m = s.monster ?: return
        val secs = if (fightStartedAt > 0) ((System.currentTimeMillis() - fightStartedAt) / 1000).toInt() else 0
        statsStore.update { st -> st.copy(history = (st.history + Encounter(m.emoji, m.name, m.maxHp, outcome, s.turn, secs, loot, m.name.startsWith("👑"))).takeLast(30)) }
    }

    // ---------- gestures ----------

    private fun onSwing(peak: Float) {
        val s = state.value
        if (s.phase != Phase.BATTLE || s.busy || (s.players[selfId]?.hp ?: 0) <= 0) return
        lastSwing.value = peak
        statsStore.update { it.copy(swings = it.swings + 1) }
        buzz(30)
        act(when { peak > 30f -> "I swing with everything I have, a wild all-out strike!"; peak > 22f -> "I swing hard at it!"; else -> "I take a swing at it." })
    }

    private fun onTwist() {
        if (state.value.phase != Phase.BATTLE) return
        buzz(20, 40, 20)
        if (role.value == Role.HOST) armParry(selfId) else link?.send(Msg.Parry(selfId))
    }

    private fun armParry(playerId: String) {
        state.update { s -> if (playerId in s.parries) s else s.copy(parries = s.parries + playerId).withLog("🛡 ${s.players[playerId]?.name ?: "?"} twists into a guard stance — next hit is parried") }
        broadcast()
    }

    fun drinkPotion() {
        if (role.value == Role.HOST) applyPotion(selfId) else link?.send(Msg.Potion(selfId))
    }

    private fun applyPotion(playerId: String) {
        state.update { s ->
            val p = s.players[playerId] ?: return@update s
            if (p.potions <= 0 || p.hp >= p.maxHp) return@update s
            val healed = min(POTION_HEAL, p.maxHp - p.hp)
            s.copy(players = s.players + (p.id to p.copy(hp = p.hp + healed, potions = p.potions - 1)), fx = Fx(nextSeq(s), p.id, label = "🧪 +$healed"))
                .withLog("🧪 ${p.name} drinks a potion: +$healed HP (${p.potions - 1} left)")
        }
        if (playerId == selfId) buzz(30, 30, 30)
        broadcast()
    }

    private fun nextSeq(s: GameState) = (s.fx?.seq ?: 0) + 1

    // ---------- exploring: omens, spawn, meet, DM chat ----------

    fun scanOmens() = scope.launch {
        val g = gemma ?: return@launch
        if (role.value != Role.HOST || state.value.busy) return@launch
        state.update { it.copy(busy = true).withLog("📡 You listen to the airwaves…") }; broadcast()
        val signals = runCatching { scanner.scan() }.getOrElse { emptyList() }.take(6)
        if (signals.isEmpty()) { state.update { it.copy(busy = false).withLog("📡 Silence. No spirits haunt this place — wild country.") }; broadcast(); return@launch }
        val (texts, line) = runCatching { g.omens(signals.map { it.name }) }.getOrElse { null to "" }
        if (texts == null) { state.update { it.copy(busy = false).withLog("📡 ${signals.size} signals, but the DM can't read them.") }; broadcast(); return@launch }
        val omens = signals.zip(texts).map { (sig, t) -> Omen(sig.name, t.name, t.lore, t.threat, sig.dbm) }
        statsStore.update { it.copy(omensRead = it.omensRead + omens.size) }
        val lair = omens.filter { it.threat >= 4 }.maxByOrNull { it.dbm }
        state.update { s ->
            var next = s.copy(busy = false, omens = omens, bossLair = lair, fx = Fx(nextSeq(s), label = "omens"))
            omens.forEach { o -> next = next.withLog("${"☠".repeat(o.threat)} ${o.name} (\"${o.signal}\"): ${o.lore}") }
            if (lair != null) next = next.withLog("👑 This is the lair of ${lair.name}. The next monster will be a BOSS.")
            if (line.isNotBlank()) next = next.withLog(line.take(160))
            next
        }
        buzz(40, 40, 40); broadcast()
    }

    private fun reputation(): String = state.value.players.values.filter { it.epithet.isNotBlank() }.joinToString(", ") { "${it.name} ${it.epithet} (L${it.level})" }

    fun spawnFromCamera(imageCapture: ImageCapture) = scope.launch {
        val g = gemma ?: run { status.value = "load Gemma first"; return@launch }
        if (state.value.busy) return@launch
        val walked = steps.sinceReset.value
        state.update { it.copy(busy = true, steps = walked).withLog("The dungeon master studies the scene…") }; broadcast()
        sfx.play("shutter")
        val path = runCatching { imageCapture.captureToFile(app) }
            .getOrElse { state.update { s -> s.copy(busy = false).withLog("camera failed: ${it.message?.take(100)}") }; broadcast(); return@launch }
        val bearing = compass.heading.value
        val loc = locator.location.value
        val spriteJob = async(Dispatchers.IO) { runCatching { SpriteMaker.make(app, path) }.getOrNull() }
        launch {
            // the cut-out lands in ~1 s: show it stirring on the table while Gemma writes the sheet
            val f = spriteJob.await() ?: return@launch
            spriteVersion.value++
            if (state.value.busy && state.value.monster == null) {
                state.update { it.copy(monster = Monster("???", 0, 0, emptyList(), "", "", bearing, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0, loc?.accuracy ?: 999f, "❔", emptyList(), f.name), fx = Fx(nextSeq(it), label = "stir", taunt = "…")) }
                broadcast(); sendSprite(f.name)
            }
        }
        val conditions = env.describe()
        val lair = state.value.bossLair
        val t0 = System.currentTimeMillis()
        val (spawned, text) = runCatching { g.spawn(path, walked, conditions, lair?.let { "${it.name} (${it.lore})" }, reputation()) }
            .getOrElse { spriteJob.cancel(); state.update { s -> s.copy(busy = false).withLog("Gemma error: ${it.message?.take(100)}") }; broadcast(); return@launch }
        if (spawned == null) {
            spriteJob.cancel()
            state.update { it.copy(busy = false, monster = null).withLog("The DM hesitates: ${text.take(200)}") }
        } else {
            val bonus = min(walked / 4, 50)
            val hp = (spawned.hp + bonus) * (if (lair != null) 3 else 2) / 2
            val spriteName = spriteJob.await()?.name ?: ""
            if (spriteName.isNotBlank()) spriteVersion.value++
            val m = Monster(if (lair != null) "👑 ${spawned.name}" else spawned.name, hp, hp, spawned.attacks, spawned.weakness, spawned.flavor, bearing, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0, loc?.accuracy ?: 999f, spawned.emoji, spawned.accessories, spriteName)
            steps.reset(); fightStartedAt = System.currentTimeMillis()
            journalMark("${m.emoji} ${m.name}")
            statsStore.update { it.copy(spawns = it.spawns + 1, spawnMs = it.spawnMs + (System.currentTimeMillis() - t0), stepsWalked = it.stepsWalked + walked, toolRetries = g.retries) }
            state.update {
                var s = it.copy(phase = Phase.BATTLE, monster = m, busy = false, turn = 0, steps = 0, env = env.summary.value, parries = emptyList(), bossLair = null, windup = 0, monsterDazed = false, recastUsed = false, victory = null, fx = Fx(nextSeq(it), label = "spawn", taunt = spawned.taunt))
                    .withLog("${m.emoji} ${m.name} appears! ${m.flavor}")
                if (lair != null) s = s.withLog("👑 BOSS FIGHT — champion of ${lair.name}. Double XP.")
                if (bonus > 0) s = s.withLog("It grew +$bonus HP from the $walked steps you walked.")
                if (conditions.isNotBlank()) s = s.withLog("(${conditions.replaceFirstChar { c -> c.uppercase() }}.)")
                if (text.isNotBlank()) s = s.withLog(text.take(200))
                s
            }
            buzz(60); onPhaseChanged(Phase.BATTLE); startWindup()
        }
        broadcast()
        state.value.monster?.sprite?.takeIf { it.isNotBlank() }?.let { sendSprite(it) }
    }

    /** Point at something and meet it instead of fighting it. */
    fun meetFromCamera(imageCapture: ImageCapture) = scope.launch {
        val g = gemma ?: return@launch
        if (state.value.busy) return@launch
        state.update { it.copy(busy = true).withLog("You approach…") }; broadcast()
        sfx.play("shutter")
        val path = runCatching { imageCapture.captureToFile(app) }
            .getOrElse { state.update { s -> s.copy(busy = false).withLog("camera failed: ${it.message?.take(100)}") }; broadcast(); return@launch }
        val bearing = compass.heading.value
        val loc = locator.location.value
        val spriteJob = async(Dispatchers.IO) { runCatching { SpriteMaker.make(app, path) }.getOrNull() }
        val (npc, text) = runCatching { g.meet(path, env.describe()) }
            .getOrElse { spriteJob.cancel(); state.update { s -> s.copy(busy = false).withLog("Gemma error: ${it.message?.take(100)}") }; broadcast(); return@launch }
        if (npc == null) {
            spriteJob.cancel()
            state.update { it.copy(busy = false).withLog("Nobody home: ${text.take(160)}") }
        } else {
            val spriteName = spriteJob.await()?.name ?: ""
            if (spriteName.isNotBlank()) spriteVersion.value++
            journalMark("${npc.emoji} ${npc.name}")
            val m = Monster(npc.name, 0, 0, emptyList(), "", npc.offer, bearing, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0, loc?.accuracy ?: 999f, npc.emoji, npc.accessories, spriteName, friendly = true, role = npc.role, offer = npc.offer)
            state.update {
                var s = it.copy(phase = Phase.NPC, monster = m, busy = false, fx = Fx(nextSeq(it), label = "spawn", taunt = npc.greeting))
                    .withLog("${m.emoji} ${m.name}, ${m.role}: \"${npc.greeting}\"").withLog("They offer: ${m.offer}")
                if (text.isNotBlank()) s = s.withLog(text.take(160))
                s
            }
            buzz(30, 30, 30); onPhaseChanged(Phase.NPC)
        }
        broadcast()
        state.value.monster?.sprite?.takeIf { it.isNotBlank() }?.let { sendSprite(it) }
    }

    fun leaveNpc() {
        if (role.value != Role.HOST || state.value.phase != Phase.NPC) return
        state.update { s -> s.copy(phase = Phase.EXPLORE, monster = null).withLog("You wave goodbye to ${s.monster?.name ?: "them"}.") }
        onPhaseChanged(Phase.EXPLORE); broadcast()
    }

    // ---------- input: text / voice / swing all land here, dispatched by phase on the host ----------

    fun act(text: String) {
        statsStore.update { it.copy(typedActions = it.typedActions + 1) }
        val loc = locator.location.value; val h = compass.heading.value
        if (role.value == Role.HOST) scope.launch { submit(selfId, text, null, h, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0) }
        else link?.send(Msg.Action(selfId, text, h, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0))
        sfx.play("blip", volume = 0.5f)
    }

    fun voiceStart() {
        val ph = state.value.phase
        if (ph != Phase.BATTLE && ph != Phase.EXPLORE && ph != Phase.NPC) return
        if (state.value.busy) return
        recorder.start(); recording.value = true
    }

    fun voiceStop() {
        if (!recording.value) return
        recording.value = false
        val wav = recorder.stop() ?: run { status.value = "too short — hold the button while you speak"; return }
        val loud = (wavLoudness(wav) * 100).roundToInt()
        statsStore.update { it.copy(shouts = it.shouts + 1, loudestShout = maxOf(it.loudestShout, loud)) }
        val loc = locator.location.value; val h = compass.heading.value
        val lat = loc?.latitude ?: 0.0; val lon = loc?.longitude ?: 0.0
        if (role.value == Role.HOST) scope.launch { submit(selfId, null, wav, h, lat, lon) }
        else { link?.send(Msg.AudioHeader(selfId, h, lat, lon, wav.size)); link?.sendStream(wav) }
    }

    private suspend fun submit(playerId: String, text: String?, audioWav: ByteArray?, headingDeg: Float, lat: Double, lon: Double) {
        when (state.value.phase) {
            Phase.BATTLE -> resolve(playerId, text, audioWav, headingDeg, lat, lon)
            Phase.NPC -> npcTalk(playerId, text, audioWav)
            Phase.EXPLORE, Phase.VICTORY, Phase.DEFEAT -> dmTalk(playerId, text, audioWav)
            else -> {}
        }
    }

    private suspend fun dmTalk(playerId: String, text: String?, audioWav: ByteArray?) {
        val g = gemma ?: return
        val p = state.value.players[playerId] ?: return
        if (state.value.busy) return
        state.update { it.copy(busy = true).withLog("${p.name}: ${text ?: "🎤 (asks the DM)"}") }; broadcast()
        val summary = state.value.players.values.joinToString("; ") { "${it.name} L${it.level} ${it.hp}/${it.maxHp} HP, ${it.potions} potions, items: ${it.items.ifEmpty { listOf(Item("none")) }.joinToString()}" } + "; kills ${state.value.kills}"
        val (reply0, potion) = runCatching { g.dmChat(p.name, text, audioWav, summary) }.getOrElse { "" to false }
        val reply = reply0.clean()
        state.update { s ->
            var me = s.players[p.id] ?: p
            var healed = 0
            if (potion && me.potions > 0 && me.hp < me.maxHp) { healed = min(POTION_HEAL, me.maxHp - me.hp); me = me.copy(hp = me.hp + healed, potions = me.potions - 1) }
            var next = s.copy(busy = false, players = s.players + (me.id to me), fx = Fx(nextSeq(s), p.id, label = "say", say = reply, potion = healed))
            if (reply.isNotBlank()) next = next.withLog("📜 ${reply.take(220)}")
            if (healed > 0) next = next.withLog("🧪 ${me.name} drinks a potion: +$healed HP")
            next
        }
        broadcast()
    }

    private suspend fun npcTalk(playerId: String, text: String?, audioWav: ByteArray?) {
        val g = gemma ?: return
        val s0 = state.value
        val npc = s0.monster ?: return
        val p = s0.players[playerId] ?: return
        if (s0.busy) return
        state.update { it.copy(busy = true).withLog("${p.name}: ${text ?: "🎤 (speaks)"}") }; broadcast()
        val (reply0, effects) = runCatching { g.npcChat(p.name, text, audioWav, npc.name, npc.role, p.xp, p.items.map { it.toString() }) }.getOrElse { "" to Triple(null, null, 0) }
        val reply = reply0.clean()
        val (trade, gift, heal) = effects
        state.update { s ->
            var me = s.players[p.id] ?: p
            var next = s.copy(busy = false)
            var spent = 0; var item = ""; var healed = 0
            if (trade != null) {
                val tag = if (trade.discount > 0) " (${trade.discount}% off — well haggled)" else ""
                if (me.xp >= trade.priceXp) { me = me.copy(xp = me.xp - trade.priceXp, items = (me.items + Item(trade.item, trade.effect)).takeLast(BAG_SLOTS)); spent = trade.priceXp; item = trade.item; next = next.withLog("💰 ${me.name} buys ${trade.item} for ${trade.priceXp} XP$tag: ${trade.effect}") }
                else next = next.withLog("💰 ${trade.item}: ${trade.priceXp} XP$tag — ${me.name} has ${me.xp}. Go earn it.")
            }
            if (gift != null) {
                if (npc.role == "merchant" && me.xp >= MERCHANT_MIN_PRICE) {
                    // merchants don't do charity: a "gift" from one is a sale at the counter minimum
                    me = me.copy(xp = me.xp - MERCHANT_MIN_PRICE, items = (me.items + Item(gift.item, gift.effect)).takeLast(BAG_SLOTS)); spent = MERCHANT_MIN_PRICE; item = gift.item
                    next = next.withLog("💰 ${me.name} buys ${gift.item} for $MERCHANT_MIN_PRICE XP: ${gift.effect}")
                } else { me = me.copy(items = (me.items + Item(gift.item, gift.effect)).takeLast(BAG_SLOTS)); item = gift.item; next = next.withLog("🎁 ${npc.name} gives ${me.name} ${gift.item}: ${gift.effect}") }
            }
            if (heal > 0 && me.hp < me.maxHp) { healed = min(heal, me.maxHp - me.hp); me = me.copy(hp = me.hp + healed); next = next.withLog("💚 ${npc.name} heals ${me.name}: +$healed HP") }
            next = next.copy(players = next.players + (me.id to me), fx = Fx(nextSeq(s), p.id, label = "say", say = reply, taunt = "", xpSpent = spent, item = item, potion = healed))
            if (reply.isNotBlank()) next = next.withLog("${npc.emoji} ${reply.take(220)}")
            next
        }
        broadcast()
    }

    // ---------- battle ----------

    private fun startWindup() {
        windupJob?.cancel()
        windupJob = scope.launch {
            while (true) {
                delay(1000)
                val s = state.value
                if (s.phase != Phase.BATTLE) break
                if (s.busy) continue
                val next = (s.windup + 100 / IDLE_STRIKE_SECONDS).coerceAtMost(100)
                if (next >= 100) { monsterTurn(); continue }
                state.update { it.copy(windup = next, fx = if (next in 70..74) Fx(nextSeq(it), label = "windup") else it.fx) }
                broadcast()
            }
        }
    }

    /** The monster strikes a hesitating player on its own. */
    private suspend fun monsterTurn() {
        val g = gemma ?: return
        val s0 = state.value
        val m = s0.monster ?: return
        val target = s0.players.values.filter { it.hp > 0 }.randomOrNull() ?: return
        state.update { it.copy(busy = true, windup = 100) }; broadcast()
        val strike = runCatching { g.monsterTurn(m.name, target.name, m.hp) }.getOrNull()
        if (strike == null) { state.update { it.copy(busy = false, windup = 0) }; broadcast(); return }
        val parried = target.id in s0.parries
        var hurt = if (parried) 0 else strike.damageToPlayer
        if (s0.monsterDazed) hurt /= 2
        if (s0.players.values.count { it.hp > 0 } <= 1 && hurt > 0) hurt = ceil(hurt * 0.6f).toInt()
        var wind = false
        state.update { s ->
            val cur = s.players[target.id] ?: target
            var hp = (cur.hp - hurt).coerceAtLeast(0)
            var me = cur.copy(hp = hp)
            if (hp == 0 && me.secondWind) { wind = true; me = me.copy(hp = SECOND_WIND_HP, secondWind = false) }
            val players = s.players + (me.id to me)
            var next = s.copy(busy = false, windup = 0, monsterDazed = false, parries = s.parries - target.id, players = players,
                fx = Fx(nextSeq(s), target.id, taken = hurt, parried = parried && strike.damageToPlayer > 0, taunt = strike.taunt, wind = wind))
                .withLog("⚡ ${m.name} strikes while ${target.name} hesitates!").withLog(strike.narration)
                .withLog(if (parried) "🛡 parried ${strike.damageToPlayer}" else if (s.monsterDazed) "−$hurt ${target.name} (dazed, halved)" else "−$hurt ${target.name}")
            if (wind) next = next.withLog("💫 ${me.name} refuses to fall — second wind!")
            if (players.values.all { it.hp <= 0 }) next = next.copy(phase = Phase.DEFEAT).withLog("The party falls. ${m.name} gloats.")
            next
        }
        if (target.id == selfId && hurt > 0) buzz(120)
        if (state.value.phase == Phase.DEFEAT) { statsStore.update { it.copy(wipes = it.wipes + 1) }; recordEncounter(state.value, "wiped the party"); onPhaseChanged(Phase.DEFEAT) }
        broadcast()
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
        state.update { it.copy(busy = true, windup = 0, players = it.players + (p.id to p)).withLog("${p.name}: $shown") }; broadcast()
        val t0 = System.currentTimeMillis()
        val (r, reply) = runCatching { g.resolve(p.name, text, audioWav, m.name, m.hp, m.weakness, facing, flanking, p.items.map { it.toString() }, p.potions, powersFor(p.level)) }
            .getOrElse { state.update { s -> s.copy(busy = false).withLog("Gemma error: ${it.message?.take(100)}") }; broadcast(); return }
        statsStore.update { it.copy(resolves = it.resolves + 1, resolveMs = it.resolveMs + (System.currentTimeMillis() - t0), audioResolves = it.audioResolves + (if (audioWav != null) 1 else 0), toolRetries = g.retries) }
        if (r == null) {
            val used = g.lastItemUsed; val potion = g.lastPotionRequested
            if (used != null || potion) {
                // the model acted (used an item / drank) but skipped the verdict: narrate the mechanics ourselves
                val itemName = used?.let { want -> p.items.firstOrNull { it.name.contains(want, true) || want.contains(it.name, true) } }
                state.update { s ->
                    val cur = s.players[p.id] ?: p
                    var me = cur; var healed = 0
                    if (potion && cur.potions > 0 && cur.hp < cur.maxHp) { healed = min(POTION_HEAL, cur.maxHp - cur.hp); me = me.copy(hp = me.hp + healed, potions = me.potions - 1) }
                    var next = s.copy(busy = false, windup = 0, players = s.players + (me.id to me), fx = Fx(nextSeq(s), p.id, potion = healed, item = itemName?.name ?: ""))
                    if (itemName != null) next = next.withLog("🎁 ${p.name} readies ${itemName.name}: ${itemName.effect}. Now strike with it!")
                    if (healed > 0) next = next.withLog("🧪 ${me.name} drinks a potion: +$healed HP (${me.potions} left)")
                    if (itemName == null && healed == 0) next = next.withLog("The DM squints. Try that again, louder.")
                    next
                }
            } else {
                state.update { it.copy(busy = false).withLog(if (reply.isBlank()) "The DM blinks. ${m.name} waits. Say it again." else "The DM mumbles: ${reply.take(200)}") }
            }
            broadcast(); return
        }

        // ---- powers (level-gated verbs) ----
        val invoked = g.lastPower
        val power = invoked?.takeIf { it.first in powersFor(p.level) }
        var powerLog = ""
        if (invoked != null && power == null) {
            val needs = listOf("Taunt" to 2, "Parley" to 3, "Bind" to 4, "Recast" to 5).firstOrNull { it.first.equals(invoked.first, true) }?.second
            powerLog = if (needs != null) "🔒 ${invoked.first} unlocks at level $needs — ${p.name} is level ${p.level}. It counts as a plain action for now." else "🔒 ${invoked.first}? No such power."
        }
        var dazeNow = false
        var recastTo: String? = null
        var parleyWon = false
        var hurtMul = 1f
        when (power?.first) {
            "Taunt" -> if (r.style >= 3) { dazeNow = true; powerLog = "😤 Taunt lands — ${m.name} is dazed, its next strike is halved" } else powerLog = "😤 The taunt falls flat."
            "Parley" -> if (r.style >= 4) { parleyWon = true; powerLog = "🕊 Parley! ${m.name} lowers its guard and listens." } else { hurtMul = 2f; powerLog = "🕊 ${m.name} isn't listening — and punishes the pause." }
            "Recast" -> if (!s0.recastUsed && power.second.isNotBlank()) { recastTo = power.second; powerLog = "🔮 Recast: ${m.name} is now weak to ${power.second}" } else powerLog = "🔮 Recast fizzles."
            else -> {}
        }
        if (parleyWon) {
            recordEncounter(s0, "parleyed by ${p.name}")
            state.update { s ->
                val me = (s.players[p.id] ?: p).copy(xp = (s.players[p.id] ?: p).xp + 40)
                s.copy(phase = Phase.NPC, busy = false, windup = 0, players = s.players + (me.id to me),
                    monster = m.copy(friendly = true, role = "sage", offer = "a truce, and whatever it knows"),
                    fx = Fx(nextSeq(s), p.id, label = "parley", taunt = r.taunt.ifBlank { "...FINE. TALK." }, power = "Parley"))
                    .withLog(r.narration).withLog(powerLog).withLog("+40 xp for words over wounds")
            }
            windupJob?.cancel(); onPhaseChanged(Phase.NPC); broadcast(); return
        }

        var dmg = r.damageToMonster
        if (!facing) dmg /= 2
        if (flanking) dmg = (dmg * 1.5f).roundToInt()
        val cry = if (audioWav != null) (wavLoudness(audioWav) * 5f).roundToInt() else 0
        if (dmg > 0) dmg += cry
        val crit = r.style >= 4
        if (r.style >= 5) dmg *= 2 else if (r.style == 4) dmg = (dmg * 1.5f).roundToInt()
        val itemUsed = r.usedItem?.let { want -> p.items.firstOrNull { it.name.equals(want, true) || it.name.contains(want, true) || want.contains(it.name, true) }?.name }
        if (itemUsed != null && dmg > 0) dmg = (dmg * 1.5f).roundToInt()
        val wantsPotion = r.drankPotion && p.potions > 0
        // companions pile on
        val companionHits = p.companions.map { it.attack }
        val companionDmg = p.companions.size * COMPANION_DMG
        dmg += companionDmg
        val parried = playerId in s0.parries
        val aliveCount = s0.players.values.count { it.hp > 0 }
        var hurt = if (parried) 0 else (r.damageToPlayer * hurtMul).roundToInt()
        if (s0.monsterDazed) hurt /= 2
        if (aliveCount <= 1 && hurt > 0) hurt = ceil(hurt * 0.6f).toInt()
        val newMonsterHp = (m.hp - dmg).coerceAtLeast(0)
        val killed = newMonsterHp == 0
        val boss = m.name.startsWith("👑")
        val xpGain = ((if (dmg > 0) 10 else 0) + (if (killed) 50 else 0) + (if (r.style >= 5) 20 else 0)) * (if (boss) 2 else 1)
        val tag = buildString {
            append("−$dmg ${m.name}")
            if (r.style >= 5) append(" ★ LEGENDARY ×2") else if (r.style == 4) append(" ★ stylish ×1.5")
            if (flanking) append(" (flank!)")
            if (!facing) append(" (not facing it)")
            if (cry > 0) append(" (battle cry +$cry)")
            if (itemUsed != null) append(" (🎁 $itemUsed ×1.5)")
            if (companionDmg > 0) append(" (🐾 +$companionDmg)")
            if (parried && r.damageToPlayer > 0) append(", 🛡 parried ${r.damageToPlayer}")
            else if (hurt > 0) append(", −$hurt ${p.name}")
            if (xpGain > 0) append(", +$xpGain xp")
        }
        if (playerId == selfId && hurt > 0) buzz(120)
        var wind = false
        var leveledTo = 0
        state.update { s ->
            val cur = s.players[p.id] ?: p
            val healed = if (wantsPotion && cur.potions > 0) min(POTION_HEAL, cur.maxHp - cur.hp) else 0
            val hpNow = (cur.hp + healed - hurt).coerceAtLeast(0)
            var me = cur.copy(hp = hpNow, xp = cur.xp + xpGain, headingDeg = headingDeg, lat = lat, lon = lon, potions = if (wantsPotion && cur.potions > 0) cur.potions - 1 else cur.potions)
            if (hpNow == 0 && me.secondWind && !killed) { wind = true; me = me.copy(hp = SECOND_WIND_HP, secondWind = false) }
            val newLevel = levelFor(me.xp)
            var next = s.copy(
                busy = false, turn = s.turn + 1, windup = 0, parries = s.parries - playerId,
                monster = m.copy(hp = newMonsterHp, weakness = recastTo ?: m.weakness),
                monsterDazed = dazeNow || (s.monsterDazed && hurt == 0 && r.damageToPlayer == 0), recastUsed = s.recastUsed || recastTo != null,
                fx = Fx(nextSeq(s), playerId, dealt = dmg, taken = hurt, crit = crit, parried = parried && r.damageToPlayer > 0, killed = killed,
                    label = if (r.style >= 5) "LEGENDARY!" else if (r.style == 4) "stylish" else "", taunt = if (killed) "" else r.taunt,
                    potion = healed, item = itemUsed ?: "", power = power?.first ?: "", companionHits = companionHits),
            ).withLog(r.narration).withLog(tag)
            if (powerLog.isNotBlank()) next = next.withLog(powerLog)
            if (companionHits.isNotEmpty()) next = next.withLog("🐾 " + p.companions.joinToString(", ") { "${it.emoji} ${it.name}: ${it.attack}" })
            if (healed > 0) next = next.withLog("🧪 ${me.name} drinks a potion: +$healed HP (${me.potions} left)")
            else if (r.drankPotion && cur.potions <= 0) next = next.withLog("🧪 ${me.name} reaches for a potion — the bag is empty")
            if (wind) next = next.withLog("💫 ${me.name} refuses to fall — second wind! Back up with $SECOND_WIND_HP HP.")
            val leveled = newLevel > me.level
            if (leveled) {
                leveledTo = newLevel
                me = me.copy(level = newLevel, maxHp = maxHpFor(newLevel), hp = maxHpFor(newLevel))
                next = next.withLog("⬆ ${me.name} reached level $newLevel! Max HP ${me.maxHp}, fully healed.")
                powerUnlockedAt(newLevel)?.let { next = next.withLog("✨ Unlocked: $it") }
            }
            next = next.copy(fx = next.fx?.copy(levelUp = leveled, wind = wind))
            var players = next.players + (me.id to me)
            if (killed) {
                players = players.mapValues { (_, q) -> if (q.hp > 0) q.copy(potions = q.potions + 1) else q }
                next = next.copy(phase = Phase.VICTORY, kills = next.kills + 1, victory = Victory(m.name, m.emoji, xpGain, p.name, turns = next.turn)).withLog("${m.emoji} ${m.name} is defeated! Everyone standing finds a potion.")
            } else if (players.values.all { it.hp <= 0 }) next = next.copy(phase = Phase.DEFEAT).withLog("The party falls. ${m.name} gloats.")
            next.copy(players = players)
        }
        if (wind && playerId == selfId) { buzz(40, 40, 40, 40, 40); statsStore.update { it.copy(secondWinds = it.secondWinds + 1) } }
        val after = state.value
        if (after.phase == Phase.DEFEAT) { statsStore.update { it.copy(wipes = it.wipes + 1) }; recordEncounter(after, "wiped the party"); onPhaseChanged(Phase.DEFEAT) }
        broadcast()
        if (killed) {
            lastSlain = m.copy(hp = 0)
            windupJob?.cancel(); onPhaseChanged(Phase.VICTORY)
            if (playerId == selfId) buzz(40, 60, 40, 60, 200)
            val (loot, line) = runCatching { g.loot(m.name, p.name) }.getOrElse { null to "" }
            recordEncounter(state.value, "slain by ${p.name}", loot?.name ?: "")
            state.update { s ->
                val me = s.players[p.id] ?: return@update s
                var next = s
                if (loot != null) next = next.copy(players = next.players + (me.id to me.copy(items = (me.items + Item(loot.name, loot.effect)).takeLast(BAG_SLOTS))), victory = next.victory?.copy(loot = loot.name, lootEffect = loot.effect)).withLog("🎁 ${me.name} loots ${loot.name}: ${loot.effect}")
                if (line.isNotBlank()) next = next.withLog(line.take(200))
                if (companionCapacity(me.level) > me.companions.size) next = next.withLog("🐾 Level ${me.level} power: you may Bind ${m.name} as a companion.")
                next
            }
            broadcast()
        }
        if (leveledTo > 0) grantEpithet(p.id, leveledTo)
    }

    private suspend fun grantEpithet(playerId: String, level: Int) {
        val g = gemma ?: return
        val p = state.value.players[playerId] ?: return
        state.update { it.copy(busy = true) }; broadcast()
        val deeds = statsStore.stats.value.history.takeLast(4).joinToString("; ") { "${it.name} ${it.outcome}" } + "; " + state.value.log.takeLast(4).joinToString(" | ")
        val title = runCatching { g.epithet(p.name, level, deeds) }.getOrNull()
        state.update { s ->
            val me = s.players[playerId] ?: return@update s.copy(busy = false)
            if (title == null) s.copy(busy = false)
            else s.copy(busy = false, players = s.players + (me.id to me.copy(epithet = title)), fx = Fx(nextSeq(s), playerId, label = "epithet", say = "${me.name} is now known as ${me.name} $title"))
                .withLog("🏷 ${me.name} is now known as ${me.name} $title")
        }
        broadcast()
    }

    /** Drop an item from your bag (host applies; clients ask the host). */
    fun dropItem(name: String) {
        if (role.value == Role.HOST) state.update { s ->
            val me = s.players[selfId] ?: return@update s
            s.copy(players = s.players + (me.id to me.copy(items = me.items.filterNot { it.name == name }))).withLog("🗑 ${me.name} drops $name")
        }.also { broadcast() }
    }

    /** Host, after a kill at L4+: bind the fallen monster as a companion. */
    fun bindFallen() {
        if (role.value != Role.HOST) return
        val m = lastSlain ?: return
        state.update { s ->
            val me = s.players[selfId] ?: return@update s
            if (s.phase != Phase.VICTORY || companionCapacity(me.level) <= me.companions.size) return@update s
            val c = Ally(m.name.removePrefix("👑 "), m.emoji, m.attacks.firstOrNull() ?: "Bite", m.sprite)
            s.copy(players = s.players + (me.id to me.copy(companions = me.companions + c)), fx = Fx(nextSeq(s), selfId, label = "bind", taunt = "I SERVE... FOR NOW."))
                .withLog("🐾 ${c.emoji} ${c.name} is bound to ${me.name}. It will use ${c.attack} every turn.")
        }
        lastSlain = null; broadcast()
    }

    fun nextEncounter() {
        if (role.value != Role.HOST) return
        if (state.value.phase == Phase.BATTLE) recordEncounter(state.value, "fled")
        windupJob?.cancel()
        steps.reset()
        state.update { s -> s.copy(phase = Phase.EXPLORE, monster = null, steps = 0, fx = null, windup = 0, victory = null, players = s.players.mapValues { (_, p) -> p.copy(hp = maxOf(p.hp, p.maxHp / 2), potions = maxOf(p.potions, 1)) }).withLog("The party regroups. Walk around, then point at something new.") }
        onPhaseChanged(Phase.EXPLORE); broadcast()
    }

    /** Try to get away. Moving matters: steps since the fight began feed the roll; level helps. */
    fun tryEscape(fromChase: Boolean) {
        if (role.value != Role.HOST) return
        val s0 = state.value
        val m = s0.monster ?: return
        if (s0.phase != Phase.BATTLE || s0.busy) return
        val me = s0.players[selfId] ?: return
        val moved = steps.sinceReset.value
        val chance = (ESCAPE_BASE + 0.05f * (me.level - 1) + 0.02f * min(moved, 20) - (if (m.name.startsWith("👑")) 0.2f else 0f)).coerceIn(0.1f, 0.85f)
        val roll = kotlin.random.Random.nextFloat()
        Log.i("Raid", "escape roll=${"%.2f".format(roll)} chance=${"%.2f".format(chance)} steps=$moved")
        if (roll < chance) {
            recordEncounter(s0, "escaped by ${me.name}")
            windupJob?.cancel(); steps.reset()
            state.update { s -> s.copy(phase = Phase.EXPLORE, monster = null, windup = 0, fx = Fx(nextSeq(s), selfId, label = "flee"))
                .withLog("🏃 ${me.name} slips away from ${m.name} (${(chance * 100).toInt()}% chance, $moved steps). No loot, no shame.") }
            onPhaseChanged(Phase.EXPLORE); broadcast()
        } else {
            state.update { s -> s.withLog("🏃 ${me.name} tries to run (${(chance * 100).toInt()}% chance)… ${m.name} is faster.") }
            if (fromChase) reanchorMonster(chase = true) else scope.launch { monsterTurn() }
        }
    }

    fun reanchorMonster(chase: Boolean) {
        if (role.value != Role.HOST) return
        val h = compass.heading.value
        val loc = locator.location.value
        state.update { s ->
            val m = s.monster ?: return@update s
            if (s.phase != Phase.BATTLE && s.phase != Phase.NPC) return@update s
            val moved = m.copy(bearingDeg = h, lat = loc?.latitude ?: m.lat, lon = loc?.longitude ?: m.lon, acc = loc?.accuracy ?: m.acc)
            if (chase) s.copy(monster = moved, fx = Fx(nextSeq(s), taunt = "YOU CAN'T RUN!", label = "chase")).withLog("👣 ${m.name} lumbers around to face you.")
            else s.copy(monster = moved, fx = Fx(nextSeq(s), label = "pin")).withLog("📍 ${m.name} re-pinned where you're looking.")
        }
        broadcast()
    }

    fun bearingToMonster(m: Monster, p: Player?): Float {
        if (p == null || p.lat == 0.0 || m.lat == 0.0) return m.bearingDeg
        if (p.acc > GPS_MAX_ACC_METERS || m.acc > GPS_MAX_ACC_METERS) return m.bearingDeg
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
        const val FACING_TOLERANCE_DEG = 50f
        const val FLANK_MIN_DEG = 60f
        const val GPS_MIN_METERS = 25f
        const val GPS_MAX_ACC_METERS = 10f
        const val POTION_HEAL = 12
        const val SECOND_WIND_HP = 6
        const val IDLE_STRIKE_SECONDS = 25
        const val COMPANION_DMG = 4
        const val MERCHANT_MIN_PRICE = 20
        const val ESCAPE_BASE = 0.35f
    }
}
