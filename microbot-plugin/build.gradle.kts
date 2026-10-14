import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

// The Microbot client jar to compile against, built from https://github.com/chsami/Microbot with
// `./gradlew :client:assemble` (runelite-client/build/libs/client-<version>-shaded.jar).
val microbotJar: String? = (findProperty("microbotJar") as String?) ?: System.getenv("MICROBOT_JAR")

dependencies {
    implementation(rootProject)
    // RuneLite ships Gson at runtime, and the core's loader takes one.
    compileOnly("com.google.code.gson:gson:2.10.1")
    if (microbotJar != null) compileOnly(files(microbotJar))
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
 * The jar Microbot sideloads from ~/.runelite/microbot-plugins. Microbot names a sideloaded plugin
 * after its jar, so the file name must match the plugin class. Kotlin's runtime goes inside, since
 * the client does not ship it. Gson stays out because RuneLite already has it.
 */
val pluginJar = tasks.register<Jar>("pluginJar") {
    group = "build"
    description = "Builds HcimGuideBotPlugin.jar for Microbot's microbot-plugins folder."
    archiveFileName.set("HcimGuideBotPlugin.jar")
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") && !it.name.startsWith("gson") && !it.name.startsWith("annotations") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**", "module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    doFirst {
        if (microbotJar == null) {
            throw GradleException("Set -PmicrobotJar=/path/to/client-<version>-shaded.jar, see microbot-plugin/README.md")
        }
    }
}

// Without a Microbot jar there is nothing to compile against, so the rest of the build skips this
// project instead of failing. `./gradlew test` still runs the core tests.
if (microbotJar == null) {
    tasks.matching { it.name != "pluginJar" }.configureEach { onlyIf { false } }
}
