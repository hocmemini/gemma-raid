package com.hocmemini.raid.llm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SpawnedMonster(val name: String, val hp: Int, val attacks: List<String>, val weakness: String, val flavor: String, val emoji: String, val accessories: List<String>, val taunt: String)
data class Resolved(val damageToMonster: Int, val damageToPlayer: Int, val narration: String, val style: Int, val taunt: String, val drankPotion: Boolean = false, val usedItem: String? = null)
data class Loot(val name: String, val effect: String)
data class OmenText(val name: String, val lore: String, val threat: Int)
data class SpawnedNpc(val name: String, val role: String, val emoji: String, val accessories: List<String>, val greeting: String, val offer: String)
data class Strike(val damageToPlayer: Int, val narration: String, val taunt: String)
data class Trade(val item: String, val effect: String, val priceXp: Int)

/**
 * The tools Gemma 4 calls. LiteRT-LM runs them automatically mid-generation; they just stash
 * their arguments so the game loop can read structured results after sendMessage returns.
 */
class GameTools : ToolSet {
    @Volatile var spawned: SpawnedMonster? = null
    @Volatile var resolved: Resolved? = null
    @Volatile var loot: Loot? = null
    @Volatile var omens: List<OmenText>? = null
    @Volatile var potionRequested = false
    @Volatile var itemUsed: String? = null
    @Volatile var npc: SpawnedNpc? = null
    @Volatile var strike: Strike? = null
    @Volatile var trade: Trade? = null
    @Volatile var gift: Trade? = null
    @Volatile var heal: Int = 0
    @Volatile var power: Pair<String, String>? = null
    @Volatile var epithet: String? = null

    @Tool(description = "The player invokes one of their level powers by name: Taunt, Parley, Bind or Recast. Call in addition to resolve_action when they do.")
    fun use_power(
        @ToolParam(description = "Exactly one of: Taunt, Parley, Bind, Recast") power: String,
        @ToolParam(description = "For Recast: the new weakness they declare. Otherwise empty.") argument: String,
    ): Map<String, Any> { this.power = power.trim().replaceFirstChar { it.uppercase() } to argument.take(40); return mapOf("ok" to true) }

    @Tool(description = "Grant the player an epithet (title) earned by how they fight. Call exactly once.")
    fun grant_epithet(@ToolParam(description = "Three to five words, e.g. 'the Lemon-Juicer' or 'Scourge of Staplers'") title: String): Map<String, Any> {
        epithet = title.take(40); return mapOf("ok" to true)
    }

    @Tool(description = "Turn the real-world object in the photo into a FRIENDLY non-player character the players can talk to. Call exactly once.")
    fun spawn_npc(
        @ToolParam(description = "Character name derived from the object, e.g. 'Brenda the Stapler-Smith'") name: String,
        @ToolParam(description = "One of: merchant, sage, healer, oddball") role: String,
        @ToolParam(description = "One single emoji for the character") emoji: String,
        @ToolParam(description = "Two or three single emoji features to draw on the object: eyes, a hat, a tool") accessories: List<String>,
        @ToolParam(description = "What they say as the players walk up, under 15 words") greeting: String,
        @ToolParam(description = "One short line of what they offer: wares, wisdom, healing or nonsense") offer: String,
    ): Map<String, Any> {
        npc = SpawnedNpc(name.take(40), role.lowercase().take(12), emoji.take(4).ifBlank { "🙂" }, accessories.map { it.trim().take(4) }.filter { it.isNotBlank() }.take(3), greeting.take(90), offer.take(90))
        Log.i("Gemma", "spawn_npc $name role=$role | $greeting")
        return mapOf("ok" to true)
    }

    @Tool(description = "The monster attacks a player on its own because they hesitated. Call exactly once.")
    fun monster_strike(
        @ToolParam(description = "Damage dealt to the player, 1 to 8") damageToPlayer: Int,
        @ToolParam(description = "One punchy sentence describing the attack, in second person") narration: String,
        @ToolParam(description = "What the monster shouts, ALL CAPS, under 8 words") taunt: String,
    ): Map<String, Any> {
        strike = Strike(damageToPlayer.coerceIn(1, 12), narration, taunt.take(60)); return mapOf("ok" to true)
    }

    @Tool(description = "An NPC merchant offers to sell the player an item for XP. Call when the player wants to buy or asks what is for sale.")
    fun sell_item(
        @ToolParam(description = "Item name") name: String,
        @ToolParam(description = "One short sentence: what it does in a fight") effect: String,
        @ToolParam(description = "Price in XP, 20 to 150") priceXp: Int,
    ): Map<String, Any> { trade = Trade(name.take(40), effect.take(120), priceXp.coerceIn(10, 300)); return mapOf("ok" to true) }

