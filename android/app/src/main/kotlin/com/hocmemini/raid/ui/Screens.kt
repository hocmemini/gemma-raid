package com.hocmemini.raid.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch
import com.hocmemini.raid.game.Fx
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hocmemini.raid.GameSession
import com.hocmemini.raid.camera.CameraPreview
import com.hocmemini.raid.game.Monster
import com.hocmemini.raid.game.Phase
import com.hocmemini.raid.game.headingDelta
import com.hocmemini.raid.game.companionCapacity
import com.hocmemini.raid.game.BAG_SLOTS
import com.hocmemini.raid.game.powerUnlockedAt
import com.hocmemini.raid.game.powersFor
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun RaidApp(vm: GameSession) {
    val role by vm.role.collectAsState()
    var showStats by remember { mutableStateOf(false) }
    var showJournal by remember { mutableStateOf(false) }
    var showModel by remember { mutableStateOf(false) }
    when {
        showModel -> { BackHandler { showModel = false }; ModelScreen(vm) { showModel = false } }
        showStats -> { BackHandler { showStats = false }; StatsScreen(vm) { showStats = false } }
        showJournal -> { BackHandler { showJournal = false }; JournalScreen(vm) { showJournal = false } }
        role == GameSession.Role.NONE -> LobbyScreen(vm, onStats = { showStats = true }, onJournal = { showJournal = true }, onModel = { showModel = true })
        else -> PlayScreen(vm, onStats = { showStats = true }, onJournal = { showJournal = true }, onModel = { showModel = true })
    }
}

