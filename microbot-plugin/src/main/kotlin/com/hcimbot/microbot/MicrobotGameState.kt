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
    @Volatile private var moving = false
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
            val at = player.worldLocation?.let(Instances::toTemplate)
            Snapshot(
                name = player.name,
                tile = at?.let { Tile(it.x, it.y, it.plane) },
                hitpoints = client.getBoostedSkillLevel(Skill.HITPOINTS),
                maxHitpoints = client.getRealSkillLevel(Skill.HITPOINTS),
            )
        }
        if (snapshot == null) return

        moving = Rs2Player.isMoving()
        busy = moving || Rs2Player.isAnimating()
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
    override val isMoving: Boolean get() = moving
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
        fun search(byIds: Collection<Int>, byNames: Collection<String>): SceneEntity? {
            val query = Microbot.getRs2NpcCache().query()
            val found = if (byIds.isNotEmpty()) query.withIds(*byIds.toIntArray()) else query.withNames(*byNames.toTypedArray())
            val nearest = if (near != null) found.nearest(near.toWorldPoint(), SEARCH_RADIUS) else found.nearest(SEARCH_RADIUS)
            return nearest?.let { entity(it.id, it.name, it.worldLocation)?.copy(index = it.index) }
        }
        search(ids, names) ?: run {
            // Updates give NPCs new ids (Morgan, Dr Harlow) while the guide and Quest Helper keep the old
            // ones. With none of the listed ids in sight, look for the same name instead.
            val client = Microbot.getClient()
            val byName = names.ifEmpty { ids.mapNotNull { client.getNpcDefinition(it)?.name } }
                .map { Text.removeTags(it).trim() }.filter { it.isNotEmpty() && !it.equals("null", true) }.distinct()
            if (ids.isEmpty() || byName.isEmpty()) null else search(emptyList(), byName)
        }
    }

    /**
     * The options Microbot can click on the NPC. Rs2Npc.interact reads the base definition,
     * not the transformed one the player sees, so that is what counts here: Veos shows
     * Travel when transformed, but Microbot only finds Talk-to and fails with "Action not found".
     */
    override fun npcActions(npc: SceneEntity): List<String>? = onClient {
        Microbot.getClient().getNpcDefinition(npc.id)?.actions?.filterNotNull()?.map(Text::removeTags)?.filter { it.isNotBlank() }
    }

    override fun showsChoice(text: String): Boolean = onClient { choiceWidget(text) != null } == true

    /** A breadth first search over the scene's collision map, in which a shut door is a wall. */
    override fun canReach(tile: Tile): Boolean? =
        runCatching { Rs2Tile.isTileReachable(Instances.toInstance(WorldPoint(tile.x, tile.y, tile.plane))) }.getOrNull()

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

    override fun npcIdNamed(constant: String): Int? = synchronized(npcIds) {
        npcIds.getOrPut(constant) { runCatching { Class.forName(NPC_IDS).getField(constant).getInt(null) }.getOrNull() }
    }

    private val npcIds = HashMap<String, Int?>()

    override fun helperStep(quest: String?) = QuestHelperProbe.currentStep(quest)

    override fun helperProblem() = QuestHelperProbe.problem

    override fun nearestObject(ids: Collection<Int>, names: Collection<String>, near: Tile?): SceneEntity? = onClient {
        val query = Microbot.getRs2TileObjectCache().query()
        val found = if (ids.isNotEmpty()) query.withIds(*ids.toIntArray()) else query.withNames(*names.toTypedArray())
        val nearest = if (near != null) found.nearest(near.toWorldPoint(), SEARCH_RADIUS) else found.nearest(SEARCH_RADIUS)
        nearest?.let { entity(it.id, it.name, it.worldLocation) }
    }

    override fun objectsNear(names: Collection<String>, near: Tile, within: Int): List<SceneEntity> = onClient {
        if (names.isEmpty()) return@onClient emptyList()
        val centre = near.toWorldPoint()
        Microbot.getRs2TileObjectCache().query().withNames(*names.toTypedArray())
            .where { it.worldLocation.distanceTo(centre) <= within }.toList()
            .mapNotNull { entity(it.id, it.name, it.worldLocation) }
    } ?: emptyList()

    override fun groundItemsNear(ids: Collection<Int>, near: Tile, within: Int): List<SceneEntity> = onClient {
        if (ids.isEmpty()) return@onClient emptyList()
        val centre = near.toWorldPoint()
        Microbot.getRs2TileItemCache().query().withIds(*ids.toIntArray())
            .where { it.worldLocation.distanceTo(centre) <= within }.toList()
            .mapNotNull { entity(it.id, it.name, it.worldLocation) }
    } ?: emptyList()

    override fun nearestGroundItem(ids: Collection<Int>, near: Tile?, within: Int?): SceneEntity? = onClient {
        if (ids.isEmpty()) return@onClient null
        val found = Microbot.getRs2TileItemCache().query().withIds(*ids.toIntArray())
        val nearest = if (near != null) found.nearest(near.toWorldPoint(), within ?: SEARCH_RADIUS) else found.nearest(SEARCH_RADIUS)
        nearest?.let { entity(it.id, it.name, it.worldLocation) }
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

    private fun Tile.toWorldPoint() = Instances.toInstance(WorldPoint(x, y, plane))

    private fun entity(id: Int, name: String?, at: WorldPoint?): SceneEntity? =
        at?.let(Instances::toTemplate)?.let { SceneEntity(id, name?.let(Text::removeTags).orEmpty(), Tile(it.x, it.y, it.plane)) }

    companion object {
        private const val HISTORY = 50
        private const val OBJECT_IDS = "net.runelite.api.gameval.ObjectID"
        private const val NPC_IDS = "net.runelite.api.gameval.NpcID"
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

        /**
         * A visible game window entry whose text is [text] or ends in ": [text]", like the
         * minecart menu's "B: Shayzien East". Call on the client thread.
         */
        fun choiceWidget(text: String): net.runelite.api.widgets.Widget? {
            val wanted = text.trim().lowercase()
            return widgetWhere { shown -> shown.lowercase() == wanted || shown.lowercase().endsWith(": $wanted") }
        }

        /** The first visible widget whose text, tags removed and trimmed, passes [matches]. Client thread. */
        fun widgetWhere(matches: (String) -> Boolean): net.runelite.api.widgets.Widget? {
            fun visit(w: net.runelite.api.widgets.Widget?): net.runelite.api.widgets.Widget? {
                if (w == null || w.isHidden) return null
                // "<br>" is a line break: a space, not nothing, or "may be<br>dangerous" reads "may bedangerous".
                val shown = Text.removeTags(w.text.orEmpty().replace("<br>", " ")).replace(Regex("\\s+"), " ").trim()
                if (shown.isNotEmpty() && matches(shown)) return w
                for (group in listOf(w.dynamicChildren, w.staticChildren, w.nestedChildren)) {
                    group?.forEach { child -> visit(child)?.let { return it } }
                }
                return null
            }
            return Microbot.getClient().widgetRoots?.firstNotNullOfOrNull { visit(it) }
        }

        /**
         * A button of the make window ("All", or "Space" for its first item), found among visible widgets with that
         * text. The chat's own "All" tab has the same text, so a match must sit with the quantity buttons: within three
         * levels above it there is a widget showing "X" and "All" (the 5 and 10 are left out when too few items are held). Returns the button itself, its parent when that is
         * the clickable area. Client thread.
         */
        fun makeWindowButton(label: String, useParent: Boolean): net.runelite.api.widgets.Widget? {
            fun textOf(w: net.runelite.api.widgets.Widget?) = Text.removeTags(w?.text.orEmpty()).trim()
            fun descendants(w: net.runelite.api.widgets.Widget?, out: MutableList<net.runelite.api.widgets.Widget>) {
                if (w == null || w.isHidden) return
                out += w
                for (group in listOf(w.dynamicChildren, w.staticChildren, w.nestedChildren)) group?.forEach { descendants(it, out) }
            }
            val all = mutableListOf<net.runelite.api.widgets.Widget>()
            Microbot.getClient().widgetRoots?.forEach { descendants(it, all) }
            for (candidate in all.filter { textOf(it).equals(label, ignoreCase = true) }) {
                var up: net.runelite.api.widgets.Widget? = candidate
                repeat(3) {
                    up = up?.parent
                    val inside = mutableListOf<net.runelite.api.widgets.Widget>()
                    descendants(up, inside)
                    val texts = inside.map(::textOf)
                    if (texts.any { it == "X" } && texts.any { it == "All" }) {
                        val target = if (useParent) candidate.parent ?: candidate else candidate
                        return if (target.bounds.width > 0) target else candidate
                    }
                }
            }
            return null
        }

        /**
         * The first item of the make window, the leftmost. Its label is "1", but "Space" moves to whichever item was
         * made last, so the items' labels cannot be used by name: take the leftmost of the number or Space labels
         * that sit below the quantity buttons. Client thread.
         */
        fun firstMakeItem(): java.awt.Rectangle? = nthMakeItem(1)

        /**
         * The clickable area of the [n]th item (1 based, from the left) of the make window. The number or Space under an
         * item is only its label, and clicking that does nothing, so the item's own box is looked for: the widget just
         * above the label that spans its width. Without one, the area above the label. Client thread.
         */
        fun nthMakeItem(n: Int): java.awt.Rectangle? {
            fun textOf(w: net.runelite.api.widgets.Widget?) = Text.removeTags(w?.text.orEmpty()).trim()
            fun descendants(w: net.runelite.api.widgets.Widget?, out: MutableList<net.runelite.api.widgets.Widget>) {
                if (w == null || w.isHidden) return
                out += w
                for (group in listOf(w.dynamicChildren, w.staticChildren, w.nestedChildren)) group?.forEach { descendants(it, out) }
            }
            val all = mutableListOf<net.runelite.api.widgets.Widget>()
            Microbot.getClient().widgetRoots?.forEach { descendants(it, all) }
            val quantity = makeWindowButton("X", useParent = false) ?: return null
            val below = quantity.bounds.y + quantity.bounds.height
            val label = all
                .filter { textOf(it) in ITEM_LABELS && it.bounds.width > 0 && it.bounds.y >= below }
                .sortedBy { it.bounds.x }
                .getOrNull(n - 1) ?: return null
            val text = label.bounds
            val centre = text.x + text.width / 2
            val box = all
                .filter { it !== label && it.bounds.width >= MIN_ITEM && it.bounds.height >= MIN_ITEM }
                .filter { it.bounds.x <= centre && centre <= it.bounds.x + it.bounds.width }
                .filter { it.bounds.y < text.y && it.bounds.y + it.bounds.height >= text.y - ITEM_GAP }
                .minByOrNull { it.bounds.width * it.bounds.height }
            if (box != null) return box.bounds
            // The area above the label, about as tall as the item is wide.
            return java.awt.Rectangle(text.x, text.y - text.width, text.width, text.width)
        }

        private const val MIN_ITEM = 30
        private const val ITEM_GAP = 8

        private val ITEM_LABELS = setOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Space")

        /** The first visible widget with a [action] option, like a window's Close button. Client thread. */
        fun widgetWithAction(action: String): net.runelite.api.widgets.Widget? {
            fun visit(w: net.runelite.api.widgets.Widget?): net.runelite.api.widgets.Widget? {
                if (w == null || w.isHidden) return null
                if (w.actions?.any { it != null && Text.removeTags(it).trim().equals(action, ignoreCase = true) } == true && w.bounds.width > 0) return w
                for (group in listOf(w.dynamicChildren, w.staticChildren, w.nestedChildren)) {
                    group?.forEach { child -> visit(child)?.let { return it } }
                }
                return null
            }
            return Microbot.getClient().widgetRoots?.firstNotNullOfOrNull { visit(it) }
        }

        fun <T> onClient(block: () -> T?): T? =
            Microbot.getClientThread().runOnClientThreadOptional(Callable { block() }).orElse(null)
    }
}
