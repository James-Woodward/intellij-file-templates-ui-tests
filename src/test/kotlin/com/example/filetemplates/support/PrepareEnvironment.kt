package com.example.filetemplates.support

import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.project.NoProject
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Downloads the IDE and reports anything the machine still needs, without running a test.
 *
 * Exists for one awkwardness on macOS: the IDE under test is downloaded into this project, and
 * macOS grants permission to control input per application, so the permission cannot be granted
 * until the IDE exists. Left to itself that makes the first run fail by design -- run, fail, grant,
 * run again -- which reads like a broken project rather than an operating system asking for
 * consent.
 *
 * `./gradlew prepare` does the download first and prints the exact path to grant, so the first
 * actual run is the first passing run. Tagged so it never runs as part of the suite.
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
                    appendLine("One step remains, because macOS will not let an application control the")
                    appendLine("mouse and keyboard until you allow it -- and it refuses silently, so without")
                    appendLine("this the IDE simply opens and nothing happens.")
                    appendLine()
                    appendLine("  System Settings > Privacy & Security > Accessibility")
                    appendLine("  Press '+', then Cmd+Shift+G, and paste the path above.")
                    appendLine("  Make sure its switch is on.")
                    appendLine()
                    appendLine("Then run:  ./gradlew test")
                    appendLine()
                    appendLine("Note: deleting out/ replaces this copy of the IDE, and the permission")
                    appendLine("has to be granted again for the replacement.")
                } else {
                    appendLine("Nothing further is needed on this platform.")
                    appendLine("Run:  ./gradlew test")
                }
                appendLine()
            },
        )
    }
}
