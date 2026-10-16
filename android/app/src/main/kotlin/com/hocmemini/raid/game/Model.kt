package com.hocmemini.raid.game

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Phase { LOBBY, EXPLORE, BATTLE, VICTORY, DEFEAT }

@Serializable
data class Monster(
    val name: String,
    val hp: Int,
    val maxHp: Int,
    val attacks: List<String>,
    val weakness: String,
    val flavor: String,
    /** Compass bearing (degrees, 0 = north) from the host's position when it was spawned. */
    val bearingDeg: Float,
    val lat: Double,
    val lon: Double,
)

@Serializable
data class Player(
    val id: String,
    val name: String,
    val hp: Int = 20,
    val maxHp: Int = 20,
    val headingDeg: Float = 0f,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
)

@Serializable
data class GameState(
    val phase: Phase = Phase.LOBBY,
    val monster: Monster? = null,
    val players: Map<String, Player> = emptyMap(),
    val log: List<String> = emptyList(),
    val turn: Int = 0,
    /** Set by the host while Gemma is thinking so clients can show a spinner. */
    val busy: Boolean = false,
) {
    fun withLog(line: String): GameState = copy(log = (log + line).takeLast(40))
}

/** Wire messages between phones. Only the host runs Gemma; clients send intents, host broadcasts state. */
@Serializable
sealed class Msg {
    @Serializable @SerialName("join")
    data class Join(val player: Player) : Msg()

    @Serializable @SerialName("action")
    data class Action(val playerId: String, val text: String, val headingDeg: Float, val lat: Double, val lon: Double) : Msg()

    /** Precedes a STREAM payload carrying a WAV clip of the player's spoken action. */
    @Serializable @SerialName("audio")
    data class AudioHeader(val playerId: String, val headingDeg: Float, val lat: Double, val lon: Double, val bytes: Int) : Msg()

    @Serializable @SerialName("pose")
    data class Pose(val playerId: String, val headingDeg: Float, val lat: Double, val lon: Double) : Msg()

    @Serializable @SerialName("state")
    data class State(val state: GameState) : Msg()
}

/** Smallest signed difference between two headings, in degrees (-180, 180]. */
fun headingDelta(fromDeg: Float, toDeg: Float): Float {
    var d = (toDeg - fromDeg) % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
}
