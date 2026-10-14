package com.hcimbot.microbot

import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics2D
import net.runelite.client.ui.overlay.OverlayPanel
import net.runelite.client.ui.overlay.OverlayPosition
import net.runelite.client.ui.overlay.components.LineComponent
import net.runelite.client.ui.overlay.components.TitleComponent

/** The box in the corner of the game screen showing what the bot is doing. */
class HcimGuideBotOverlay(private val status: () -> BotStatus) : OverlayPanel() {
    init {
        position = OverlayPosition.TOP_LEFT
    }

    override fun render(graphics: Graphics2D): Dimension? {
        val now = status()
        panelComponent.children.add(TitleComponent.builder().text("HCIM Guide Bot ${HcimGuideBotPlugin.VERSION}").color(Color.WHITE).build())
        panelComponent.children.add(LineComponent.builder().left(now.state).leftColor(now.stateColor).build())
        now.step?.let { line(it, Color.WHITE) }
        now.doing?.let { line("Doing: $it", Color.LIGHT_GRAY) }
        now.detail?.let { line(it, now.stateColor) }
        panelComponent.setPreferredSize(Dimension(WIDTH, 0))
        return super.render(graphics)
    }

    /** Long text is cut into lines, since the panel does not wrap it. */
    private fun line(text: String, color: Color) {
        wrap(text, CHARS_PER_LINE).forEach {
            panelComponent.children.add(LineComponent.builder().left(it).leftColor(color).build())
        }
    }

    companion object {
        private const val WIDTH = 230
        private const val CHARS_PER_LINE = 38

        fun wrap(text: String, width: Int): List<String> {
            val lines = mutableListOf<String>()
            var line = StringBuilder()
            for (word in text.split(' ')) {
                if (line.isNotEmpty() && line.length + 1 + word.length > width) {
                    lines += line.toString()
                    line = StringBuilder()
                }
                if (line.isNotEmpty()) line.append(' ')
                line.append(word)
            }
            if (line.isNotEmpty()) lines += line.toString()
            return lines.take(MAX_LINES)
        }

        private const val MAX_LINES = 6
    }
}