@Composable
fun LobbyScreen(vm: GameSession, onStats: () -> Unit, onJournal: () -> Unit, onModel: () -> Unit) {
    var name by remember { mutableStateOf(vm.savedName) }
    val modelPresent by vm.modelPresent.collectAsState()
    val gemmaStatus by vm.gemmaStatus.collectAsState()
    val gemmaReady by vm.gemmaReady.collectAsState()
    val gemmaLoading by vm.gemmaLoading.collectAsState()
    val status by vm.status.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importModel) }

    // Hosting needs the model: auto-pick the strongest file and fastest backend, load immediately.
    LaunchedEffect(modelPresent) { if (modelPresent) vm.loadModel() }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("⚔ RAID", style = MaterialTheme.typography.displayLarge, color = Dungeon.gold)
        Text("Real objects become monsters. Fight them together. No internet, no server: Gemma 4 runs on the host's phone.", color = Dungeon.parchment.copy(alpha = 0.85f))
        vm.characterSummary()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFD54F)) }
        OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            gemmaReady -> "🧠 Dungeon master $gemmaStatus"
                            gemmaLoading -> "🧠 $gemmaStatus"
                            modelPresent -> "🧠 ready to load"
                            else -> "🧠 no model on this phone yet"
                        }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                    )
                    TextButton(onModel) { Text("Change") }
                }
                Text(vm.modelInfo, style = MaterialTheme.typography.bodySmall, color = Dungeon.parchment.copy(alpha = 0.8f))
                if (gemmaLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        val radio = vm.radioProblems()
        if (radio != null) {
            val ctx = LocalContext.current
            Card { Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("📡 Multiplayer: $radio", color = Dungeon.ember, modifier = Modifier.weight(1f))
                TextButton({ runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) } }) { Text("Settings") }
            } }
        }
        Button({ vm.host(name) }, enabled = name.isNotBlank() && gemmaReady, modifier = Modifier.fillMaxWidth()) {
            Text(if (gemmaReady) "Host (this phone is the dungeon master)" else "Host (waiting for Gemma…)")
        }
        OutlinedButton({ vm.join(name) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Join a nearby host")
        }
        var howTo by remember { mutableStateOf(false) }
        TextButton({ howTo = !howTo }, modifier = Modifier.fillMaxWidth()) { Text(if (howTo) "Hide how to play" else "❓ How to play") }
        if (howTo) Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("1. Host. Walk around; things you pass may start following you.", style = MaterialTheme.typography.bodyMedium)
            Text("2. Aim the reticle at anything and Spawn. It becomes the monster, in its own voice.", style = MaterialTheme.typography.bodyMedium)
            Text("3. Turn to face it. Shout your move, type it, or swing the phone. Wit hits harder than force.", style = MaterialTheme.typography.bodyMedium)
            Text("4. Twist to parry. Potions heal. Run if you must. Loot, level, unlock Taunt, Parley, Bind, Recast.", style = MaterialTheme.typography.bodyMedium)
            Text("5. Meet an object instead to trade, heal or get a tip. Friends join over Bluetooth, no internet.", style = MaterialTheme.typography.bodyMedium)
        } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onStats) { Text("📊 Stats") }
            TextButton(onJournal) { Text("📖 Journal") }
            TextButton(onModel) { Text("🧠 Brain") }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun PlayScreen(vm: GameSession, onStats: () -> Unit, onJournal: () -> Unit, onModel: () -> Unit) {
    val textScale by vm.textScale.collectAsState()
    val state by vm.state.collectAsState()
    val role by vm.role.collectAsState()
    val status by vm.status.collectAsState()
    val heading by vm.compass.heading.collectAsState()
    val steps by vm.steps.sinceReset.collectAsState()
    val recording by vm.recording.collectAsState()
    val envNow by vm.env.summary.collectAsState()
    val latencyNow by vm.latency.collectAsState()
    val lastPing by vm.lastPingFromHost.collectAsState()
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var action by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    val narrate by vm.narrateAloud.collectAsState()
    val debug by vm.debugHud.collectAsState()
    var confirmLeave by remember { mutableStateOf(false) }
    var showBag by remember { mutableStateOf(false) }
    var showQuests by remember { mutableStateOf(false) }

    BackHandler { confirmLeave = true }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave the raid?") },
            text = { Text(if (role == GameSession.Role.HOST) "Your party will be disconnected. Gemma stays loaded." else "You'll drop out of the host's party.") },
            confirmButton = { Button({ confirmLeave = false; vm.leave() }) { Text("Leave") } },
            dismissButton = { TextButton({ confirmLeave = false }) { Text("Stay") } },
        )
    }

    if (showQuests) {
        AlertDialog(
            onDismissRequest = { showQuests = false },
            title = { Text("📜 Quests · ${state.questsDone} done") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.quests.isEmpty()) Text("The host's quest board is empty.")
                    state.quests.forEach { q ->
                        Column {
                            Text("${if (q.done) "✅" else "▸"} ${q.title}", fontWeight = FontWeight.SemiBold)
                            LinearProgressIndicator(progress = { q.progress.toFloat() / q.target }, modifier = Modifier.fillMaxWidth().height(5.dp))
                            Text("${q.progress}/${q.target} · reward ${q.rewardXp} XP + a potion", fontSize = 12.sp)
                        }
                    }
                    Text("New quests replace finished ones. Progress comes from real events: kills, buys, parries, steps, ambushes.", fontSize = 11.sp, color = Dungeon.parchment.copy(alpha = 0.6f))
                }
            },
            confirmButton = { TextButton({ showQuests = false }) { Text("Close") } },
        )
    }
    if (showBag) {
        val me = state.players[vm.selfId]
        AlertDialog(
            onDismissRequest = { showBag = false },
            title = { Text("🎒 ${me?.name ?: "Your"} bag") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (me != null) {
                        Text("Level ${me.level}${if (me.epithet.isNotBlank()) " · ${me.epithet}" else ""} · ${me.xp} XP · ${me.hp}/${me.maxHp} HP · 🧪 ${me.potions}", fontWeight = FontWeight.Bold)
                        Text("Items (${me.items.size}/$BAG_SLOTS slots). Use one by saying its name in a fight: ×1.5 damage.", fontSize = 12.sp)
                        if (me.items.isEmpty()) Text("Empty. Loot monsters, charm NPCs, or buy with XP.", fontSize = 13.sp)
                        me.items.forEach { it ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text("🎁 ${it.name}", fontWeight = FontWeight.SemiBold); if (it.effect.isNotBlank()) Text(it.effect, fontSize = 12.sp) }
                                if (role == GameSession.Role.HOST) TextButton({ vm.dropItem(it.name) }) { Text("drop") }
                            }
                        }
                        if (me.companions.isNotEmpty()) {
                            Text("Companions", fontWeight = FontWeight.Bold)
                            me.companions.forEach { Text("${it.emoji} ${it.name} — ${it.attack} every turn") }
                        }
                        val powers = powersFor(me.level)
                        Text("Powers", fontWeight = FontWeight.Bold)
                        if (powers.isEmpty()) Text("None yet. Level 2 unlocks Taunt.", fontSize = 13.sp)
                        powers.forEach { pw -> Text("• ${powerUnlockedAt(listOf("Taunt", "Parley", "Bind", "Recast").indexOf(pw) + 2) ?: pw}", fontSize = 13.sp) }
                        powerUnlockedAt(me.level + 1)?.let { Text("Next level: $it", fontSize = 12.sp, color = Color(0xFFFFD54F)) }
                    }
                }
            },
            confirmButton = { TextButton({ showBag = false }) { Text("Close") } },
        )
    }

    DisposableEffect(Unit) { vm.startSensors(); onDispose { vm.stopSensors() } }
    if (role == GameSession.Role.CLIENT) {
        LaunchedEffect(Unit) { while (true) { vm.sendPose(); delay(2000) } }
    }

    val self = state.players[vm.selfId]
    val monster = state.monster
    val isHost = role == GameSession.Role.HOST
    val spriteVersion by vm.spriteVersion.collectAsState()

    // Host: a monster you turn your back on for 4 s comes around to face you.
    if (isHost) {
        LaunchedEffect(state.phase, monster?.bearingDeg) {
            if (state.phase != Phase.BATTLE || monster == null) return@LaunchedEffect
            var awaySince = 0L
            while (true) {
                delay(500)
                val d = abs(headingDelta(vm.compass.heading.value, vm.bearingToMonster(monster, vm.state.value.players[vm.selfId])))
                val now = System.currentTimeMillis()
                if (d > 60f) { if (awaySince == 0L) awaySince = now; if (now - awaySince > 4000 && !vm.state.value.busy) { vm.tryEscape(fromChase = true); return@LaunchedEffect } }
                else awaySince = 0L
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(Modifier.fillMaxSize()) { imageCapture = it; vm.camera = it }
        val danger = state.phase == Phase.BATTLE && self != null && self.hp > 0 && self.hp * 100 / maxOf(1, self.maxHp) <= 30
        DungeonVignette(intensity = if (state.phase == Phase.BATTLE) 1f else 0.8f, danger = danger)
        if (isHost && state.phase == Phase.EXPLORE) Reticle()
        // long-press anywhere: re-pin the monster to where you're looking (compass drift fix)
        if (isHost && (state.phase == Phase.BATTLE || state.phase == Phase.NPC)) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onLongPress = { vm.reanchorMonster(chase = false) }) })
        }

        val shake = remember { Animatable(0f) }
        LaunchedEffect(state.fx?.seq) { val f = state.fx ?: return@LaunchedEffect; if (f.crit || (f.taken >= 6)) { repeat(5) { shake.animateTo(if (it % 2 == 0) 14f else -14f, tween(40)) }; shake.animateTo(0f, tween(40)) } }
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = shake.value; translationY = shake.value * 0.6f }) {
            if (monster != null && (state.phase == Phase.BATTLE || state.phase == Phase.NPC || (state.phase == Phase.EXPLORE && state.busy))) {
                MonsterOverlay(monster, delta = headingDelta(heading, vm.bearingToMonster(monster, self)), fx = state.fx, spriteVersion = spriteVersion, allies = self?.companions ?: emptyList())
            }
        }
        Splashes(vm, state, spriteVersion)
        FxLayer(state.fx, vm.selfId)

        // top HUD: party, then a status strip
        Column(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.players.values.forEach { p ->
                    Column(Modifier.background(Dungeon.stone.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).border(1.dp, Dungeon.gold.copy(alpha = if (p.id == vm.selfId) 0.8f else 0.35f), RoundedCornerShape(8.dp)).pointerInput(p.id) { detectTapGestures { if (p.id == vm.selfId) showBag = true } }.padding(6.dp)) {
                        Text(
                            "${p.name}${if (p.epithet.isNotBlank()) " ${p.epithet}" else ""} · L${p.level}",
                            color = if (p.hp > 0) Color.White else Color.Red,
                            fontWeight = if (p.id == vm.selfId) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp,
                        )
                        LinearProgressIndicator(progress = { p.hp.toFloat() / p.maxHp }, modifier = Modifier.width(120.dp).height(5.dp), color = hpColor(p.hp, p.maxHp))
                        Text("${p.hp}/${p.maxHp} · ${p.xp} xp · 🧪${p.potions}${if (p.companions.isNotEmpty()) " · " + p.companions.joinToString("") { it.emoji } else ""}", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
                        if (p.items.isNotEmpty()) Text("🎁 " + p.items.takeLast(3).joinToString(" · ") { it.name }, color = Color(0xFFFFD54F), fontSize = 11.sp)
                    }
                }
            }
            if (debug) Text(
                buildString {
                    append("${heading.toInt()}° · ${if (isHost) "host" else "player"} · kills ${state.kills} · $steps steps")
                    val e = if (isHost) envNow else state.env
                    if (e.isNotBlank()) append(" · $e")
                    if (status.isNotBlank()) append(" · $status")
                    if (vm.selfId in state.parries) append(" · 🛡 guard up")
                    state.bossLair?.let { append(" · 👑 lair of ${it.name}") }
                },
                color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(6.dp)).padding(4.dp),
            ) else {
                val chips = buildList {
                    if (vm.selfId in state.parries) add("🛡 guard up")
                    if (state.monsterDazed && state.phase == Phase.BATTLE) add("😵 dazed")
                    state.bossLair?.let { add("👑 lair of ${it.name}") }
                    if (state.phase == Phase.EXPLORE && isHost && steps > 0) add("👣 $steps")
                    if (state.lurking > 0 && state.phase == Phase.EXPLORE) add("👁 ${state.lurking} lurking")
                    state.quests.firstOrNull { !it.done }?.let { add("📜 ${it.title} (${it.progress}/${it.target})") }
                    if (state.dungeon.isNotBlank() && state.phase == Phase.EXPLORE) add("🏰 ${state.dungeon}")
                    val lat = latencyNow
                    if (isHost && lat.isNotEmpty()) add("📶 ${lat.size} player${if (lat.size > 1) "s" else ""} · ${lat.values.average().toInt()} ms")
                    if (!isHost && lastPing > 0) add(if (System.currentTimeMillis() - lastPing > 15000) "📶 host silent" else "📶 host ok")
                }
                if (chips.isNotEmpty()) Text(chips.joinToString(" · "), color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(6.dp)).padding(4.dp))
            }
        }

        // client with no host: say so loudly, with retry and leave
        if (role == GameSession.Role.CLIENT) {
            val peersNow by vm.peers.collectAsState()
            if (peersNow.isEmpty()) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Card(Modifier.padding(32.dp)) { Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (state.players.isEmpty()) "📡 Looking for a host…" else "📡 Lost the host", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(status.ifBlank { "Both phones need Bluetooth, Wi-Fi and Location on. The host taps Host first." }, fontSize = 13.sp, textAlign = TextAlign.Center)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ vm.rejoin() }) { Text("Search again") }
                        OutlinedButton({ vm.leave() }) { Text("Leave") }
                    }
                } }
            }
        }

        // top-right menu
        Box(Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp)) {
            OutlinedButton({ menuOpen = true }) { Text("☰") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("📜 Quests") }, onClick = { menuOpen = false; showQuests = true })
                DropdownMenuItem(text = { Text("🎒 Inventory") }, onClick = { menuOpen = false; showBag = true })
                DropdownMenuItem(text = { Text("📖 Journal") }, onClick = { menuOpen = false; onJournal() })
                DropdownMenuItem(text = { Text("🧠 Brain (model)") }, onClick = { menuOpen = false; onModel() })
                DropdownMenuItem(text = { Text("Aa Text size (${(textScale * 100).toInt()}%)") }, onClick = { vm.cycleTextScale() })
                DropdownMenuItem(text = { Text("📊 Stats") }, onClick = { menuOpen = false; onStats() })
                DropdownMenuItem(text = { Text(if (vm.sfx.muted) "🔈 Unmute" else "🔇 Mute") }, onClick = { vm.sfx.muted = !vm.sfx.muted; menuOpen = false })
                DropdownMenuItem(text = { Text(if (narrate) "🗣 Stop narrating aloud" else "🗣 Narrate aloud") }, onClick = { vm.narrateAloud.value = !narrate; menuOpen = false })
                DropdownMenuItem(text = { Text(if (debug) "🛠 Hide debug" else "🛠 Show debug") }, onClick = { vm.debugHud.value = !debug; menuOpen = false })
                if (isHost && state.phase == Phase.NPC) DropdownMenuItem(text = { Text("👋 Leave conversation") }, onClick = { menuOpen = false; vm.leaveNpc() })
                if (isHost && state.phase == Phase.BATTLE) DropdownMenuItem(text = { Text("🏳 Abandon fight, regroup") }, onClick = { menuOpen = false; vm.nextEncounter() })
                DropdownMenuItem(text = { Text("🚪 Leave raid") }, onClick = { menuOpen = false; confirmLeave = true })
            }
        }

        // bottom: log + controls
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Dungeon.stone.copy(alpha = 0.82f)).safeDrawingPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(Dungeon.gold.copy(alpha = 0.5f)))
            val logState = rememberScrollState()
            LaunchedEffect(state.logTotal) { logState.scrollTo(logState.maxValue) }
            Column(
                Modifier.fillMaxWidth().heightIn(max = (150 * textScale).dp).verticalScroll(logState)
                    .pointerInput(Unit) { detectTapGestures(onTap = { onJournal() }) },
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
            state.log.takeLast(12).forEach { line ->
                val tint = when {
                    line.startsWith("−") || line.contains("LEGENDARY") -> Dungeon.gold
                    line.startsWith("📜") || line.startsWith("🏷") -> Dungeon.parchment
                    line.startsWith("🧪") || line.startsWith("💚") -> Dungeon.moss
                    line.startsWith("⚡") || line.contains("falls") -> Dungeon.ember
                    else -> Color.White
                }
                Text(line, color = tint, fontSize = (14 * textScale).sp)
            }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (state.phase == Phase.BATTLE && state.windup > 0) {
                LinearProgressIndicator(progress = { state.windup / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp), color = if (state.windup > 70) Color(0xFFFF5252) else Color(0xFFFFA726))
            }
            val alive = (self?.hp ?: 1) > 0
            val canTalk = state.phase == Phase.BATTLE || state.phase == Phase.EXPLORE || state.phase == Phase.NPC
            if (canTalk) {
                val chips = when (state.phase) {
                    Phase.NPC -> listOf("What's for sale?", "Heal me", "Tell me a secret", "Anything for free?")
                    Phase.EXPLORE -> listOf("Any tips?", "What is this place?", "Who are you?")
                    else -> (if (isHost) listOf("🏃 Run!") else emptyList()) + powersFor(self?.level ?: 1).map { when (it) { "Recast" -> "I Recast its weakness to…"; "Parley" -> "I Parley: hear me out!"; "Bind" -> "I Bind it!"; else -> "I Taunt it: come at me!" } }
                }
                if (chips.isNotEmpty() && !state.busy) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        chips.forEach { c ->
                            OutlinedButton({ if (c == "🏃 Run!") vm.tryEscape(fromChase = false) else if (c.endsWith("…")) action = c.dropLast(1) + " " else vm.act(c) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                                Text(c, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ vm.drinkPotion() }, enabled = (self?.potions ?: 0) > 0 && (self?.hp ?: 0) < (self?.maxHp ?: 1)) { Text("🧪${self?.potions ?: 0}") }
                    OutlinedTextField(
                        action, { action = it }, modifier = Modifier.weight(1f), singleLine = true,
                        placeholder = {
                            Text(
                                when {
                                    state.phase == Phase.NPC -> "Say something to ${monster?.name ?: "them"}…"
                                    state.phase == Phase.EXPLORE -> "Ask the dungeon master…"
                                    !alive -> "You're down — cheer from the floor"
                                    else -> "What do you do?"
                                }
                            )
                        },
                        enabled = alive || state.phase != Phase.BATTLE,
                    )
                    Button({ vm.act(action.trim()); action = "" }, enabled = !state.busy && action.isNotBlank() && (alive || state.phase != Phase.BATTLE)) {
                        Text(if (state.phase == Phase.BATTLE) "Act" else "Say")
                    }
                }
                Box(
                    Modifier.fillMaxWidth().height(52.dp)
                        .background(if (recording) Dungeon.blood else if (alive || state.phase != Phase.BATTLE) Dungeon.panel else Color.DarkGray, RoundedCornerShape(12.dp))
                        .let { if (!recording) it.background(Dungeon.gold.copy(alpha = 0.12f), RoundedCornerShape(12.dp)) else it }
                        .pointerInput(alive, state.busy, state.phase) {
                            if (!alive && state.phase == Phase.BATTLE) return@pointerInput
                            detectTapGestures(onPress = { vm.voiceStart(); tryAwaitRelease(); vm.voiceStop() })
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            recording -> "● listening… release to send"
                            state.phase == Phase.BATTLE -> "🎤 hold to shout your move"
                            state.phase == Phase.NPC -> "🎤 hold to talk"
                            else -> "🎤 hold to ask the dungeon master"
                        }, color = Color.White, fontWeight = FontWeight.Bold,
                    )
                }
            }
            when (state.phase) {
                Phase.BATTLE -> {
                    val powers = powersFor(self?.level ?: 1)
                    Text(
                        (if (isHost) "swing to strike · twist to parry · long-press to re-pin" else "swing to strike · twist to parry") +
                            (if (powers.isNotEmpty()) " · powers: ${powers.joinToString(", ")}" else ""),
                        color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                Phase.NPC -> if (isHost) OutlinedButton({ vm.leaveNpc() }, modifier = Modifier.fillMaxWidth()) { Text("👋 Leave ${monster?.name ?: ""}") }
                Phase.EXPLORE -> if (isHost) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ vm.scanOmens() }, enabled = !state.busy) { Text("📡") }
                        OutlinedButton({ imageCapture?.let { vm.meetFromCamera(it) } }, enabled = !state.busy && imageCapture != null) { Text("🤝 Meet") }
                        Button({ imageCapture?.let { vm.spawnFromCamera(it) } }, enabled = !state.busy && imageCapture != null, modifier = Modifier.weight(1f)) {
                            Text(
                                when {
                                    state.bossLair != null -> "👑 Spawn the BOSS"
                                    steps >= 30 -> "⚔ Spawn (+${minOf(steps / 4, 50)} HP)"
                                    else -> "⚔ Spawn"
                                }
                            )
                        }
                    }
                } else Text("Waiting for the host to find something…", color = Color.White)
                Phase.VICTORY, Phase.DEFEAT -> if (isHost) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.phase == Phase.VICTORY && self != null && companionCapacity(self.level) > self.companions.size) {
                            OutlinedButton({ vm.bindFallen() }) { Text("🐾 Bind it") }
                        }
                        Button({ vm.nextEncounter() }, modifier = Modifier.weight(1f)) {
                            Text(if (state.phase == Phase.VICTORY) "Explore for the next one" else "Regroup and explore")
                        }
                    }
                } else Text(if (state.phase == Phase.VICTORY) "Victory! Waiting for the host…" else "Wiped. Waiting for the host…", color = Color.White)
                else -> {}
            }
        }
    }
}