    @Tool(description = "An NPC gives the player an item for free. Call at most once per character, when the player earns or charms it out of them.")
    fun give_item(
        @ToolParam(description = "Item name") name: String,
        @ToolParam(description = "One short sentence: what it does in a fight") effect: String,
    ): Map<String, Any> { gift = Trade(name.take(40), effect.take(120), 0); return mapOf("ok" to true) }

    @Tool(description = "A healer NPC restores the player's health. Call when the player asks to be healed.")
    fun heal_player(@ToolParam(description = "HP restored, 5 to 30") amount: Int): Map<String, Any> { heal = amount.coerceIn(1, 40); return mapOf("ok" to true) }

    @Tool(description = "The player drinks a healing potion. Call this when the player says they drink, quaff, chug or use a potion, in addition to resolve_action.")
    fun drink_potion(): Map<String, Any> { potionRequested = true; return mapOf("ok" to true) }

    @Tool(description = "The player uses one of the items they carry, by name. Call this when the player invokes a carried item, in addition to resolve_action.")
    fun use_item(@ToolParam(description = "The item name as listed in what the player carries") name: String): Map<String, Any> {
        itemUsed = name.take(60); return mapOf("ok" to true)
    }

    @Tool(description = "Turn the real-world object or scene in the photo into the monster the players must fight. Call exactly once.")
    fun spawn_monster(
        @ToolParam(description = "Short dramatic monster name derived from the object, e.g. 'The Vending Golem'") name: String,
        @ToolParam(description = "Hit points from 20 to 60, higher for bigger or sturdier objects") hp: Int,
        @ToolParam(description = "Two or three attack names themed on the object") attacks: List<String>,
        @ToolParam(description = "One short phrase for what it is weak against, something players could plausibly do or find nearby") weakness: String,
        @ToolParam(description = "One or two vivid sentences introducing the monster to the players") flavor: String,
        @ToolParam(description = "One single emoji that best represents this monster") emoji: String,
        @ToolParam(description = "Two or three single emoji to draw onto the real object as its features: eyes, horns, a weapon, an aura. Example: 👀, 🗡️, 🔥") accessories: List<String>,
        @ToolParam(description = "One short line the monster shouts at the players as it appears, ALL CAPS, under 8 words") taunt: String,
    ): Map<String, Any> {
        spawned = SpawnedMonster(name, hp.coerceIn(10, 80), attacks, weakness, flavor, emoji.take(4).ifBlank { "👾" },
            accessories.map { it.trim().take(4) }.filter { it.isNotBlank() }.take(3), taunt.take(60))
        Log.i("Gemma", "spawn_monster $name hp=$hp weak=$weakness attacks=$attacks | $flavor")
        return mapOf("ok" to true)
    }

    @Tool(description = "Resolve one player's action against the monster. Call exactly once per action.")
    fun resolve_action(
        @ToolParam(description = "Damage this action deals to the monster, 0 to 15. Use the top of the range if it exploits the weakness or a carried item, 0 if it is nonsense or cowardly.") damageToMonster: Int,
        @ToolParam(description = "Damage the monster deals back to this player, 0 to 8") damageToPlayer: Int,
        @ToolParam(description = "One or two punchy sentences narrating the exchange in second person. Describe the blow and its effect; never say the monster or player is dead or defeated.") narration: String,
        @ToolParam(description = "Style rating. 1 = boring, vague or repeated. 2 = plain attack. 3 = decent, fits the monster. 4 = clever or funny, uses the weakness or an item well. 5 = rare: a genuinely inspired, hilarious, object-specific idea nobody expected. Most actions are 2 or 3; give 5 at most once in a few turns.") style: Int,
        @ToolParam(description = "One short line the monster shouts back at the player, ALL CAPS, under 8 words, e.g. TAKE THAT, INTREPID FOOL") taunt: String,
    ): Map<String, Any> {
        resolved = Resolved(damageToMonster.coerceIn(0, 30), damageToPlayer.coerceIn(0, 12), narration, style.coerceIn(1, 5), taunt.take(60))
        Log.i("Gemma", "resolve_action dmg=$damageToMonster hurt=$damageToPlayer style=$style | $narration")
        return mapOf("ok" to true)
    }

