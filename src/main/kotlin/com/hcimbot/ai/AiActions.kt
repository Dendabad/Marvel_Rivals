package com.hcimbot.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hcimbot.game.GameActions
import com.hcimbot.game.GameState
import com.hcimbot.guide.Tile

/**
 * What an AI model may ask the bot to do when a step is stuck. A short fixed list: walk, click an NPC, object or item with a
 * named option, take a ground item, pick a chat or window option, continue, wait. Nothing else can be said, whatever the
 * model replies, and each is checked before it runs (see [AiExecutor]).
 */
sealed interface AiAction {
    data class Walk(val tile: Tile) : AiAction
    data class Npc(val id: Int, val option: String) : AiAction
    data class Obj(val id: Int, val option: String) : AiAction
    data class Item(val id: Int, val option: String) : AiAction
    data class Take(val id: Int) : AiAction
    data class Choose(val text: String) : AiAction
    object Continue : AiAction
    data class Wait(val ticks: Int) : AiAction

    companion object {
        const val MAX_ACTIONS = 8

        /** The actions in a model's reply: the first JSON array in it, each entry with a "do". Unknown entries are dropped. */
        fun parse(reply: String): List<AiAction> {
            val start = reply.indexOf('[')
            val end = reply.lastIndexOf(']')
            if (start < 0 || end <= start) return emptyList()
            val array = runCatching { JsonParser().parse(reply.substring(start, end + 1)) as? JsonArray }.getOrNull() ?: return emptyList()
            return array.mapNotNull { element -> (element as? JsonObject)?.let(::one) }.take(MAX_ACTIONS)
        }

        private fun one(o: JsonObject): AiAction? = runCatching {
            fun int(name: String) = o.get(name).asInt
            fun text(name: String) = o.get(name).asString.trim()
            when (text("do").lowercase()) {
                "walk" -> Walk(Tile(int("x"), int("y"), if (o.has("plane")) int("plane") else 0))
                "npc" -> Npc(int("id"), text("option"))
                "object" -> Obj(int("id"), text("option"))
                "item" -> Item(int("id"), text("option"))
                "take" -> Take(int("id"))
                "choose" -> Choose(text("text"))
                "continue" -> Continue
                "wait" -> Wait(int("ticks").coerceIn(1, 20))
                else -> null
            }
        }.getOrNull()
    }
}

/**
 * Carries out a list of [AiAction]s one tick at a time, checking each first: never into the Wilderness, never an Attack,
 * drop or destroy, and only things that are actually in sight. A refused action stops the list.
 */
class AiExecutor(private val plan: List<AiAction>, private val log: (String) -> Unit) {
    enum class State { Running, Done, Failed }

    private var index = 0
    private var ticks = 0
    private var waited = 0
    private var walkTicks = 0
    var failure: String? = null
        private set

    fun tick(game: GameState, actions: GameActions): State {
        if (failure != null) return State.Failed
        if (index >= plan.size) return State.Done
        if (++ticks > MAX_TICKS) return fail("took over $MAX_TICKS ticks")
        val action = plan[index]
        if (game.isBusy && action !is AiAction.Walk && action !is AiAction.Wait) return State.Running
        val ok: Boolean? = when (action) {
            is AiAction.Walk -> walk(action, game, actions)
            is AiAction.Npc -> click("NPC ${action.id} ${action.option}", action.option) {
                game.nearestNpc(listOf(action.id), emptyList())?.takeIf { it.id == action.id }?.let { actions.interactNpc(it, action.option) }
            }
            is AiAction.Obj -> click("object ${action.id} ${action.option}", action.option) {
                game.nearestObject(listOf(action.id), emptyList())?.takeIf { it.id == action.id }?.let { actions.interactObject(it, action.option) }
            }
            is AiAction.Item -> click("item ${action.id} ${action.option}", action.option) { actions.itemAction(action.id, action.option) }
            is AiAction.Take -> {
                log("AI: take ${action.id}")
                val taken = game.nearestGroundItem(listOf(action.id))?.takeIf { !it.tile.inWilderness }?.let { actions.takeGroundItem(it) }
                advance()
                taken
            }
            is AiAction.Choose -> {
                log("AI: choose \"${action.text}\"")
                val picked = if (game.dialogOptions().isNotEmpty()) actions.chooseDialogOption(action.text) else actions.clickChoice(action.text)
                advance()
                picked
            }
            AiAction.Continue -> {
                log("AI: continue")
                val continued = actions.continueDialog()
                advance()
                continued
            }
            is AiAction.Wait -> {
                if (++waited >= action.ticks) advance()
                true
            }
        }
        if (failure != null) return State.Failed
        if (ok == null || ok == false) return fail("could not do $action")
        return if (index >= plan.size) State.Done else State.Running
    }

    private fun walk(action: AiAction.Walk, game: GameState, actions: GameActions): Boolean {
        if (action.tile.inWilderness) {
            fail("walk into the Wilderness refused")
            return false
        }
        val at = game.playerTile ?: return true
        if (walkTicks == 0) log("AI: walk to ${action.tile}")
        if (at.plane == action.tile.plane && at.distanceTo(action.tile) <= 3) {
            advance()
            return true
        }
        if (++walkTicks > WALK_TICKS) {
            fail("could not reach ${action.tile}")
            return false
        }
        if (!game.isMoving) actions.walkTo(action.tile)
        return true
    }

    private fun click(what: String, option: String, block: () -> Boolean?): Boolean? {
        if (BLOCKED.containsMatchIn(option)) {
            fail("refused to $option")
            return false
        }
        log("AI: $what")
        val result = block()
        advance()
        return result
    }

    private fun advance() {
        index++
        waited = 0
        walkTicks = 0
    }

    private fun fail(why: String): State {
        failure = why
        log("AI plan stopped: $why")
        return State.Failed
    }

    private companion object {
        const val MAX_TICKS = 200
        const val WALK_TICKS = 60

        /** Fights and anything that destroys or gives away items are never started by the model. */
        val BLOCKED = Regex("attack|fight|drop|destroy|release|pickpocket|wilderness", RegexOption.IGNORE_CASE)
    }
}