/**
 * Heading-locked augmentation. The photographed object, cut out by on-device segmentation, becomes the
 * monster: it dances in a lock-on ring, sidesteps when you miss, flies at the screen with perspective
 * when it strikes, and bursts when slain. Gemma's emoji features orbit it; taunts pop in a speech bubble.
 */
@Composable
private fun MonsterOverlay(m: Monster, delta: Float, fx: Fx?, spriteVersion: Int, allies: List<com.hocmemini.raid.game.Ally> = emptyList()) {
    val context = LocalContext.current
    val allyBitmaps = remember(allies.map { it.sprite }) { allies.map { a -> a.sprite.takeIf { it.isNotBlank() }?.let { runCatching { BitmapFactory.decodeFile(File(context.cacheDir, it).absolutePath)?.asImageBitmap() }.getOrNull() } } }
    val allyLunge = remember { Animatable(0f) }
    val sprite: ImageBitmap? = remember(m.sprite, spriteVersion) {
        if (m.sprite.isBlank()) null
        else runCatching { BitmapFactory.decodeFile(File(context.cacheDir, m.sprite).absolutePath)?.asImageBitmap() }.getOrNull()
    }
    val dance by rememberInfiniteTransition(label = "dance").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "d",
    )
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "p",
    )
    val shakeX = remember { Animatable(0f) }
    val hitFlash = remember { Animatable(0f) }
    val squash = remember { Animatable(0f) }       // 1 = squashed (hit)
    val fly = remember { Animatable(0f) }          // 0 = home, 1 = in your face
    val dodge = remember { Animatable(0f) }        // -1..1 sidestep
    val hop = remember { Animatable(0f) }
    val death = remember { Animatable(0f) }
    val bubble = remember { Animatable(0f) }
    val ringAlpha = remember { Animatable(1f) }
    var bubbleText by remember { mutableStateOf("") }
    val ringColor = if (m.friendly) Color(0xFF69F0AE) else Color(0xFFFF5252)

    LaunchedEffect(m.name) { ringAlpha.snapTo(1f); kotlinx.coroutines.delay(2500); ringAlpha.animateTo(0.22f, tween(1200)) }
    LaunchedEffect(fx?.seq) {
        val f = fx ?: return@LaunchedEffect
        if (f.taunt.isNotBlank() || f.say.isNotBlank()) {
            bubbleText = f.taunt.ifBlank { f.say }
            bubbleText = f.taunt
            launch { hop.animateTo(1f, tween(140)); hop.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
            launch {
                bubble.snapTo(0f); bubble.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                kotlinx.coroutines.delay(4500); bubble.animateTo(0f, tween(300))
            }
        }
        if (f.label == "spawn") { death.snapTo(1f); death.animateTo(0f, tween(650)); return@LaunchedEffect }
        if (f.label == "chase") {
            fly.snapTo(0.5f); fly.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)); return@LaunchedEffect
        }
        if (f.companionHits.isNotEmpty()) {
            launch { kotlinx.coroutines.delay(380); allyLunge.animateTo(1f, tween(160, easing = FastOutLinearInEasing)); allyLunge.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
        }
        if (f.dealt > 0) {
            launch { hitFlash.snapTo(1f); hitFlash.animateTo(0f, tween(420)) }
            launch { squash.animateTo(1f, tween(90)); squash.animateTo(0f, spring(dampingRatio = Spring.DampingRatioHighBouncy)) }
            repeat(4) { shakeX.animateTo(if (it % 2 == 0) 26f else -26f, tween(45)) }
            shakeX.animateTo(0f, tween(45))
        } else if (f.playerId.isNotBlank() && f.potion == 0 && !f.killed) {
            // a miss: it sidesteps
            val side = if (f.seq % 2 == 0) 1f else -1f
            dodge.animateTo(side, tween(150, easing = FastOutLinearInEasing))
            kotlinx.coroutines.delay(120)
            dodge.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
        if (f.killed) { death.animateTo(1f, tween(750)); return@LaunchedEffect }
        if (f.taken > 0 || f.parried) {
            // perspective strike: accelerate into the screen, hold, snap back
            fly.animateTo(1f, tween(400, easing = CubicBezierEasing(0.75f, 0f, 1f, 1f)))
            kotlinx.coroutines.delay(140)
            fly.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val halfW = maxWidth / 2
        val ringDp = maxWidth * 0.46f
        val density = LocalDensity.current
        val ringPx = with(density) { ringDp.toPx() }
        val screenHpx = with(density) { maxHeight.toPx() }
        if (abs(delta) <= 45f) {
            val xOff = halfW * (delta / 45f)
            // lock-on ring + orbiting features (stay put while the sprite flies)
            Box(
                Modifier.align(Alignment.Center).offset(x = xOff).size(ringDp * 1.9f).graphicsLayer {
                    translationX = shakeX.value
                    alpha = (1f - death.value) * (1f - fly.value * 0.7f)
                    scaleX = 1f + death.value * 0.8f; scaleY = 1f + death.value * 0.8f
                },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(ringDp)) {
                    val r = size.minDimension / 2
                    // soft ground shadow under the creature
                    drawOval(Color.Black.copy(alpha = 0.35f * (1f - fly.value)), topLeft = Offset(center.x - r * 0.55f, center.y + r * 0.62f), size = androidx.compose.ui.geometry.Size(r * 1.1f, r * 0.22f))
                    drawCircle(
                        color = ringColor.copy(alpha = 0.9f * ringAlpha.value), radius = r * 0.98f,
                        style = Stroke(width = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(40f, 24f), phase = pulse * 64f)),
                    )
                    if (hitFlash.value > 0f) drawCircle(Color.White.copy(alpha = hitFlash.value), radius = r * (1.0f + (1f - hitFlash.value) * 0.4f), style = Stroke(width = 14f * hitFlash.value + 2f))
                    if (ringAlpha.value > 0.5f) drawCircle(ringColor.copy(alpha = (1f - pulse) * 0.5f * ringAlpha.value), radius = r * (1f + pulse * 0.35f), style = Stroke(width = 3f))
                }
                val feats = m.accessories
                val wob = sin(dance * 2f * Math.PI.toFloat())
                feats.forEachIndexed { i, e ->
                    val ang = when (i) { 0 -> -90.0; 1 -> 20.0; else -> 200.0 }
                    val rad = Math.toRadians(ang)
                    val px = (cos(rad) * ringPx * 0.62f).toFloat()
                    val py = (sin(rad) * ringPx * 0.62f).toFloat()
                    Text(e, fontSize = if (i == 0) 60.sp else 52.sp, modifier = Modifier.graphicsLayer {
                        translationX = px + wob * 6f * (i + 1); translationY = py + wob * 5f; rotationZ = wob * 8f
                    })
                }
            }
            // the object itself, as the monster
            Box(
                Modifier.align(Alignment.Center).offset(x = xOff).size(ringDp * 0.92f).graphicsLayer {
                    val d = death.value
                    val fl = fly.value
                    val bob = sin(dance * 2f * Math.PI.toFloat())
                    val wig = sin(dance * 4f * Math.PI.toFloat())
                    val sq = squash.value
                    val s = (1f + fl * 5.5f) * (1f - d)
                    scaleX = s * (1f + 0.05f * wig + 0.25f * sq)
                    scaleY = s * (1f - 0.05f * wig - 0.2f * sq)
                    translationX = shakeX.value + dodge.value * ringPx * 0.7f
                    translationY = bob * 10f - hop.value * 60f + fl * screenHpx * 0.22f + sq * 18f
                    rotationZ = bob * 5f - dodge.value * 18f + d * 540f + fl * 4f
                    rotationX = -fl * 22f
                    cameraDistance = 10f * density.density
                    alpha = 1f - d
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                },
                contentAlignment = Alignment.Center,
            ) {
                if (sprite != null) {
                    // ghost trail while flying
                    if (fly.value > 0.1f) {
                        Image(sprite, null, Modifier.fillMaxSize().graphicsLayer { scaleX = 0.8f; scaleY = 0.8f; alpha = 0.35f * fly.value }, contentScale = ContentScale.Fit)
                        Image(sprite, null, Modifier.fillMaxSize().graphicsLayer { scaleX = 0.9f; scaleY = 0.9f; alpha = 0.5f * fly.value }, contentScale = ContentScale.Fit)
                    }
                    Image(
                        sprite, contentDescription = m.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                        colorFilter = if (hitFlash.value > 0f) ColorFilter.tint(Color.White.copy(alpha = hitFlash.value * 0.9f), BlendMode.SrcAtop) else null,
                    )
                } else {
                    Text(m.emoji, fontSize = 110.sp)
                }
                if (death.value > 0.05f && fx?.killed == true) Text("💥", fontSize = (40 + 100 * death.value).sp, modifier = Modifier.graphicsLayer { alpha = 1f - death.value })
            }
            // companions: small cut-outs to the lower-left of the ring, lunging in when they strike
            allies.forEachIndexed { i, a ->
                val bmpA = allyBitmaps.getOrNull(i)
                Box(
                    Modifier.align(Alignment.Center).offset(x = xOff - ringDp * 0.78f, y = ringDp * (0.35f + i * 0.42f)).size(ringDp * 0.36f)
                        .graphicsLayer {
                            val bob = sin((dance + i * 0.3f) * 2f * Math.PI.toFloat())
                            translationX = allyLunge.value * ringPx * 0.5f
                            translationY = bob * 6f
                            rotationZ = bob * 4f + allyLunge.value * 15f
                            scaleX = 1f + allyLunge.value * 0.3f; scaleY = 1f + allyLunge.value * 0.3f
                            alpha = 1f - death.value
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmpA != null) Image(bmpA, a.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) else Text(a.emoji, fontSize = 44.sp)
                    Text(a.name, color = Color.White, fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
                }
            }
            // compact name chip above the ring
            Column(
                Modifier.align(Alignment.Center).offset(x = xOff, y = -(ringDp * 0.82f)).graphicsLayer { alpha = (1f - death.value) * (1f - fly.value) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (m.name != "???") Card { Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${m.emoji} ${m.name}", fontSize = 17.sp, fontWeight = FontWeight.Black)
                    if (m.friendly) Text("${m.role} · ${m.offer}", fontSize = 11.sp)
                    else {
                        LinearProgressIndicator(progress = { m.hp.toFloat() / m.maxHp }, modifier = Modifier.width(200.dp).height(7.dp), color = hpColor(m.hp, m.maxHp))
                        Text("${if (m.hp * 100 / maxOf(1, m.maxHp) <= 20) "💀 " else ""}${m.hp}/${m.maxHp} · weak to ${m.weakness}", fontSize = 11.sp)
                    }
                } }
            }
            // speech bubble, upper-right of the ring
            if (bubble.value > 0.01f && bubbleText.isNotBlank()) {
                Box(
                    Modifier.align(Alignment.Center).offset(x = xOff + ringDp * 0.55f, y = -(ringDp * 0.45f))
                        .graphicsLayer { scaleX = bubble.value; scaleY = bubble.value; transformOrigin = TransformOrigin(0f, 1f) },
                ) {
                    SpeechBubble(bubbleText)
                }
            }
        } else {
            Text(
                if (delta > 0) "${m.emoji}  turn right  ▶" else "◀  turn left  ${m.emoji}",
                color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(12.dp),
            )
        }
    }
}

