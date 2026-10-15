package com.hcimbot.plan

import com.hcimbot.engine.BotSettings
import com.hcimbot.game.GameActions
import com.hcimbot.guide.Guide
import com.hcimbot.game.canReachBeside
import com.hcimbot.guide.ItemRef
import com.hcimbot.guide.Step
import com.hcimbot.guide.Tile

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
class StepScripts(private val guide: Guide) {

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

    /**
     * Visits Aubury in south east Varrock (3253, 3401) for the Research notes if the bag has none. The bank had none in step 143,
     * and the Wizards' Tower in step 148 needs them for Sedridor. Each task is decided as it starts, so once the notes are in the
     * bag the rest are skipped.
     */
    private fun notesFromAubury(): List<Task> {
        val noNotes: (TaskContext) -> Boolean = { ctx -> !has(291)(ctx) }
        val askForNotes = DialoguePlan.Texts(listOf("notes", "lost", "research", "package"))
        return listOf(
            OnlyIf(noNotes, WalkTask(Tile(3253, 3400, 0), radius = 3)),
            OnlyIf(noNotes, InteractTask(InteractTask.Kind.NPC, listOf(11434, 2886, 11435, 10681), listOf("Aubury"), listOf(Tile(3253, 3401, 0)), "Talk-to", askForNotes)),
            OnlyIf(noNotes, WaitUntilTask("get the Research notes from Aubury", 30, askForNotes, has(291))),
        )
    }

    /** Step 105 still has logs to collect: under 11 held, and the 10 planks not yet made. */
    private fun needsLogs(ctx: TaskContext): Boolean =
        (ctx.game.inventory()[LOGS] ?: 0) < 11 && (ctx.game.inventory()[PLANK] ?: 0) < 10

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

        // 10. "Go upstairs again and Collect 4x logs on the top floor"
        // The guide has no place for the logs, so go up to the top floor first. They
        // lie in the room by the bank, well within reach of the top of the stairs.
        "7589a86b77" to { _ ->
            auto(
                WalkTask(LUMBRIDGE_TOP_FLOOR, radius = 3),
                TakeItemTask(listOf(item("Logs", LOGS, 4))),
            )
        },

        // 12. "Keep the Shrimps in your bank for later": nothing to do now.
        "caa42731e5" to { _ -> skip("a reminder to keep an item, nothing to do") },

        // 13. Bank layout, placeholders and bank PIN: tidying the player does not need.
        "a90bd856f8" to { _ -> skip("bank tidying the bot does not need") },

        // 65. "Collect a Doogle Leaf south of Gertrude": the leaves lie on the ground just south of her house.
        "5227d55cd4" to { _ ->
            auto(WalkTask(SOUTH_OF_GERTRUDE, radius = 3), TakeItemTask(listOf(item("Doogle leaves", DOOGLE_LEAVES))))
        },

