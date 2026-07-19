plugins {
    kotlin("jvm") version "2.3.21"
}

repositories {
    mavenCentral()
    // IDE Starter + Driver artifacts live here (NOT in intellij-dependencies).
    maven("https://www.jetbrains.com/intellij-repository/releases")
    // Transitive dependencies of the Starter/Driver artifacts.
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
}

// Pinned so a run is reproducible.
//
// This is deliberately the exact build number of the IDE under test: IntelliJ IDEA 2026.1.4 is
// build 261.26222.65 (see IdeVersion.kt for why that IDE). The Driver talks to a matching agent
// inside the running IDE, so keeping the tooling and the IDE on the same build removes a whole
// class of protocol mismatch.
val starterVersion = "261.26222.65"

dependencies {
    testImplementation("com.jetbrains.intellij.tools:ide-starter-squashed:$starterVersion")
    testImplementation("com.jetbrains.intellij.tools:ide-starter-junit5:$starterVersion")
    // Bridges Starter and Driver: provides runIdeWithDriver()/useDriverAndCloseIde().
    testImplementation("com.jetbrains.intellij.tools:ide-starter-driver:$starterVersion")
    testImplementation("com.jetbrains.intellij.driver:driver-client:$starterVersion")
    testImplementation("com.jetbrains.intellij.driver:driver-sdk:$starterVersion")
    testImplementation("com.jetbrains.intellij.driver:driver-model:$starterVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.kodein.di:kodein-di-jvm:7.20.2")

    // Gradle 9 no longer puts the JUnit Platform launcher on the test runtime classpath for us.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

// After the test run, open the Gradle HTML report in the default browser -- a convenience for
// local runs. It is wired to the `test` task, so it works however the tests are launched (IDE
// gutter, `./gradlew test`, or run-tests.ps1). It is skipped on CI (never launch a browser on a
// build agent) and can be turned off with -PnoOpenReport.
val openTestReport = tasks.register("openTestReport") {
    description = "Opens the Gradle test HTML report in the default browser (local runs only)."
    doLast {
        val report = layout.buildDirectory.file("reports/tests/test/index.html").get().asFile
        val onCi = listOf("CI", "TEAMCITY_VERSION", "GITHUB_ACTIONS", "JENKINS_URL", "GITLAB_CI", "BUILD_NUMBER")
            .any { System.getenv(it) != null }
        when {
            onCi -> logger.lifecycle("CI detected -- not opening the test report.")
            !report.exists() -> logger.lifecycle("No test report found at $report")
            else -> {
                logger.lifecycle("Opening test report: ${report.absolutePath}")
                // Use the OS-native file opener (plain ProcessBuilder -- no AWT, which is not on
                // the Gradle build-script classpath). On Windows, rundll32's FileProtocolHandler
                // opens the file with its default handler (the browser) and handles spaces in the
                // path as a single argument.
                val os = System.getProperty("os.name").lowercase()
                val cmd = when {
                    os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", report.absolutePath)
                    os.contains("mac") -> listOf("open", report.absolutePath)
                    else -> listOf("xdg-open", report.absolutePath)
                }
                runCatching { ProcessBuilder(cmd).start() }
                    .onFailure { logger.lifecycle("Could not open the report automatically: ${it.message}") }
            }
        }
    }
}

tasks.test {
    useJUnitPlatform {
        // SettingsUiExplorer is an investigation tool (it dumps the live Swing tree so locators
        // can be written from fact rather than guesswork), not one of the deliverable tests.
        // Keep it out of the normal suite; run it on demand with -PincludeExplore.
        if (!project.hasProperty("includeExplore")) {
            excludeTags("explore")
        }
    }
    testLogging {
        events("started", "passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Open the report after the run (local only; see openTestReport above).
    if (!project.hasProperty("noOpenReport")) {
        finalizedBy(openTestReport)
    }
}
