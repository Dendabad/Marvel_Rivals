package com.hcimbot

import com.hcimbot.engine.BotSettings
import com.hcimbot.engine.GuideRunner
import com.hcimbot.game.SceneEntity
import com.hcimbot.guide.Guide
import com.hcimbot.guide.GuideLoader
import com.hcimbot.guide.Tile
import com.hcimbot.progress.InMemoryProgressStore
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GuideRunnerTest {
    private lateinit var guide: Guide
    private lateinit var game: FakeGame
    private val log = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        guide = GuideLoader().load(javaClass.getResourceAsStream("/test-guide.json")!!)
        game = FakeGame()
        log.clear()
    }

    private fun runner(done: Set<String> = emptySet(), settings: BotSettings = BotSettings()) =
        GuideRunner(guide, game, game, InMemoryProgressStore(done), settings, log::add).also { it.start() }

    private fun GuideRunner.ticks(n: Int) = repeat(n) { onGameTick() }

    private fun allBefore(id: String): Set<String> = guide.steps.takeWhile { it.id != id }.map { it.id }.toSet()

    @Test
    fun `quest step does only its own part of the quest`() {
        game.npcs += SceneEntity(100, "Priest", Tile(3243, 3210, 0))
        game.npcs += SceneEntity(101, "Hermit", Tile(3147, 3175, 0))
        game.onNpcInteract = { npc, _ ->
            if (npc.id == 100) game.options = listOf("Who are you?", "I'm looking for a quest!")
        }
        game.onOption = { text ->
            if (text == "I'm looking for a quest!") game.options = listOf("Yes.", "No.")
            if (text == "Yes.") game.varps[107] = 1
        }

        val runner = runner()
        runner.ticks(10)

        assertEquals("diary", runner.currentStep?.id, "should move on once the priest part is done: $log")
        assertTrue(game.calls.none { it.contains("Hermit") }, "must not run ahead to the next quest part: ${game.calls}")
        assertTrue("option Yes." in game.calls)
    }

    @Test
    fun `diary step repeats until the diary bit is set`() {
        game.npcs += SceneEntity(200, "Man", Tile(3222, 3219, 0))
        var attempts = 0
        game.onNpcInteract = { _, action ->
            assertEquals("Pickpocket", action)
            if (++attempts == 3) game.varps[1194] = 1 shl 6
        }

        val runner = runner(done = allBefore("diary"))
        runner.ticks(10)

        assertEquals(3, attempts)
        assertEquals("bank", runner.currentStep?.id)
    }

    @Test
    fun `bank step deposits everything and withdraws the listed items`() {
        game.inv[1511] = 5
        game.bank[995] = 1000
        game.bank[952] = 1

        val runner = runner(done = allBefore("bank"))
        runner.ticks(12)

        // The bank is closed before the bot walks on to the next step.
        assertEquals(
            listOf("open bank", "deposit all", "withdraw 995 x0", "withdraw 952 x0", "close", "walk (3103, 3265, 0)"),
            game.calls,
        )
        assertEquals(mapOf(995 to 1000, 952 to 1), game.inv.toMap())
        assertFalse(game.isBankOpen)
    }

    @Test
    fun `bank step pauses when an item is missing from the bank`() {
        game.bank[995] = 1000

        val runner = runner(done = allBefore("bank"))
        runner.ticks(12)

        val state = assertIs<GuideRunner.State.Paused>(runner.state)
        assertTrue("Spade" in state.reason, state.reason)
    }

    @Test
    fun `walk step finishes on arrival`() {
        val runner = runner(done = allBefore("walk"))
        runner.ticks(3)

        assertEquals(Tile(3103, 3265, 0), game.playerTile)
        assertEquals("tip", runner.currentStep?.id)
    }

    @Test
    fun `tips pause until ticked in the plugin`() {
        val progress = InMemoryProgressStore(allBefore("tip"))
        val runner = GuideRunner(guide, game, game, progress, BotSettings(), log::add).also { it.start() }
        runner.ticks(3)
        val paused = assertIs<GuideRunner.State.Paused>(runner.state)
        assertTrue(paused.reason.startsWith("Tip:"))

        progress.markComplete("tip")
        runner.ticks(1)
        // Moved on to the next step, which is a combat step and pauses on its own.
        val next = assertIs<GuideRunner.State.Paused>(runner.state)
        assertEquals("fight", next.step.id)
    }

    @Test
    fun `combat pauses unless turned on`() {
        val runner = runner(done = allBefore("fight"))
        runner.ticks(2)
        val paused = assertIs<GuideRunner.State.Paused>(runner.state)
        assertTrue("combat" in paused.reason)
        assertTrue(game.calls.isEmpty())
    }

    @Test
    fun `finishing quest step follows quest helper branches to the end`() {
        game.varps[107] = 2
        game.objects += SceneEntity(400, "Coffin", Tile(3250, 3193, 0))
        game.onObjectInteract = { obj, _ ->
            when (obj.id) {
                400 -> {
                    game.objects.clear()
                    game.objects += SceneEntity(401, "Open coffin", Tile(3250, 3193, 0))
                }
                401 -> game.varps[107] = 3
            }
        }

        val runner = runner(done = allBefore("quest-rest"))
        runner.ticks(30)

        assertEquals(listOf("walk (3250, 3193, 0)", "object Coffin Open", "object Open coffin Search"), game.calls)
        assertEquals(GuideRunner.State.Finished, runner.state)
    }

    @Test
    fun `restarting at a step forgets the bot's ticks from there and starts there`() {
        val progress = InMemoryProgressStore(guide.steps.map { it.id }.toSet())
        val runner = GuideRunner(guide, game, game, progress, BotSettings(), log::add).also { it.start() }
        runner.ticks(1)
        assertEquals(GuideRunner.State.Finished, runner.state)

        // Step 4 is "walk": retest it.
        val stillTicked = runner.restartAt(4)
        assertTrue(stillTicked.isEmpty())
        assertEquals(guide.steps.take(3).map { it.id }.toSet(), progress.completedIds())
        runner.ticks(1)
        assertEquals("walk", runner.currentStep?.id)
        assertEquals(4, runner.currentStepNumber)
    }

    @Test
    fun `stuck on the agility rooftops, the bot finishes the lap before the next step`() {
        val runner = runner(done = allBefore("walk"))
        game.playerTile = Tile(3090, 3275, 3) // on the Draynor course, before the second tightrope
        game.objects += SceneEntity(11406, "Tightrope", Tile(3092, 3276, 3))
        runner.ticks(1)
        assertEquals(listOf("object Tightrope null"), game.calls)
        assertTrue(log.any { "finishing the lap to get down first" in it }, log.toString())
    }

    @Test
    fun `a paused step the player then does is ticked and the bot carries on`() {
        // The bank has neither item, so the bot pauses. The player gets them some other way.
        val runner = runner(done = allBefore("bank"))
        runner.ticks(10)
        assertIs<GuideRunner.State.Paused>(runner.state)
        game.inv[995] = 100
        game.inv[952] = 1
        runner.ticks(2)
        assertEquals("walk", runner.currentStep?.id)
        assertTrue(log.any { "you did it while the bot waited" in it }, log.toString())
    }

    @Test
    fun `the bot's ticks are copied to the plugin and taken back on restart, the player's are not`() {
        val ticked = mutableListOf<String>()
        var pluginSave = "player-step"
        var bot: String? = null
        val progress = com.hcimbot.progress.SharedProgressStore({ pluginSave }, { bot }, { bot = it }) { id, done ->
            ticked += "$id $done"
        }
        progress.markComplete("a")
        pluginSave = "player-step,a" // the plugin saved the bot's tick
        progress.forget(listOf("a", "player-step"))
        assertEquals(listOf("a true", "a false"), ticked)
        // Before the plugin saves the untick, "a" already counts as not done.
        assertEquals(setOf("player-step"), progress.completedIds())
        pluginSave = "player-step"
        assertEquals(setOf("player-step"), progress.completedIds())
    }

    @Test
    fun `restarting leaves the player's own ticks and reports them`() {
        // A store where the plugin ticked everything: the bot cannot forget those.
        val plugin = guide.steps.joinToString(",") { it.id }
        var bot: String? = null
        val progress = com.hcimbot.progress.SharedProgressStore({ plugin }, { bot }, { bot = it })
        val runner = GuideRunner(guide, game, game, progress, BotSettings(), log::add).also { it.start() }
        val stillTicked = runner.restartAt(6)
        assertEquals(listOf("quest-rest"), stillTicked.map { it.id })
        // The step started at runs even though the plugin has it ticked.
        runner.ticks(1)
        assertEquals("fight", runner.currentStep?.id)
    }

    @Test
    fun `skip ticks the current step even while it runs, and the display shows what it is doing`() {
        val runner = runner(done = allBefore("walk"))
        runner.ticks(1)
        assertEquals("walk", runner.currentStep?.id)
        assertEquals("Walk to (3103, 3265, 0)", runner.currentTaskDescription)
        runner.skipStep()
        runner.ticks(1)
        assertEquals("tip", runner.currentStep?.id)
    }

    @Test
    fun `low hitpoints halt the bot`() {
        game.hitpoints = 3
        val runner = runner()
        runner.ticks(1)
        assertIs<GuideRunner.State.Halted>(runner.state)
        assertTrue(game.calls.isEmpty())
    }

    @Test
    fun `eats before acting when food is set`() {
        game.hitpoints = 5
        game.inv[379] = 2
        val runner = runner(settings = BotSettings(foodIds = listOf(379)))
        runner.ticks(1)
        assertEquals(listOf("item 379 Eat"), game.calls)
        assertEquals(GuideRunner.State.Running, runner.state)
    }

    @Test
    fun `being attacked during a non combat step pauses`() {
        game.isInCombat = true
        val runner = runner()
        runner.ticks(1)
        assertIs<GuideRunner.State.Paused>(runner.state)
    }
}
