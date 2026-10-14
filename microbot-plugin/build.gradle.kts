import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

// The Microbot client jar to compile against, built from https://github.com/chsami/Microbot with
// `./gradlew :client:assemble` (runelite-client/build/libs/client-<version>-shaded.jar).
val microbotJar: String? = (findProperty("microbotJar") as String?) ?: System.getenv("MICROBOT_JAR")

// A wrong path would otherwise fail later as hundreds of "Unresolved reference" errors.
if (microbotJar != null && !file(microbotJar).isFile) {
    throw GradleException(
        "Can't find the Microbot jar at: $microbotJar\n" +
            "Check the file name and folder. On Windows, Shift + right click the jar, choose Copy as path, " +
            "and paste that after -PmicrobotJar="
    )
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(rootProject)
    // RuneLite ships Gson 2.8.5 at runtime, so build against exactly that.
    compileOnly("com.google.code.gson:gson:2.8.5")
    if (microbotJar != null) {
        compileOnly(files(microbotJar))
        testImplementation(files(microbotJar))
    }
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // The panel test builds Swing components, which needs no screen.
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
 * Two jars. HcimGuideBotPlugin.jar is the small loader Microbot sideloads from
 * ~/.runelite/microbot-plugins: the plugin class, its settings and Kotlin's runtime, which the client
 * does not ship. Microbot names a sideloaded plugin after its jar, so the name must match the class.
 *
 * hcim-guide-bot-core.jar is the bot itself (this project's other classes and the core), which the
 * loader reads from ~/.runelite/hcim-guide-bot and reloads whenever it changes, with no restart.
 * Gson stays out of both because RuneLite already has it.
 *
 * Both are reproducible, so an unchanged loader is byte for byte the same and need not be replaced.
 */
val loaderClasses = listOf("com/hcimbot/loader/**", "com/hcimbot/microbot/HcimGuideBotConfig*")

val coreJar = tasks.register<Jar>("coreJar") {
    group = "build"
    description = "Builds hcim-guide-bot-core.jar, the bot the loader plugin runs."
    archiveFileName.set("hcim-guide-bot-core.jar")
    from(sourceSets.main.get().output) { exclude(loaderClasses) }
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") && !it.name.startsWith("kotlin") && !it.name.startsWith("gson") && !it.name.startsWith("annotations") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**", "module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val pluginJar = tasks.register<Jar>("pluginJar") {
    group = "build"
    description = "Builds HcimGuideBotPlugin.jar (the loader) and hcim-guide-bot-core.jar (the bot)."
    archiveFileName.set("HcimGuideBotPlugin.jar")
    dependsOn(coreJar)
    from(sourceSets.main.get().output) { include(loaderClasses) }
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") && it.name.startsWith("kotlin") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**", "module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    doFirst {
        if (microbotJar == null) {
            throw GradleException("Set -PmicrobotJar=/path/to/client-<version>-shaded.jar, see microbot-plugin/README.md")
        }
    }
}

// Without a Microbot jar there is nothing to compile against, so the rest of the build skips this
// project instead of failing. `./gradlew test` still runs the core tests.
if (microbotJar == null) {
    tasks.matching { it.name != "pluginJar" && it.name != "coreJar" }.configureEach { onlyIf { false } }
}
