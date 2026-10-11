plugins {
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "dota.fetch.Main"
}

tasks.named<JavaExec>("run") {
    // data/ and config/ are resolved relative to the project root
    workingDir = rootDir
}

tasks.register<JavaExec>("analyze") {
    group = "application"
    description = "Builds Markdown reports in analysis/ from data/"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dota.analysis.Analyze"
    workingDir = rootDir
}

tasks.test {
    useJUnitPlatform()
}