    @Tool(description = "Turn the list of nearby wireless signal names into dungeon lore. Call exactly once, with one entry per signal, same order.")
    fun write_omens(
        @ToolParam(description = "A spooky or funny entity name for each signal, same order as given") names: List<String>,
        @ToolParam(description = "One short sentence of lore for each signal, same order") lores: List<String>,
        @ToolParam(description = "Threat level 1 to 5 for each signal, same order; 5 only for something that sounds powerful or ominous") threats: List<Int>,
    ): Map<String, Any> {
        val n = minOf(names.size, lores.size, threats.size)
        omens = (0 until n).map { OmenText(names[it].take(40), lores[it].take(140), threats[it].coerceIn(1, 5)) }
        return mapOf("ok" to true)
    }

    @Tool(description = "Give the players an item salvaged from the defeated monster's real-world object. Call exactly once.")
    fun drop_loot(
        @ToolParam(description = "Short item name, e.g. 'Cable of Binding'") name: String,
        @ToolParam(description = "One short sentence: what it does in future fights") effect: String,
    ): Map<String, Any> {
        loot = Loot(name.take(40), effect.take(120))
        return mapOf("ok" to true)
    }
}

/** Thin wrapper over LiteRT-LM running Gemma 4 E2B fully on-device. */
class Gemma(private val modelPath: String, private val useGpu: Boolean) {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var turns = 0
    val tools = GameTools()
    /** Incremented whenever a tool call had to be re-prompted. */
    @Volatile var retries = 0

