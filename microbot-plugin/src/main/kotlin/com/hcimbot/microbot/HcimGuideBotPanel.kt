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
import javax.swing.JPasswordField
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
    /** Hands the typed PIN on. False when it is not exactly 4 digits. Held in memory only, never saved. */
    private val setBankPin: (CharArray?) -> Boolean = { true },
    private val hasBankPin: () -> Boolean = { false },
) : PluginPanel() {
    private val state = JLabel("Starting")
    private val step = text()
    private val doing = text()
    private val detail = text()
    private val recent = text()
    private val pause = JButton("Pause")
    private val pinField = JPasswordField(4)
    private val pinStatus = JLabel(" ")
    private val refresh = Timer(REFRESH_MS) { update() }

    init {
        layout = BorderLayout()
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(JLabel("HCIM Guide Bot").apply { font = font.deriveFont(Font.BOLD, 16f) })
            // On its own line: next to the title it is cut off in the narrow sidebar.
            add(JLabel("Version ${BotModuleImpl.VERSION}").apply { foreground = Color.GRAY })
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
        val pin = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(0, 8, 8, 8)
            add(JLabel("Bank PIN").apply { foreground = Color.GRAY })
            add(JLabel("Kept in memory only, never saved.").apply { foreground = Color.GRAY; font = font.deriveFont(10f) })
            add(JPanel(GridLayout(1, 2, 6, 0)).apply {
                add(pinField)
                add(JButton("Set").apply { addActionListener { savePin() } })
            })
            add(JButton("Forget PIN").apply { addActionListener { forgetPin() } })
            add(pinStatus)
        }
        val lower = JPanel(BorderLayout()).apply {
            add(buttons, BorderLayout.NORTH)
            add(pin, BorderLayout.CENTER)
            // Below the buttons, so a long history never pushes them out of sight.
            add(section("Recent", recent).apply { border = BorderFactory.createEmptyBorder(0, 8, 8, 8) }, BorderLayout.SOUTH)
        }
        add(body, BorderLayout.NORTH)
        add(lower, BorderLayout.CENTER)
        update()
    }

    private fun savePin() {
        val typed = pinField.password
        val ok = setBankPin(typed)
        typed.fill('0')
        pinField.text = ""
        pinStatus.text = if (ok) "PIN set" else "Needs exactly 4 digits"
        pinStatus.foreground = if (ok) BotStatus.RUNNING else BotStatus.STOPPED
    }

    private fun forgetPin() {
        setBankPin(null)
        pinField.text = ""
        pinStatus.text = "PIN forgotten"
        pinStatus.foreground = Color.GRAY
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
        // The PIN was forgotten behind the panel's back, e.g. after a refusal.
        if (!hasBankPin() && pinStatus.text == "PIN set") {
            pinStatus.text = "PIN forgotten"
            pinStatus.foreground = Color.GRAY
        }
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
