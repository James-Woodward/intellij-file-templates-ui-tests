package com.example.filetemplates.support

import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.remote.Component

/**
 * Raising the IDE's window so that clicks reach it.
 *
 * The Driver clicks by moving the real cursor to a screen position and pressing, which hits
 * whatever window happens to be on top at that point. If anything else is covering the IDE -- a
 * maximised editor, a browser, a chat window -- every click in the run silently lands on that
 * instead, and the test fails as a component that "never appeared" while the screenshot shows a
 * completely unrelated application. This is the single most common reason a UI suite that passes
 * on its author's machine fails on someone else's.
 *
 * Raising the window is done through the IDE rather than by clicking on it, because a click cannot
 * be used to reveal a window that is not visible in the first place.
 */

@Remote("javax.swing.SwingUtilities")
interface SwingUtilitiesRef {
    fun getWindowAncestor(component: Component): Component?
}

@Remote("java.awt.Window")
interface WindowRef {
    fun toFront()
    fun isAlwaysOnTopSupported(): Boolean
    fun setAlwaysOnTop(alwaysOnTop: Boolean)
}

/**
 * Brings the window containing this component in front of other applications.
 *
 * Both steps are needed. `toFront()` alone is unreliable: Windows refuses to let a background
 * process take the foreground, so the call can be ignored. Marking the window always-on-top is not
 * subject to that restriction and reliably lifts it above other applications, which is all a click
 * needs. The flag is left set for the rest of the run -- the IDE is a throwaway instance that is
 * closed when the test ends.
 */
fun UiComponent.raiseOwningWindow() {
    val window = driver.utility(SwingUtilitiesRef::class).getWindowAncestor(component) ?: return
    val windowRef = driver.cast(window, WindowRef::class)
    driver.withContext(OnDispatcher.EDT) {
        if (windowRef.isAlwaysOnTopSupported()) {
            windowRef.setAlwaysOnTop(true)
        }
        windowRef.toFront()
    }
}
