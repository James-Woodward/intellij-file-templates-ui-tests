package com.example.filetemplates.pages

import com.example.filetemplates.support.activateIdeWindow
import com.example.filetemplates.support.press
import com.example.filetemplates.support.requestFocusDirectly
import com.example.filetemplates.support.selectRowDirectly
import com.example.filetemplates.support.withModalDialog
import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.toolwindows.projectView
import com.intellij.driver.sdk.ui.components.elements.JTextFieldUI
import com.intellij.driver.sdk.ui.components.elements.JTreeUiComponent
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.popupMenu
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.driver.sdk.ui.ui

/**
 * Page object for generating a file from a template through the Project view's **File | New** menu.
 *
 * Kept separate from [FileAndCodeTemplatesPage] because it drives a different part of the IDE (the
 * project frame and its context menu) rather than the Settings dialog.
 *
 * This is the flow that would normally force a UI test onto the mouse, since a context menu is
 * opened by right-clicking. It is avoided in three steps: the row is selected through the tree's
 * own model, the menu is opened by invoking `ShowPopupMenu` -- the action the context-menu key is
 * bound to -- and its entries are activated with `doClick()`, because IntelliJ's `ActionMenu` and
 * `ActionMenuItem` both extend `AbstractButton`.
 */
class NewFileFromTemplatePage(private val driver: Driver) {

    /** The Project view's file tree. A getter, so it re-resolves rather than going stale. */
    private val projectTree: JTreeUiComponent
        get() = driver.ideFrame().projectView().projectViewTree

    /**
     * Creates a file from [templateName] in the project root, naming it [fileName].
     */
    fun createInProjectRoot(templateName: String, fileName: String) {
        // Resolved once: the same component is needed for the selection and as the action's context.
        val tree = projectTree
        tree.shouldBe("project view tree should be present") { present() }

        // The New action applies to whatever is selected, so the root is selected first. Row 0 is
        // used rather than a name because the generated project's folder name varies per run.
        tree.selectRowDirectly(0)

        // The context-menu action acts on the focused component, so the window has to be active
        // before asking the tree for focus -- an inactive window has no focus owner to give.
        driver.activateIdeWindow()
        val focused = tree.requestFocusDirectly()

        // Queued rather than run inline, for the same reason as ShowSettings: executed on the spot
        // the action is refused as disabled while the frame is not the active window.
        driver.invokeAction("ShowPopupMenu", now = false, component = tree.component)

        awaitContextMenu(focusGranted = focused)

        // "New" is a submenu: activating it opens and populates the nested menu, where the
        // template entry lives. Opening a submenu is not modal, so it does not block.
        driver.ui.popupMenu().findMenuItemByText("New").press()
        driver.ui.popupMenu().shouldBe("the New submenu should list '$templateName'") {
            itemsList().contains(templateName)
        }

        // Choosing the template opens a modal dialog, so it is driven over a second connection.
        driver.withModalDialog(
            expectedDialogTitle = "New $templateName",
            openModal = { driver.ui.popupMenu().findMenuItemByText(templateName).press() },
            handleDialog = { second ->
                second.ui.dialog({ byTitle("New $templateName") }) {
                    val nameField = x(JTextFieldUI::class.java) { byJavaClass("javax.swing.JTextField") }
                    nameField.shouldBe("the file name field should appear") { present() }
                    nameField.text = fileName
                    okButton.press()
                }
            },
        )
    }

    /**
     * Waits for the context menu, and says what was on screen instead when it does not appear.
     *
     * The menu is opened through the action system rather than by right-clicking, and an action
     * that declines to run leaves no trace, so the interesting facts -- whether focus was granted,
     * and whether any popup opened at all -- are gathered here rather than left to a bare timeout.
     */
    private fun awaitContextMenu(focusGranted: Boolean, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { driver.ui.popupMenu().present() }.getOrDefault(false)) return
            Thread.sleep(300)
        }

        val popups = runCatching {
            driver.ui.xx("//div[@class='HeavyWeightWindow' or @class='MyMenu' or @class='JPopupMenu']")
                .list().size
        }.getOrDefault(-1)
        error(
            "the project view context menu did not open within ${timeoutMs}ms " +
                "(focus request granted: $focusGranted, popup-like windows found: $popups)",
        )
    }
}