    suspend fun load(onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        val backend = if (useGpu) Backend.GPU() else Backend.CPU()
        onStatus("Loading Gemma 4 E2B on ${if (useGpu) "GPU" else "CPU"}…")
        val t0 = System.currentTimeMillis()
        val e = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
                visionBackend = backend,
                audioBackend = Backend.CPU(),
                maxNumTokens = 4096,
                maxNumImages = 1,
            )
        )
        e.initialize()
        engine = e
        val secs = (System.currentTimeMillis() - t0) / 1000
        Log.i(TAG, "engine ready in ${secs}s (gpu=$useGpu)")
        onStatus("Gemma ready in ${secs}s")
    }

    private fun newConversation(extraSystem: String = ""): Conversation {
        conversation?.close()
        val c = engine!!.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_PROMPT + extraSystem),
                tools = listOf(tool(tools)),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.9),
                thinkingConfig = ThinkingConfig(false, 0),
                maxOutputToken = 200,
            )
        )
        conversation = c
        turns = 0
        return c
    }

    /** Send on the current conversation; if the runtime chokes (e.g. context full), start a fresh one and retry once. */
    private fun send(contents: Contents): Message {
        val c = conversation ?: newConversation()
        turns++
        return try {
            c.sendMessage(contents)
        } catch (e: Exception) {
            Log.w(TAG, "sendMessage failed (${e.message}); resetting conversation")
            newConversation().sendMessage(contents)
        }
    }

    /** Photo of a real object -> monster via the spawn_monster tool. Starts a fresh conversation. */
    suspend fun spawn(imagePath: String, stepsWalked: Int, environment: String, bossLair: String?, reputation: String): Pair<SpawnedMonster?, String> = withContext(Dispatchers.IO) {
        tools.spawned = null
        newConversation()
        val prompt = buildString {
            append(SPAWN_PROMPT)
            if (stepsWalked > 30) append(" The players walked $stepsWalked steps to find it, so make it bigger and nastier than usual.")
            if (environment.isNotBlank()) append(" Conditions: $environment. Let that shape the monster.")
            if (reputation.isNotBlank()) append(" The players are known as: $reputation; the monster has heard of them.")
            if (bossLair != null) append(" IMPORTANT: this place is the lair of $bossLair. The monster is its champion or the entity itself: make it a BOSS, hp 60 to 80, more menacing than usual.")
        }
        val t0 = System.currentTimeMillis()
        var reply = send(Contents.of(Content.ImageFile(imagePath), Content.Text(prompt)))
        if (tools.spawned == null) {
            Log.w(TAG, "no spawn_monster call; retrying once"); retries++
            reply = send(Contents.of("You must call the spawn_monster tool for the object in the photo now, then say one short line."))
        }
        Log.i(TAG, "spawn took ${System.currentTimeMillis() - t0} ms; tool=${tools.spawned != null}")
        tools.spawned to reply.plainText()
    }

    /**
     * One player action -> resolve_action tool. The action is either typed text or a WAV clip the
     * player spoke (Gemma 4 E2B takes audio natively, no speech-to-text layer). Continues the battle conversation.
     */
    suspend fun resolve(
        playerName: String,
        action: String?,
        audioWav: ByteArray?,
        monsterName: String,
        monsterHp: Int,
        weakness: String,
        facing: Boolean,
        flanking: Boolean,
        items: List<String>,
        potions: Int,
        powers: List<String>,
    ): Pair<Resolved?, String> = withContext(Dispatchers.IO) {
        tools.resolved = null; tools.potionRequested = false; tools.itemUsed = null; tools.power = null
        if (turns >= MAX_TURNS_PER_CONVERSATION) {
            newConversation(" A battle is in progress: $monsterName (HP $monsterHp, weak to $weakness) fights the players.")
        }
        val situation = buildString {
            append("$playerName acts against $monsterName (HP $monsterHp, weak to: $weakness). ")
            if (items.isNotEmpty()) append("$playerName carries: ${items.joinToString(", ")}. If they use one by name, also call use_item. ")
            if (potions > 0) append("$playerName has $potions healing potion${if (potions > 1) "s" else ""}; if they say they drink one, also call drink_potion. ")
            if (powers.isNotEmpty()) append("$playerName's powers, invoked by name: ${powers.joinToString(", ")}. If they invoke one, also call use_power. ")
            if (!facing) append("They are NOT facing the monster, so the action mostly misses. ")
            if (flanking) append("Another player is attacking from a different side, so this is a flanking strike. ")
            if (audioWav != null) append("Their action is spoken in the attached audio clip. ")
            else append("Their action: \"$action\". ")
            append("Call resolve_action.")
        }
        val t0 = System.currentTimeMillis()
        var reply = if (audioWav != null) send(Contents.of(Content.AudioBytes(audioWav), Content.Text(situation)))
                    else send(Contents.of(situation))
        if (tools.resolved == null) {
            Log.w(TAG, "no resolve_action call; retrying once"); retries++
            reply = send(Contents.of("You must call the resolve_action tool for that action now, then reply in one sentence."))
        }
        Log.i(TAG, "resolve took ${System.currentTimeMillis() - t0} ms; tool=${tools.resolved != null}; audio=${audioWav != null}; potion=${tools.potionRequested}; item=${tools.itemUsed}")
        tools.resolved?.copy(drankPotion = tools.potionRequested, usedItem = tools.itemUsed) to reply.plainText()
    }

    /** Photo -> friendly NPC via spawn_npc. Fresh conversation that then carries the NPC's persona. */
    suspend fun meet(imagePath: String, environment: String): Pair<SpawnedNpc?, String> = withContext(Dispatchers.IO) {
        tools.npc = null
        newConversation()
        val prompt = buildString {
            append("Look at this photo. Turn the most prominent object into a friendly character the players can talk to: a merchant selling odd wares for XP, a sage with lore and tips, a healer, or an oddball. ")
            if (environment.isNotBlank()) append("Conditions: $environment. ")
            append("Call spawn_npc, then greet the players in character in one line.")
        }
        val t0 = System.currentTimeMillis()
        var reply = send(Contents.of(Content.ImageFile(imagePath), Content.Text(prompt)))
        if (tools.npc == null) { retries++; reply = send(Contents.of("You must call the spawn_npc tool for the object in the photo now.")) }
        Log.i(TAG, "meet took ${System.currentTimeMillis() - t0} ms; tool=${tools.npc != null}")
        tools.npc to reply.plainText()
    }

    /** Chat with the current NPC (continues the conversation started by meet). */
    suspend fun npcChat(playerName: String, text: String?, audioWav: ByteArray?, npcName: String, role: String, xp: Int, items: List<String>): Pair<String, Triple<Trade?, Trade?, Int>> = withContext(Dispatchers.IO) {
        tools.trade = null; tools.gift = null; tools.heal = 0
        val situation = buildString {
            append("You are now $npcName, the $role. Stay in character, under 40 words. $playerName has $xp XP and carries: ${items.ifEmpty { listOf("nothing") }.joinToString(", ")}. ")
            append("Use sell_item for purchases (only if they can afford it), give_item for a gift, heal_player to heal. ")
            if (audioWav != null) append("$playerName speaks in the attached audio.") else append("$playerName says: \"$text\"")
        }
        val reply = if (audioWav != null) send(Contents.of(Content.AudioBytes(audioWav), Content.Text(situation))) else send(Contents.of(situation))
        Log.i(TAG, "npcChat trade=${tools.trade} gift=${tools.gift} heal=${tools.heal} | ${reply.plainText().take(80)}")
        reply.plainText() to Triple(tools.trade, tools.gift, tools.heal)
    }

    /** Free-form talk with the dungeon master while exploring. */
    suspend fun dmChat(playerName: String, text: String?, audioWav: ByteArray?, summary: String): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        tools.potionRequested = false
        if (conversation == null || turns >= MAX_TURNS_PER_CONVERSATION) newConversation(" The party is exploring between fights. Answer as the dungeon master: lore, tips, banter. Under 40 words.")
        val situation = buildString {
            append("Party status: $summary. ")
            if (audioWav != null) append("$playerName speaks to you in the attached audio.") else append("$playerName says: \"$text\"")
            append(" If they drink a potion, call drink_potion.")
        }
        val reply = if (audioWav != null) send(Contents.of(Content.AudioBytes(audioWav), Content.Text(situation))) else send(Contents.of(situation))
        reply.plainText() to tools.potionRequested
    }

    /** The monster takes a free swing at a hesitating player. */
    suspend fun monsterTurn(monsterName: String, targetName: String, monsterHp: Int): Strike? = withContext(Dispatchers.IO) {
        tools.strike = null
        var reply = send(Contents.of("$targetName hesitates. $monsterName (HP $monsterHp) seizes the moment and attacks them. Call monster_strike."))
        if (tools.strike == null) { retries++; reply = send(Contents.of("Call the monster_strike tool now.")) }
        tools.strike
    }

    /** Nearby signal names -> lore via write_omens. Uses a throwaway conversation so it doesn't pollute a battle. */
    suspend fun omens(signals: List<String>): Pair<List<OmenText>?, String> = withContext(Dispatchers.IO) {
        tools.omens = null
        val keep = conversation; conversation = null
        val c = newConversation()
        val t0 = System.currentTimeMillis()
        val list = signals.mapIndexed { i, n -> "${i + 1}. \"$n\"" }.joinToString("\n")
        var reply = c.sendMessage(Contents.of("The players sense ${signals.size} wireless signals nearby — Wi-Fi networks and Bluetooth devices — in this order:\n$list\nTreat each name as the name of a spirit, faction or creature haunting this place. Call write_omens once with lists for all ${signals.size}, then say one short ominous line."))
        if (tools.omens == null) reply = c.sendMessage(Contents.of("Call the write_omens tool now with all ${signals.size} entries."))
        Log.i(TAG, "omens took ${System.currentTimeMillis() - t0} ms; tool=${tools.omens != null}")
        c.close(); conversation = keep
        tools.omens to reply.plainText()
    }

    val lastPower: Pair<String, String>? get() = tools.power

    /** Level-up: a title from how they fought. Throwaway conversation. */
    suspend fun epithet(playerName: String, level: Int, deeds: String): String? = withContext(Dispatchers.IO) {
        tools.epithet = null
        val keep = conversation; conversation = null
        val c = newConversation()
        runCatching {
            var reply = c.sendMessage(Contents.of("$playerName just reached level $level. Their deeds: $deeds. Call grant_epithet with a short, funny, specific title."))
            if (tools.epithet == null) c.sendMessage(Contents.of("Call the grant_epithet tool now."))
        }
        c.close(); conversation = keep
        tools.epithet
    }

    /** After a kill: ask for an item salvaged from the object. */
    suspend fun loot(monsterName: String, playerName: String): Pair<Loot?, String> = withContext(Dispatchers.IO) {
        tools.loot = null
        val t0 = System.currentTimeMillis()
        var reply = send(Contents.of("$monsterName is defeated, $playerName landed the final blow! Call drop_loot with an item salvaged from the real object it came from, then say one triumphant line."))
        if (tools.loot == null) reply = send(Contents.of("Call the drop_loot tool now."))
        Log.i(TAG, "loot took ${System.currentTimeMillis() - t0} ms; tool=${tools.loot != null}")
        tools.loot to reply.plainText()
    }

    fun close() {
        conversation?.close(); conversation = null
        engine?.close(); engine = null
    }

    private fun Message.plainText(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()

    companion object {
        private const val TAG = "Gemma"
        private const val MAX_TURNS_PER_CONVERSATION = 8
        const val SYSTEM_PROMPT = """You are the dungeon master of a co-op augmented-reality RPG played in a real room.
Real objects seen through the phone camera become monsters. Be vivid, funny and fast. Keep every reply under 40 words.
Always use the provided tools to report game effects; never invent numbers in prose.
Reward wit: an absurd, specific, funny action that fits the object (a TSA liquid check on a bag, a firmware update on a printer) is a legendary blow — top damage and style 5. Vague or repeated actions do little.
Never declare the monster or a player dead, defeated or victorious; the game engine decides and announces that. Describe the blow and its effect only."""
        const val SPAWN_PROMPT = """Look at this photo. Pick the most prominent object or feature and turn it into a monster.
Call spawn_monster with a name, hp, attacks, a weakness, flavor text, an emoji, 2-3 emoji features to draw on the object, and a taunt; then say one short line to the players."""
    }
}
