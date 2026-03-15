plugins {
    kotlin("jvm") version "2.3.10"
    application
}

group = "org.bogsnebes.engines"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("dev.inmo:tgbotapi:32.0.0")
    implementation("io.ktor:ktor-client-cio:3.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
    runtimeOnly("org.slf4j:slf4j-jdk14:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass = "org.bogsnebes.engines.MainKt"
}

kotlin {
    jvmToolchain(17)
}
