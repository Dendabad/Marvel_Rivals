package com.hcimbot.plan

import com.hcimbot.engine.BotSettings
import com.hcimbot.game.GameActions
import com.hcimbot.guide.Guide
import com.hcimbot.guide.ItemRef
import com.hcimbot.guide.Step
import com.hcimbot.guide.Tile
import com.hcimbot.route.RouteStore

/**
 * Hand written plans for steps whose guide data says less than the step's text.
 *
 * guide.json describes one action per step, so "Head North-West to Magic combat
 * tutor & drop Air & Mind Runes and claim more" only carries the tutor. A script
 * here spells the whole step out. Scripts are keyed by step id, so a step the
 * plugin's guide rewrites gets a new id and simply falls back to the planner.
 *
 * Ids come from RuneLite's gameval lists (ItemID, NpcID, ObjectID) and tiles
 * from guide.json and its Quest Helper data. Every task still fails with a
 * reason rather than guess, so a script that meets something unexpected
 * pauses the bot.
 */
class StepScripts(private val guide: Guide, private val routes: RouteStore? = null) {

    fun planFor(step: Step, settings: BotSettings): StepPlan? = scripts[step.id]?.invoke(settings)

    private fun auto(vararg tasks: Task, combat: Boolean = false, trust: Boolean = false) =
        StepPlan.Auto(tasks.toList(), trustTasks = trust, combat = combat)

    private fun skip(why: String) = StepPlan.Skip(why)

    private fun item(name: String, id: Int, count: Int? = null) = ItemRef(name, listOf(id), count)

    private fun talk(ids: List<Int>, name: String, at: Tile, dialogue: DialoguePlan) =
        InteractTask(InteractTask.Kind.NPC, ids, listOf(name), listOf(at), "Talk-to", dialogue)

    private fun obj(ids: List<Int>, names: List<String>, at: Tile, action: String?, near: Tile? = at) =
        InteractTask(InteractTask.Kind.OBJECT, ids, names, listOf(at), action, near = near)

    private fun has(id: Int, count: Int = 1): (TaskContext) -> Boolean = { it.conditions.count(listOf(id)) >= count }

    private fun onPlane(plane: Int): (TaskContext) -> Boolean = { it.game.playerTile?.plane == plane }

    private fun diaryBit(ctx: TaskContext, varp: Int, bit: Int): Boolean = (ctx.game.varp(varp) shr bit) and 1 == 1

    private fun quest(key: String, stopAt: Int, allowCombat: Boolean): Task {
        val helper = guide.questHelpers[key] ?: return Fail("no Quest Helper data for $key")
        return QuestTask(key, helper, allowCombat, stopAtValue = stopAt)
    }

    /** A task that pauses at once with [reason]. */
    private class Fail(private val reason: String) : Task {
        override val description = reason
        override fun tick(ctx: TaskContext) = TaskResult.Failed(reason)
    }

    private fun combat(settings: BotSettings, vararg tasks: Task): StepPlan =
        if (!settings.allowCombat) StepPlan.Manual("this step fights. Turn on Allow combat to let the bot do it.")
        else auto(*tasks, combat = true)

