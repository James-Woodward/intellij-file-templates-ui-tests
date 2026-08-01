plugins {
    // Lets Gradle download the JDK the build asks for when the machine does not already have it.
    //
    // Without this, a machine without a JDK 21 fails before compiling anything, with the real
    // reason ("Cannot find a Java installation ... Toolchain auto-provisioning is not enabled")
    // buried a few hundred lines inside a stack trace. Whoever is running the project then has to
    // work out which JDK to install before they can see a single test. With it, any recent JDK is
    // enough to start the build and the correct one is fetched automatically.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "intellij-file-templates-ui-tests"
