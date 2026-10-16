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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
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
    var showStats by remember { mutableStateOf(false) }
    if (showStats) {
        BackHandler { showStats = false }
        StatsScreen(vm) { showStats = false }
    } else if (role == GameSession.Role.NONE) LobbyScreen(vm) { showStats = true }
    else PlayScreen(vm) { showStats = true }
}

@Composable
fun LobbyScreen(vm: GameSession, onStats: () -> Unit) {
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
        TextButton(onStats, modifier = Modifier.fillMaxWidth()) { Text("📊 Stats") }
        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun PlayScreen(vm: GameSession, onStats: () -> Unit) {
    val state by vm.state.collectAsState()
    val role by vm.role.collectAsState()
    val status by vm.status.collectAsState()
    val heading by vm.compass.heading.collectAsState()
    val steps by vm.steps.sinceReset.collectAsState()
    val recording by vm.recording.collectAsState()
    val envNow by vm.env.summary.collectAsState()
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var action by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

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

        // top-right menu
        Box(Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp)) {
            OutlinedButton({ menuOpen = true }) { Text("☰") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("📊 Stats") }, onClick = { menuOpen = false; onStats() })
                if (isHost && state.phase == Phase.BATTLE) DropdownMenuItem(text = { Text("🏳 Abandon fight, regroup") }, onClick = { menuOpen = false; vm.nextEncounter() })
                DropdownMenuItem(text = { Text("🚪 Leave raid") }, onClick = { menuOpen = false; confirmLeave = true })
            }
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

/**
 * Heading-locked augmentation: a lock-on ring around the real object (centre of the frame at the spawn bearing),
 * Gemma's emoji features pinned around it, a weapon that swings at you, and a comic speech bubble for taunts.
 * The object itself stays visible; nothing opaque sits on it.
 */
@Composable
private fun MonsterOverlay(m: Monster, delta: Float, fx: Fx?) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "p",
    )
    val wobble by rememberInfiniteTransition(label = "wobble").animateFloat(
        -1f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "w",
    )
    val shakeX = remember { Animatable(0f) }
    val hitRing = remember { Animatable(0f) }      // white ring flash on hit
    val weapon = remember { Animatable(0f) }       // 0 = resting, 1 = thrust at the player
    val death = remember { Animatable(0f) }        // 0 = alive, 1 = gone
    val bubble = remember { Animatable(0f) }
    var bubbleText by remember { mutableStateOf("") }

    LaunchedEffect(fx?.seq) {
        val f = fx ?: return@LaunchedEffect
        if (f.taunt.isNotBlank()) {
            bubbleText = f.taunt
            launch {
                bubble.snapTo(0f); bubble.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                kotlinx.coroutines.delay(4500); bubble.animateTo(0f, tween(300))
            }
        }
        if (f.label == "spawn") { death.snapTo(1f); death.animateTo(0f, tween(600)); return@LaunchedEffect }
        if (f.dealt > 0) {
            launch { hitRing.snapTo(1f); hitRing.animateTo(0f, tween(450)) }
            repeat(4) { shakeX.animateTo(if (it % 2 == 0) 26f else -26f, tween(45)) }
            shakeX.animateTo(0f, tween(45))
        }
        if (f.killed) { death.animateTo(1f, tween(700)); return@LaunchedEffect }
        if (f.taken > 0 || f.parried) {
            weapon.animateTo(1f, tween(230, easing = FastOutLinearInEasing))
            kotlinx.coroutines.delay(150)
            weapon.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val halfW = maxWidth / 2
        val ringDp = maxWidth * 0.46f
        val density = LocalDensity.current
        if (abs(delta) <= 45f) {
            val xOff = halfW * (delta / 45f)
            // ring + features, centred on the object
            Box(
                Modifier.align(Alignment.Center).offset(x = xOff).size(ringDp * 1.9f).graphicsLayer {
                    translationX = shakeX.value
                    alpha = 1f - death.value
                    scaleX = 1f + death.value * 0.8f; scaleY = 1f + death.value * 0.8f
                },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(ringDp)) {
                    val r = size.minDimension / 2
                    // lock-on ring: dashed, rotating, red-orange
                    drawCircle(
                        color = Color(0xFFFF5252).copy(alpha = 0.9f), radius = r * 0.98f,
                        style = Stroke(width = 6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(40f, 24f), phase = pulse * 64f)),
                    )
                    // corner ticks
                    val t = r * 0.18f
                    listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).forEach { (sx, sy) ->
                        val cx = center.x + sx * r * 1.08f; val cy = center.y + sy * r * 1.08f
                        drawLine(Color.White, Offset(cx, cy), Offset(cx - sx * t, cy), strokeWidth = 5f)
                        drawLine(Color.White, Offset(cx, cy), Offset(cx, cy - sy * t), strokeWidth = 5f)
                    }
                    // hit flash ring
                    if (hitRing.value > 0f) drawCircle(Color.White.copy(alpha = hitRing.value), radius = r * (1.0f + (1f - hitRing.value) * 0.4f), style = Stroke(width = 14f * hitRing.value + 2f))
                    // pulse ripple
                    drawCircle(Color(0xFFFF5252).copy(alpha = (1f - pulse) * 0.5f), radius = r * (1f + pulse * 0.35f), style = Stroke(width = 3f))
                }
                // features pinned around the ring: eyes top, weapon right, third left
                val feats = m.accessories.ifEmpty { listOf(m.emoji) }
                val ringPx = with(density) { ringDp.toPx() }
                feats.forEachIndexed { i, e ->
                    val ang = when (i) { 0 -> -90.0; 1 -> 20.0; else -> 200.0 }
                    val rad = Math.toRadians(ang)
                    val px = (cos(rad) * ringPx * 0.62f).toFloat()
                    val py = (sin(rad) * ringPx * 0.62f).toFloat()
                    val isWeapon = i == 1 && feats.size > 1
                    Text(
                        e, fontSize = if (i == 0) 64.sp else 56.sp,
                        modifier = Modifier.graphicsLayer {
                            translationX = px + wobble * 6f * (i + 1)
                            translationY = py + (if (isWeapon) weapon.value * ringPx * 0.9f else wobble * 5f)
                            val sc = if (isWeapon) 1f + weapon.value * 1.6f else 1f
                            scaleX = sc; scaleY = sc
                            rotationZ = if (isWeapon) -25f + weapon.value * 60f else wobble * 8f
                        },
                    )
                }
                if (death.value > 0.05f) Text("💥", fontSize = (40 + 100 * death.value).sp, modifier = Modifier.graphicsLayer { alpha = 1f - death.value })
            }
            // compact name chip above the ring, so the object stays visible
            Column(
                Modifier.align(Alignment.Center).offset(x = xOff, y = -(ringDp * 0.78f)).graphicsLayer { alpha = 1f - death.value },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Card { Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${m.emoji} ${m.name}", fontSize = 17.sp, fontWeight = FontWeight.Black)
                    LinearProgressIndicator(progress = { m.hp.toFloat() / m.maxHp }, modifier = Modifier.width(200.dp).height(7.dp))
                    Text("${m.hp}/${m.maxHp} · weak to ${m.weakness}", fontSize = 11.sp)
                } }
            }
            // speech bubble, upper-right of the ring
            if (bubble.value > 0.01f && bubbleText.isNotBlank()) {
                Box(
                    Modifier.align(Alignment.Center).offset(x = xOff + ringDp * 0.55f, y = -(ringDp * 0.45f))
                        .graphicsLayer { scaleX = bubble.value; scaleY = bubble.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f) },
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
