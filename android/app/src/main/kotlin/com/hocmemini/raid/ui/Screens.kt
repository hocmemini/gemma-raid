package com.hocmemini.raid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hocmemini.raid.GameViewModel
import com.hocmemini.raid.camera.CameraPreview
import com.hocmemini.raid.game.Monster
import com.hocmemini.raid.game.Phase
import com.hocmemini.raid.game.headingDelta
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun RaidApp(vm: GameViewModel) {
    val role by vm.role.collectAsState()
    if (role == GameViewModel.Role.NONE) LobbyScreen(vm) else PlayScreen(vm)
}

@Composable
fun LobbyScreen(vm: GameViewModel) {
    var name by remember { mutableStateOf("") }
    val modelPresent by vm.modelPresent.collectAsState()
    val gemmaStatus by vm.gemmaStatus.collectAsState()
    val gemmaReady by vm.gemmaReady.collectAsState()
    val status by vm.status.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importModel) }

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
                    OutlinedButton({ picker.launch(arrayOf("*/*")) }) { Text("Pick file") }
                    Button({ vm.loadModel(true) }, enabled = modelPresent && !gemmaReady) { Text("Load on GPU") }
                    TextButton({ vm.loadModel(false) }, enabled = modelPresent && !gemmaReady) { Text("CPU") }
                }
                Text(gemmaStatus, style = MaterialTheme.typography.bodySmall)
            }
        }

        Button({ vm.host(name) }, enabled = name.isNotBlank() && gemmaReady, modifier = Modifier.fillMaxWidth()) {
            Text("Host (this phone is the dungeon master)")
        }
        OutlinedButton({ vm.join(name) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Join a nearby host")
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun PlayScreen(vm: GameViewModel) {
    val state by vm.state.collectAsState()
    val role by vm.role.collectAsState()
    val status by vm.status.collectAsState()
    val heading by vm.compass.heading.collectAsState()
    val recording by vm.recording.collectAsState()
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var action by remember { mutableStateOf("") }

    DisposableEffect(Unit) { vm.startSensors(); onDispose { vm.stopSensors() } }
    if (role == GameViewModel.Role.CLIENT) {
        LaunchedEffect(Unit) { while (true) { vm.sendPose(); delay(2000) } }
    }

    val self = state.players[vm.selfId]
    val monster = state.monster

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(Modifier.fillMaxSize()) { imageCapture = it }

        if (monster != null && state.phase == Phase.BATTLE) {
            MonsterOverlay(monster, delta = headingDelta(heading, vm.bearingToMonster(monster, self)))
        }

        // top HUD
        Column(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.players.values.forEach { p ->
                    Text(
                        "${p.name} ${p.hp}/${p.maxHp}",
                        color = if (p.hp > 0) Color.White else Color.Red,
                        fontWeight = if (p.id == vm.selfId) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.5f)).padding(6.dp),
                    )
                }
            }
            Text("${heading.toInt()}° · ${role.name.lowercase()} · $status", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp).background(Color.Black.copy(alpha = 0.4f)).padding(4.dp))
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
                        OutlinedTextField(
                            action, { action = it }, modifier = Modifier.weight(1f), singleLine = true,
                            placeholder = { Text("What do you do?") }, enabled = alive,
                        )
                        Button({ vm.act(action.trim()); action = "" }, enabled = !state.busy && action.isNotBlank() && alive) { Text("Act") }
                    }
                    // Hold-to-talk: the clip goes straight into Gemma 4 as audio, no speech-to-text.
                    Box(
                        Modifier.fillMaxWidth().height(52.dp)
                            .background(if (recording) Color(0xFFD32F2F) else Color(0xFF3949AB), RoundedCornerShape(12.dp))
                            .pointerInput(alive, state.busy) {
                                if (!alive) return@pointerInput
                                detectTapGestures(onPress = { vm.voiceStart(); tryAwaitRelease(); vm.voiceStop() })
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (recording) "● listening… release to attack" else "🎤 hold to shout your move", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                else -> if (role == GameViewModel.Role.HOST) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ imageCapture?.let { vm.spawnFromCamera(it) } }, enabled = !state.busy && imageCapture != null, modifier = Modifier.weight(1f)) {
                            Text(if (state.phase == Phase.EXPLORE) "Point at something · Spawn" else "Next encounter")
                        }
                        if (state.phase == Phase.VICTORY || state.phase == Phase.DEFEAT) {
                            OutlinedButton({ vm.nextEncounter() }) { Text("Regroup") }
                        }
                    }
                } else {
                    Text("Waiting for the host to find a monster…", color = Color.White)
                }
            }
        }
    }
}

/** Heading-locked overlay: the monster card slides across the screen as you turn toward its bearing. */
@Composable
private fun MonsterOverlay(m: Monster, delta: Float) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val halfW = maxWidth / 2
        if (abs(delta) <= 45f) {
            Card(Modifier.align(Alignment.Center).offset(x = halfW * (delta / 45f)).width(260.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(m.name, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    LinearProgressIndicator(progress = { m.hp.toFloat() / m.maxHp }, modifier = Modifier.fillMaxWidth().height(10.dp))
                    Text("${m.hp} / ${m.maxHp} HP")
                    Text("Weak to: ${m.weakness}", fontWeight = FontWeight.SemiBold)
                    Text(m.attacks.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Text(
                if (delta > 0) "turn right  ▶" else "◀  turn left",
                color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.5f)).padding(12.dp),
            )
        }
        Spacer(Modifier.height(0.dp))
    }
}