    private val scripts: Map<String, (BotSettings) -> StepPlan> = mapOf(
        // 1. "Starting Out"
        "332728ed58" to { _ -> skip("section heading") },

        // 2. "All Settings -> Disable level-up"
        "174485548c" to { _ -> auto(SettingTask(GameActions.DISABLE_LEVEL_UP)) },

        // 5. "Head North-West to Magic combat tutor & drop Air & Mind Runes and claim more."
        // The tutor only hands out runes to players who have none, so drop them, claim, then pick the dropped ones back up.
        "cce7a1b4dc" to { _ ->
            auto(
                WalkTask(MAGIC_TUTOR),
                DropTask(listOf(AIR_RUNE, MIND_RUNE)),
                // The tutor's right click "Claim" hands the runes out without the chat menu.
                InteractTask(
                    InteractTask.Kind.NPC, listOf(3218), listOf("Magic combat tutor"), listOf(MAGIC_TUTOR), "Claim",
                    // Should the claim open chat anyway, take the runes option, never "How do I make runes?".
                    DialoguePlan.Texts(listOf("air and mind", "claim")),
                ),
                PickUpTask(listOf(AIR_RUNE, MIND_RUNE)),
            )
        },

        // 6. "Sell Bronze Dagger, Sword, Wooden Shield & Shortbow to General Store. Buy a Spade Chisel & hammer"
        "42b86f0029" to { _ ->
            auto(
                WalkTask(LUMBRIDGE_GENERAL_STORE),
                SellTask(emptyList(), SHOPKEEPERS, LUMBRIDGE_GENERAL_STORE, listOf(BRONZE_DAGGER, BRONZE_SWORD, WOODEN_SHIELD, SHORTBOW)),
                ShopTask(
                    listOf(ShopTask.Seller("Shop keeper", emptyList(), listOf(LUMBRIDGE_GENERAL_STORE))),
                    listOf(item("Spade", 952), item("Chisel", 1755), item("Hammer", 2347)),
                ),
            )
        },

        // 8. "Check playtime on Hans": the diary ticks when Hans says how long you have played.
        // His right click "Age" says it without the chat menu; if that does not tick the task, ask him in chat.
        "d3c4ea0a99" to { _ ->
            val hans = Tile(3212, 3219, 0)
            auto(
                Optional(InteractTask(InteractTask.Kind.NPC, listOf(3105), listOf("Hans"), listOf(hans), "Age", DialoguePlan.Continue)),
                OnlyIf({ !diaryBit(it, LUMBRIDGE_DIARY, HANS_TASK_BIT) }, talk(listOf(3105), "Hans", hans, DialoguePlan.Texts(HANS_OPTIONS))),
            )
        },

        // 12. "Keep the Shrimps in your bank for later": nothing to do now.
        "caa42731e5" to { _ -> skip("a reminder to keep an item, nothing to do") },

        // 13. Bank layout, placeholders and bank PIN: tidying the player does not need.
        "a90bd856f8" to { _ -> skip("bank tidying the bot does not need") },

        // 15. "Go down south staircase twice (Right-Click 'Bottom-floor')"
        "39fa224085" to { _ ->
            auto(
                RepeatUntil("Down the Lumbridge south staircase", onPlane(0), {
                    obj(LUMBRIDGE_STAIRS, listOf("Staircase"), LUMBRIDGE_SOUTH_STAIRS, "Climb-down")
                }, maxRounds = 3),
            )
        },

        // 17. "Fill the jug & bowl on the sink in the kitchen"
        "9a570ec093" to { _ ->
            auto(
                UseItemTask(JUG_EMPTY, null, emptyList(), listOf("Sink"), LUMBRIDGE_KITCHEN),
                UseItemTask(BOWL_EMPTY, null, emptyList(), listOf("Sink"), LUMBRIDGE_KITCHEN),
            )
        },

        // 19. "Kill a giant rat with Wind Strike and collect the Bones + Raw Rat Meat"
        "9b78ddbf34" to { settings ->
            combat(settings, KillTask(emptyList(), listOf("Giant rat"), listOf(item("Bones", 526), item("Raw rat meat", 2134)), spell = "WIND_STRIKE"))
        },

        // 20. "Head West towards Draynor hugging the fence to avoid the Jail Guards"
        // The shortest path passes the guards, so this walks a route the player recorded.
        "6af6abb1f6" to { _ -> auto(FollowRouteTask(DRAYNOR_FENCE_ROUTE, routes)) },

        // 21. "Talk to Father Urhney in the lumbridge swamp for Ghost Speak Amulet. (2,1) Wield this."
        "ce4913f789" to { _ ->
            auto(
                talk(listOf(923), "Father Urhney", Tile(3144, 3178, 0), DialoguePlan.Numbered(listOf(2, 1))),
                EquipTask(listOf(item("Ghostspeak amulet", GHOSTSPEAK))),
            )
        },

        // 27. "Kill a Chicken. Take Everything (Make sure you get a feather, else hop worlds and repeat)"
        "0cd68d667b" to { settings ->
            combat(
                settings,
                KillTask(
                    listOf(1173, 1174, 9488, 3661, 3662, 2804),
                    listOf("Chicken"),
                    listOf(item("Feather", 314), item("Bones", 526), item("Raw chicken", 2138)),
                ),
            )
        },

        // 29. "...talk to Friendly Forester and obtain Forestry kit (right click shop and buy 1)"
        "f46e24b9c3" to { _ ->
            auto(ShopTask(listOf(ShopTask.Seller("Friendly Forester", listOf(11427, 6724), listOf(Tile(3096, 3238, 0)))), listOf(item("Forestry kit", 28136))))
        },

        // 32. "Collect Cheese from Aggie": the cheese lies in Aggie's house.
        "2727618762" to { _ -> auto(WalkTask(Tile(3086, 3259, 0)), TakeItemTask(listOf(item("Cheese", CHEESE)))) },

        // 35. "Go up the stairs and collect 4x Garlic from the cupboard" (Morgan's house)
        "1d0e75f508" to { _ ->
            auto(
                OnlyIf({ it.game.playerTile?.plane == 0 }, obj(listOf(15645), listOf("Staircase"), Tile(3100, 3267, 0), "Climb-up")),
                Optional(obj(listOf(2612), listOf("Cupboard"), Tile(3096, 3270, 1), "Open")),
                RepeatUntil("Search the cupboard for 4 garlic", has(GARLIC, 4), {
                    obj(listOf(2613), listOf("Cupboard"), Tile(3096, 3270, 1), "Search")
                }, maxRounds = 8),
            )
        },

        // 36. The tip to spam the cupboard, which script 35 already does.
        "1d41a5add4" to { _ -> skip("tip covered by the garlic step") },

        // 38. "Bank at Draynor and deposit the Garlic & Cheese"
        "9db23d849e" to { _ -> auto(DepositItemsTask(listOf(GARLIC, CHEESE))) },

        // 39. "Talk to Fortunato & buy 5 Jugs of wine. Hop worlds and buy 10 in total."
        "e3eead8e86" to { _ ->
            val fortunato = listOf(ShopTask.Seller("Fortunato", listOf(1260), listOf(Tile(3085, 3252, 0))))
            auto(
                ShopTask(fortunato, listOf(item("Jug of wine", JUG_WINE, 5))),
                HopTask(),
                ShopTask(fortunato, listOf(item("Jug of wine", JUG_WINE, 10))),
            )
        },

        // 43. "Destroy the lamp. You can collect this later as you can't bank it."
        "90957d3c70" to { _ -> auto(ItemActionTask(XMARKS_LAMP, "Destroy", DialoguePlan.Texts(listOf("Yes")))) },

        // 44. "Travel to Port Piscarilius via Veos"
        "12c39282b4" to { _ ->
            auto(
                InteractTask(
                    InteractTask.Kind.NPC, VEOS, listOf("Veos"), listOf(Tile(3054, 3245, 0)), "Travel",
                    DialoguePlan.Texts(listOf("Piscarilius", "Yes")),
                ),
            )
        },

        // 47. "Open Minimap & follow the path South-West to Shayzien"
        "a2f6e1e60a" to { _ -> auto(WalkTask(Tile(1519, 3592, 0), radius = 10)) },

        // 56. "Search the Coffin, south-east in the graveyard. Talk to Restless Ghost (1)"
        "64c83f7e13" to { settings ->
            auto(
                Optional(EquipTask(listOf(item("Ghostspeak amulet", GHOSTSPEAK)))),
                quest("therestlessghost", stopAt = 3, allowCombat = settings.allowCombat),
                trust = true,
            )
        },

        // 59 to 61 are about the Stronghold of Security, which stays a manual step for now.
        "3c394b81d3" to { _ -> skip("Stronghold advice") },
        "0d2ffd5660" to { _ -> skip("Stronghold advice") },

        // 68. "RIGHT CLICK Use the bones on the Stray Dog whenever you find him"
        "3ad09c7fc7" to { _ -> auto(Optional(UseItemOnNpcTask(526, listOf(2902, 10675, 2922)))) },

        // 72. "Buy Pink Skirt, Brown Apron & Silk. Wear Brown Apron & Pink Skirt in honour of Faux"
        "ff702e072d" to { _ ->
            val skirt = item("Pink skirt", 1013)
            val apron = item("Brown apron", 1757)
            auto(
                ShopTask(listOf(ShopTask.Seller("Thessalia", listOf(10477, 10478), listOf(Tile(3206, 3416, 0)))), listOf(skirt, apron, item("Silk", 950))),
                EquipTask(listOf(apron, skirt)),
            )
        },

        // 75. "Head North" (towards Romeo, the next step)
        "e7e42b5eb3" to { _ -> auto(WalkTask(Tile(3211, 3425, 0), radius = 6)) },

        // 84. "Search for book and talk to Reldo again (1)"
        "811c3bd7b5" to { settings ->
            auto(
                RepeatUntil("Search the bookcase for the book", has(ARRAV_BOOK), {
                    obj(listOf(2402), listOf("Bookcase"), Tile(3212, 3493, 0), "Search")
                }, maxRounds = 4),
                ItemActionTask(ARRAV_BOOK, "Read"),
                quest("shieldofarravphoenixgang", stopAt = 3, allowCombat = settings.allowCombat),
                trust = true,
            )
        },

        // 85. "Head East and up the stairs in the Kitchen."
        "0bb322cd46" to { _ ->
            auto(OnlyIf({ it.game.playerTile?.plane == 0 }, obj(listOf(11789), listOf("Staircase"), VARROCK_KITCHEN_STAIRS, "Climb-up")))
        },

        // 86. "Collect both buckets and fill one up downstairs. Use Bucket of Water on drain outside then refill it."
        "40dc307ca8" to { _ ->
            auto(
                TakeItemTask(listOf(item("Bucket", BUCKET_EMPTY, 2))),
                OnlyIf({ it.game.playerTile?.plane == 1 }, obj(emptyList(), listOf("Staircase"), VARROCK_KITCHEN_STAIRS.copy(plane = 1), "Climb-down")),
                UseItemTask(BUCKET_EMPTY, null, emptyList(), listOf("Sink"), VARROCK_KITCHEN),
                UseItemTask(BUCKET_WATER, null, listOf(QUEST_DRAIN), listOf("Drain"), VARROCK_KITCHEN),
                UseItemTask(BUCKET_EMPTY, null, emptyList(), listOf("Sink"), VARROCK_KITCHEN),
            )
        },

        // 97. "Talk to Old Man Yarlo, then destroy all the furniture in the house"
        "711ae4d841" to { settings ->
            auto(
                quest("daddyshome", stopAt = 2, allowCombat = settings.allowCombat),
                RepeatUntil("Remove the broken furniture", { it.game.nearestObject(BROKEN_FURNITURE, near = YARLO_HOUSE) == null }, {
                    obj(BROKEN_FURNITURE, emptyList(), YARLO_HOUSE, null)
                }, maxRounds = 12),
                trust = true,
            )
        },

        // 99. "Take 2x Logs & 2x ashes after cutscene"
        "86ad75cbe2" to { _ -> auto(TakeItemTask(listOf(item("Logs", 1511, 2), item("Ashes", 592, 2)))) },

        // 100. "Search crates for 3x Waxwood log"
        "d6dacf88c9" to { _ ->
            auto(
                RepeatUntil("Search the crates for 3 waxwood logs", has(WAXWOOD_LOG, 3), {
                    obj(listOf(40214), listOf("Crates"), Tile(3243, 3398, 0), "Search")
                }, maxRounds = 10),
            )
        },
    )

