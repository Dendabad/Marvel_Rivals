package com.hcimbot.microbot

import com.hcimbot.game.GameState
import com.hcimbot.game.SceneEntity
import com.hcimbot.guide.Tile
import java.util.concurrent.Callable
import java.util.stream.Collectors
import net.runelite.api.Skill
import net.runelite.api.coords.WorldPoint
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.shop.Rs2Shop
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile
import net.runelite.client.util.Text

/**
 * [GameState] on Microbot. The bot takes at most one action per tick, so most
 * of the state is read once per tick by [refresh] and then served from that
 * snapshot. Entity lookups stay live, since the bot acts on what they return.
 *
 * Every read goes through the client thread, as Microbot requires. A read
 * that times out leaves the snapshot unusable, and [isLoggedIn] then reports
 * false so the bot sits that tick out instead of acting on missing data.
 */
class MicrobotGameState : GameState {
    private class Snapshot(
        val name: String?,
        val tile: Tile?,
        val hitpoints: Int,
        val maxHitpoints: Int,
    )

    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var busy = false
    @Volatile private var attacked = false
    @Volatile private var inventory: Map<Int, Int> = emptyMap()
    @Volatile private var worn: Map<Int, Int> = emptyMap()
    @Volatile private var options: List<String> = emptyList()
    @Volatile private var canContinue = false
    @Volatile private var dialog: String? = null
    private val history = ArrayDeque<String>()

    private val varbits = HashMap<Int, Int>()
    private val varps = HashMap<Int, Int>()
    private val levels = HashMap<String, Int>()

    /** Reads this tick's state. Call once per tick, before the runner. */
    fun refresh() {
        varbits.clear()
        varps.clear()
        levels.clear()
        snapshot = if (!Microbot.isLoggedIn()) null else onClient {
            val client = Microbot.getClient()
            val player = client.localPlayer ?: return@onClient null
            val at = player.worldLocation
            Snapshot(
                name = player.name,
                tile = at?.let { Tile(it.x, it.y, it.plane) },
                hitpoints = client.getBoostedSkillLevel(Skill.HITPOINTS),
                maxHitpoints = client.getRealSkillLevel(Skill.HITPOINTS),
            )
        }
        if (snapshot == null) return

        busy = Rs2Player.isMoving() || Rs2Player.isAnimating()
        inventory = Rs2Inventory.items().collect(Collectors.toList()).groupingBy { it.id }.fold(0) { sum, item -> sum + item.quantity }
        worn = Rs2Equipment.items().associate { it.id to it.quantity }
        attacked = onClient { underAttack() } ?: false

        canContinue = Rs2Dialogue.hasContinue()
        options = onClient { Rs2Dialogue.getDialogueOptions().mapNotNull { it.text?.let(Text::removeTags) } } ?: emptyList()
        dialog = onClient { currentDialogLine() }
        dialog?.let { line ->
            if (history.lastOrNull() != line) history.addLast(line)
            while (history.size > HISTORY) history.removeFirst()
        }
    }

    /** A monster with an Attack option is targeting the player. Talking NPCs face you too, so the option matters. */
    private fun underAttack(): Boolean {
        val client = Microbot.getClient()
        return Microbot.getRs2NpcCache().query()
            .where { it.isInteractingWithPlayer }
            .toList()
            .any { npc -> client.getNpcDefinition(npc.id)?.actions?.any { it.equals("Attack", ignoreCase = true) } == true }
    }

    /** "Speaker|text", the way guide.json's dialog conditions are written. */
    private fun currentDialogLine(): String? {
        if (!Rs2Dialogue.isInDialogue()) return null
        val text = Rs2Dialogue.getDialogueText()?.takeIf { it.isNotBlank() } ?: return null
        val client = Microbot.getClient()
        val npcName = client.getWidget(NPC_DIALOG_GROUP, NPC_NAME_CHILD)?.takeIf { !it.isHidden }?.text
        val speaker = npcName ?: if (client.getWidget(PLAYER_DIALOG_GROUP, 0) != null) client.localPlayer?.name else null
        return "${speaker?.let(Text::removeTags).orEmpty()}|$text"
    }

