package com.example.filetemplates.support

import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.ui.components.UiComponent
import java.awt.Point

/**
 * Detects the case where macOS is silently discarding the input these tests depend on.
 *
 * The Driver works by synthesising mouse and keyboard events inside the IDE. macOS only allows an
 * application to do that once it has been granted Accessibility permission, and it refuses without
 * any error: the events are simply dropped. The IDE that IDE Starter downloads is a fresh copy that
 * macOS has never seen, so it does not have that permission by default.
 *
 * The symptom is uninformative -- the IDE is visibly open and doing nothing, and the test fails
 * fifteen seconds later reporting a component that never appeared, which looks like a broken
 * locator. The check below turns that into a statement of what is actually wrong.
 */

@Remote("java.awt.MouseInfo")
interface MouseInfoRef {
    fun getPointerInfo(): PointerInfoRef?
}

@Remote("java.awt.PointerInfo")
interface PointerInfoRef {
    fun getLocation(): Point
}

private val isMac: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

/** Verified once per JVM: the permission is granted per application, so it cannot vary per test. */
@Volatile
private var alreadyVerified = false

/**
 * Confirms the IDE can actually move the pointer, and explains how to fix it when it cannot.
 *
 * Only meaningful on macOS; elsewhere synthetic input needs no permission and this returns
 * immediately. The pointer is moved to a point it is not already at and the position read back,
 * which is the only reliable signal -- the call to move it succeeds either way.
 */
@Synchronized
fun UiComponent.verifyInputIsPermitted() {
    if (!isMac || alreadyVerified) return

    val mouseInfo = driver.utility(MouseInfoRef::class)
    val start = mouseInfo.getPointerInfo()?.getLocation() ?: return

    // Somewhere the pointer demonstrably is not, so "unchanged" can only mean the move was ignored.
    val target = if (start.x > 300 || start.y > 300) Point(100, 100) else Point(500, 500)
    robot.moveMouse(target)
    Thread.sleep(300)
    val after = mouseInfo.getPointerInfo()?.getLocation()

    check(after != null && (after.x != start.x || after.y != start.y)) {
        """
        The IDE cannot control the pointer, so no click in this suite will have any effect.

        On macOS an application may only synthesise mouse and keyboard input once it has been
        granted Accessibility permission, and it is refused silently -- the events are dropped with
        no error, which is why this would otherwise surface as a component that never appears.

        The IDE under test is a copy downloaded into this project, so granting the permission to an
        installed IntelliJ IDEA does not cover it. Grant it to this one:

          System Settings > Privacy & Security > Accessibility > "+"
          then Cmd+Shift+G and paste:
          <project>/out/ide-tests/cache/builds/<build>/IntelliJ IDEA.app

        Make sure the switch next to it is on, then run the tests again. Deleting out/ discards
        that IDE copy, and the permission has to be granted again for the replacement.
        """.trimIndent()
    }

    alreadyVerified = true
}
