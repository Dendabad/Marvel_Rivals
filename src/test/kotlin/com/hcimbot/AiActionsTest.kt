package com.hcimbot

import com.hcimbot.ai.AiAction
import com.hcimbot.ai.AiExecutor
import com.hcimbot.game.SceneEntity
import com.hcimbot.guide.Tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiActionsTest {
    @Test
    fun `a reply is read for its actions, ignoring words around it and unknown entries`() {
        val reply = "Sure, try this:\n[{\"do\":\"walk\",\"x\":1581,\"y\":3140,\"plane\":1},{\"do\":\"object\",\"id\":52636,\"option\":\"Climb-down\"},{\"do\":\"format disk\"},{\"do\":\"continue\"}] hope it helps"
        assertEquals(
            listOf(AiAction.Walk(Tile(1581, 3140, 1)), AiAction.Obj(52636, "Climb-down"), AiAction.Continue),
            AiAction.parse(reply),
        )
        assertEquals(emptyList(), AiAction.parse("I cannot help"))
    }

    @Test
    fun `the executor clicks what is in sight and refuses attacks and the Wilderness`() {
        val game = FakeGame().apply {
            playerTile = Tile(1581, 3142, 1)
            objects += SceneEntity(52636, "Staircase", Tile(1581, 3140, 1))
            npcs += SceneEntity(1, "Guard", Tile(1582, 3142, 1))
        }
        val ok = AiExecutor(listOf(AiAction.Obj(52636, "Climb-down")), {})
        repeat(3) { ok.tick(game, game) }
        assertTrue(game.calls.contains("object Staircase Climb-down"), game.calls.toString())

        val attack = AiExecutor(listOf(AiAction.Npc(1, "Attack")), {})
        assertEquals(AiExecutor.State.Failed, attack.tick(game, game))
        assertTrue(game.calls.none { it.contains("Attack") })

        val wild = AiExecutor(listOf(AiAction.Walk(Tile(3000, 3700, 0))), {})
        assertEquals(AiExecutor.State.Failed, wild.tick(game, game))
    }
}
