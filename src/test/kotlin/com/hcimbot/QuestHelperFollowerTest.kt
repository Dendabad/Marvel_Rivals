package com.hcimbot

import com.hcimbot.engine.Conditions
import com.hcimbot.game.HelperStep
import com.hcimbot.game.SceneEntity
import com.hcimbot.guide.Tile
import com.hcimbot.plan.FollowQuestHelperTask
import com.hcimbot.plan.TaskContext
import com.hcimbot.plan.TaskResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuestHelperFollowerTest {
    private fun ctx(game: FakeGame) = TaskContext(game, game, Conditions(game))

    @Test
    fun `it talks to the NPC Quest Helper points at`() {
        val game = FakeGame()
        game.playerTile = Tile(3100, 3200, 0)
        game.npcs += SceneEntity(1, "Hetty", Tile(3101, 3200, 0))
        game.helper = HelperStep("Talk to Hetty in Rimmington.", listOf(1), Tile(3101, 3200, 0))
        val task = FollowQuestHelperTask("witchspotion")
        repeat(3) { task.tick(ctx(game)) }
        assertTrue(game.calls.contains("npc Hetty Talk-to"), game.calls.toString())
    }

    @Test
    fun `an object is clicked with the verb the instruction opens with`() {
        val game = FakeGame()
        game.playerTile = Tile(3100, 3200, 0)
        game.objects += SceneEntity(77, "Ladder", Tile(3101, 3200, 0))
        game.helper = HelperStep("Climb down the ladder.", emptyList(), Tile(3101, 3200, 0), objectIds = listOf(77))
        val task = FollowQuestHelperTask("anyquest")
        repeat(3) { task.tick(ctx(game)) }
        assertTrue(game.calls.contains("object Ladder Climb-down"), game.calls.toString())
    }

    @Test
    fun `a fight is refused with combat off and a boss far above the player is refused with it on`() {
        val game = FakeGame()
        game.npcs += SceneEntity(5, "Bear", Tile(3222, 3219, 0))
        game.helper = HelperStep("Kill the bear.", listOf(5))
        val off = FollowQuestHelperTask("anyquest").tick(ctx(game))
        assertTrue(off is TaskResult.Failed && off.reason.contains("combat is turned off"), off.toString())

        game.levels["HITPOINTS"] = 10
        game.helper = HelperStep("Kill Sourhog (level 37).", listOf(5))
        val strong = FollowQuestHelperTask("anyquest", allowCombat = true).tick(ctx(game))
        assertTrue(strong is TaskResult.Failed && strong.reason.contains("too risky"), strong.toString())
    }

    @Test
    fun `no instruction left means the quest is done`() {
        val game = FakeGame()
        game.helper = null
        assertEquals(TaskResult.Done, FollowQuestHelperTask("anyquest").tick(ctx(game)))
    }

    @Test
    fun `an instruction that needs an item used on something says so`() {
        val game = FakeGame()
        game.helper = HelperStep("Use the cheese on the mouse hole.", emptyList())
        val result = FollowQuestHelperTask("anyquest").tick(ctx(game))
        assertTrue(result is TaskResult.Failed && result.reason.contains("item used"), result.toString())
    }

    @Test
    fun `the verb comes from the first words, through a right click prefix`() {
        assertEquals("Climb-over", FollowQuestHelperTask.verbOf("Climb over the stile"))
        assertEquals("Search", FollowQuestHelperTask.verbOf("Right-click search the drawers"))
        assertEquals("Talk-to", FollowQuestHelperTask.verbOf("Bring the notes to Sedridor"))
        assertEquals(null, FollowQuestHelperTask.verbOf("Sneak around the perimeter"))
    }
}
