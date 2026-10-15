package com.hcimbot

import com.hcimbot.engine.Conditions
import com.hcimbot.guide.GuideLoader
import com.hcimbot.guide.Memory
import com.hcimbot.guide.Need
import com.hcimbot.guide.Requirement
import com.hcimbot.guide.Step
import com.hcimbot.guide.Tile
import com.hcimbot.guide.VarCheck
import com.hcimbot.plan.StepPlanner
import com.hcimbot.plan.TravelTask
import com.hcimbot.progress.ProgressFormat
import com.hcimbot.progress.SharedProgressStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartsTest {
    @Test
    fun `loader rejects a schema it does not understand`() {
        val json = """{"schemaVersion": 2, "sections": []}"""
        assertFailsWith<GuideLoader.GuideLoadException> { GuideLoader().load(json.byteInputStream()) }
    }

    @Test
    fun `conditions match the plugin's rules`() {
        val game = FakeGame()
        val conditions = Conditions(game)
        game.varbits[1] = 5
        game.playerTile = Tile(10, 10, 0)

        assertTrue(conditions.holds(VarCheck("varbit", 1, 4, ">=")))
        assertFalse(conditions.holds(VarCheck("varbit", 1, 4, "==")))
        assertTrue(conditions.holds(Requirement(zone = listOf(listOf(5, 5, 0, 15, 15, 0)))))
        assertFalse(conditions.holds(Requirement(zone = listOf(listOf(5, 5, 1, 15, 15, 1)))))

        game.inv[995] = 3
        game.bank[995] = 10
        assertFalse(conditions.holds(Requirement(item = Need("Coins", 5, listOf(995)))))
        assertTrue(conditions.holds(Requirement(item = Need("Coins", 5, listOf(995), bank = true))))
        assertTrue(conditions.holds(Requirement(not = Requirement(item = Need("Coins", 5, listOf(995))))))
    }

    @Test
    fun `once latches after its condition held`() {
        val game = FakeGame()
        val conditions = Conditions(game)
        val once = Requirement(once = Memory("k", condition = Requirement(varCheck = VarCheck("varp", 9, 1))))

        conditions.observe(once)
        assertFalse(conditions.holds(once))
        game.varps[9] = 1
        conditions.observe(once)
        game.varps[9] = 0
        assertTrue(conditions.holds(once))
    }

    @Test
    fun `quest fights pause unless combat is on`() {
        val game = FakeGame()
        game.npcs += com.hcimbot.game.SceneEntity(5, "Imp", Tile(3222, 3218, 0))
        val helper = com.hcimbot.guide.QuestHelper(
            com.hcimbot.guide.QuestVar("varp", 1),
            lastValue = 1,
            steps = mapOf("0" to com.hcimbot.guide.Instruction(kind = "npc", text = "Kill the Imp.", ids = listOf(5))),
        )
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))

        val refused = com.hcimbot.plan.QuestTask("q", helper, allowCombat = false).tick(ctx)
        assertTrue(refused is com.hcimbot.plan.TaskResult.Failed && "combat" in refused.reason, refused.toString())
        assertTrue(game.calls.isEmpty())

        com.hcimbot.plan.QuestTask("q", helper, allowCombat = true).tick(ctx)
        assertEquals(listOf("npc Imp Attack"), game.calls)
    }

    @Test
    fun `quest stairs with no id are climbed by walking to the floor above`() {
        // Rune Mysteries at the start: "use the south staircase", with no staircase id,
        // and a branch for once you are upstairs with the Duke.
        val game = FakeGame()
        game.playerTile = Tile(3222, 3218, 0)
        val upstairs = Requirement(zone = listOf(listOf(3203, 3206, 1, 3218, 3231, 1)))
        val duke = com.hcimbot.guide.Instruction(
            kind = "npc", point = listOf(3209, 3222, 1), text = "Talk to Duke Horacio on the first floor of Lumbridge castle.",
            ids = listOf(815), dialogue = listOf("Have you any quests for me?", "Yes."), condition = upstairs,
        )
        val helper = com.hcimbot.guide.QuestHelper(
            com.hcimbot.guide.QuestVar("varp", 63),
            lastValue = 1,
            steps = mapOf(
                "0" to com.hcimbot.guide.Instruction(
                    kind = "object", point = listOf(3205, 3208, 0), text = duke.text, whenIn = listOf(duke),
                ),
            ),
        )
        game.npcs += com.hcimbot.game.SceneEntity(815, "Duke Horacio", Tile(3209, 3222, 1))
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.QuestTask("runemysteries", helper, allowCombat = false, boundedTo = setOf(815, 5327))

        val first = task.tick(ctx)
        assertTrue(first !is com.hcimbot.plan.TaskResult.Failed, first.toString())
        assertEquals(listOf("walk (3209, 3222, 1)"), game.calls)

        repeat(5) { task.tick(ctx) }
        assertTrue(game.calls.any { it.startsWith("npc Duke Horacio") }, game.calls.toString())
    }

    @Test
    fun `a quest step climbs down to the NPC it is about`() {
        // Rune Mysteries at 1: "climb down the Wizards' Tower ladder", and once in the
        // basement, "talk to Sedridor". Step 26 is about Sedridor, so it takes the ladder.
        val game = FakeGame()
        game.playerTile = Tile(3108, 3164, 0)
        game.varps[63] = 1
        game.inv[1438] = 1
        game.objects += com.hcimbot.game.SceneEntity(2147, "Ladder", Tile(3104, 3162, 0))
        val basement = Requirement(zone = listOf(listOf(3094, 9553, 0, 3125, 9582, 0)))
        val sedridor = com.hcimbot.guide.Instruction(
            kind = "npc", point = listOf(3104, 9571, 0), text = "Bring the Air Talisman to Sedridor in the Wizard Tower's basement.",
            ids = listOf(11432), dialogue = listOf("I'm looking for the head wizard."), condition = basement,
        )
        val helper = com.hcimbot.guide.QuestHelper(
            com.hcimbot.guide.QuestVar("varplayer", 63),
            lastValue = 5,
            steps = mapOf(
                "1" to com.hcimbot.guide.Instruction(
                    kind = "object", point = listOf(3104, 3162, 0), ids = listOf(2147),
                    text = "Bring the Air Talisman to Sedridor in the Wizard Tower's basement.", whenIn = listOf(sedridor),
                ),
            ),
        )
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.QuestTask("runemysteries", helper, allowCombat = false, boundedTo = setOf(11432, 11433, 11450))
        val result = task.tick(ctx)
        assertTrue(result !is com.hcimbot.plan.TaskResult.Failed, result.toString())
        assertEquals(listOf("object Ladder null"), game.calls)
    }

    @Test
    fun `X Marks the Spot digs come from Quest Helper's own source`() {
        val game = FakeGame()
        game.playerTile = Tile(3222, 3218, 0)
        game.inv[com.hcimbot.plan.DigTask.SPADE] = 1
        game.varbits[8063] = 2
        val talk = com.hcimbot.guide.Instruction(kind = "npc", text = "Talk to Veos.", ids = listOf(8484), dialogue = listOf("Yes."))
        val helper = com.hcimbot.guide.QuestHelper(
            com.hcimbot.guide.QuestVar("varbit", 8063),
            lastValue = 7,
            steps = mapOf("0" to talk, "1" to talk, "6" to talk, "7" to talk),
        )
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.QuestTask("xmarksthespot", helper, allowCombat = false)

        repeat(4) { task.tick(ctx) }
        assertEquals(listOf("walk (3230, 3209, 0)", "item 952 Dig"), game.calls)
    }

    private fun xMarksHelper(): com.hcimbot.guide.QuestHelper {
        val talk = com.hcimbot.guide.Instruction(kind = "npc", text = "Talk to Veos.", ids = listOf(8484), dialogue = listOf("Yes."))
        return com.hcimbot.guide.QuestHelper(
            com.hcimbot.guide.QuestVar("varbit", 8063),
            lastValue = 7,
            steps = mapOf("0" to talk, "1" to talk, "6" to talk, "7" to talk),
        )
    }

    @Test
    fun `starting X Marks the Spot does not run ahead to the first dig`() {
        // Step 7 run again once the quest has started: its part is done, and the dig is step 18's.
        val game = FakeGame()
        game.inv[com.hcimbot.plan.DigTask.SPADE] = 1
        game.varbits[8063] = 2
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.QuestTask("xmarksthespot", xMarksHelper(), allowCombat = false, boundedTo = setOf(8484))
        assertEquals(com.hcimbot.plan.TaskResult.Done, task.tick(ctx))
        assertTrue(game.calls.isEmpty(), game.calls.toString())
    }

    @Test
    fun `finishing X Marks the Spot does the last dig first if it is owed`() {
        val game = FakeGame()
        game.inv[com.hcimbot.plan.DigTask.SPADE] = 1
        game.varbits[8063] = 5
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.QuestTask(
            "xmarksthespot", xMarksHelper(), allowCombat = false, boundedTo = setOf(8484), finishesQuest = true,
        )
        task.tick(ctx)
        assertEquals(listOf("walk (3078, 3259, 0)"), game.calls)
    }

    @Test
    fun `withdrawing the treasure scroll takes whichever clue item the quest is on`() {
        val game = FakeGame()
        game.varbits[8063] = 3
        game.bank[23069] = 1 // the mysterious orb the first dig swapped the scroll for
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.BankTask(false, listOf(com.hcimbot.guide.ItemRef("Treasure Scroll", listOf(23067, 23068, 23070))))
        repeat(6) { task.tick(ctx) }
        assertEquals(1, game.inv[23069])
    }

    @Test
    fun `a treasure scroll missing because the quest never started sends the bot back to step 7`() {
        val game = FakeGame()
        game.bank[995] = 100
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.BankTask(
            false,
            listOf(com.hcimbot.guide.ItemRef("Treasure Scroll", listOf(23067)), com.hcimbot.guide.ItemRef("Coins", listOf(995))),
        )
        var result: com.hcimbot.plan.TaskResult = com.hcimbot.plan.TaskResult.Running
        repeat(8) { if (result == com.hcimbot.plan.TaskResult.Running) result = task.tick(ctx) }
        assertEquals(100, game.inv[995], "takes out what the bank has first")
        assertTrue(result is com.hcimbot.plan.TaskResult.NeedsStep && result.stepId == "e2deed373e", result.toString())
    }

    @Test
    fun `with no rat in sight the bot goes to where giant rats spawn`() {
        val game = FakeGame()
        game.playerTile = Tile(3222, 3218, 0)
        game.spawns["giant rat"] = listOf(
            Tile(3197, 3201, 0), // Lumbridge Swamp, close by
            Tile(3292, 3377, 0), // Varrock, too far to go looking
            Tile(3040, 3700, 0), // the Wilderness: never
        )
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.KillTask(emptyList(), listOf("Giant rat"), listOf(com.hcimbot.guide.ItemRef("Bones", listOf(526))), spell = "WIND_STRIKE")

        task.tick(ctx)
        task.tick(ctx)
        assertEquals(listOf("walk (3197, 3201, 0)"), game.calls)

        game.npcs += com.hcimbot.game.SceneEntity(2510, "Giant rat", Tile(3199, 3200, 0))
        repeat(6) { task.tick(ctx) }
        assertTrue("cast WIND_STRIKE on Giant rat" in game.calls, game.calls.toString())
    }

    @Test
    fun `wind strike is cast again and again until the rat dies`() {
        val ratAt = Tile(3223, 3218, 0)
        val rat = com.hcimbot.game.SceneEntity(2510, "Giant rat", ratAt, index = 7)
        val game = FakeGame()
        game.npcs += rat
        var casts = 0
        game.onNpcInteract = { _, _ ->
            // Three hits kill it, and it drops its bones.
            if (++casts == 3) {
                game.npcs.remove(rat)
                game.groundItems += com.hcimbot.game.SceneEntity(526, "Bones", ratAt)
            }
        }
        game.alive = { npc -> npc.index == 7 && rat in game.npcs }
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.KillTask(
            emptyList(), listOf("Giant rat"), listOf(com.hcimbot.guide.ItemRef("Bones", listOf(526))), spell = "WIND_STRIKE",
        )
        var result: com.hcimbot.plan.TaskResult = com.hcimbot.plan.TaskResult.Running
        repeat(60) { if (result == com.hcimbot.plan.TaskResult.Running) result = task.tick(ctx) }
        assertEquals(com.hcimbot.plan.TaskResult.Done, result)
        assertEquals(3, game.calls.count { it == "cast WIND_STRIKE on Giant rat" }, game.calls.toString())
        assertEquals(1, game.inv[526])
    }

    @Test
    fun `wind strike keeps going while the rat fights back, even when the game cannot say if it lives`() {
        val rat = com.hcimbot.game.SceneEntity(2510, "Giant rat", Tile(3223, 3218, 0))
        val game = FakeGame()
        game.npcs += rat
        game.isBusy = true // hit back every tick, so the block animation never stops
        game.onNpcInteract = { _, _ -> game.isInCombat = true }
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.KillTask(
            emptyList(), listOf("Giant rat"), listOf(com.hcimbot.guide.ItemRef("Bones", listOf(526))), spell = "WIND_STRIKE",
        )
        game.isBusy = false
        task.tick(ctx) // first cast
        game.isBusy = true
        repeat(11) { task.tick(ctx) }
        assertEquals(3, game.calls.count { it == "cast WIND_STRIKE on Giant rat" }, game.calls.toString())
    }

    @Test
    fun `an NPC behind a shut door is walked to before being clicked`() {
        // Father Urhney's house: the walker opens the door on the way in.
        val urhneyAt = Tile(3147, 3175, 0)
        val game = FakeGame()
        game.playerTile = Tile(3150, 3170, 0)
        game.npcs += com.hcimbot.game.SceneEntity(923, "Father Urhney", urhneyAt)
        game.unreachable += urhneyAt
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.InteractTask(
            com.hcimbot.plan.InteractTask.Kind.NPC, listOf(923), listOf("Father Urhney"), listOf(urhneyAt), "Talk-to",
        )
        task.tick(ctx)
        assertEquals(listOf("walk (3147, 3175, 0)"), game.calls)
        game.unreachable.remove(urhneyAt)
        game.unreachable += Tile(0, 0, 0) // still able to answer, and his tile is open now
        task.tick(ctx)
        assertEquals("npc Father Urhney Talk-to", game.calls.last())
    }

    @Test
    fun `an item on a table behind a shut door is walked to before being taken`() {
        val table = Tile(3103, 3165, 0)
        val game = FakeGame()
        game.playerTile = Tile(3108, 3164, 0)
        game.groundItems += com.hcimbot.game.SceneEntity(1061, "Leather boots", table)
        // The table can never be stood on, and the room around it is shut.
        game.unreachable += listOf(table, Tile(3102, 3165, 0), Tile(3104, 3165, 0), Tile(3103, 3164, 0), Tile(3103, 3166, 0))
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.TakeItemTask(listOf(com.hcimbot.guide.ItemRef("Leather boots", listOf(1061))))
        task.tick(ctx)
        assertEquals(listOf("walk (3103, 3165, 0)"), game.calls)
        // Door open: the tile beside the table can be reached now, so it takes them.
        game.unreachable.remove(Tile(3104, 3165, 0))
        task.tick(ctx)
        assertTrue(game.calls.last().startsWith("take"), game.calls.toString())
    }

    @Test
    fun `an object behind a shut door is walked to before being used`() {
        // The Restless Ghost's coffin, in the shed by Lumbridge graveyard.
        val coffinAt = Tile(3249, 3192, 0)
        val game = FakeGame()
        game.playerTile = Tile(3245, 3197, 0)
        game.objects += com.hcimbot.game.SceneEntity(2145, "Coffin", coffinAt)
        game.unreachable += listOf(coffinAt, Tile(3248, 3192, 0), Tile(3250, 3192, 0), Tile(3249, 3191, 0), Tile(3249, 3193, 0))
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.InteractTask(com.hcimbot.plan.InteractTask.Kind.OBJECT, listOf(2145), listOf("Coffin"), listOf(coffinAt), "Search")
        task.tick(ctx)
        assertEquals(listOf("walk (3249, 3192, 0)"), game.calls)
        game.unreachable.remove(Tile(3249, 3193, 0)) // door open
        task.tick(ctx)
        assertEquals("object Coffin Search", game.calls.last())
    }

    @Test
    fun `someone behind a counter is clicked anyway after a few tries`() {
        val shopAt = Tile(3212, 3247, 0)
        val game = FakeGame()
        game.playerTile = Tile(3212, 3245, 0)
        game.npcs += com.hcimbot.game.SceneEntity(506, "Shop keeper", shopAt)
        game.unreachable += shopAt
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.InteractTask(
            com.hcimbot.plan.InteractTask.Kind.NPC, listOf(506), listOf("Shop keeper"), listOf(shopAt), "Trade",
        )
        repeat(4) { task.tick(ctx) }
        assertEquals(3, game.calls.count { it.startsWith("walk") }, game.calls.toString())
        assertEquals("npc Shop keeper Trade", game.calls.last(), game.calls.toString())
    }

    @Test
    fun `a shop that sells one per click is asked again until there are enough`() {
        val game = FakeGame()
        game.isShopOpen = true
        game.buyCap = { 1 }
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.ShopTask(emptyList(), listOf(com.hcimbot.guide.ItemRef("Feather", listOf(314), 3)))
        repeat(12) { task.tick(ctx) }
        assertEquals(3, game.inv[314])
        assertEquals(listOf("buy 314 x3", "buy 314 x2", "buy 314 x1"), game.calls.filter { it.startsWith("buy") })
    }

    @Test
    fun `a quest NPC with a new id since an update is still found`() {
        // Quest Helper has Morgan as 3479, but before the quest he is 16274 now.
        val game = FakeGame()
        game.playerTile = Tile(3100, 3266, 0)
        game.npcs += com.hcimbot.game.SceneEntity(16274, "Morgan", Tile(3098, 3268, 0))
        val helper = com.hcimbot.guide.QuestHelper(
            com.hcimbot.guide.QuestVar("varp", 178),
            lastValue = 2,
            steps = mapOf(
                "0" to com.hcimbot.guide.Instruction(
                    kind = "npc", point = listOf(3098, 3268, 0), text = "Talk to Morgan in the north of Draynor Village.",
                    ids = listOf(3479), dialogue = listOf("Yes."),
                ),
            ),
        )
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        com.hcimbot.plan.QuestTask("vampyreslayer", helper, allowCombat = false, boundedTo = setOf(3479, 16274, 16275)).tick(ctx)
        assertEquals(listOf("npc Morgan Talk-to"), game.calls)
    }

    @Test
    fun `a conversation left open by the last step is ended, not paused on`() {
        val plan = com.hcimbot.plan.DialoguePlan.None
        val morgan = listOf("What else can you tell me about this vampyre?", "Alright, I'll see about paying him a visit.")
        assertEquals(com.hcimbot.plan.DialoguePlan.Choice.ByText(morgan[1]), plan.choose(morgan))
        assertEquals(
            com.hcimbot.plan.DialoguePlan.Choice.ByText("No thanks."),
            plan.choose(listOf("Yes please.", "No thanks.")),
        )
        // Veos after X Marks the Spot, offering a trip to Great Kourend.
        assertEquals(
            com.hcimbot.plan.DialoguePlan.Choice.ByText("I'm good thanks."),
            plan.choose(listOf("I'd love to come!", "Tell me more about Great Kourend.", "I'm good thanks.")),
        )
        // Nothing that ends it, so still a pause rather than a guess.
        assertTrue(plan.choose(listOf("Yes.", "Tell me more.")) is com.hcimbot.plan.DialoguePlan.Choice.Stuck)
    }

    @Test
    fun `the Draynor rooftop course is run obstacle by obstacle, lap after lap`() {
        val course = com.hcimbot.plan.AgilityCourse.DRAYNOR
        val game = FakeGame()
        game.playerTile = Tile(3104, 3277, 0)
        course.obstacles.forEach { game.objects += com.hcimbot.game.SceneEntity(it.objectId, it.name, it.tile) }
        // Each obstacle lands the player in the next one's area, and the crate back on the ground.
        val landings = mapOf(
            11404 to Tile(3099, 3279, 3), 11405 to Tile(3090, 3275, 3), 11406 to Tile(3091, 3266, 3),
            11430 to Tile(3088, 3258, 3), 11630 to Tile(3090, 3254, 3), 11631 to Tile(3098, 3258, 3), 11632 to Tile(3103, 3261, 0),
        )
        game.onObjectInteract = { obj, _ -> game.playerTile = landings.getValue(obj.id) }
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game), log::add)
        val task = com.hcimbot.plan.AgilityCourseTask(course)
        repeat(60) { assertTrue(task.tick(ctx) !is com.hcimbot.plan.TaskResult.Failed) }
        val used = game.calls.filter { it.startsWith("object") }
        assertEquals(listOf("Rough wall", "Tightrope", "Tightrope", "Narrow wall", "Wall", "Gap", "Crate"), used.take(7).map { it.removePrefix("object ").removeSuffix(" null") })
        assertTrue(log.any { "Finished lap 1" in it }, log.toString())
    }

    private val log = mutableListOf<String>()

    @Test
    fun `a lap is finished before the step can end`() {
        val game = FakeGame()
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.AgilityCourseTask(com.hcimbot.plan.AgilityCourse.DRAYNOR)
        game.playerTile = Tile(3090, 3275, 3) // on a rooftop
        assertFalse(task.canStopHere(ctx))
        game.playerTile = Tile(3103, 3261, 0) // down off the crate
        assertTrue(task.canStopHere(ctx))
    }

    @Test
    fun `each obstacle is clicked the moment the player lands, without waiting for the animation`() {
        val course = com.hcimbot.plan.AgilityCourse.DRAYNOR
        val game = FakeGame()
        game.playerTile = Tile(3104, 3277, 0)
        course.obstacles.forEach { game.objects += com.hcimbot.game.SceneEntity(it.objectId, it.name, it.tile) }
        val landings = mapOf(
            11404 to Tile(3099, 3279, 3), 11405 to Tile(3090, 3275, 3), 11406 to Tile(3091, 3266, 3),
            11430 to Tile(3088, 3258, 3), 11630 to Tile(3090, 3254, 3), 11631 to Tile(3098, 3258, 3), 11632 to Tile(3103, 3261, 0),
        )
        game.onObjectInteract = { obj, _ ->
            game.playerTile = landings.getValue(obj.id)
            game.isBusy = true // still playing the landing animation
        }
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.AgilityCourseTask(course)
        repeat(7) { task.tick(ctx) }
        assertEquals(7, game.calls.count { it.startsWith("object") }, game.calls.toString())
    }

    @Test
    fun `upstairs in a house next to the course, the bot walks to the start`() {
        val course = com.hcimbot.plan.AgilityCourse.DRAYNOR
        // Morgan's house, first floor, where step 35 ends.
        assertEquals(course.start, course.next(Tile(3099, 3268, 1)))
        // Between two rooftops mid-jump: wait, do not walk off.
        assertNull(course.next(Tile(3093, 3279, 3)))
    }

    @Test
    fun `a quest fight far above the player is refused`() {
        val game = FakeGame()
        game.levels["HITPOINTS"] = 10
        game.varps[1] = 25
        val helper = com.hcimbot.guide.QuestHelper(
            com.hcimbot.guide.QuestVar("varp", 1),
            lastValue = 35,
            steps = mapOf(
                "25" to com.hcimbot.guide.Instruction(
                    kind = "object", point = listOf(3151, 3348, 0), ids = listOf(40341),
                    text = "Climb down into the Strange Hole east of Draynor Manor. Be prepared to fight Sourhog (level 37)",
                ),
            ),
        )
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val result = com.hcimbot.plan.QuestTask("aporcineofinterest", helper, allowCombat = true).tick(ctx)
        assertTrue(result is com.hcimbot.plan.TaskResult.Failed && "level 37" in result.reason, result.toString())
        assertTrue(game.calls.isEmpty())
    }

    @Test
    fun `a first trip with no Travel option talks and says yes to going`() {
        val veosAt = Tile(3054, 3245, 0)
        val game = FakeGame()
        game.playerTile = Tile(3055, 3247, 0)
        game.npcs += com.hcimbot.game.SceneEntity(8630, "Veos", veosAt)
        game.npcMenus[8630] = listOf("Talk-to")
        game.onNpcInteract = { _, _ -> game.options = listOf("I'd love to come!", "Tell me more about Great Kourend.", "I'm good thanks.") }
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.TravelTask(
            "Port Piscarilius", listOf(com.hcimbot.plan.TravelTask.Departure(veosAt, 8630, "Travel Veos")), Tile(1824, 3690, 0),
        )
        task.tick(ctx)
        assertEquals("npc Veos Talk-to", game.calls.last())
        task.tick(ctx)
        assertEquals("option I'd love to come!", game.calls.last())
    }

    @Test
    fun `a Travel click that keeps failing falls back to talking`() {
        val veosAt = Tile(3054, 3245, 0)
        val game = FakeGame()
        game.playerTile = Tile(3055, 3247, 0)
        game.npcs += com.hcimbot.game.SceneEntity(8630, "Veos", veosAt)
        game.npcInteractWorks = { _, action -> action == "Talk-to" }
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.TravelTask(
            "Port Piscarilius", listOf(com.hcimbot.plan.TravelTask.Departure(veosAt, 8630, "Travel Veos")), Tile(1824, 3690, 0),
        )
        repeat(3) { task.tick(ctx) }
        assertEquals(listOf("npc Veos Travel", "npc Veos Travel", "npc Veos Talk-to"), game.calls)
    }

    @Test
    fun `the minecart's destination menu is answered by clicking the place`() {
        val cartAt = Tile(1761, 3709, 0)
        val game = FakeGame()
        game.playerTile = Tile(1760, 3710, 0)
        game.objects += com.hcimbot.game.SceneEntity(28835, "Minecart", cartAt)
        game.onObjectInteract = { _, _ -> game.windowChoices = listOf("1: Arceuus", "A: Port Piscarilius", "B: Shayzien East", "D: (Cancel)") }
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.TravelTask(
            "Shayzien East", listOf(com.hcimbot.plan.TravelTask.Departure(cartAt, 28835, "Travel Minecart")), Tile(1590, 3620, 0),
        )
        task.tick(ctx)
        task.tick(ctx)
        assertEquals(listOf("object Minecart Travel", "choose Shayzien East"), game.calls)
    }

    @Test
    fun `an item the game shows further off is walked to and taken`() {
        val beerAt = Tile(1520, 3610, 0)
        val game = FakeGame()
        game.playerTile = Tile(1520, 3592, 0) // 18 tiles away
        game.groundItems += com.hcimbot.game.SceneEntity(1917, "Beer", beerAt)
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.TakeItemTask(listOf(com.hcimbot.guide.ItemRef("Beer", listOf(1917))))
        repeat(4) { task.tick(ctx) }
        assertEquals(listOf("walk (1520, 3610, 0)", "take Beer"), game.calls)
    }

    @Test
    fun `a fight's block animation does not hold back the next walking order`() {
        val game = FakeGame()
        game.playerTile = Tile(2156, 5288, 0)
        game.isBusy = true // hit, so animating...
        game.movingOverride = false // ...but standing still
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.WalkTask(Tile(2144, 5280, 0), radius = 2)
        task.tick(ctx)
        assertEquals(listOf("walk (2144, 5280, 0)"), game.calls)
        // Still walking: do not give another order on top of it.
        game.movingOverride = true
        game.playerTile = Tile(2150, 5284, 0)
        task.tick(ctx)
        assertEquals(1, game.calls.size)
    }

    @Test
    fun `the Stray Dog is looked for around Varrock and given the bones`() {
        val dogAt = Tile(3253, 3422, 0)
        val game = FakeGame()
        game.playerTile = Tile(3185, 3436, 0)
        game.inv[526] = 1
        // The dog is only in sight from the east bank; nothing is nearby from the west one.
        game.npcs += com.hcimbot.game.SceneEntity(2902, "Stray dog", dogAt)
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val round = listOf(Tile(3185, 3436, 0), Tile(3253, 3420, 0))
        val task = com.hcimbot.plan.FindAndUseTask(526, "Stray dog", round)
        // The fake game shows NPCs up to 20 tiles away, as in the real one up to 50.
        var result: com.hcimbot.plan.TaskResult = com.hcimbot.plan.TaskResult.Running
        repeat(12) { if (result == com.hcimbot.plan.TaskResult.Running) result = task.tick(ctx) }
        assertTrue("walk (3253, 3420, 0)" in game.calls, game.calls.toString())
        assertTrue(game.calls.any { it.startsWith("use 526") || it.contains("526") }, game.calls.toString())
        assertEquals(com.hcimbot.plan.TaskResult.Done, result)
    }

    @Test
    fun `no dog anywhere on the round fails with a clear message`() {
        val game = FakeGame()
        game.playerTile = Tile(3185, 3436, 0)
        game.inv[526] = 1
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.FindAndUseTask(526, "Stray dog", listOf(Tile(3185, 3436, 0), Tile(3253, 3420, 0)), rounds = 1)
        var result: com.hcimbot.plan.TaskResult = com.hcimbot.plan.TaskResult.Running
        repeat(20) { if (result == com.hcimbot.plan.TaskResult.Running) result = task.tick(ctx) }
        assertTrue(result is com.hcimbot.plan.TaskResult.Failed && "did not find the Stray dog" in result.reason, result.toString())
    }

    @Test
    fun `step 19 looks for rats behind Lumbridge castle first`() {
        val game = FakeGame()
        game.playerTile = Tile(3230, 3209, 0)
        game.spawns["giant rat"] = listOf(Tile(3229, 3188, 0)) // nearer, but in the swamp
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.KillTask(
            emptyList(), listOf("Giant rat"), listOf(com.hcimbot.guide.ItemRef("Bones", listOf(526))),
            lookFirst = Tile(3195, 3205, 0),
        )
        task.tick(ctx)
        task.tick(ctx)
        assertEquals(listOf("walk (3195, 3205, 0)"), game.calls)
    }

    @Test
    fun `hunting gives up rather than going far or into the Wilderness`() {
        val game = FakeGame()
        game.spawns["giant rat"] = listOf(Tile(3040, 3700, 0), Tile(3292, 3377, 0))
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val result = com.hcimbot.plan.KillTask(emptyList(), listOf("Giant rat"), emptyList<com.hcimbot.guide.ItemRef>() + com.hcimbot.guide.ItemRef("Bones", listOf(526))).tick(ctx)
        assertTrue(result is com.hcimbot.plan.TaskResult.Failed, result.toString())
        assertTrue(game.calls.isEmpty())
    }

    @Test
    fun `quest objects with only a RuneLite name are looked up`() {
        val game = FakeGame()
        game.objectNames["PORCINE_NOTICEBOARD"] = 40307
        game.objects += com.hcimbot.game.SceneEntity(40307, "Notice board", Tile(3222, 3218, 0))
        val helper = com.hcimbot.guide.QuestHelper(
            com.hcimbot.guide.QuestVar("varp", 1),
            lastValue = 35,
            steps = mapOf(
                "0" to com.hcimbot.guide.Instruction(
                    kind = "object", point = listOf(3222, 3218, 0), text = "Read the notice board in Draynor Village.",
                    constant = "PORCINE_NOTICEBOARD",
                ),
            ),
        )
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        com.hcimbot.plan.QuestTask("aporcineofinterest", helper, allowCombat = false, boundedTo = emptySet()).tick(ctx)
        assertEquals(listOf("object Notice board Read"), game.calls)
    }

    @Test
    fun `menu verbs come from the step text`() {
        assertEquals("Pickpocket", StepPlanner.verb(Step(text = "Pickpocket a man/woman [Lumbridge Easy Diary]")))
        assertEquals("Talk-to", StepPlanner.verb(Step(text = "Go upstairs and Talk to Duke Horacio (1,1)")))
        assertEquals("Attack", StepPlanner.verb(Step(text = "Kill a giant rat with Wind Strike")))
        assertNull(StepPlanner.verb(Step(text = "Head North-West")))
        assertEquals("Talk-to", StepPlanner.verb(Step(text = "Walk to the Hosidius General Store, talk to Horace (3,2)")))
        assertNull(StepPlanner.verb(Step(text = "Teleport to Essence Mine via right click Wizard Cromperty")))
    }

    @Test
    fun `compound steps are spotted`() {
        assertTrue(StepPlanner.isCompound(Step(text = "Kill a giant rat and collect the Bones")))
        assertTrue(StepPlanner.isCompound(Step(text = "Sell the dagger & buy a hammer")))
        assertFalse(StepPlanner.isCompound(Step(text = "Talk to Father Aereck (3,1) [Restless Ghost & more]")))
        assertFalse(
            StepPlanner.isCompound(Step(text = "Bank at Draynor and Deposit all"), ignoringBanking = true)
        )
        assertFalse(StepPlanner.isCompound(Step(text = "Head South & pray at the Guthix Altar")))
        assertFalse(StepPlanner.isCompound(Step(text = "Go upstairs and Talk to Duke Horacio (1,1)")))
        assertTrue(StepPlanner.isCompound(Step(text = "Teleport to Kourend. Enter Catacombs. Head West")))
    }

    @Test
    fun `steps that only move become walks`() {
        assertTrue(StepPlanner.isMovement(Step(text = "Head to Canifis")))
        assertFalse(StepPlanner.isMovement(Step(text = "Complete the Agility Pyramid until 627,000 Agility Experience")))
        assertFalse(StepPlanner.isMovement(Step(text = "Teleport to Grand Exchange via spellbook")))
        assertFalse(StepPlanner.isMovement(Step(text = "Return to Ardougne & deposit the Pineapples & bones")))
        assertTrue(StepPlanner.isOpenEnded(Step(text = "Buy Balls of Wool at Ardougne if needed")))
        assertFalse(StepPlanner.isOpenEnded(Step(text = "Withdraw: Coins, Spade (2 Inventory slots)")))
    }

    @Test
    fun `teleport destinations`() {
        fun dest(text: String, via: String = "") =
            StepPlanner.teleportDestination(Step(text = text, teleport = com.hcimbot.guide.Teleport(via, listOf(1))))
        assertEquals("wintertodt", dest("Games necklace -> Wintertodt"))
        assertEquals("Ardougne", dest("Ardougne: Ardy Cloak 1 -> Run North", via = "Ardougne: Ardy Cloak 1"))
        assertNull(dest("Ectophial"))
    }

    @Test
    fun `travel menu option drops the target name`() {
        assertEquals("Travel", TravelTask.menuOption("Travel Minecart", "Minecart"))
        assertEquals("Brimhaven", TravelTask.menuOption("Brimhaven Captain Barnaby", "Captain Barnaby"))
        assertEquals("Pay-fare", TravelTask.menuOption("Pay-fare", "Customs officer"))
    }

    @Test
    fun `progress reads the plugin's ticks and keeps its own`() {
        var plugin = "a,b"
        var bot: String? = null
        val store = SharedProgressStore({ plugin }, { bot }, { bot = it })

        assertEquals(setOf("a", "b"), store.completedIds())
        store.markComplete("c")
        assertEquals("c", bot)
        plugin = "a,b,d"
        assertEquals(setOf("a", "b", "c", "d"), store.completedIds())
        assertEquals(setOf("x", "y"), ProgressFormat.decode(" x, ,y,"))
    }

    @Test
    fun `150 wines are bought in trips between the shop and the bank`() {
        val game = FakeGame()
        game.playerTile = Tile(1390, 2862, 0)
        game.inv[995] = 5000
        game.npcs += com.hcimbot.game.SceneEntity(13928, "Bartender", Tile(1390, 2860, 0))
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val seller = com.hcimbot.plan.ShopTask.Seller("Bartender", listOf(13928), listOf(Tile(1390, 2860, 0)))
        val task = com.hcimbot.plan.StockUpTask(listOf(com.hcimbot.plan.StockUpTask.Want("Jug of wine", 1993, 150)), listOf(seller))
        var result: com.hcimbot.plan.TaskResult = com.hcimbot.plan.TaskResult.Running
        var ticks = 0
        while (result == com.hcimbot.plan.TaskResult.Running && ticks++ < 2000) {
            // The fake does not open a shop by itself, so open it after the Bartender is clicked.
            if (game.calls.lastOrNull()?.endsWith("Trade") == true) game.isShopOpen = true
            result = task.tick(ctx)
        }
        assertEquals(com.hcimbot.plan.TaskResult.Done, result, game.calls.takeLast(10).toString())
        assertEquals(150, (game.bank[1993] ?: 0) + (game.inv[1993] ?: 0))
        assertEquals(5000, game.inv[995])
    }
}
