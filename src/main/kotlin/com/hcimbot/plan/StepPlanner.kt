package com.hcimbot.plan

import com.hcimbot.engine.BotSettings
import com.hcimbot.guide.Guide
import com.hcimbot.guide.ItemRef
import com.hcimbot.guide.Step
import com.hcimbot.guide.Target
import com.hcimbot.guide.Travel
import com.hcimbot.guide.toTile

sealed interface StepPlan {
    /**
     * Run [tasks] in order.
     *
     * [trustTasks]: once the tasks finish the step is done, even when the
     * plugin's own check would wait for something later (used for a quest step
     * that is one part of a quest).
     *
     * [confirm]: the tasks cover the step's main action but the text may ask
     * for a little more ("withdraw and equip"), so the bot pauses afterwards for
     * the player to confirm.
     *
     * [combat]: the step fights on purpose, so being attacked is expected.
     */
    data class Auto(
        val tasks: List<Task>,
        val trustTasks: Boolean = false,
        val confirm: Boolean = false,
        val combat: Boolean = false,
    ) : StepPlan

    /** The bot cannot do this step. It pauses and shows [reason] until the step is ticked. */
    data class Manual(val reason: String) : StepPlan
}

/**
 * Turns a guide step into tasks, using only the structured data guide.json
 * carries for it. Anything the data does not pin down becomes a manual step,
 * so the bot pauses rather than guess on a hardcore account.
 */
class StepPlanner(private val guide: Guide, private val settings: BotSettings) {

    fun plan(step: Step): StepPlan {
        if (step.isNote) return StepPlan.Manual("Note: ${step.text}")
        if (step.advice && settings.pauseOnAdvice) return StepPlan.Manual("Tip: ${step.text}")

        quest(step)?.let { return it }
        if (isOpenEnded(step)) return manual(step, "depends on a condition or repeats")
        if (step.banking || step.withdraw || step.depositsAll) return bank(step)
        if (isCompound(step)) return manual(step, "asks for several things")

        step.spell?.let { spell -> return StepPlan.Auto(listOf(CastSpellTask(spell.name, spell.widget))) }
        step.teleport?.takeIf { it.ids.isNotEmpty() }?.let { teleport ->
            val destination = teleportDestination(step) ?: return manual(step, "unclear where to teleport")
            if (FAIRY_RING.matches(destination)) return manual(step, "fairy ring codes are not automated")
            return StepPlan.Auto(listOf(ItemTeleportTask(teleport.via, teleport.ids, destination)))
        }
        step.travel?.let { travel -> return travel(step, travel) }
        if (step.sellers.isNotEmpty()) return shop(step)
        step.inventoryAction?.let { action -> inventoryAction(step, action.highlightIds)?.let { return it } }

        val target = step.target
        if (target != null && target.kind == "npc") return npc(step, target)
        if (target != null && target.kind == "object") return obj(step, target)

        // Only a step that is just movement ("Head to Canifis") becomes a walk.
        // "Teleport to the GE" or "Complete the Agility Pyramid" also carry a
        // location, but walking there is not what they ask.
        val arrival = step.arrivesAt.toTile() ?: target?.takeIf { it.kind == "place" }?.tiles?.firstOrNull()
        if (arrival != null && isMovement(step)) return StepPlan.Auto(listOf(WalkTask(arrival)))

        return StepPlan.Manual(step.text)
    }

    private fun quest(step: Step): StepPlan? {
        val key = step.questHelper ?: return null
        if (!step.questStep && !step.questFollow) return null
        val helper = guide.questHelperFor(step) ?: return manual(step, "no Quest Helper data for $key")
        if (step.questStopUnresolved) return manual(step, "the guide's stopping point for this quest is not machine readable")

        val carriesOn = step.questFollow || step.questCompletes || step.questStopValue != null ||
            step.questStopCondition != null || step.questStopItems.isNotEmpty()
        return if (carriesOn) {
            StepPlan.Auto(listOf(QuestTask(key, helper, settings.allowCombat)), combat = settings.allowCombat)
        } else {
            val ids = step.target?.ids.orEmpty().toSet() + step.approach?.ids.orEmpty()
            StepPlan.Auto(listOf(QuestTask(key, helper, settings.allowCombat, boundedTo = ids)), trustTasks = true, combat = settings.allowCombat)
        }
    }

    private fun bank(step: Step): StepPlan {
        val text = step.text.lowercase()
        // "Deposit all except the Ice Gloves" must not deposit the gloves.
        if (Regex("\\b(except|keep|but)\\b").containsMatchIn(text)) return manual(step, "deposits only some items")
        val withdraw = if (step.withdraw) step.items else emptyList()
        if ("withdraw" in text && withdraw.isEmpty()) return manual(step, "does not say exactly what to withdraw")
        val unresolved = withdraw.filter { it.ids.isEmpty() }
        if (unresolved.isNotEmpty()) return manual(step, "cannot identify ${unresolved.joinToString { it.name }}")
        if (isCompound(step, ignoringBanking = true)) return manual(step, "asks for more than banking")

        val tasks = mutableListOf<Task>()
        step.target?.takeIf { it.kind == "place" || it.kind == "bank" }?.tiles?.firstOrNull()?.let { tasks += WalkTask(it, radius = 10) }
        tasks += BankTask(depositAll = step.depositsAll, withdraw = withdraw)
        return StepPlan.Auto(tasks, confirm = Regex("\\b(equip|wear|wield)\\b").containsMatchIn(text))
    }

