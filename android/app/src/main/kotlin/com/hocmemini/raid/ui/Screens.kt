package com.hocmemini.raid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun RaidApp(vm: GameSession) {
    val role by vm.role.collectAsState()
    if (role == GameSession.Role.NONE) LobbyScreen(vm) else PlayScreen(vm)
}

@Composable
fun LobbyScreen(vm: GameSession) {
    var name by remember { mutableStateOf(vm.savedName) }
    val modelPresent by vm.modelPresent.collectAsState()
    val gemmaStatus by vm.gemmaStatus.collectAsState()
    val gemmaReady by vm.gemmaReady.collectAsState()
    val gemmaLoading by vm.gemmaLoading.collectAsState()
    val status by vm.status.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importModel) }

    // Hosting needs the model; start loading it on GPU as soon as the file is there.
    LaunchedEffect(modelPresent) { if (modelPresent) vm.loadModel(useGpu = true) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("RAID", fontSize = 40.sp, fontWeight = FontWeight.Black)
        Text("Real objects become monsters. Fight them together. No internet, no server: Gemma 4 runs on the host's phone.")
        OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Gemma 4 E2B · on-device", fontWeight = FontWeight.Bold)
                Text(if (modelPresent) "model file present" else "model file missing — pick the .litertlm you downloaded, or push it with adb")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ picker.launch(arrayOf("*/*")) }, enabled = !gemmaLoading) { Text("Pick file") }
                    Button({ vm.loadModel(true) }, enabled = modelPresent && !gemmaReady && !gemmaLoading) { Text("Load on GPU") }
                    TextButton({ vm.loadModel(false) }, enabled = modelPresent && !gemmaReady && !gemmaLoading) { Text("CPU") }
                }
                if (gemmaLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(gemmaStatus, style = MaterialTheme.typography.bodySmall)
            }
        }

        Button({ vm.host(name) }, enabled = name.isNotBlank() && gemmaReady, modifier = Modifier.fillMaxWidth()) {
            Text(if (gemmaReady) "Host (this phone is the dungeon master)" else "Host (waiting for Gemma…)")
        }
        OutlinedButton({ vm.join(name) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Join a nearby host")
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun PlayScreen(vm: GameSession) {
    val state by vm.state.collectAsState()
    val role by vm.role.collectAsState()
    val status by vm.status.collectAsState()
    val heading by vm.compass.heading.collectAsState()
    val steps by vm.steps.sinceReset.collectAsState()
    val recording by vm.recording.collectAsState()
    val envNow by vm.env.summary.collectAsState()
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var action by remember { mutableStateOf("") }

    DisposableEffect(Unit) { vm.startSensors(); onDispose { vm.stopSensors() } }
    if (role == GameSession.Role.CLIENT) {
        LaunchedEffect(Unit) { while (true) { vm.sendPose(); delay(2000) } }
    }

    val self = state.players[vm.selfId]
    val monster = state.monster
    val isHost = role == GameSession.Role.HOST

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(Modifier.fillMaxSize()) { imageCapture = it }

        if (monster != null && state.phase == Phase.BATTLE) {
            MonsterOverlay(monster, delta = headingDelta(heading, vm.bearingToMonster(monster, self)), fx = state.fx)
        }
        FxLayer(state.fx, vm.selfId)

        // top HUD: party, then a status strip
        Column(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.players.values.forEach { p ->
                    Column(Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(6.dp)) {
                        Text(
                            "${p.name} · L${p.level}",
                            color = if (p.hp > 0) Color.White else Color.Red,
                            fontWeight = if (p.id == vm.selfId) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp,
                        )
                        Text("${p.hp}/${p.maxHp} hp · ${p.xp} xp · 🧪${p.potions}", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                        if (p.items.isNotEmpty()) Text("🎁 " + p.items.takeLast(3).joinToString(" · "), color = Color(0xFFFFD54F), fontSize = 11.sp)
                    }
                }
            }
            Text(
                buildString {
                    append("${heading.toInt()}° · ${if (isHost) "host" else "player"} · kills ${state.kills}")
                    if (state.phase == Phase.EXPLORE && isHost) append(" · $steps steps")
                    val e = if (isHost) envNow else state.env
                    if (e.isNotBlank()) append(" · $e")
                    if (vm.selfId in state.parries) append(" · 🛡 guard up")
                    state.bossLair?.let { append(" · 👑 lair of ${it.name}") }
                    if (status.isNotBlank()) append(" · $status")
                },
                color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(6.dp)).padding(4.dp),
            )
        }

        // bottom: log + controls
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.65f)).safeDrawingPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.log.takeLast(5).forEach { Text(it, color = Color.White, fontSize = 14.sp) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (state.phase) {
                Phase.BATTLE -> {
                    val alive = (self?.hp ?: 1) > 0
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            { vm.drinkPotion() },
                            enabled = (self?.potions ?: 0) > 0 && (self?.hp ?: 0) < (self?.maxHp ?: 1),
                        ) { Text("🧪${self?.potions ?: 0}") }
                        OutlinedTextField(
                            action, { action = it }, modifier = Modifier.weight(1f), singleLine = true,
                            placeholder = { Text(if (alive) "What do you do?" else "You're down — cheer from the floor") }, enabled = alive,
                        )
                        Button({ vm.act(action.trim()); action = "" }, enabled = !state.busy && action.isNotBlank() && alive) { Text("Act") }
                    }
                    // Hold-to-talk: the clip goes straight into Gemma 4 as audio, no speech-to-text.
                    Box(
                        Modifier.fillMaxWidth().height(52.dp)
                            .background(if (recording) Color(0xFFD32F2F) else if (alive) Color(0xFF3949AB) else Color.DarkGray, RoundedCornerShape(12.dp))
                            .pointerInput(alive, state.busy) {
                                if (!alive) return@pointerInput
                                detectTapGestures(onPress = { vm.voiceStart(); tryAwaitRelease(); vm.voiceStop() })
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (recording) "● listening… release to attack" else "🎤 hold to shout your move", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Text("or swing the phone to strike · double-twist to parry", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                else -> if (isHost) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.phase == Phase.EXPLORE) {
                            OutlinedButton({ vm.scanOmens() }, enabled = !state.busy) { Text("📡 Omens") }
                            Button({ imageCapture?.let { vm.spawnFromCamera(it) } }, enabled = !state.busy && imageCapture != null, modifier = Modifier.weight(1f)) {
                                Text(
                                    when {
                                        state.bossLair != null -> "👑 Spawn the BOSS"
                                        steps >= 30 -> "Spawn (+${minOf(steps / 4, 50)} HP from $steps steps)"
                                        else -> "Point at something · Spawn"
                                    }
                                )
                            }
                        } else {
                            Button({ vm.nextEncounter() }, modifier = Modifier.weight(1f)) {
                                Text(if (state.phase == Phase.VICTORY) "Loot taken · Explore for the next one" else "Regroup and explore")
                            }
                        }
                    }
                } else {
                    Text(
                        when (state.phase) {
                            Phase.VICTORY -> "Victory! Waiting for the host to explore…"
                            Phase.DEFEAT -> "Wiped. Waiting for the host to regroup…"
                            else -> "Waiting for the host to find a monster…"
                        }, color = Color.White,
                    )
                }
            }
        }
    }
}

