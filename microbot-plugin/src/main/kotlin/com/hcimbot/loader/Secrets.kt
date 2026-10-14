package com.hcimbot.loader

/**
 * Things that must live only in memory. The bank PIN is typed into the bot's sidebar and held here,
 * never written to a file or to Microbot's settings (Microbot's own saved PIN is "encrypted" with a
 * key that is the same for everyone). It is gone when the plugin is turned off or the client closes.
 *
 * This lives in the loader, not the bot, so it survives the bot reloading itself during an update.
 */
class Secrets {
    @Volatile private var bankPin: CharArray? = null

    fun hasBankPin(): Boolean = bankPin != null

    fun bankPin(): String? = bankPin?.let { String(it) }

    /** Takes a 4 digit PIN. Returns false, and keeps nothing, for anything else. */
    fun setBankPin(pin: CharArray?): Boolean {
        if (pin == null) {
            forgetBankPin()
            return true
        }
        if (pin.size != 4 || !pin.all { it in '0'..'9' }) return false
        forgetBankPin()
        bankPin = pin.copyOf()
        return true
    }

    fun forgetBankPin() {
        bankPin?.fill('0')
        bankPin = null
    }
}
