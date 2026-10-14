plugins {
    application
    // JTE: шаблоны из src/main/jte превращаются в Java-классы при сборке — ошибка в шаблоне ломает build
    id("gg.jte.gradle") version "3.2.4"
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

    // сайт
    implementation("io.javalin:javalin:6.7.0")
    implementation("gg.jte:jte:3.2.4")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

jte {
    generate()
    contentType = gg.jte.ContentType.Html
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

tasks.register<JavaExec>("web") {
    group = "application"
    description = "Starts the local web app on http://127.0.0.1:7070"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dota.web.WebApp"
    workingDir = rootDir
}

tasks.test {
    useJUnitPlatform()
    // GoldenReportTest сверяет генератор с закоммиченным analysis/ — изменения в нём должны перезапускать тесты
    inputs.dir("analysis").optional()
    inputs.dir("config")
}
