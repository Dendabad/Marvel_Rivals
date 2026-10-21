package com.questbot

import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics2D
import net.runelite.client.ui.overlay.OverlayPanel
import net.runelite.client.ui.overlay.OverlayPosition
import net.runelite.client.ui.overlay.components.LineComponent
import net.runelite.client.ui.overlay.components.TitleComponent

/** The box in the corner of the game screen showing what the bot is doing. */
class QuestHelperBotOverlay(private val lines: () -> List<Pair<String, Color>>) : OverlayPanel() {
    init {
        position = OverlayPosition.TOP_LEFT
    }

    override fun render(graphics: Graphics2D): Dimension? {
        panelComponent.children.add(TitleComponent.builder().text("Quest Helper Bot").color(Color.WHITE).build())
        lines().forEach { (text, color) ->
            wrap(text).forEach { panelComponent.children.add(LineComponent.builder().left(it).leftColor(color).build()) }
        }
        panelComponent.setPreferredSize(Dimension(WIDTH, 0))
        return super.render(graphics)
    }

    private fun wrap(text: String): List<String> {
        val result = mutableListOf<String>()
        var line = StringBuilder()
        for (word in text.split(' ')) {
            if (line.isNotEmpty() && line.length + 1 + word.length > CHARS) {
                result += line.toString()
                line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) result += line.toString()
        return result.take(MAX_LINES)
    }

    private companion object {
        const val WIDTH = 230
        const val CHARS = 38
        const val MAX_LINES = 6
    }
}