/** Heading-locked overlay: the monster slides across the screen as you turn toward its bearing, breathes, lunges and dies. */
@Composable
private fun MonsterOverlay(m: Monster, delta: Float, fx: Fx?) {
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(
        0.95f, 1.07f, infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s",
    )
    val scale = remember { Animatable(1f) }
    val lungeY = remember { Animatable(0f) }
    val shakeX = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(fx?.seq) {
        val f = fx ?: return@LaunchedEffect
        if (f.label == "spawn") {
            alpha.snapTo(0f); scale.snapTo(0.2f)
            launch { alpha.animateTo(1f, tween(500)) }
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
            return@LaunchedEffect
        }
        if (f.dealt > 0) {
            launch { flash.snapTo(0.85f); flash.animateTo(0f, tween(380)) }
            repeat(4) { shakeX.animateTo(if (it % 2 == 0) 30f else -30f, tween(45)) }
            shakeX.animateTo(0f, tween(45))
        }
        if (f.killed) {
            scale.animateTo(1.6f, tween(260))
            launch { alpha.animateTo(0f, tween(550)) }
            scale.animateTo(0.05f, tween(550))
            return@LaunchedEffect
        }
        if (f.taken > 0 || f.parried) {
            launch { lungeY.animateTo(420f, tween(220, easing = FastOutLinearInEasing)) }
            scale.animateTo(1.8f, tween(220, easing = FastOutLinearInEasing))
            kotlinx.coroutines.delay(140)
            launch { lungeY.animateTo(0f, tween(320)) }
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val halfW = maxWidth / 2
        if (abs(delta) <= 45f) {
            Column(
                Modifier.align(Alignment.Center).offset(x = halfW * (delta / 45f)).graphicsLayer {
                    scaleX = scale.value * breathe; scaleY = scale.value * breathe
                    translationX = shakeX.value; translationY = lungeY.value
                    this.alpha = alpha.value
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(Modifier.width(150.dp).height(150.dp).background(Color.Black.copy(alpha = 0.25f), CircleShape))
                    Text(m.emoji, fontSize = 110.sp)
                    if (flash.value > 0f) Box(Modifier.width(150.dp).height(150.dp).background(Color.White.copy(alpha = flash.value), CircleShape))
                }
                Card(Modifier.width(260.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(m.name, fontSize = 22.sp, fontWeight = FontWeight.Black)
                        LinearProgressIndicator(progress = { m.hp.toFloat() / m.maxHp }, modifier = Modifier.fillMaxWidth().height(10.dp))
                        Text("${m.hp} / ${m.maxHp} HP")
                        Text("Weak to: ${m.weakness}", fontWeight = FontWeight.SemiBold)
                        Text(m.attacks.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
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
        if (f.parried || f.killed || f.label.isNotBlank() && f.label != "spawn") {
            launch { banner.snapTo(1f); kotlinx.coroutines.delay(1300); banner.animateTo(0f, tween(450)) }
        }
        if (f.taken > 0 && f.playerId == selfId) {
            kotlinx.coroutines.delay(260)
            red.snapTo(0.55f); red.animateTo(0f, tween(650))
        }
    }

    val bannerText = when {
        fx == null -> ""
        fx.killed -> "💥 SLAIN"
        fx.parried -> "🛡 PARRIED"
        fx.label == "LEGENDARY!" -> "★ LEGENDARY ★"
        fx.label == "stylish" -> "★ stylish"
        fx.label.startsWith("🧪") -> fx.label
        else -> ""
    }

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
                bannerText, color = Color(0xFFFFD54F), fontSize = 40.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 170.dp)
                    .graphicsLayer { alpha = banner.value; scaleX = 0.7f + 0.3f * banner.value; scaleY = 0.7f + 0.3f * banner.value }
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}
