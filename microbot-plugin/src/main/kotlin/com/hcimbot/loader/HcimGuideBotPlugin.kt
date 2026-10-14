package com.hcimbot.loader

import com.google.inject.Provides
import com.hcimbot.microbot.HcimGuideBotConfig
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.swing.SwingUtilities
import net.runelite.client.config.ConfigManager
import net.runelite.client.eventbus.Subscribe
import net.runelite.client.events.ConfigChanged
import net.runelite.client.input.KeyManager
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.ui.ClientToolbar
import net.runelite.client.ui.overlay.OverlayManager
import org.slf4j.LoggerFactory

/**
 * The plugin Microbot loads. It holds no bot logic: it loads the bot from
 * ~/.runelite/hcim-guide-bot/hcim-guide-bot-core.jar and watches that file.
 * When build-and-install.bat puts a new one there, the bot is stopped, the new
 * code loaded and the bot started again, all while the game keeps running.
 *
 * The bot runs from a copy of the jar, since Windows will not let a jar that
 * is in use be replaced.
 *
 * Microbot names a sideloaded plugin after its jar, so this jar must be called
 * HcimGuideBotPlugin.jar to match this class.
 */
@PluginDescriptor(
    name = PluginDescriptor.Default + "HCIM Guide Bot",
    description = "Follows the B0aty HCIM Guide v3 plugin step by step, pausing on anything it cannot do safely",
    tags = ["hcim", "b0aty", "guide", "quest", "microbot"],
    enabledByDefault = false,
    isExternal = true,
    version = HcimGuideBotPlugin.LOADER_VERSION,
)
class HcimGuideBotPlugin : Plugin() {
    private val log = LoggerFactory.getLogger(HcimGuideBotPlugin::class.java)

    @field:Inject
    private lateinit var keyManager: KeyManager

    @field:Inject
    private lateinit var overlayManager: OverlayManager

    @field:Inject
    private lateinit var clientToolbar: ClientToolbar

    private class Loaded(val module: BotModule, val loader: URLClassLoader, val copy: File, val stamp: Stamp)

    private data class Stamp(val modified: Long, val size: Long)

    /** Held here so the bank PIN survives the bot reloading. Wiped when the plugin is turned off. */
    private val secrets = Secrets()

    @Volatile private var loaded: Loaded? = null
    private var watcher: ScheduledExecutorService? = null
    private var seen: Stamp? = null

    /** RuneLite finds a plugin's settings through a @Provides method like this one. */
    @Provides
    fun provideConfig(configManager: ConfigManager): HcimGuideBotConfig =
        configManager.getConfig(HcimGuideBotConfig::class.java)

    override fun startUp() {
        // Microbot loads every jar in microbot-plugins, so an old copy of the bot left
        // there runs alongside this one, and pausing this one does not pause it.
        if (jarName(this) != JAR && otherCopies().any { jarName(it) == JAR }) {
            Microbot.showMessage(
                "Two copies of HCIM Guide Bot are in your microbot-plugins folder. This one, ${jarName(this)}, " +
                    "will not run. Close Microbot and delete every jar there except $JAR.",
                MESSAGE_MS,
            )
            return
        }
        keepJarFresh()
        RUNNING.mkdirs()
        RUNNING.listFiles()?.forEach { it.delete() }
        load(announce = false)
        watcher = Executors.newSingleThreadScheduledExecutor { Thread(it, "hcim-guide-bot-watcher").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay(::watch, WATCH_SECONDS, WATCH_SECONDS, TimeUnit.SECONDS) }
    }

    override fun shutDown() {
        watcher?.shutdownNow()
        watcher = null
        unload()
        secrets.forgetBankPin()
    }

    @Subscribe
    fun onConfigChanged(event: ConfigChanged) {
        if (event.group == HcimGuideBotConfig.GROUP) loaded?.module?.onConfigChanged(event.key, event.newValue)
    }

    /** Every few seconds: reload a new core jar once it has finished copying, and stop stray copies. */
    private fun watch() {
        try {
            stopOtherCopies()
            val now = stamp() ?: return
            val running = loaded?.stamp
            if (now == running) {
                seen = null
                return
            }
            // Wait one more check with the same size and time, so a half copied file is never loaded.
            if (now != seen) {
                seen = now
                return
            }
            seen = null
            SwingUtilities.invokeLater {
                unload()
                load(announce = true)
            }
            keepJarFresh()
        } catch (e: Exception) {
            log.warn("HCIM guide bot watcher failed", e)
        }
    }

