package com.hcimbot.guide

import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Loads the B0aty guide data.
 *
 * The bot does not ship its own copy of the guide. It reads the plugin's
 * guide.json, so the steps the bot follows are always exactly the steps the
 * plugin shows, and the guide text (CC BY-NC-SA, from the OSRS Wiki) stays in
 * the plugin where it is credited.
 */
class GuideLoader(private val gson: Gson = Gson()) {

    class GuideLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Reads guide.json out of the installed B0aty plugin. Plugin hub jars share
     * one classpath, so this finds it whenever both plugins are loaded.
     */
    fun loadFromPlugin(classLoader: ClassLoader = GuideLoader::class.java.classLoader): Guide {
        val stream = classLoader.getResourceAsStream(PLUGIN_RESOURCE)
            ?: throw GuideLoadException(
                "guide.json not found. Install and enable the B0aty HCIM Guide v3 plugin first."
            )
        return stream.use { load(it) }
    }

    fun loadFromFile(file: File): Guide = file.inputStream().use { load(it) }

    fun load(stream: InputStream): Guide {
        val guide = try {
            gson.fromJson(InputStreamReader(stream, StandardCharsets.UTF_8), Guide::class.java)
        } catch (e: JsonParseException) {
            throw GuideLoadException("guide.json is not valid JSON", e)
        } ?: throw GuideLoadException("guide.json was empty")

        if (guide.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw GuideLoadException(
                "guide.json is schema ${guide.schemaVersion}, the bot understands $SUPPORTED_SCHEMA_VERSION. " +
                    "The plugin changed its format, so the bot needs updating before it can follow it."
            )
        }
        if (guide.steps.isEmpty()) throw GuideLoadException("guide.json contains no steps")
        return guide
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val PLUGIN_RESOURCE = "com/b0atyguide/guide.json"
    }
}
