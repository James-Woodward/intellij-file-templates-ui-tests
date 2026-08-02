package com.example.filetemplates.support

import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.project.NoProject
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Optional. Checks the machine and downloads the IDE, without running a test.
 *
 * Useful for getting the ~1.5 GB download and the environment checks out of the way separately
 * from the run itself, and for being told up front about the one thing macOS requires. Not a
 * prerequisite: `./gradlew test` does all of this by itself.
 *
 * Tagged so it never runs as part of the suite.
 */
@Tag("prepare")
class PrepareEnvironment {

    @Test
    fun downloadIdeAndReportSetup() {
        EnvironmentCheck.verify()

        // Creating the context is what downloads and unpacks the build; no IDE is started.
        val context = Starter.newContext(
            "prepare",
            TestCase(IdeProductProvider.IU, projectInfo = NoProject).withVersion(IDE_VERSION),
        )
        val idePath = context.ide.installationPath.toAbsolutePath()

        val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)
        println(
            buildString {
                appendLine()
                appendLine("IntelliJ IDEA $IDE_VERSION is ready at:")
                appendLine("  $idePath")
                appendLine()
                if (isMac) {
                    appendLine("One thing to check, because macOS will not let an application control the")
                    appendLine("mouse and keyboard until you allow it -- and it refuses silently, so without")
                    appendLine("this the IDE opens and nothing happens.")
                    appendLine()
                    appendLine("  System Settings > Privacy & Security > Accessibility")
                    appendLine()
                    appendLine("Switch on the application you are running these tests from: Terminal,")
                    appendLine("iTerm, or the IDE whose terminal you are using. macOS attributes the")
                    appendLine("permission to whichever application started the run, not to the IDE")
                    appendLine("above, so it is granted once per machine and survives deleting out/.")
                    appendLine()
                    appendLine("Then run:  ./gradlew test")
                } else {
                    appendLine("Nothing further is needed on this platform.")
                    appendLine("Run:  ./gradlew test")
                }
                appendLine()
            },
        )
    }
}