    companion object {
        // Places used by several scripts.
        private val LUMBRIDGE_SOUTH_STAIRS = Tile(3206, 3208, 0)
        private val LUMBRIDGE_KITCHEN = Tile(3210, 3214, 0)
        private val LUMBRIDGE_GENERAL_STORE = Tile(3212, 3246, 0)
        private val MAGIC_TUTOR = Tile(3218, 3238, 0)
        private val VARROCK_KITCHEN_STAIRS = Tile(3219, 3497, 0)
        private val VARROCK_KITCHEN = Tile(3222, 3495, 0)
        private val YARLO_HOUSE = Tile(3244, 3396, 0)

        /** The recorded route for step 20. Record it under this name. */
        const val DRAYNOR_FENCE_ROUTE = "draynor-fence"

        private const val LUMBRIDGE_DIARY = 1194
        private const val HANS_TASK_BIT = 5

        /** Wordings of Hans's play time question, most specific first. */
        private val HANS_OPTIONS = listOf("how long", "been here", "playing", "played", "age")

        // Ids from RuneLite's gameval lists.
        private const val AIR_RUNE = 556
        private const val MIND_RUNE = 558
        private const val BRONZE_DAGGER = 1205
        private const val BRONZE_SWORD = 1277
        private const val WOODEN_SHIELD = 1171
        private const val SHORTBOW = 841
        private const val JUG_EMPTY = 1935
        private const val BOWL_EMPTY = 1923
        private const val BUCKET_EMPTY = 1925
        private const val BUCKET_WATER = 1929
        private const val GHOSTSPEAK = 552
        private const val CHEESE = 1985
        private const val GARLIC = 1550
        private const val JUG_WINE = 1993
        private const val XMARKS_LAMP = 23072
        private const val ARRAV_BOOK = 757
        private const val WAXWOOD_LOG = 24938
        private const val QUEST_DRAIN = 2843
        private val SHOPKEEPERS = listOf("Shop keeper", "Shop assistant")
        private val VEOS = listOf(2850, 8484, 8630, 10723)
        private val LUMBRIDGE_STAIRS = listOf(16671, 16672, 16673)

        /** The broken stool, chair, table, bed and carpet in Old Man Yarlo's house, once he has asked for them gone. */
        private val BROKEN_FURNITURE = listOf(40216, 40220, 40226, 40230, 40236)
    }
}