    private fun load(announce: Boolean) {
        val stamp = stamp() ?: run {
            Microbot.showMessage(
                "HCIM Guide Bot could not find $CORE in\n${CORE.parentFile}\n\nRun build-and-install.bat, then turn the plugin off and on.",
                MESSAGE_MS,
            )
            return
        }
        try {
            val copy = File(RUNNING, "core-${System.currentTimeMillis()}.jar")
            CORE.copyTo(copy, overwrite = true)
            val loader = BotClassLoader(copy.toURI().toURL(), javaClass.classLoader)
            val module = loader.loadClass(MODULE).getDeclaredConstructor().newInstance() as BotModule
            val config = Microbot.getConfigManager().getConfig(HcimGuideBotConfig::class.java)
            module.start(BotHost(this, config, keyManager, overlayManager, clientToolbar, secrets))
            loaded = Loaded(module, loader, copy, stamp)
            log.info("HCIM guide bot {} loaded", module.version)
            if (announce) Microbot.showMessage("HCIM Guide Bot updated to ${module.version}. It carries on from the current step.", NOTE_MS)
        } catch (e: Exception) {
            log.error("Could not load the HCIM guide bot", e)
            Microbot.showMessage("HCIM Guide Bot could not start the bot: ${e.message}\nRun build-and-install.bat again.", MESSAGE_MS)
        }
    }

    private fun unload() {
        val old = loaded ?: return
        loaded = null
        runCatching { old.module.stop() }.onFailure { log.warn("Stopping the HCIM guide bot failed", it) }
        runCatching { old.loader.close() }
        old.copy.delete()
    }

    private fun stamp(): Stamp? = CORE.takeIf { it.isFile }?.let { Stamp(it.lastModified(), it.length()) }

    /** Microbot deletes unknown jars in microbot-plugins once they are 3 days old. This one rarely changes. */
    private fun keepJarFresh() {
        runCatching { codeFile(this)?.setLastModified(System.currentTimeMillis()) }
    }

    private fun otherCopies(): List<Plugin> =
        Microbot.getPluginManager().plugins.filter { it !== this && it.javaClass.simpleName == javaClass.simpleName }

    /** Stops any other copy of the bot that is running, and says which jar it came from. */
    private fun stopOtherCopies() {
        val manager = Microbot.getPluginManager()
        for (other in otherCopies().filter { manager.isPluginActive(it) }) {
            val name = jarName(other) ?: "another jar"
            Microbot.showMessage(
                "HCIM Guide Bot found a second copy of itself running, from $name, and stopped it. " +
                    "It would keep playing while this one is paused. Close Microbot and delete $name from microbot-plugins.",
                MESSAGE_MS,
            )
            SwingUtilities.invokeLater { runCatching { manager.stopPlugin(other) } }
        }
    }

    /**
     * Loads the bot's own classes from the core jar before looking anywhere else,
     * so a new jar always means new code. The settings and the loader's interface
     * come from this plugin, as do RuneLite, Microbot and Kotlin.
     *
     * Everything else is asked of [host] through its one argument loadClass: Microbot's
     * PluginJarClassLoader only falls back to the client there, so the usual parent
     * delegation (the two argument form) would never find RuneLite's classes.
     */
    private class BotClassLoader(jar: URL, private val host: ClassLoader) : URLClassLoader(arrayOf(jar), null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            val ours = name.startsWith("com.hcimbot.") && !name.startsWith("com.hcimbot.loader.") &&
                !name.startsWith(CONFIG_CLASS)
            if (ours) {
                try {
                    return findClass(name).also { if (resolve) resolveClass(it) }
                } catch (e: ClassNotFoundException) {
                    // Not in the core jar: fall through to the plugin.
                }
            }
            try {
                host.loadClass(name)
            } catch (e: ClassNotFoundException) {
                findClass(name)
            }
        }

        override fun getResource(name: String): URL? = findResource(name) ?: host.getResource(name)
    }

    companion object {
        /** The loader's own version. Changes to it need Microbot restarted, the bot's do not. */
        const val LOADER_VERSION = "1.0.0"

        private const val JAR = "HcimGuideBotPlugin.jar"
        private const val MODULE = "com.hcimbot.microbot.BotModuleImpl"
        private const val CONFIG_CLASS = "com.hcimbot.microbot.HcimGuideBotConfig"
        private const val WATCH_SECONDS = 2L
        private const val MESSAGE_MS = 120_000
        private const val NOTE_MS = 8_000

        private val HOME = File(System.getProperty("user.home"), ".runelite/hcim-guide-bot")
        val CORE = File(HOME, "hcim-guide-bot-core.jar")
        private val RUNNING = File(HOME, "running")

        private fun codeFile(plugin: Any): File? =
            runCatching { File(plugin.javaClass.protectionDomain.codeSource.location.toURI()) }.getOrNull()

        private fun jarName(plugin: Any): String? = codeFile(plugin)?.name
    }
}
