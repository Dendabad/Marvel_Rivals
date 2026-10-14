package com.hcimbot.microbot

import java.awt.Color

/** What the bot is up to, for the on screen box and the sidebar panel. */
data class BotStatus(
    /** One or two words: Running, PAUSED, STOPPED and so on. */
    val state: String,
    val stateColor: Color,
    /** "Step 5 of 2925: Head North-West to Magic combat tutor...". */
    val step: String? = null,
    /** The action in progress, e.g. "Walk to (3218, 3238, 0)". */
    val doing: String? = null,
    /** Why it paused or stopped, or what to do next. */
    val detail: String? = null,
    /** What the bot did lately, newest first: steps started, done and why, pauses. */
    val recent: List<String> = emptyList(),
) {
    companion object {
        val RUNNING: Color = Color(0x4C, 0xAF, 0x50)
        val PAUSED: Color = Color(0xFF, 0xB3, 0x00)
        val STOPPED: Color = Color(0xE5, 0x39, 0x35)
        val IDLE: Color = Color(0xB0, 0xB0, 0xB0)

        val NOT_STARTED = BotStatus("Starting", IDLE, detail = "Log in to start.")
    }
}
