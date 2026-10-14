package com.hcimbot.microbot

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.Timer
import net.runelite.client.ui.PluginPanel

/**
 * The sidebar panel: what the bot is doing, and buttons to pause it, skip the
 * current step or retry it. The buttons talk to the script directly, so they
 * work whatever state the settings are in.
 */
class HcimGuideBotPanel(
    private val status: () -> BotStatus,
    private val isPaused: () -> Boolean,
    private val setPaused: (Boolean) -> Unit,
    private val skip: () -> Unit,
    private val retry: () -> Unit,
) : PluginPanel() {
    private val state = JLabel("Starting")
    private val step = text()
    private val doing = text()
    private val detail = text()
    private val recent = text()
    private val pause = JButton("Pause")
    private val refresh = Timer(REFRESH_MS) { update() }

    init {
        layout = BorderLayout()
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(JLabel("HCIM Guide Bot").apply { font = font.deriveFont(Font.BOLD, 16f) })
            add(state.apply { font = font.deriveFont(Font.BOLD, 14f) })
            add(section("Step", step))
            add(section("Doing", doing))
            add(section("Details", detail))
        }
        val buttons = JPanel(GridLayout(0, 1, 0, 6)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(pause.apply { addActionListener { setPaused(!isPaused()); update() } })
            add(JButton("Skip this step").apply {
                toolTipText = "Tick the current step as done and move on"
                addActionListener { skip(); update() }
            })
            add(JButton("Retry this step").apply {
                toolTipText = "Start the current step's actions again from where you are"
                addActionListener { retry(); update() }
            })
        }
        add(body, BorderLayout.NORTH)
        add(buttons, BorderLayout.CENTER)
        // Below the buttons, so a long history never pushes them out of sight.
        add(section("Recent", recent).apply { border = BorderFactory.createEmptyBorder(0, 8, 8, 8) }, BorderLayout.SOUTH)
        update()
    }

    override fun onActivate() = refresh.start()

    override fun onDeactivate() = refresh.stop()

    fun stop() = refresh.stop()

    private fun update() {
        val now = status()
        state.text = now.state
        state.foreground = now.stateColor
        step.text = now.step ?: "-"
        doing.text = now.doing ?: "-"
        detail.text = now.detail ?: "-"
        recent.text = now.recent.joinToString("\n").ifEmpty { "-" }
        pause.text = if (isPaused()) "Resume" else "Pause"
    }

    private fun text() = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = null
    }

    private fun section(title: String, content: JTextArea) = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
        add(JLabel(title).apply { foreground = Color.GRAY }, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
    }

    companion object {
        private const val REFRESH_MS = 500

        /** The sidebar icon, drawn here so the plugin needs no image files: a green H. */
        fun icon(): BufferedImage = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).also { image ->
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = BotStatus.RUNNING
            g.fillRoundRect(0, 0, 16, 16, 5, 5)
            g.color = Color.WHITE
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
            g.drawString("H", 4, 13)
            g.dispose()
        }
    }
}