    private fun travel(step: Step, travel: Travel): StepPlan {
        val departures = travel.departures.mapNotNull { departure ->
            val origin = departure.origin.toTile() ?: return@mapNotNull null
            val entry = departure.action ?: return@mapNotNull null
            TravelTask.Departure(origin, departure.id, entry)
        }
        if (departures.isEmpty()) return StepPlan.Manual(step.text)
        return StepPlan.Auto(listOf(TravelTask(travel.place, departures, travel.destination.toTile())))
    }

    private fun shop(step: Step): StepPlan {
        if (Regex("\\bsell\\b").containsMatchIn(step.text.lowercase())) return manual(step, "selling is not automated")
        // "Buy 2,100 Iron Arrowtips": guide.json's item counts do not read thousands.
        if (Regex("\\d,\\d").containsMatchIn(step.text)) return manual(step, "unsure of the amount to buy")
        val items = buyable(step)
        if (items.isEmpty()) return manual(step, "cannot identify what to buy")
        val sellers = step.sellers
            .map { ShopTask.Seller(it.name, it.ids, it.points.mapNotNull { p -> p.toTile() }) }
            .filter { it.ids.isNotEmpty() && it.tiles.isNotEmpty() }
        if (sellers.isEmpty()) return manual(step, "no shop location known")
        return StepPlan.Auto(listOf(ShopTask(sellers, items)))
    }

    private fun buyable(step: Step): List<ItemRef> {
        if (step.items.isNotEmpty()) {
            // Every item the step names must be known, or the bot would leave some unbought.
            return if (step.items.all { it.ids.isNotEmpty() }) step.items else emptyList()
        }
        val target = step.target ?: return emptyList()
        return if (target.kind == "item" && target.ids.isNotEmpty()) listOf(ItemRef(target.name, target.ids)) else emptyList()
    }

    private fun inventoryAction(step: Step, ids: List<Int>): StepPlan? {
        val objectIds = step.target?.takeIf { it.kind == "object" }?.ids.orEmpty()
        val task = when {
            ids.size >= 2 -> UseItemTask(ids[0], ids[1])
            ids.size == 1 && objectIds.isNotEmpty() -> UseItemTask(ids[0], null, objectIds)
            else -> return null
        }
        return StepPlan.Auto(listOf(task))
    }

    private fun npc(step: Step, target: Target): StepPlan {
        if (target.ids.isEmpty() && target.allNames.isEmpty()) return StepPlan.Manual(step.text)
        val verb = verb(step) ?: if (step.dialogue.isNotEmpty()) "Talk-to" else null
        if (verb == "Attack" && !settings.allowCombat) return manual(step, "combat is turned off for safety")
        // A left click on a monster attacks it, so never click an NPC without knowing the option.
        if (verb == null) return manual(step, "unclear what to click")
        val tasks = approach(step) + InteractTask(
            kind = InteractTask.Kind.NPC,
            ids = target.ids,
            names = target.allNames,
            tiles = target.tiles,
            action = verb,
            dialogue = dialogue(step),
            repeat = step.completion != null,
        )
        return StepPlan.Auto(tasks, combat = verb == "Attack")
    }

    private fun obj(step: Step, target: Target): StepPlan {
        if (target.ids.isEmpty() && target.allNames.isEmpty()) return StepPlan.Manual(step.text)
        // Left clicks on objects include things like crossing the Wilderness ditch.
        val verb = verb(step) ?: return manual(step, "unclear what to click")
        val tasks = approach(step) + InteractTask(
            kind = InteractTask.Kind.OBJECT,
            ids = target.ids,
            names = target.allNames,
            tiles = target.tiles,
            action = verb,
            dialogue = dialogue(step),
            repeat = step.completion != null,
        )
        return StepPlan.Auto(tasks)
    }

    /** Stairs or a ladder the guide says to take first, e.g. "Go upstairs and talk to...". */
    private fun approach(step: Step): List<Task> {
        val approach = step.approach ?: return emptyList()
        val point = approach.point.toTile() ?: return emptyList()
        if (approach.ids.isEmpty()) return emptyList()
        val destination = step.target?.tiles?.firstOrNull() ?: return emptyList()
        val action = if (destination.plane > point.plane) "Climb-up" else "Climb-down"
        val climb = InteractTask(InteractTask.Kind.OBJECT, approach.ids, emptyList(), listOf(point), action)
        return listOf(OnlyIf({ ctx -> ctx.game.playerTile?.plane != destination.plane }, climb))
    }

    private fun dialogue(step: Step): DialoguePlan =
        if (step.dialogue.isEmpty()) DialoguePlan.None else DialoguePlan.Numbered(step.dialogue.first())