        // 73. "Head South to the Sword Shop and go up the ladder. Loot the lit candle."
        // Microbot's walker knows the shop's ladder, so walking upstairs climbs it.
        "81e3343b7f" to { _ ->
            auto(WalkTask(ABOVE_SWORD_SHOP, radius = 2), TakeItemTask(listOf(ItemRef("Lit candle", listOf(LIT_CANDLE, CANDLE)))))
        },

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
            combat(settings, KillTask(
                emptyList(), listOf("Giant rat"), listOf(item("Bones", 526), item("Raw rat meat", 2134)),
                spell = "WIND_STRIKE", lookFirst = BEHIND_LUMBRIDGE_CASTLE,
            ))
        },


        // 20. "Head West towards Draynor hugging the fence to avoid the Jail Guards"
        // Only a direction to set off in: the next step is Father Urhney in Lumbridge
        // Swamp, and step 21 walks straight to him. guide.json's point is the middle of
        // Draynor Village, which the route never goes to.
        "6af6abb1f6" to { _ -> skip("only directions, step 21 walks to Father Urhney") },

        // 23. "Head to the Wizards Tower": guide.json's spot is the north end of the bridge,
        // where the walk would stop. Step 24 walks all the way in, so nothing to do here.
        "0ad397ae61" to { _ -> skip("step 24 walks into the Wizards' Tower") },

        // 24. "Take Leather Boots off the table & wield"
        // Go inside the tower first, since the table cannot be seen from outside. The
        // walker opens the door.
        "a778106253" to { _ ->
            auto(
                WalkTask(INSIDE_WIZARDS_TOWER, radius = 3),
                TakeItemTask(listOf(item("Leather boots", LEATHER_BOOTS))),
                EquipTask(listOf(item("Leather boots", LEATHER_BOOTS))),
            )
        },

        // 37. "Train Draynor Agility to 5 Agility [Lumbridge Easy Diary]"
        // Laps of the rooftop course until both the level and the diary task are done.
        "0f6d8ccd8b" to { _ -> StepPlan.Auto(listOf(AgilityCourseTask(AgilityCourse.DRAYNOR)), patient = true) },

        // 44. "Travel to Port Piscarilius via Veos": guide.json has no travel data for it, so it
        // planned as a plain "Travel" click, which Microbot cannot find on Veos (his base
        // definition has only Talk-to). The travel task talks and says yes instead. The boat
        // lands at Port Piscarilius dock, where step 45 talks to Veos again, so done on arrival.
        "12c39282b4" to { _ ->
            auto(
                TravelTask("Port Piscarilius", listOf(TravelTask.Departure(VEOS_PORT_SARIM, VEOS.first(), "Travel Veos", VEOS.drop(1))), PISCARILIUS_DOCK),
                trust = true,
            )
        },

        // 58. "Complete 3 Floors of Stronghold of Security": walk to each floor's reward and claim
        // it. Microbot's walker knows the entrance, the ladders between floors and the security
        // doors, which it answers. 2,000 + 3,000 + 5,000 coins makes the 10,000 step 61 expects.
        // Steps 59 and 60: spiders hit 7 and Catablepons 9, so drink a wine on any damage.
        "5405a57b1e" to { _ ->
            StepPlan.Auto(
                listOf(
                    // Each floor only while not already past it, so a restart deep inside never goes back up.
                    OnlyIf({ strongholdFloor(it) <= 1 }, WalkTask(GIFT_OF_PEACE, radius = 2)),
                    OnlyIf({ strongholdFloor(it) <= 1 }, InteractTask(InteractTask.Kind.OBJECT, listOf(SOS_WAR_CHEST), listOf("Gift of Peace"), listOf(GIFT_OF_PEACE), null, DialoguePlan.Continue)),
                    OnlyIf({ strongholdFloor(it) <= 2 }, WalkTask(GRAIN_OF_PLENTY, radius = 2)),
                    OnlyIf({ strongholdFloor(it) <= 2 }, InteractTask(InteractTask.Kind.OBJECT, listOf(SOS_FAM_SACK), listOf("Grain of Plenty"), listOf(GRAIN_OF_PLENTY), null, DialoguePlan.Continue)),
                    WalkTask(BOX_OF_HEALTH, radius = 2),
                    InteractTask(InteractTask.Kind.OBJECT, listOf(SOS_PEST_CHEST), listOf("Box of Health"), listOf(BOX_OF_HEALTH), null, DialoguePlan.Continue),
                ),
                // Being attacked in there is expected: pausing to stand still would be worse.
                combat = true,
                patient = true,
                drinkOnDamage = listOf(JUG_WINE),
                acceptWarnings = STRONGHOLD_WARNINGS,
                walkerOverrides = NO_PORTALS,
            )
        },

        // 61. "Once you have 10,000GP from Floor 3, leave via the ropes": back to the surface,
        // the walker climbs the vines, ladders and chain on the way.
        "b2738b149e" to { _ ->
            StepPlan.Auto(
                listOf(WalkTask(STRONGHOLD_ENTRANCE, radius = 4)),
                combat = true, patient = true, drinkOnDamage = listOf(JUG_WINE), acceptWarnings = STRONGHOLD_WARNINGS,
                walkerOverrides = NO_PORTALS,
            )
        },

        // 74. "Head East and talk to Dr Harlow (1,2 talk again) [Vampyre Slayer]"
        // Quest Helper sends a player with no garlic back to Morgan's cupboard. The garlic was banked at
        // step 38, so take it from the bank first, and only if the bank has none let Quest Helper
        // send the bot for it.
        "8c44826480" to { settings ->
            val helper = guide.questHelpers["vampyreslayer"]
            if (helper == null) {
                auto(Fail("no Quest Helper data for vampyreslayer"))
            } else {
                auto(
                    Optional(OnlyIf({ GARLIC !in it.game.inventory() }, BankTask(false, listOf(item("Garlic", GARLIC, 1))))),
                    QuestTask("vampyreslayer", helper, settings.allowCombat, boundedTo = HARLOW, finishesQuest = true, doneWithItem = STAKE),
                    trust = true,
                    combat = settings.allowCombat,
                )
            }
        },

        // 82. "Head North and go up 2x Stairs and talk to Captain Rovin (3,1,2,2,3,1) [Demon Slayer]": the step is
        // getting Captain Rovin's key (Quest Helper's Silverlight key 2400). Once held, the bot must not go on to
        // the bucket and the drain, which are the steps after.
        "087f8c0dc8" to { settings ->
            val helper = guide.questHelpers["demonslayer"]
            if (helper == null) {
                auto(Fail("no Quest Helper data for demonslayer"))
            } else {
                auto(
                    QuestTask("demonslayer", helper, settings.allowCombat, boundedTo = setOf(5085), doneWithItem = ROVINS_KEY),
                    trust = true,
                )
            }
        },

        // 96. "Light both logs in the house right next to the rune shop": Old Man Yarlo's house, east of
        // the castle (the next step talks to him there). The tinderbox was withdrawn at step 94.
        "08e4afe002" to { _ ->
            auto(LightLogsTask(listOf(LOGS), TINDERBOX, count = 2, around = YARLOS_HOUSE), trust = true)
        },

        // 91. "Enter Varrock Sewer and take the North-West path to collect the Demon Slayer Key"
        // The key is Quest Helper's QIP_DS_SEWER_KEY at (3225, 9897), the third key, dropped there when
        // the bucket of water went down the drain at step 86. The walker climbs down the manhole. The giant
        // rats in the way hit for 1 at most, so a fight is not a reason to stop.
        "578b1fb44c" to { _ ->
            StepPlan.Auto(
                listOf(
                    WalkTask(SEWER_KEY, radius = 2),
                    InteractTask(InteractTask.Kind.OBJECT, listOf(SEWER_KEY_OBJECT), listOf("Rusty key"), listOf(SEWER_KEY), null),
                ),
                trustTasks = true, combat = true,
            )
        },

        // 92. "Enter Varrock Museum ... complete the Natural History Quiz": the quiz asks questions
        // that need an answer table the bot does not have, and it only gives 9 Slayer and Hunter XP and
        // kudos, so it is left out rather than guessed at.
        "b721b17da2" to { _ -> skip("the Natural History Quiz needs an answer table the bot does not have, and only gives a little Slayer and Hunter XP") },

        // 78. "Talk to Shilop to continue Gertrudes Cat (2,2)": only the talk. Following Quest Helper here
        // sent the bot off for the doogle leaves and sardine, which the guide fetches at step 201.
        "f374255214" to { _ ->
            auto(talk(listOf(3501), "Shilop", Tile(3220, 3435, 0), DialoguePlan.Numbered(listOf(2, 2))), trust = true)
        },

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
        // The Stray Dog wanders Varrock: fetch a bone if there is none, walk a round of the places
        // he is seen, and give it to him. The game never shows this as done, so it ends with its actions.
        "3ad09c7fc7" to { _ ->
            auto(
                OnlyIf({ BONES !in it.game.inventory() }, BankTask(false, listOf(item("Bones", BONES, 1)))),
                FindAndUseTask(BONES, "Stray dog", VARROCK_ROUND),
                trust = true,
            )
        },

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
                // The buckets are upstairs in the kitchen. Step 85 climbs there, but from anywhere else on the
                // ground floor this step has to as well, or the buckets are never in sight.
                OnlyIf({ it.game.playerTile?.plane == 0 && BUCKET_EMPTY !in it.game.inventory() }, obj(listOf(11789), listOf("Staircase"), VARROCK_KITCHEN_STAIRS, "Climb-up")),
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
                // Quest Helper points at each piece in turn. Clicking the old ids went on to rebuild the
                // piece just removed, which leaves a build spot with the same object ids.
                HelperObjectsTask(Regex("^(demolish|remove|right-click remove)", RegexOption.IGNORE_CASE), "daddyshome"),
                trust = true,
            )
        },

        // 101. "Bank the ashes": the ashes from step 99, into the nearest bank. Left to the planner this was a bank step
        // with nothing to deposit, which finished at once without going to a bank.
        "758b5743b7" to { _ -> auto(DepositItemsTask(listOf(ASHES))) },

        // 102. "Head North-East to the Lumberyard": the sawmill, where the next steps are. The guide's own point for
        // "Lumberyard" is a spot north of it, past the fence step 103 climbs.
        "c5b29450c6" to { _ -> auto(WalkTask(SAWMILL, radius = 5)) },

        // 103. "Cut the Dying tree [Varrock Easy Diary]": the tree is behind a broken fence in the Lumber Yard.
        // Climb the fence if the tree cannot be reached, then chop it until the diary task ticks.
        "8cb2f6d1a2" to { _ ->
            auto(
                WalkTask(SAWMILL, radius = 5),
                OnlyIf({ ctx: TaskContext -> ctx.game.nearestObject(emptyList(), listOf("Dying tree"), SAWMILL)?.let { tree -> ctx.game.canReachBeside(tree.tile) } != true },
                    obj(emptyList(), listOf("Broken fence"), SAWMILL, "Climb-over")),
                RepeatUntil("Chop down the dying tree", { diaryBit(it, VARROCK_DIARY, DYING_TREE_BIT) }, {
                    obj(emptyList(), listOf("Dying tree"), SAWMILL, "Chop down")
                }, maxRounds = 8),
                trust = true,
            )
        },

        // 104. "Collect the Logs & fletch 2,100 Arrowshafts (hop between 2 worlds)": 2,100 shafts is about 140 logs, chopped
        // from the trees round the Lumber Yard (outside its pen, where the piles cannot be reached) and fletched with
        // the knife, hopping worlds if no tree is in reach.
        "acc8776385" to { _ ->
            StepPlan.Auto(
                listOf(FletchShaftsTask()),
                trustTasks = true,
                patient = true,
                ignoreStartSignal = true,
            )
        },

        // 105. "Collect 11 Logs & make 10 into Planks. Make the 3 Waxwood Planks.": back over the broken fence first
        // (step 103 left the player outside it, by the dying tree), then the logs lying in the yard. Making the planks
        // is the sawmill operator's Buy-plank window. The bot climbs back out of the pen first, ready for step 106.
        "5cfa279c9b" to { _ ->
            // "Collect 11 Logs" is met by logs already held (from step 104), so the game's done signal is ignored until
            // the planks have been made, or the step would be passed over at once.
            StepPlan.Auto(
                listOf(
                    // Only go into the pen for the logs when they are still needed: fewer than 11 in the bag, and the 10 planks
                    // not already made.
                    OnlyIf({ ctx: TaskContext -> needsLogs(ctx) && ctx.game.canReach(SAWMILL) == false },
                        obj(emptyList(), listOf("Broken fence"), SAWMILL, "Climb-over")),
                    OnlyIf(::needsLogs, WalkTask(SAWMILL, radius = 8)),
                    OnlyIf(::needsLogs, TakeYardLogsTask(11)),
                    LeavePenTask(),
                    MakePlanksTask(planks = 10, waxwood = 3),
                ),
                trustTasks = true,
                ignoreStartSignal = true,
            )
        },

        // 106. "Buy 5x Bolts of Cloth & 100 Steel Nails": from the sawmill operator. The player may still be inside the
        // Lumber Yard's pen after step 105, whose only way out is the broken fence, so climb out first.
        "8714080b94" to { _ ->
            auto(
                LeavePenTask(),
                ShopTask(
                    listOf(ShopTask.Seller("Sawmill operator", listOf(3101), listOf(SAWMILL))),
                    listOf(item("Bolt of cloth", 8790, 5), item("Steel nails", 1539, 100)),
                ),
            )
        },

        // 108. "Light 1 log & take the 1x log": in Old Man Yarlo's house again (the next step collects the ashes): light
        // one of the logs lying there and take the other.
        "e7c81d37a4" to { _ ->
            auto(
                LightLogsTask(listOf(LOGS), TINDERBOX, count = 1, around = YARLOS_HOUSE),
                PickUpTask(listOf(LOGS), radius = 6),
                trust = true,
            )
        },

        // 109. "Collect Ashes": the ashes the log lit at step 108 leaves, once it has burnt away. They are not there
        // yet when this step starts, so wait for them in Old Man Yarlo's house.
        "08f1329ea4" to { _ -> auto(WaitAndTakeTask(ASHES, YARLOS_HOUSE), trust = true) },

        // 112. "Open the crate": the crate of supplies Marlo rewards at the end of Daddy's Home. Its item id is not in the
        // guide, so it is found by name.
        "e07bea62a5" to { _ -> auto(ItemByNameTask("crate", "Open"), trust = true) },

        // 115. "Travel east to Regulus Cento & travel to Varlamore": the quetzal master east of Varrock. Microbot only sees
        // his base options (Talk-to), so he is talked to and "Let's do it!" chosen, which takes the player to Varlamore.
        "15227aad5e" to { _ ->
            auto(
                InteractTask(
                    InteractTask.Kind.NPC, listOf(12883, 12884, 12885), listOf("Regulus Cento"), listOf(Tile(3281, 3413, 0)), "Talk-to",
                    // His chat offers "Let's do it!" or "There's something I need to do first.": the first goes.
                    DialoguePlan.Texts(listOf("do it!", "Civitas illa Fortis", "Varlamore", "Travel")),
                ),
                WaitUntilTask("Arrive in Varlamore", maxTicks = 60) { (it.game.playerTile?.x ?: 9999) < 2500 },
                trust = true,
            )
        },

        // 116. "Open the Minimap and Head South out of the City": out of Civitas illa Fortis by the south gate (the guards
        // stand at x 1681 around y 3070), to Outer Fortis, where the next step's items lie.
        "904a520a61" to { _ -> auto(WalkTask(Tile(1680, 3058, 0), radius = 3)) },

        // 118. "Head West and pick 2 Nettles": the nettle bushes west of Outer Fortis, picked with leather gloves on. The place
        // is not in the guide, so the bot picks the nearest "Nettles" object it can see, from where step 117 ended (the
        // bushes should be in view heading west), and pauses if there are none.
        "56a948d81c" to { _ ->
            auto(
                // Nettles sting: the leather gloves go on before anything else, and a missing pair stops the step.
                EquipTask(listOf(item("Leather gloves", 1059))),
                OnlyIf({ ctx: TaskContext -> 1059 !in ctx.game.equipment() }, Fail("the leather gloves are not on, and nettles hurt without them")),
                WalkTask(Tile(1640, 3065, 0), radius = 6),
                RepeatUntil("Pick 2 nettles", has(NETTLES, 2), {
                    obj(emptyList(), listOf("Nettles"), Tile(1640, 3065, 0), "Pick", near = null)
                }, maxRounds = 6),
                trust = true,
            )
        },

        // 119. "Collect 1 Sweetcorn [Recipe for Disaster]": the guide's spawn points are at Ortus Farm, (1574, 3139) on the
        // first floor, and Cam Torum. Go to the farm, then take the sweetcorn lying there.
        "1383b1d521" to { _ ->
            auto(
                // The walker cannot path to the first floor tile from outside (it said so), so go to the farm's ground
                // floor below it, and let the item task climb or walk to the sweetcorn once it can see it.
                // Only from the ground floor: asked to walk to a ground tile from upstairs the walker finds no path and falls back
                // on teleporting home, which is what happened when this step was retried on the first floor.
                OnlyIf({ ctx: TaskContext -> ctx.game.playerTile?.plane == 0 }, WalkTask(Tile(1574, 3139, 0), radius = 6)),
                // The sweetcorn lies on the farmhouse's first floor, up the staircase at (1581, 3140) (the walker will not
                // path to a first floor tile from outside). Climb it, then take the sweetcorn there.
                OnlyIf({ ctx: TaskContext -> ctx.game.playerTile?.plane == 0 },
                    obj(emptyList(), listOf("Staircase"), Tile(1581, 3140, 0), "Climb-up")),
                TakeItemTask(listOf(item("Sweetcorn", 7088, 1))),
                // The guide data has another id for it (5986), so its own done check would never be met.
                trust = true,
            )
        },

        // 120. "Head West to the Hunter's Guild": from Ortus Farm's first floor, where step 119 ends. The walker finds no path from
        // upstairs to a ground floor tile and teleports home instead, so come down the staircase first.
        "342c3bbd0f" to { _ ->
            auto(
                OnlyIf({ ctx: TaskContext -> (ctx.game.playerTile?.plane ?: 0) > 0 },
                    obj(emptyList(), listOf("Staircase"), Tile(1581, 3140, 1), "Climb-down")),
                WalkTask(Tile(1559, 3048, 0), radius = 5),
            )
        },

        // 124. "Enter Guildmaster Apatura's building and go up both ladders": the Hunter Guild's building. Its ladder is at
        // (1552, 3033), and a second ladder on the floor above takes the player up again.
        "e03782f0a1" to { _ ->
            auto(
                OnlyIf({ ctx: TaskContext -> (ctx.game.playerTile?.plane ?: 0) == 0 },
                    obj(listOf(52614), listOf("Ladder"), Tile(1552, 3033, 0), "Climb-up")),
                // The ladder to climb on the first floor is not the one just come up by (the nearest, which goes back down).
                OnlyIf({ ctx: TaskContext -> (ctx.game.playerTile?.plane ?: 0) == 1 }, ClimbOtherLadderTask()),
                trust = true,
            )
        },

        // 125. "Loot 28 Eclipse Reds (hop worlds)": the bottles upstairs in the Hunter Guild building (item 29415). The spawn is
        // taken on this world, so the bot hops after each one picked up, and again if none is in sight.
        "34ff59db82" to { _ ->
            StepPlan.Auto(listOf(LootAndHopTask(29415, 28)), trustTasks = true, patient = true, ignoreStartSignal = true)
        },

        // 129. "Head South-West to Sunset Coast. Be careful of the aggressive NPCs along the way": to the fly fishing rod's spawn at
        // (1530, 2979), where the next step loots. Aggressive monsters on the way are expected, so being attacked does not pause.
        "03b96ab731" to { settings ->
            auto(WalkTask(Tile(1530, 2979, 0), radius = 5), combat = settings.allowCombat)
        },

        // 130. "Loot Fly Fishing Rod, Lobster Pot, 3x Iron Bar (hop worlds), Big Fishing Net & Feather": at Sunset Coast. The ground items
        // the report listed are the rod (309), the pot (301) and an iron bar (2351). Take what is in sight, hop for the rest.
        "cadc3a1bed" to { _ ->
            StepPlan.Auto(
                listOf(LootManyAndHopTask(mapOf(309 to 1, 301 to 1, 2351 to 3, 305 to 1, 314 to 1))),
                trustTasks = true, patient = true, ignoreStartSignal = true,
            )
        },

        // 131. "Travel with Antonia to Aldarin": Antonia, the ship's captain at Sunset Coast (13984 and 13985, at (1495, 2985)), takes
        // the player to Aldarin, to the west. Microbot sees only her Talk-to, so she is talked to and the trip agreed to.
        "3eaf3bdd31" to { _ ->
            auto(
                InteractTask(
                    InteractTask.Kind.NPC, listOf(13984, 13985), listOf("Antonia"), listOf(Tile(1495, 2985, 0)), "Talk-to",
                    DialoguePlan.Texts(listOf("Aldarin", "Yes", "Okay", "Let's go")),
                ),
                WaitUntilTask("Arrive in Aldarin", maxTicks = 100, dialogue = DialoguePlan.Texts(listOf("Aldarin", "Yes", "Okay", "Let's go"))) { (it.game.playerTile?.x ?: 9999) < 1470 },
                trust = true,
            )
        },

        // 134. "Pick a Cabbage [Black Knight's Fortress]": buy one from Faustus's fruit and veg shop in Aldarin, where the player already is,
        // instead of looking for a cabbage patch.
        "a1db8dedf1" to { _ ->
            auto(
                ShopTask(
                    listOf(ShopTask.Seller("Faustus", listOf(13914), listOf(Tile(1420, 2981, 0)))),
                    listOf(item("Cabbage", 1965, 1)),
                ),
                trust = true,
            )
        },

        // 137. "Head West and trade Antonius and sell all 28 at once.": Antonius (13916) at (1356, 2926) buys the 28 Eclipse reds
        // (29415) the player carries.
        "bf4801cd5e" to { _ ->
            auto(
                SellTask(listOf(13916), listOf("Antonius"), Tile(1356, 2926, 0), listOf(29415)),
                trust = true,
            )
        },

        // 138. "Head East after selling Eclipse reds and buy 3 Sunbeam ales from the Bartender in the pub": the Bartender (13917)
        // at (1376, 2927). The pub's door (at (1375, 2927)) is opened if it is in the way, by the NPC task.
        "dcccfc8512" to { _ ->
            auto(
                // The pub's west door at (1375, 2927) is locked ("The door is locked"), and the entrance is on another side. Walking to
                // a spot inside, beside the bar, lets the walker find the way in, where clicking the Bartender from outside fails.
                WalkTask(Tile(1379, 2927, 0), radius = 1),
                ShopTask(
                    listOf(ShopTask.Seller("Bartender", listOf(13917), listOf(Tile(1376, 2927, 0)))),
                    listOf(item("Sunbeam ale", 29409, 3)),
                ),
                trust = true,
            )
        },

        // 139. "Head north-east to the Gem Store & buy 9x Uncut Sapphire, 10x Uncut Emerald, and as many Uncut Rubies as possible": Toci's
        // gem store in Aldarin (Toci, 13915, at (1428, 2974)). The rubies are whatever the shop has, so a lack of them does not stop the step.
        "381733ed40" to { _ ->
            val toci = listOf(ShopTask.Seller("Toci", listOf(13915), listOf(Tile(1428, 2974, 0))))
            auto(
                // The guide allows a few or none when the shop is out of stock, so the ruby is optional, and sapphires and emeralds hop worlds until all are bought.
                ShopTask(toci, listOf(item("Uncut sapphire", 1623, 9), item("Uncut emerald", 1621, 10)), hopWhenOut = true),
                Optional(ShopTask(toci, listOf(item("Uncut ruby", 1619, 5)))),
                trust = true,
            )
        },

        // 99. "Take 2x Logs & 2x ashes after cutscene"
        // 141. "Buy Steel Pickaxe, 150 Wines, 20 mindbombs & 20 Stews": step 140 deposited everything, coins too, so the coins come
        // out of the bank first. Necto (13930) runs Mistrock Mining Supplies at (1399, 2867), where the steel pickaxe is.
        "480b030dea" to { _ ->
            val necto = listOf(ShopTask.Seller("Necto", listOf(13930), listOf(Tile(1399, 2867, 0))))
            StepPlan.Auto(
                listOf(
                    BankTask(depositAll = false, withdraw = listOf(item("Coins", 995))),
                    ShopTask(necto, listOf(item("Steel pickaxe", 1269, 1))),
                    // 150 wines do not fit in a bag, so it is bought in trips: fill up, bank, come back. The drinks and the stew are
                    // sold by one of the Bartender (13928) or Ichta (13929), whichever has them.
                    StockUpTask(
                        listOf(
                            StockUpTask.Want("Mind bomb", 1907, 20),
                            StockUpTask.Want("Stew", 2003, 20),
                            StockUpTask.Want("Jug of wine", 1993, 150),
                        ),
                        listOf(
                            ShopTask.Seller("Bartender", listOf(13928), listOf(Tile(1390, 2860, 0))),
                            ShopTask.Seller("Ichta", listOf(13929), listOf(Tile(1387, 2868, 0))),
                        ),
                    ),
                ),
                trustTasks = true,
                // Holding a steel pickaxe is enough for the guide to call this step done, so it is never read from the bag.
                ignoreStartSignal = true,
            )
        },

        // 143. "Withdraw: Coins, Research Notes (2 inventory Slots) / Bronze pickaxe not needed": the Research Notes are not an item
        // the guide gives an id for. They are the Rune Mysteries ones (291), taken if the bank has them, and the bank not having
        // them does not stop the step.
        "133f34c1ac" to { _ ->
            // The coins alone make the guide call this step done, which ended it before the bank was asked for the notes.
            StepPlan.Auto(
                listOf(BankTask(depositAll = false, withdraw = listOf(item("Coins", 995), item("Research notes", 291)))),
                trustTasks = true,
                ignoreStartSignal = true,
            )
        },

        // 144. "Home Teleport to Lumbridge": step 145 goes to the Wizards' Tower, where the Research notes are handed to Sedridor in
        // step 148. Without them (the bank had none in step 143), Aubury in south east Varrock (3253, 3401) is visited first and
        // asked for them again, which is a Rune Mysteries step of its own and so not something the guide lists here.
        "b2118c6c31" to { _ ->
            // Anywhere in the town counts: the castle is 16 tiles from where the teleport lands, and the spell is on a 30 minute cooldown.
            val lumbridge = Tile(3222, 3218, 0)
            StepPlan.Auto(
                listOf(
                    OnlyIf({ ctx -> ctx.game.playerTile?.let { it.distanceTo(lumbridge) > LUMBRIDGE_AREA } ?: true }, CastSpellTask("TELEPORT_HOME_STANDARD", 14286854)),
                    WaitUntilTask("arrive in Lumbridge", 80) { ctx -> ctx.game.playerTile?.let { it.plane == 0 && it.distanceTo(lumbridge) <= LUMBRIDGE_AREA } == true },
                ) + notesFromAubury(),
                trustTasks = true,
                ignoreStartSignal = true,
            )
        },

        // 145. "Head to the Wizards Tower": the notes from Aubury come first if the bag has none (see step 144).
        "bc89942abe" to { _ ->
            StepPlan.Auto(
                notesFromAubury() + WalkTask(Tile(3114, 3181, 0), radius = 4),
                trustTasks = true,
                ignoreStartSignal = true,
            )
        },

        // 147. "Search the altar to collect the Ghost's skull. Run from the level 13 skeleton.": the altar (2146) at (3120, 9566) in the
        // Wizards' Tower basement. The skeleton by it (3120, 9569) attacks, so the bot walks off towards Sedridor (3104, 9570), whom
        // step 148 talks to, as soon as the skull is held.
        "56dfd5848b" to { _ ->
            val skull = 553
            StepPlan.Auto(
                listOf(
                    OnlyIf({ ctx -> !has(skull)(ctx) }, obj(listOf(2146), listOf("Altar"), Tile(3120, 9566, 0), "Search")),
                    OnlyIf({ ctx -> !has(skull)(ctx) }, WaitUntilTask("get the Ghost's skull", 12, reached = has(skull))),
                    WalkTask(Tile(3104, 9572, 0), radius = 2),
                ),
                trustTasks = true,
                ignoreStartSignal = true,
            )
        },

        // 149. "Teleport to Rune Essence Mine [Lumbridge Easy Diary]": Sedridor (11432) in the Wizards' Tower basement teleports
        // once Rune Mysteries is done, then the portal there leads back out.
        "4b327ee13c" to { _ ->
            val inMine: (TaskContext) -> Boolean = { ctx -> ctx.game.playerTile?.let { it.y > 4700 } == true }
            StepPlan.Auto(
                listOf(
                    OnlyIf({ ctx -> !inMine(ctx) }, InteractTask(InteractTask.Kind.NPC, listOf(11432), listOf("Archmage Sedridor", "Sedridor"), listOf(Tile(3104, 9570, 0)), "Teleport")),
                    WaitUntilTask("arrive in the Rune Essence Mine", 40, reached = inMine),
                    InteractTask(InteractTask.Kind.OBJECT, emptyList(), listOf("Portal"), emptyList(), "Use"),
                ),
                trustTasks = true,
                ignoreStartSignal = true,
            )
        },

        // 154. "Use Pages on Chronicle and wield": the guide's item id (4573) is not in the game, and the Chronicle (13660) is charged
        // with the Teleport cards (13658) bought from Diango in step 153.
        "a168930db1" to { _ ->
            auto(OnlyIf({ ctx -> (ctx.game.inventory()[13658] ?: 0) > 0 }, UseItemTask(13658, 13660)), EquipTask(listOf(item("Chronicle", 13660))), trust = true)
        },

        // 156. "Head South-West and buy a Harpoon, 50 Bait (non-packs) and 10 Feather packs": Gerrant's Fishy Business in Port Sarim.
        // The white apron (1005) is taken from the wall and worn.
        "2d2e048246" to { _ ->
            val gerrant = listOf(ShopTask.Seller("Gerrant", emptyList(), listOf(Tile(3014, 3224, 0))))
            auto(
                ShopTask(gerrant, listOf(item("Harpoon", 311, 1), item("Fishing bait", 313, 50), item("Feather pack", 11881, 10))),
                // Only one in sight of the room the shop is in: the guide's spawn list has others, in the houses round about.
                PickUpTask(listOf(1005), 6),
                EquipTask(listOf(item("White apron", 1005))),
                trust = true,
            )
        },

        // 157. "Make 454 Headless Arrows": the feather packs are opened, and the feathers used on the arrow shafts.
        "7a92b7572a" to { _ ->
            StepPlan.Auto(
                listOf(
                    RepeatUntil("open the feather packs", { ctx -> ctx.conditions.count(listOf(314)) >= 454 || 11881 !in ctx.game.inventory() }, { ItemActionTask(11881, "Open") }, maxRounds = 12),
                    MakeAllTask(314, 52, 53, 454, "headless arrows"),
                ),
                trustTasks = true,
                ignoreStartSignal = true,
            )
        },

        // 197. "Chronicle to Varrock": the Chronicle teleports to the Champions' Guild, on Varrock's doorstep.
        "6f4e6c292e" to { _ ->
            auto(ItemTeleportTask("Chronicle", listOf(13660), "Champions' Guild"), trust = true)
        },

        // 158. "Take Ship to Karamja": the Customs officer (14980) at Port Sarim's dock, Travel. Musa Point, on Karamja, is south of
        // y 3200 and west of x 3000, where Port Sarim is north of it.
        "75f2484b3d" to { _ ->
            val onKaramja: (TaskContext) -> Boolean = { ctx -> ctx.game.playerTile?.let { it.x < 3000 && it.y < 3200 } == true }
            val agree = DialoguePlan.Texts(listOf("Yes", "Okay", "Ok"))
            StepPlan.Auto(
                listOf(
                    OnlyIf({ ctx -> !onKaramja(ctx) }, WalkTask(Tile(3027, 3222, 0), radius = 3)),
                    OnlyIf({ ctx -> !onKaramja(ctx) }, InteractTask(InteractTask.Kind.NPC, listOf(14980), listOf("Customs officer"), listOf(Tile(3027, 3218, 0)), "Travel", agree)),
                    WaitUntilTask("arrive on Karamja", 60, agree, onKaramja),
                ),
                trustTasks = true,
                ignoreStartSignal = true,
            )
        },

        // 165. "Take boat back to Port Sarim": the Customs officer (14984) at Musa Point, at (2953, 3146).
        "0550c457c4" to { _ ->
            val onKaramja: (TaskContext) -> Boolean = { ctx -> ctx.game.playerTile?.let { it.x < 3000 && it.y < 3200 } == true }
            val agree = DialoguePlan.Texts(listOf("Yes", "Okay", "Ok"))
            StepPlan.Auto(
                listOf(
                    OnlyIf(onKaramja, InteractTask(InteractTask.Kind.NPC, listOf(14984), listOf("Customs officer"), listOf(Tile(2953, 3146, 0)), "Travel", agree)),
                    WaitUntilTask("arrive at Port Sarim", 60, agree) { ctx -> !onKaramja(ctx) },
                ),
                trustTasks = true,
                ignoreStartSignal = true,
            )
        },

        // 160. "Fish Raw Shrimps [Karamja Easy Diary] Drop Shrimps": the net fishing spots on the south coast of Musa Point, around
        // (2924, 3178), with the small fishing net (303) the bag already holds. The shrimps (317) are dropped afterwards, so the bag
        // has room for the bananas.
        "d36b23fa85" to { _ ->
            val shrimp = 317
            StepPlan.Auto(
                listOf(
                    OnlyIf({ ctx -> !has(shrimp)(ctx) }, WalkTask(Tile(2924, 3176, 0), radius = 4)),
                    OnlyIf(
                        { ctx -> !has(shrimp)(ctx) },
                        InteractTask(InteractTask.Kind.NPC, listOf(1525), listOf("Fishing spot"), listOf(Tile(2924, 3178, 0)), "Small Net"),
                    ),
                    OnlyIf({ ctx -> !has(shrimp)(ctx) }, WaitUntilTask("catch the shrimps", 120, reached = has(shrimp))),
                    DropTask(listOf(shrimp)),
                ),
                trustTasks = true,
                ignoreStartSignal = true,
            )
        },

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
        private val LUMBRIDGE_TOP_FLOOR = Tile(3208, 3220, 2)
        private val SOUTH_OF_GERTRUDE = Tile(3152, 3405, 0)
        private val ABOVE_SWORD_SHOP = Tile(3205, 3396, 1)
        /** The Wizards' Tower ground floor, south of its door. */
        private val INSIDE_WIZARDS_TOWER = Tile(3108, 3164, 0)
        private val VEOS_PORT_SARIM = Tile(3054, 3245, 0)
        private val YARLOS_HOUSE = Tile(3239, 3396, 0)
        private val SEWER_KEY = Tile(3225, 9897, 0)

        /** Where Varrock's Stray Dog is seen: the square, both banks, the north and south streets. */
        private val VARROCK_ROUND = listOf(
            Tile(3212, 3428, 0), Tile(3230, 3452, 0), Tile(3253, 3420, 0), Tile(3270, 3395, 0),
            Tile(3236, 3385, 0), Tile(3210, 3396, 0), Tile(3185, 3436, 0),
        )
        private val GIFT_OF_PEACE = Tile(1907, 5222, 0)
        private val GRAIN_OF_PLENTY = Tile(2022, 5216, 0)
        private val BOX_OF_HEALTH = Tile(2144, 5280, 0)
        private val STRONGHOLD_ENTRANCE = Tile(3081, 3421, 0)
        /** Where Veos's boat lands, by Veos at Port Piscarilius. */
        private val PISCARILIUS_DOCK = Tile(1824, 3691, 0)
        /** Giant rats spawn just west of the castle, by the second X Marks the Spot dig. */
        private val BEHIND_LUMBRIDGE_CASTLE = Tile(3195, 3205, 0)
        private val LUMBRIDGE_GENERAL_STORE = Tile(3212, 3246, 0)
        private val MAGIC_TUTOR = Tile(3218, 3238, 0)
        private val VARROCK_KITCHEN_STAIRS = Tile(3219, 3497, 0)
        private val VARROCK_KITCHEN = Tile(3222, 3495, 0)
        private val YARLO_HOUSE = Tile(3244, 3396, 0)


        private const val LUMBRIDGE_DIARY = 1194
        private const val VARROCK_DIARY = 1176
        private const val DYING_TREE_BIT = 7
        private val SAWMILL = Tile(3302, 3492, 0)
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
        private const val LUMBRIDGE_AREA = 30

        private const val LOGS = 1511
        private const val PLANK = 960
        private const val LEATHER_BOOTS = 1061
        private const val BONES = 526
        private const val TINDERBOX = 590
        private const val SEWER_KEY_OBJECT = 17431
        /** Dr Harlow: Quest Helper has 3480, but an update added 16276 and 16277 for the Vampyre Slayer rework. */
        private val HARLOW = setOf(3480, 16276, 16277)
        /** The Stronghold's rewards, from Quest Helper's Varrock diary and RuneLite's gameval ObjectID. */
        private const val SOS_WAR_CHEST = 20656
        private const val SOS_FAM_SACK = 19000
        private const val SOS_PEST_CHEST = 23709

        /** "Are you sure you want to climb down?" at each ladder down to a lower floor. */
        private val STRONGHOLD_WARNINGS = listOf("Yes - I know that it may be")

        /**
         * The Stronghold's shortcut portals only work once their floor is done, but Microbot's
         * teleportation_portals.tsv has floor 2's (2040,5240) and some of floor 3's with no
         * requirement, so the walker tries them and loops ("not of sufficient experience").
         */
        private val NO_PORTALS: Map<String, Any> = mapOf("useTeleportationPortals" to false)

        /**
         * Which Stronghold of Security floor the player is on: 1 to 4, or 0 when outside it.
         * Each floor is its own block of the map, west to east (start tiles 1859, 2042, 2123, 2358).
         */
        fun strongholdFloor(ctx: TaskContext): Int {
            val at = ctx.game.playerTile ?: return 0
            if (at.plane != 0 || at.y !in 5150..5320) return 0
            return when (at.x) {
                in 1850..1950 -> 1
                in 1980..2070 -> 2
                in 2100..2200 -> 3
                in 2300..2400 -> 4
                else -> 0
            }
        }
        private const val DOOGLE_LEAVES = 1573
        private const val LIT_CANDLE = 33
        private const val CANDLE = 36
        private const val CHEESE = 1985
        private const val GARLIC = 1550
        private const val STAKE = 1549
        private const val ASHES = 592
        private const val NETTLES = 4241
        private const val ROVINS_KEY = 2400
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
