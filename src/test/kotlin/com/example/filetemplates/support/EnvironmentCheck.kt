package com.example.filetemplates.support

import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Robot
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

    /**
     * Below this the IDE window does not fit and controls end up outside the screen. Measured
     * against the display's resolution, so a normal laptop screen is not rejected for the space
     * its menu bar and dock take up.
     */
    private const val MIN_SCREEN_WIDTH = 1024
    private const val MIN_SCREEN_HEIGHT = 768

    private const val BYTES_PER_GB = 1024L * 1024 * 1024

    private val isMac: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    fun verify() {
        verifyDisplay()
        verifyDiskSpace()
        verifyInputIsPermitted()
    }

    /**
     * Checks that this machine will let the tests move the pointer, before anything is downloaded.
     *
     * macOS refuses synthetic input unless the application responsible for the process has
     * Accessibility permission, and it refuses in silence: no error, no prompt, the events are
     * simply dropped. Left undetected that surfaces minutes later as an IDE sitting open doing
     * nothing, which looks like a broken suite rather than a permission that was never granted.
     *
     * The check runs here, in the process Gradle started, because that is the same process macOS
     * holds responsible for the IDE the tests later launch -- so a pointer that moves here will
     * move there. Being first also means the answer arrives in seconds rather than after a 1.5 GB
     * download.
     */
    private fun verifyInputIsPermitted() {
        if (!isMac) return

        val start = MouseInfo.getPointerInfo()?.location ?: return
        // Somewhere the pointer demonstrably is not, so "unchanged" can only mean it was ignored.
        val target = if (start.x > 300 || start.y > 300) Point(100, 100) else Point(500, 500)

        val robot = Robot()
        robot.mouseMove(target.x, target.y)
        Thread.sleep(200)
        val moved = MouseInfo.getPointerInfo()?.location
        robot.mouseMove(start.x, start.y)

        if (moved != null && (moved.x != start.x || moved.y != start.y)) return

        openAccessibilitySettings()
        error(
            """
            macOS is discarding the input these tests depend on, so nothing they click would happen.

            Allow the application you started this run from -- Terminal, iTerm, or the IDE whose
            terminal you used. macOS attributes the permission to whichever application is
            responsible for the process, not to the IDE these tests launch.

              System Settings > Privacy & Security > Accessibility
              (System Preferences > Security & Privacy > Privacy on older macOS)

            Expect to add it yourself: an application only appears in that list once it has asked
            for the permission or been added by hand, and these tests never ask -- macOS refuses
            silently rather than prompting. Press '+', then Cmd+Shift+G, and enter its path;
            Terminal is at /System/Applications/Utilities/Terminal.app

            Then quit and reopen the terminal -- it keeps the old answer while it is running -- and
            run the tests again. The permission is granted once per machine.

            That panel has been opened for you.
            """.trimIndent(),
        )
    }

    /** Best effort: failing to open a settings window is no reason to fail the check itself. */
    fun openAccessibilitySettings() {
        runCatching {
            ProcessBuilder(
                "open",
                "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility",
            ).start()
        }
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

        // The display's own resolution, not the usable work area: the latter excludes the menu
        // bar and dock, which would reject an ordinary 1280x800 laptop for being "714 high".
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.bounds
        check(screen.width >= MIN_SCREEN_WIDTH && screen.height >= MIN_SCREEN_HEIGHT) {
            """
            The display is ${screen.width}x${screen.height}, below the
            ${MIN_SCREEN_WIDTH}x$MIN_SCREEN_HEIGHT these tests need.

            The IDE window and its Settings dialog do not fit, so controls the tests click end up
            outside the visible area and can never be reached.
            Use a larger display or, on CI, a larger virtual one -- the default Xvfb screen is
            far smaller than this (for example  xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew test).
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
