plugins {
    kotlin("jvm") version "1.9.20"
    kotlin("plugin.serialization") version "1.9.20"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation("org.jsoup:jsoup:1.17.1")
    // Provide a simple SLF4J binding so we don't see the SLF4J NOP warning at runtime.
    // This is lightweight and fine for a small CLI tool. If you use a different logger
    // in production, replace this with the appropriate binding.
    implementation("org.slf4j:slf4j-simple:2.0.9")
    // Local sqlite memory for storing findings between steps
    implementation("org.xerial:sqlite-jdbc:3.41.2.1")
    // PDF text extraction for pages that return PDFs
    implementation("org.apache.pdfbox:pdfbox:2.0.29")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("agent.MainKt")
}