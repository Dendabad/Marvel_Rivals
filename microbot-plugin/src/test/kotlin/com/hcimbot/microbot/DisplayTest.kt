package com.hcimbot.microbot

import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JTextArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayTest {
    private fun Container.all(): List<Component> = components.flatMap { listOf(it) + ((it as? Container)?.all() ?: emptyList()) }

    @Test
    fun `sidebar buttons pause, resume, skip and retry`() {
        var paused = false
        var skips = 0
        var retries = 0
        val status = BotStatus("Running", BotStatus.RUNNING, step = "Step 5 of 2925: Head North-West", doing = "Walk to (3218, 3238, 0)")
        val panel = HcimGuideBotPanel({ status }, { paused }, { paused = it }, { skips++ }, { retries++ })
        val buttons = panel.all().filterIsInstance<JButton>().associateBy { it.text }

        assertTrue(panel.all().filterIsInstance<JTextArea>().any { it.text == "Walk to (3218, 3238, 0)" })
        assertTrue(panel.all().filterIsInstance<JLabel>().any { it.text == "Running" })

        buttons.getValue("Pause").doClick()
        assertTrue(paused)
        assertEquals("Resume", buttons.getValue("Pause").text, "the button turns into Resume")
        buttons.getValue("Pause").doClick()
        assertTrue(!paused)

        buttons.getValue("Skip this step").doClick()
        buttons.getValue("Retry this step").doClick()
        assertEquals(1, skips)
        assertEquals(1, retries)
    }

    @Test
    fun `sidebar lists what the bot did lately`() {
        val status = BotStatus(
            "Running", BotStatus.RUNNING,
            recent = listOf("12:00:05  Done (the game shows it is done): Collect 4x logs", "12:00:01  Paused by you"),
        )
        val panel = HcimGuideBotPanel({ status }, { false }, {}, {}, {})
        assertTrue(panel.all().filterIsInstance<JTextArea>().any { "Done (the game shows it is done)" in it.text && "Paused by you" in it.text })
    }

    @Test
    fun `long lines wrap for the on screen box`() {
        val lines = HcimGuideBotOverlay.wrap("Head North-West to Magic combat tutor & drop Air & Mind Runes and claim more.", 38)
        assertTrue(lines.size > 1)
        assertTrue(lines.all { it.length <= 38 }, "$lines")
        assertEquals("Head North-West to Magic combat tutor & drop Air & Mind Runes and claim more.", lines.joinToString(" "))
    }
}
