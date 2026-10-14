package com.hcimbot.microbot

import com.hcimbot.loader.Secrets
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPasswordField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BankPinTest {
    private fun Container.all(): List<Component> = components.flatMap { listOf(it) + ((it as? Container)?.all() ?: emptyList()) }

    @Test
    fun `only a 4 digit PIN is kept, and forgetting it removes it`() {
        val secrets = Secrets()
        assertFalse(secrets.setBankPin("12a4".toCharArray()))
        assertFalse(secrets.setBankPin("123".toCharArray()))
        assertFalse(secrets.hasBankPin())
        assertTrue(secrets.setBankPin("1234".toCharArray()))
        assertEquals("1234", secrets.bankPin())
        secrets.forgetBankPin()
        assertNull(secrets.bankPin())
    }

    @Test
    fun `the sidebar sets and forgets the PIN and clears the box after typing`() {
        val secrets = Secrets()
        val panel = HcimGuideBotPanel(
            { BotStatus("Running", BotStatus.RUNNING) }, { false }, {}, {}, {},
            setBankPin = { secrets.setBankPin(it) }, hasBankPin = secrets::hasBankPin,
        )
        val field = panel.all().filterIsInstance<JPasswordField>().single()
        val buttons = panel.all().filterIsInstance<JButton>().associateBy { it.text }

        field.text = "12"
        buttons.getValue("Set").doClick()
        assertFalse(secrets.hasBankPin())
        assertTrue(panel.all().filterIsInstance<JLabel>().any { it.text == "Needs exactly 4 digits" })

        field.text = "4821"
        buttons.getValue("Set").doClick()
        assertEquals("4821", secrets.bankPin())
        assertEquals("", String(field.password), "the typed PIN does not stay in the box")

        buttons.getValue("Forget PIN").doClick()
        assertFalse(secrets.hasBankPin())
    }
}