@Composable
private fun SpeechBubble(text: String) {
    Box {
        Canvas(Modifier.matchParentSize()) {
            val tail = Path().apply {
                moveTo(18f, size.height - 2f); lineTo(-22f, size.height + 34f); lineTo(60f, size.height - 2f); close()
            }
            drawPath(tail, Color.White)
        }
        Text(
            text, color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 220.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** Screen-wide effects: slash sweep, floating damage number, red hit flash, banners for crits, parries, potions, kills. */
@Composable
private fun FxLayer(fx: Fx?, selfId: String) {
    val red = remember { Animatable(0f) }
    val numY = remember { Animatable(0f) }
    val numA = remember { Animatable(0f) }
    val banner = remember { Animatable(0f) }
    val slashX = remember { Animatable(-2f) }

    LaunchedEffect(fx?.seq) {
        val f = fx ?: return@LaunchedEffect
        if (f.dealt > 0) {
            launch { slashX.snapTo(-1.2f); slashX.animateTo(1.2f, tween(380, easing = LinearEasing)); slashX.snapTo(-2f) }
            launch { numA.snapTo(1f); numY.snapTo(0f); numY.animateTo(-300f, tween(950)); numA.animateTo(0f, tween(250)) }
        }
        if (f.parried || f.killed || f.potion > 0 || f.item.isNotBlank() || f.label == "quest" || f.label.isNotBlank() && f.label != "spawn" && f.label != "say" && f.label != "stir" && f.label != "omens" && f.label != "chase" && f.label != "pin") {
            launch { banner.snapTo(1f); kotlinx.coroutines.delay(1300); banner.animateTo(0f, tween(450)) }
        }
        if (f.taken > 0 && f.playerId == selfId) {
            kotlinx.coroutines.delay(260)
            red.snapTo(0.55f); red.animateTo(0f, tween(650))
        }
    }

    val bannerText = if (fx == null) "" else buildList {
        if (fx.killed) add("💥 SLAIN")
        if (fx.parried) add("🛡 PARRIED")
        if (fx.label == "LEGENDARY!") add("★ LEGENDARY ★")
        if (fx.label == "stylish") add("★ stylish")
        if (fx.label.startsWith("🧪")) add(fx.label)
        if (fx.potion > 0) add("🧪 +${fx.potion}")
        if (fx.item.isNotBlank()) add("🎁 ${fx.item}")
        if (fx.label == "quest") add("📜 QUEST COMPLETE\n${fx.say}")
    }.joinToString("\n")

    Box(Modifier.fillMaxSize()) {
        if (red.value > 0f) Box(Modifier.fillMaxSize().background(Color.Red.copy(alpha = red.value)))
        if (slashX.value > -1.5f) {
            Text("⚔️", fontSize = 84.sp, modifier = Modifier.align(Alignment.Center).graphicsLayer {
                translationX = slashX.value * 650f; rotationZ = slashX.value * 70f
            })
        }
        if (numA.value > 0f && fx != null && fx.dealt > 0) {
            Text(
                "−${fx.dealt}", color = if (fx.crit) Color(0xFFFFD54F) else Color.White, fontSize = 52.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.Center).graphicsLayer { translationY = numY.value - 230f; alpha = numA.value },
            )
        }
        if (banner.value > 0f && bannerText.isNotBlank()) {
            Text(
                bannerText, color = Color(0xFFFFD54F), fontSize = 34.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 170.dp)
                    .graphicsLayer { alpha = banner.value; scaleX = 0.7f + 0.3f * banner.value; scaleY = 0.7f + 0.3f * banner.value }
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

/** Aiming reticle for Explore: whatever sits under it becomes the monster or the NPC. */
@Composable
private fun Reticle() {
    val pulse by rememberInfiniteTransition(label = "ret").animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "r")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(140.dp)) {
            val r = size.minDimension / 2
            val gold = Color(0xFFD4AF37)
            drawCircle(gold.copy(alpha = 0.85f), radius = r * 0.55f, style = Stroke(width = 3f))
            drawCircle(gold.copy(alpha = (1f - pulse) * 0.5f), radius = r * (0.55f + pulse * 0.4f), style = Stroke(width = 2f))
            val g = r * 0.12f
            listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f).forEach { (dx, dy) ->
                drawLine(gold, Offset(center.x + dx * r * 0.62f, center.y + dy * r * 0.62f), Offset(center.x + dx * (r * 0.62f + g * 2), center.y + dy * (r * 0.62f + g * 2)), strokeWidth = 3f)
            }
        }
        Text("aim at the thing", color = Color(0xFFD4AF37).copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.offset(y = 90.dp))
    }
}

/** Full-screen moments: encounter intro (tap to skip), victory with XP and loot, defeat. */
@Composable
private fun Splashes(vm: GameSession, state: com.hocmemini.raid.game.GameState, spriteVersion: Int) {
    val context = LocalContext.current
    val isHost = vm.role.collectAsState().value == GameSession.Role.HOST
    val self = state.players[vm.selfId]
    var introSeq by remember { mutableStateOf(-1) }
    var introVisible by remember { mutableStateOf(false) }
    var victoryDismissed by remember { mutableStateOf(false) }
    var defeatDismissed by remember { mutableStateOf(false) }
    val m = state.monster

    // intro: whenever a hostile spawn Fx arrives
    LaunchedEffect(state.fx?.seq) {
        val f = state.fx ?: return@LaunchedEffect
        if (f.label == "spawn" && m != null && !m.friendly && f.seq != introSeq) {
            introSeq = f.seq; introVisible = true
            delay(3200); introVisible = false
        }
    }
    LaunchedEffect(state.phase) { if (state.phase != Phase.VICTORY) victoryDismissed = false; if (state.phase != Phase.DEFEAT) defeatDismissed = false }

    val sprite: ImageBitmap? = remember(m?.sprite, spriteVersion) {
        m?.sprite?.takeIf { it.isNotBlank() }?.let { runCatching { BitmapFactory.decodeFile(File(context.cacheDir, it).absolutePath)?.asImageBitmap() }.getOrNull() }
    }
    val scale = remember { Animatable(0.6f) }
    LaunchedEffect(introVisible, state.phase) { scale.snapTo(0.6f); scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }

    when {
        introVisible && m != null -> Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xEE0A0603), Color(0x99140A00), Color(0xEE0A0603))))
                .pointerInput(Unit) { detectTapGestures { introVisible = false } },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(24.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }) {
                Text(if (state.ambush) "⚡ AMBUSH" else if (m.name.startsWith("👑")) "A BOSS APPEARS" else "A FOE APPEARS", color = Dungeon.ember, style = MaterialTheme.typography.titleMedium, letterSpacing = 3.sp)
                if (state.ambush) Text("it followed you from back there", color = Dungeon.parchment.copy(alpha = 0.7f), fontSize = 12.sp)
                else if (state.dungeon.isNotBlank()) Text(state.dungeon, color = Dungeon.parchment.copy(alpha = 0.7f), fontSize = 12.sp)
                if (sprite != null) Image(sprite, m.name, Modifier.size(240.dp), contentScale = ContentScale.Fit) else Text(m.emoji, fontSize = 140.sp)
                Text(m.name, color = Dungeon.gold, style = MaterialTheme.typography.displayLarge, fontSize = 34.sp, textAlign = TextAlign.Center)
                Text(m.flavor, color = Dungeon.parchment, textAlign = TextAlign.Center, fontSize = 15.sp)
                Text("${m.hp} HP · weak to ${m.weakness}", color = Dungeon.moss, fontWeight = FontWeight.Bold)
                Text(m.attacks.joinToString(" · "), color = Dungeon.parchment.copy(alpha = 0.7f), fontSize = 12.sp)
                Text("tap to fight", color = Dungeon.parchment.copy(alpha = 0.5f), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        state.phase == Phase.VICTORY && !victoryDismissed && state.victory != null -> {
            val v = state.victory
            Box(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xEE0A0603), Color(0x880A0603), Color(0xEE0A0603))))
                    .pointerInput(Unit) { detectTapGestures { victoryDismissed = true } },
                contentAlignment = Alignment.Center,
            ) {
                Confetti(key = v.name)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(24.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }) {
                    Text("💥 SLAIN", color = Dungeon.gold, style = MaterialTheme.typography.displayLarge, fontSize = 40.sp)
                    Text("${v.emoji} ${v.name}", color = Dungeon.parchment, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Text("felled by ${v.by} in ${v.turns} turn${if (v.turns == 1) "" else "s"}", color = Dungeon.parchment.copy(alpha = 0.75f), fontSize = 13.sp)
                    Text("+${v.xp} XP", color = Dungeon.moss, fontWeight = FontWeight.Black, fontSize = 28.sp)
                    Card(Modifier.widthIn(max = 300.dp)) { Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (v.loot.isBlank()) { Text("🎁 …", fontWeight = FontWeight.Bold); Text("the DM rummages through the remains", fontSize = 12.sp) }
                        else { Text("🎁 ${v.loot}", fontWeight = FontWeight.Bold); Text(v.lootEffect, fontSize = 12.sp, textAlign = TextAlign.Center) }
                        Text("🧪 +1 potion for everyone standing", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    } }
                    if (isHost) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (self != null && companionCapacity(self.level) > self.companions.size) Button({ vm.bindFallen(); victoryDismissed = true }) { Text("🐾 Bind it") }
                        Button({ vm.nextEncounter() }) { Text("Explore on") }
                        OutlinedButton({ victoryDismissed = true }) { Text("Linger") }
                    } else Text("tap to continue", color = Dungeon.parchment.copy(alpha = 0.5f), fontSize = 11.sp)
                    TextButton({ vm.saveTrophy() }) { Text("🏆 Save trophy card to Photos") }
                }
            }
        }
        state.phase == Phase.DEFEAT && !defeatDismissed -> Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xF0140000), Color(0xAA3A0000), Color(0xF0140000))))
                .pointerInput(Unit) { detectTapGestures { defeatDismissed = true } },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(24.dp)) {
                Text("☠ THE PARTY FALLS", color = Dungeon.ember, style = MaterialTheme.typography.displayLarge, fontSize = 32.sp, textAlign = TextAlign.Center)
                if (m != null) Text("${m.emoji} ${m.name} gloats over you.", color = Dungeon.parchment, textAlign = TextAlign.Center)
                Text("Your character keeps its XP, items and companions.", color = Dungeon.parchment.copy(alpha = 0.7f), fontSize = 12.sp, textAlign = TextAlign.Center)
                if (isHost) Button({ vm.nextEncounter() }) { Text("Regroup at half health") } else Text("waiting for the host…", color = Dungeon.parchment.copy(alpha = 0.5f), fontSize = 11.sp)
            }
        }
    }
}

private fun hpColor(hp: Int, max: Int): Color {
    val pct = hp * 100 / maxOf(1, max)
    return when { pct <= 20 -> Color(0xFFFF5252); pct <= 50 -> Color(0xFFFFA726); else -> Color(0xFF69F0AE) }
}

/** Emoji burst from the centre, once per victory. */
@Composable
private fun Confetti(key: String) {
    val t = remember(key) { Animatable(0f) }
    val parts = remember(key) { val r = java.util.Random(key.hashCode().toLong()); List(22) { Triple(r.nextFloat() * 6.283f, 0.5f + r.nextFloat(), listOf("✨", "⚔️", "💰", "🎉", "💥", "🏆")[r.nextInt(6)]) } }
    LaunchedEffect(key) { t.snapTo(0f); t.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(LocalDensity.current) { maxWidth.toPx() }
        parts.forEach { (ang, speed, e) ->
            Text(e, fontSize = 26.sp, modifier = Modifier.align(Alignment.Center).graphicsLayer {
                val d = t.value * speed * w * 0.55f
                translationX = cos(ang) * d; translationY = sin(ang) * d + t.value * t.value * 260f
                alpha = 1f - t.value * t.value; rotationZ = t.value * 360f * speed
            })
        }
    }
}
