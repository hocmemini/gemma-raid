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
    val emoji: String = "👾",
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
    val xp: Int = 0,
    val level: Int = 1,
    val items: List<String> = emptyList(),
    val potions: Int = 2,
    /** Once per raid: at 0 HP you get back up with a few HP instead of going down. */
    val secondWind: Boolean = true,
)

/** One exchange's visual effects; the UI animates whenever seq changes. */
@Serializable
data class Fx(
    val seq: Int,
    val playerId: String = "",
    val dealt: Int = 0,
    val taken: Int = 0,
    val crit: Boolean = false,
    val parried: Boolean = false,
    val killed: Boolean = false,
    val label: String = "",
)

/** A nearby Wi-Fi/Bluetooth signal that Gemma turned into lore. */
@Serializable
data class Omen(val signal: String, val name: String, val lore: String, val threat: Int, val dbm: Int)

@Serializable
data class GameState(
    val phase: Phase = Phase.LOBBY,
    val monster: Monster? = null,
    val players: Map<String, Player> = emptyMap(),
    val log: List<String> = emptyList(),
    val turn: Int = 0,
    /** Set by the host while Gemma is thinking so clients can show a spinner. */
    val busy: Boolean = false,
    val kills: Int = 0,
    /** Steps the host walked since the last fight; feeds monster size. */
    val steps: Int = 0,
    /** Host's environment summary for the HUD, e.g. "🌑 dark · ⬇2 floors". */
    val env: String = "",
    /** Players with a parry armed (double twist). */
    val parries: List<String> = emptyList(),
    val omens: List<Omen> = emptyList(),
    val fx: Fx? = null,
    /** Set when the strongest omen is menacing enough: the next spawn is its boss. */
    val bossLair: Omen? = null,
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

    @Serializable @SerialName("potion")
    data class Potion(val playerId: String) : Msg()

    /** The player twisted the phone: block the monster's next hit on them. */
    @Serializable @SerialName("parry")
    data class Parry(val playerId: String) : Msg()

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

fun levelFor(xp: Int): Int = 1 + xp / 100
fun maxHpFor(level: Int): Int = 20 + 5 * (level - 1)
