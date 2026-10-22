import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

// The Microbot client jar to compile against, the same one microbot-plugin uses.
val microbotJar: String? = (findProperty("microbotJar") as String?) ?: System.getenv("MICROBOT_JAR")

if (microbotJar != null && !file(microbotJar).isFile) {
    throw GradleException("Can't find the Microbot jar at: $microbotJar")
}

repositories {
    mavenCentral()
}

dependencies {
    // The bot's core (the quest follower, the Grand Exchange task) and the Microbot glue the HCIM Guide Bot also uses.
    implementation(rootProject)
    implementation(project(":microbot-plugin"))
    compileOnly("com.google.code.gson:gson:2.8.5")
    if (microbotJar != null) {
        compileOnly(files(microbotJar))
        testImplementation(files(microbotJar))
    }
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

/*
 * One jar, BlackChinBotPlugin.jar, for Microbot's sideload folder (~/.runelite/microbot-plugins). Microbot names a sideloaded plugin
 * after its jar, so the name must match the plugin class. It holds this plugin, the bot's core, the Microbot glue classes and Kotlin's
 * runtime. The HCIM Guide Bot's own loader and settings are left out, so installing this does not also install that bot.
 */
val hcimOnly = listOf("com/hcimbot/loader/**", "com/hcimbot/microbot/HcimGuideBot*", "com/hcimbot/microbot/BotModuleImpl*", "com/hcimbot/microbot/B0atyGuideTicker*", "com/hcimbot/microbot/ReportUploader*", "com/hcimbot/microbot/AiAdvisor*")

val pluginJar = tasks.register<Jar>("pluginJar") {
    group = "build"
    description = "Builds BlackChinBotPlugin.jar."
    archiveFileName.set("BlackChinBotPlugin.jar")
    from(sourceSets.main.get().output)
    from(project(":microbot-plugin").extensions.getByType<SourceSetContainer>()["main"].output) { exclude(hcimOnly) }
    dependsOn(project(":microbot-plugin").tasks.named("classes"))
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") && !it.name.startsWith("gson") && !it.name.startsWith("annotations") && !it.name.contains("microbot-plugin") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**", "module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    doFirst {
        if (microbotJar == null) throw GradleException("Set -PmicrobotJar=/path/to/client-<version>-shaded.jar")
    }
}

if (microbotJar == null) {
    tasks.matching { it.name != "pluginJar" }.configureEach { onlyIf { false } }
}