    override val isLoggedIn: Boolean get() = snapshot != null
    override val playerName: String? get() = snapshot?.name
    override val playerTile: Tile? get() = snapshot?.tile
    override val isBusy: Boolean get() = busy
    override val isInCombat: Boolean get() = attacked
    override val hitpoints: Int get() = snapshot?.hitpoints ?: 0
    override val maxHitpoints: Int get() = snapshot?.maxHitpoints ?: 0

    override fun varbit(id: Int): Int = varbits.getOrPut(id) { onClient { Microbot.getClient().getVarbitValue(id) } ?: 0 }
    override fun varp(id: Int): Int = varps.getOrPut(id) { onClient { Microbot.getClient().getVarpValue(id) } ?: 0 }

    override fun skillLevel(skill: String): Int = levels.getOrPut(skill) {
        val parsed = runCatching { Skill.valueOf(skill) }.getOrNull() ?: return@getOrPut 0
        onClient { Microbot.getClient().getRealSkillLevel(parsed) } ?: 0
    }

    override fun inventory(): Map<Int, Int> = inventory
    override fun equipment(): Map<Int, Int> = worn
    override fun bankCount(itemId: Int): Int = Rs2Bank.count(itemId)
    override val isBankOpen: Boolean get() = Rs2Bank.isOpen()
    override val isShopOpen: Boolean get() = Rs2Shop.isOpen() || Omnishop.isOpen()

    override fun shopOffers(itemId: Int): String? = if (Omnishop.isOpen()) Omnishop.describe(itemId) else null

    override fun nearestNpc(ids: Collection<Int>, names: Collection<String>, near: Tile?): SceneEntity? = onClient {
        val query = Microbot.getRs2NpcCache().query()
        val found = if (ids.isNotEmpty()) query.withIds(*ids.toIntArray()) else query.withNames(*names.toTypedArray())
        val nearest = if (near != null) found.nearest(near.toWorldPoint(), SEARCH_RADIUS) else found.nearest(SEARCH_RADIUS)
        nearest?.let { entity(it.id, it.name, it.worldLocation)?.copy(index = it.index) }
    }

    /** The NPC's current options, transformed for NPCs whose look depends on quest progress. */
    override fun npcActions(npc: SceneEntity): List<String>? = onClient {
        val client = Microbot.getClient()
        val definition = client.getNpcDefinition(npc.id)?.let { it.transform() ?: it } ?: return@onClient null
        definition.actions?.filterNotNull()?.map(Text::removeTags)?.filter { it.isNotBlank() }
    }

    /** A breadth first search over the scene's collision map, in which a shut door is a wall. */
    override fun canReach(tile: Tile): Boolean? =
        runCatching { Rs2Tile.isTileReachable(WorldPoint(tile.x, tile.y, tile.plane)) }.getOrNull()

    /** Found again by its index. Gone, dead or at 0 health all count as killed. */
    override fun npcAlive(npc: SceneEntity): Boolean? {
        if (npc.index < 0) return null
        return onClient {
            val found = Microbot.getRs2NpcCache().query().where { it.index == npc.index }.first()
            found != null && !found.npc.isDead && found.healthPercentage != 0.0
        }
    }

    override fun npcSpawns(name: String): List<Tile> = spawns[name.lowercase()].orEmpty()

    /** Microbot's NPC location data, by lower case NPC name. Read once, from Microbot's own jar. */
    private val spawns: Map<String, List<Tile>> by lazy {
        runCatching {
            val stream = Microbot::class.java.getResourceAsStream(NPC_LOCATIONS) ?: return@runCatching emptyMap()
            stream.use { parseSpawns(java.io.InputStreamReader(it, Charsets.UTF_8)) }
        }.getOrElse { emptyMap() }
    }

