package com.example.filetemplates.support

import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.ui.components.UiComponent
import java.awt.Point

/**
 * Detects the case where macOS is silently discarding the input these tests depend on.
 *
 * The Driver works by synthesising mouse and keyboard events inside the IDE. macOS only allows an
 * application to do that once it has been granted Accessibility permission, and it refuses without
 * any error: the events are simply dropped. The permission is attributed to the application
 * responsible for the process -- the terminal the run was started from, not the IDE it launches --
 * so it is granted once per machine rather than per checkout.
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

        Grant it to the application these tests were started from -- Terminal, iTerm, or the IDE
        whose terminal you used. macOS attributes the permission to the application responsible for
        the process, which is the one that launched the run, not the IDE it downloads and starts.

          System Settings > Privacy & Security > Accessibility

        Switch that application on, then run the tests again. The permission belongs to it rather
        than to anything in this project, so it survives deleting out/ and is granted once per
        machine.
        """.trimIndent()
    }

    alreadyVerified = true
}