    private fun manual(step: Step, why: String) = StepPlan.Manual("${step.text} ($why)")

    companion object {
        private val VERBS = listOf(
            "talk to" to "Talk-to",
            "speak to" to "Talk-to",
            "pickpocket" to "Pickpocket",
            "steal from" to "Steal-from",
            "search" to "Search",
            "open" to "Open",
            "climb up" to "Climb-up",
            "climb down" to "Climb-down",
            "enter" to "Enter",
            "pick" to "Pick",
            "kill" to "Attack",
            "attack" to "Attack",
            "fight" to "Attack",
            "mine" to "Mine",
            "chop" to "Chop down",
            "pray at" to "Pray-at",
            "trade" to "Trade",
            "take" to "Take",
        )

        /** Words that start an action. Two of them in one step means it asks for several things. */
        private val ACTION_WORDS = setOf(
            "talk", "speak", "kill", "attack", "fight", "bop", "collect", "pick", "take", "grab", "loot", "get",
            "buy", "sell", "wear", "equip", "wield", "deposit", "withdraw", "bank", "use", "teleport", "tele",
            "enter", "exit", "leave", "climb", "search", "open", "close", "cut", "chop", "mine", "fish", "cook",
            "craft", "smith", "make", "fletch", "light", "burn", "bury", "drop", "pray", "cast", "catch", "fill",
            "empty", "trade", "steal", "pickpocket", "give", "read", "dig", "cross", "jump", "complete", "continue",
            "start", "train", "claim", "check", "drink", "eat", "repair", "build", "plant", "harvest", "charter",
            "receive", "spin", "smelt", "tan", "turn", "wait", "hop", "disable", "enable", "set", "toggle",
            "travel", "home", "minigame",
        )

        /** Movement clauses, which every task already does on its way. */
        private val MOVEMENT = Regex("^(head|go|walk|run|return|make your way)\\b")

        private val FAIRY_RING = Regex("(?i)[a-d][i-l][p-s]")

        /** Words that make a step conditional or open ended, which the bot cannot judge. */
        private val OPEN_ENDED = Regex("\\b(if|until|optional|optionally|roughly|as many|per inv|unless|or)\\b")

        /**
         * The menu option the step's text asks for. A verb only counts at the
         * start of an instruction, so "Talk to Horace" gives Talk-to but the
         * place name in "Teleport to Essence Mine" does not give Mine.
         */
        fun verb(step: Step): String? = verb(step.text)

        fun verb(text: String): String? {
            for (clause in clauses(text)) {
                VERBS.firstOrNull { (phrase, _) -> clause == phrase || clause.startsWith("$phrase ") }
                    ?.let { return it.second }
            }
            return null
        }

        /**
         * True when the text asks for more than one action ("Kill a rat and
         * collect the bones"). Leading movement ("Head South & pray at the
         * altar") and item lists ("Buy 5x Cloth & 100 Nails") do not count.
         */
        fun isCompound(step: Step, ignoringBanking: Boolean = false): Boolean {
            var text = step.text
            if (ignoringBanking) {
                text = text.lowercase().replace(Regex("deposit all,? (then )?|and deposit all|bank at [^,.&]*|withdraw:?"), "")
            }
            return clauses(text).count { clause ->
                !MOVEMENT.containsMatchIn(clause) && clause.substringBefore(' ').trim(':') in ACTION_WORDS
            } > 1
        }

        fun isOpenEnded(step: Step): Boolean = OPEN_ENDED.containsMatchIn(stripped(step.text))

        /** True when the step only moves the player: it starts with movement and asks for no action after it. */
        fun isMovement(step: Step): Boolean {
            val clauses = clauses(step.text)
            return clauses.firstOrNull()?.let { MOVEMENT.containsMatchIn(it) } == true &&
                clauses.none { !MOVEMENT.containsMatchIn(it) && it.substringBefore(' ') in ACTION_WORDS }
        }

        /**
         * Where an item teleport goes: "Ardougne: Ardy Cloak 1", "Games necklace
         * -> Wintertodt", or the step's place. "-> Run North" is what to do after
         * landing, not a destination.
         */
        fun teleportDestination(step: Step): String? {
            step.teleport?.via?.takeIf { ':' in it }?.substringBefore(':')?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            val arrow = step.text.split("->").getOrNull(1)?.let { clauses(it).firstOrNull() }
            if (arrow != null && !MOVEMENT.containsMatchIn(arrow)) return arrow
            return step.target?.takeIf { it.kind == "place" }?.name?.takeIf { it.isNotBlank() }
        }

        /** The step's instructions, lowercased, with [tags] and (chat numbers) removed. */
        private fun clauses(text: String): List<String> =
            stripped(text)
                .split(Regex(",|&|;|->|[.!]\\s|\\band\\b|\\bthen\\b"))
                .map { it.trim(' ', '.', '!') }
                .filter { it.isNotEmpty() }

        private fun stripped(text: String) =
            text.lowercase().replace(Regex("\\[[^]]*]"), "").replace(Regex("\\([^)]*\\)"), "")
    }
}