    /** Read from RuneLite's gameval ObjectID by name, and remembered. */
    override fun objectIdNamed(constant: String): Int? = synchronized(objectIds) {
        objectIds.getOrPut(constant) { runCatching { Class.forName(OBJECT_IDS).getField(constant).getInt(null) }.getOrNull() }
    }

    private val objectIds = HashMap<String, Int?>()

    override fun nearestObject(ids: Collection<Int>, names: Collection<String>, near: Tile?): SceneEntity? = onClient {
        val query = Microbot.getRs2TileObjectCache().query()
        val found = if (ids.isNotEmpty()) query.withIds(*ids.toIntArray()) else query.withNames(*names.toTypedArray())
        val nearest = if (near != null) found.nearest(near.toWorldPoint(), SEARCH_RADIUS) else found.nearest(SEARCH_RADIUS)
        nearest?.let { entity(it.id, it.name, it.worldLocation) }
    }

    override fun nearestGroundItem(ids: Collection<Int>): SceneEntity? = onClient {
        if (ids.isEmpty()) return@onClient null
        Microbot.getRs2TileItemCache().query().withIds(*ids.toIntArray())
            .nearest(SEARCH_RADIUS)?.let { entity(it.id, it.name, it.worldLocation) }
    }

    override fun dialogOptions(): List<String> = options
    override val canContinueDialog: Boolean get() = canContinue
    override fun currentDialog(): String? = dialog
    override fun dialogHistory(): List<String> = history.toList()

    override fun itemOptions(itemId: Int): List<String> = onClient {
        val held = Rs2Inventory.get(itemId)
        val actions = held?.inventoryActions?.toList() ?: Rs2Equipment.items().firstOrNull { it.id == itemId }?.equipmentActions
        actions.orEmpty().filterNotNull().map(Text::removeTags).filter { it.isNotBlank() }
    } ?: emptyList()

    override fun widgetText(group: Int, child: Int): String? =
        onClient { Microbot.getClient().getWidget(group, child)?.takeIf { !it.isHidden }?.text }

    override fun isInterfaceOpen(packedWidgetId: Int): Boolean =
        onClient { Microbot.getClient().getWidget(packedWidgetId)?.let { !it.isHidden } } ?: false

    private fun Tile.toWorldPoint() = WorldPoint(x, y, plane)

    private fun entity(id: Int, name: String?, at: WorldPoint?): SceneEntity? =
        at?.let { SceneEntity(id, name?.let(Text::removeTags).orEmpty(), Tile(it.x, it.y, it.plane)) }

    companion object {
        private const val HISTORY = 50
        private const val OBJECT_IDS = "net.runelite.api.gameval.ObjectID"
        private const val NPC_LOCATIONS = "/npc/npcs_locations.json"

        /** How far to look for an NPC, object or item. The loaded scene is about this big. */
        private const val SEARCH_RADIUS = 50

        private const val NPC_DIALOG_GROUP = 231
        private const val NPC_NAME_CHILD = 4
        private const val PLAYER_DIALOG_GROUP = 217

        /** Reads npcs_locations.json: {"Giant rat": [{"location_name": ..., "coords": [[x, y, plane], ...]}]}. */
        fun parseSpawns(reader: java.io.Reader): Map<String, List<Tile>> {
            // RuneLite ships Gson 2.8.5, which has no static JsonParser.parseReader.
            val json = com.google.gson.Gson().fromJson(reader, com.google.gson.JsonObject::class.java) ?: return emptyMap()
            return json.entrySet().associate { (npc, places) ->
                npc.lowercase() to places.asJsonArray.flatMap { place ->
                    place.asJsonObject.getAsJsonArray("coords")?.map { c ->
                        val xyz = c.asJsonArray
                        Tile(xyz[0].asInt, xyz[1].asInt, xyz[2].asInt)
                    }.orEmpty()
                }
            }
        }

        fun <T> onClient(block: () -> T?): T? =
            Microbot.getClientThread().runOnClientThreadOptional(Callable { block() }).orElse(null)
    }
}
