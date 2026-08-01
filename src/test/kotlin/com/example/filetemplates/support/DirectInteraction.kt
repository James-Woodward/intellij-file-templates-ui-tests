package com.example.filetemplates.support

import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.elements.ActionButtonUi
import com.intellij.driver.sdk.ui.components.elements.JListUiComponent
import com.intellij.driver.sdk.ui.components.elements.JTreeUiComponent

/**
 * Interactions that act on Swing components directly instead of moving the real mouse.
 *
 * The Driver's `click()` goes through `SmoothRobot`, a `java.awt.Robot` inside the IDE: it moves
 * the actual cursor and needs the IDE window to be frontmost. That costs more than convenience --
 * macOS refuses synthetic input outright unless the application has been granted Accessibility
 * permission, so on a Mac a click-driven suite does nothing at all until someone grants it.
 *
 * Everything here performs a remote call on the IDE's EDT, which asks the component to do the
 * thing rather than aiming a cursor at it. No cursor, no window focus, and nothing for the OS to
 * refuse. It is the same mechanism the Driver uses internally -- `ActionButtonUi.performAction()`
 * and `JTreeUiComponent.selectRows()` are both EDT calls rather than clicks.
 *
 * The trade-off is deliberate and worth stating: a real click also proves a control is visible and
 * not covered by anything, and these calls do not. Every action here is therefore paired in the
 * page objects with a wait on an observable result, so a control that never appeared still fails
 * the test rather than passing silently.
 */

/** Swing's `AbstractButton`: `JButton`, `JMenuItem` and IntelliJ's `ActionMenuItem` all extend it. */
@Remote("javax.swing.AbstractButton")
interface AbstractButtonRef {
    fun doClick()
    fun isEnabled(): Boolean
}

@Remote("javax.swing.JList")
interface JListSelectionRef {
    fun setSelectedIndex(index: Int)
    fun ensureIndexIsVisible(index: Int)
}

@Remote("javax.swing.JTree")
interface JTreeSelectionRef {
    fun setSelectionRow(row: Int)
    fun scrollRowToVisible(row: Int)
}

@Remote("java.awt.Component")
interface ComponentFocusRef {
    fun requestFocusInWindow(): Boolean
}

@Remote("com.intellij.openapi.actionSystem.Presentation")
interface PresentationStateRef {
    fun isEnabled(): Boolean
}

@Remote("com.intellij.openapi.actionSystem.impl.ActionButton")
interface ActionButtonStateRef {
    fun getPresentation(): PresentationStateRef
}

/**
 * Whether the action behind this toolbar button is currently enabled.
 *
 * A toolbar button whose action is disabled ignores being activated, silently. Reading the state
 * turns "nothing happened" into a fact worth reporting, which matters because an action's enabled
 * state is recalculated by the toolbar and can be stale while the window is inactive.
 */
fun ActionButtonUi.isActionEnabled(): Boolean =
    runCatching {
        driver.cast(component, ActionButtonStateRef::class).getPresentation().isEnabled()
    }.getOrDefault(false)

/**
 * Asks for keyboard focus, reporting whether it was granted.
 *
 * Some actions -- the context menu one in particular -- act on the focused component and report
 * themselves disabled when there is none. The request is refused when the IDE window is not the
 * active window, hence the returned flag rather than an assumption.
 */
fun UiComponent.requestFocusDirectly(): Boolean {
    val focusable = driver.cast(component, ComponentFocusRef::class)
    return driver.withContext(OnDispatcher.EDT) { focusable.requestFocusInWindow() }
}

/**
 * Activates this component as the button it is.
 *
 * `doClick()` runs the button's listeners exactly as a press would, which for a dialog button or a
 * menu entry is the whole behaviour under test. Reading [UiComponent.component] first blocks until
 * the component is found, so a missing button fails here rather than doing nothing quietly.
 */
fun UiComponent.press() {
    val button = driver.cast(component, AbstractButtonRef::class)
    driver.withContext(OnDispatcher.EDT) { button.doClick() }
}

/**
 * Selects the list row whose text is [itemText].
 *
 * Setting the selection fires the list's selection listeners, which is what makes the rest of the
 * UI react -- on the settings page, showing that template's body in the editor.
 */
fun JListUiComponent.selectItem(itemText: String) {
    val index = items.indexOfFirst { it == itemText }
    require(index >= 0) { "no list item '$itemText'; items were: $items" }
    val list = driver.cast(component, JListSelectionRef::class)
    driver.withContext(OnDispatcher.EDT) {
        list.ensureIndexIsVisible(index)
        list.setSelectedIndex(index)
    }
}

/**
 * Selects the tree node at [row].
 *
 * Used where the node's text is not known ahead of time: the generated sample project's folder is
 * named per run, so the project view is addressed by position instead.
 */
fun JTreeUiComponent.selectRowDirectly(row: Int) {
    waitForNodesLoaded()
    val tree = driver.cast(component, JTreeSelectionRef::class)
    driver.withContext(OnDispatcher.EDT) {
        tree.scrollRowToVisible(row)
        tree.setSelectionRow(row)
    }
}

/**
 * Expands to and selects the tree node at [path].
 *
 * Expansion reuses the SDK's [JTreeUiComponent.expandPath], which is already an EDT call; only the
 * final selection needs replacing, since the SDK's `clickPath` would use the mouse.
 */
fun JTreeUiComponent.selectPathDirectly(vararg path: String) {
    waitForNodesLoaded()
    if (path.size > 1) {
        expandPath(*path.sliceArray(0..path.size - 2), fullMatch = true)
    }
    val row = findExpandedPath(*path, fullMatch = true)?.row
        ?: error("tree path ${path.toList()} not found; visible paths: ${collectExpandedPathsAsStrings()}")
    val tree = driver.cast(component, JTreeSelectionRef::class)
    driver.withContext(OnDispatcher.EDT) {
        tree.scrollRowToVisible(row)
        tree.setSelectionRow(row)
    }
}
