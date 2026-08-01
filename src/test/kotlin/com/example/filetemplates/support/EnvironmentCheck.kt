package com.example.filetemplates.support

import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fails fast, with an explanation, when the machine cannot run these tests.
 *
 * These are GUI tests, so they have real environment requirements that a unit test does not. When
 * one of them is missing the symptom is otherwise a component lookup timing out fifteen seconds
 * into a run -- which reads like a broken test rather than a machine that was never able to run it.
 * Each check below therefore states what is wrong and what to do about it, and runs before the
 * first IDE is downloaded so the failure arrives in seconds rather than minutes.
 */
object EnvironmentCheck {

    /** The IDE download is ~1.5 GB and is extracted alongside itself, plus per-test config. */
    private const val REQUIRED_FREE_GB = 5L

    /** Below this the Settings dialog does not fit, and controls end up outside the screen. */
    private const val MIN_SCREEN_WIDTH = 1024
    private const val MIN_SCREEN_HEIGHT = 768

    private const val BYTES_PER_GB = 1024L * 1024 * 1024

    fun verify() {
        verifyDisplay()
        verifyDiskSpace()
    }

    private fun verifyDisplay() {
        check(!GraphicsEnvironment.isHeadless()) {
            """
            No display available, so these UI tests cannot run.

            They launch a real IntelliJ IDEA window and drive it with real mouse and keyboard
            input, which a headless JVM cannot do. This is not a configuration option that can be
            switched off: the IDE's Swing UI has to be on a screen for the test to click it.

            On a desktop machine: run from a normal desktop session, not a service or an SSH shell.
            On CI: provide a virtual display, for example  xvfb-run -a ./gradlew test
            Also make sure -Djava.awt.headless=true is not being set for the test JVM.
            """.trimIndent()
        }

        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        check(screen.width >= MIN_SCREEN_WIDTH && screen.height >= MIN_SCREEN_HEIGHT) {
            """
            The screen is ${screen.width}x${screen.height}, which is smaller than the
            ${MIN_SCREEN_WIDTH}x$MIN_SCREEN_HEIGHT these tests need.

            The Settings dialog is laid out larger than that, so some of the controls the tests
            click would fall outside the visible screen and never be clickable.
            Use a larger screen or, on CI, a larger virtual display
            (for example  xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew test).
            """.trimIndent()
        }
    }

    private fun verifyDiskSpace() {
        // out/ is where IDE Starter puts the installer, the extracted build and each test's config.
        val target = Path.of("out").toAbsolutePath()
        val existingAncestor = generateSequence(target) { it.parent }.firstOrNull { Files.exists(it) }
            ?: return

        val freeGb = Files.getFileStore(existingAncestor).usableSpace / BYTES_PER_GB
        check(freeGb >= REQUIRED_FREE_GB) {
            """
            Only ${freeGb} GB free on the drive holding $target, and about $REQUIRED_FREE_GB GB is needed.

            The first run downloads a full IntelliJ IDEA build (~1.5 GB), extracts it, and gives
            each test its own config, system and plugins directories.
            Free up space, or run the build from a drive that has room.
            """.trimIndent()
        }
    }
}
