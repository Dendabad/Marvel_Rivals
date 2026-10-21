package com.hcimbot.microbot

import net.runelite.client.plugins.microbot.Microbot

/**
 * Drives the Grand Exchange through Microbot's Rs2GrandExchange, which is looked up by name since the build here has only stubs of
 * it. Each call that cannot be made leaves a short reason in [problem], which the task puts in its pause message.
 */
object GrandExchangeBridge {
    @Volatile var problem: String? = null

    private val type: Class<*>? by lazy {
        runCatching { Class.forName("net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange") }.getOrNull()
    }

    private fun invoke(name: String, vararg args: Any?): Any? {
        val cls = type ?: run { problem = "Microbot's Rs2GrandExchange is not in this version"; return null }
        val method = cls.methods.firstOrNull { m ->
            m.name == name && m.parameterCount == args.size && m.parameterTypes.indices.all { i -> accepts(m.parameterTypes[i], args[i]) }
        } ?: run {
            problem = "Rs2GrandExchange has no $name(${args.joinToString { it?.javaClass?.simpleName ?: "null" }}), it has " +
                cls.methods.filter { it.name == name }.joinToString { m -> m.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName } }.ifEmpty { "no $name at all" }
            return null
        }
        return runCatching { method.invoke(null, *args) }.getOrElse { problem = "$name failed: ${it.cause?.message ?: it.message}"; null }
    }

    private fun accepts(type: Class<*>, arg: Any?): Boolean = when (arg) {
        null -> !type.isPrimitive
        is Int -> type == Int::class.javaPrimitiveType || type == Integer::class.java
        is Boolean -> type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java
        else -> type.isInstance(arg)
    }

    fun isOpen(): Boolean = invoke("isOpen") as? Boolean ?: false

    fun open(): Boolean {
        problem = null
        return (invoke("openExchange") as? Boolean) ?: false
    }

    /** The price the game guide gives, from RuneLite's item manager, or 0 when it is not known. */
    private fun guidePrice(itemId: Int): Int = runCatching {
        val manager = Microbot::class.java.getMethod("getItemManager").invoke(null)
        (manager.javaClass.methods.first { it.name == "getItemPrice" && it.parameterCount == 1 }.invoke(manager, itemId) as Number).toInt()
    }.getOrDefault(0)

    /** An offer at well over the guide price, so that it fills at once, for the cheapest the market has. */
    fun buy(name: String, itemId: Int, quantity: Int): Boolean {
        problem = null
        val base = guidePrice(itemId)
        val price = if (base > 0) (base * 1.3).toInt() + 5 else 100_000
        return (invoke("buyItem", name, price, quantity) as? Boolean) ?: false
    }

    fun collect(): Boolean {
        problem = null
        return (invoke("collectAll", true) as? Boolean) ?: (invoke("collectToInventory") != null)
    }
}
