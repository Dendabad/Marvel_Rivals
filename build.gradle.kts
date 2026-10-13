import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.20"
}

group = "com.hcimbot"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // RuneLite already ships Gson, so swap this to compileOnly once the
    // Bot Client SDK (which brings RuneLite in) is added.
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation(kotlin("test"))
}

// RuneLite plugins target Java 11.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

tasks.test {
    useJUnitPlatform()
    // ./gradlew test -PguideJson=/path/to/guide.json checks the bot against the plugin's real guide.
    systemProperty("guideJson", findProperty("guideJson") ?: "")
    testLogging { showStandardStreams = true }
}
