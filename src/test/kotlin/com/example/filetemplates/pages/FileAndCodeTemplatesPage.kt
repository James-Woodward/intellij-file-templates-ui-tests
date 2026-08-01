package com.example.filetemplates.pages

import com.example.filetemplates.support.activateIdeWindow
import com.example.filetemplates.support.awaitButton
import com.example.filetemplates.support.isActionEnabled
import com.example.filetemplates.support.press
import com.example.filetemplates.support.raiseOwningWindow
import com.example.filetemplates.support.selectItem
import com.example.filetemplates.support.selectPathDirectly
import com.example.filetemplates.support.withModalDialog
import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.elements.ActionButtonUi
import com.intellij.driver.sdk.ui.components.elements.JListUiComponent
import com.intellij.driver.sdk.ui.components.elements.JTextFieldUI
import com.intellij.driver.sdk.ui.components.elements.WindowUiComponent
import com.intellij.driver.sdk.ui.components.elements.accessibleTree
import com.intellij.driver.sdk.ui.components.elements.actionButton
import com.intellij.driver.sdk.ui.components.elements.list
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.driver.sdk.ui.ui

/**
 * Page object for **Settings | Editor | File and Code Templates**.
 *
 * Every locator for this page is declared here once, so tests call named members instead of
 * repeating query expressions. All locators were read from UI Inspector dumps of the live Swing
 * tree, not guessed.
 *
 * Locators use `get()` rather than plain `val`: the Settings dialog is closed and reopened during a
 * test, so a component captured eagerly would go stale. A getter re-resolves on every access.
 *
 * Nothing here moves the mouse; see [com.example.filetemplates.support.press] and friends for why.
 * Two kinds of control need different treatment: an `ActionButton` is a bare `JComponent` with its
 * own `click()`, while OK, Cancel and Reset are real `JButton`s, so the first uses `performAction()`
 * and the second `press()`.
 */
class FileAndCodeTemplatesPage(private val driver: Driver) {

    // ---------------------------------------------------------------- locators

    /**
     * The IDE's main window: the project frame, or the Welcome screen when no project is open.
     *
     * Matched by either class because two of the three tests run without a project.
     */
    private val mainWindow
        get() = driver.ui.x(
            "//div[@class='IdeFrameImpl' or @class='FlatWelcomeFrame']",
            WindowUiComponent::class.java,
        )

    /** The Settings dialog's left-hand category tree. */
    private val settingsCategories
        get() = driver.ui.accessibleTree { byAccessibleName("Settings categories") }

    /** Toolbar "+": creates a new custom template. */
    val createTemplateButton: ActionButtonUi
        get() = driver.ui.actionButton { byAccessibleName("Create Template") }

    /** Toolbar action that restores a modified built-in template to its original. */
    val revertToOriginalButton: ActionButtonUi
        get() = driver.ui.actionButton { byAccessibleName("Revert to Original Template") }

    /** Commits changes and closes the Settings dialog. */
    val dialogOkButton get() = driver.ui.x { byAccessibleName("OK") }

    /** Closes the Settings dialog without applying further changes. */
    val dialogCancelButton get() = driver.ui.x { byAccessibleName("Cancel") }

    /** The template list. A plain JBList with no accessible name, so it is located by class. */
    val templateList: JListUiComponent
        get() = driver.ui.list { byType("com.intellij.ui.components.JBList") }

    /**
     * The "Name:" input. That accessible name is shared with its caption label, so the Java class
     * is matched as well in order to hit the editable field rather than the label.
     */
    val nameField: JTextFieldUI
        get() = driver.ui.x(JTextFieldUI::class.java) {
            and(byJavaClass("javax.swing.JTextField"), byAccessibleName("Name:"))
        }

    /** The "Extension:" input. Same label/field disambiguation as [nameField]. */
    val extensionField: JTextFieldUI
        get() = driver.ui.x(JTextFieldUI::class.java) {
            and(byJavaClass("javax.swing.JTextField"), byAccessibleName("Extension:"))
        }

    /**
     * The editor showing the selected template's body.
     *
     * A new template's panel holds two `EditorComponentImpl` editors (the body and the small
     * "File name:" field), while a built-in shows only the body. The body is by far the taller,
     * so the tallest is chosen, which is correct in both cases.
     */
    val bodyEditor: JEditorUiComponent
        get() = driver.ui.xx(JEditorUiComponent::class.java) { byClass("EditorComponentImpl") }
            .list()
            .maxByOrNull { it.component.height }
            ?: error("no template body editor (EditorComponentImpl) found on the page")

    // ---------------------------------------------------------------- actions

    /** Opens Settings and navigates to Editor > File and Code Templates, waiting at each step. */
    fun open() {
        mainWindow.shouldBe("the IDE window should be present") { present() }
        // Raising keeps the IDE visible, which is what makes a failure screenshot worth having.
        // Activating matters more: an inactive window has no focus owner, and IntelliJ reports an
        // action disabled when it cannot resolve one -- which is what stops a toolbar button's
        // enabled state from being refreshed.
        mainWindow.raiseOwningWindow()
        driver.activateIdeWindow()

        // now = false is what makes this work rather than a preference. Executed immediately, the
        // action system runs the action's update() on the spot and refuses it as disabled while
        // the frame is not the active window, which is normal for an IDE launched by a harness.
        // Queued, the check happens once the EDT has settled and the action runs. It is also how
        // the Driver's own welcomeScreen/ideFrame helpers open Settings.
        driver.invokeAction("ShowSettings", now = false)

        settingsCategories.shouldBe("Settings dialog should be open") { present() }
        settingsCategories.selectPathDirectly("Editor", "File and Code Templates")
        // Wait for a control that will actually be used: ActionToolbarImpl builds its buttons
        // lazily, so the page can be present while the toolbar is still empty.
        createTemplateButton.shouldBe("File and Code Templates page should be shown") { present() }
    }

    /** Creates a template via the toolbar "+" and fills in its name, body and optional extension. */
    fun createTemplate(name: String, body: String, extension: String? = null) {
        val templateCountBefore = templateList.items.size

        createTemplateButton.performAction()

        // The action returns immediately, so the new row and its panel are waited for rather than
        // assumed: filling the name too early writes into the previously selected template's panel
        // and the new template is left called "Unnamed".
        templateList.shouldBe("a new template row should be added") {
            items.size > templateCountBefore
        }
        nameField.shouldBe("Name field should appear for a new template") { present() }

        nameField.text = name
        if (extension != null) extensionField.text = extension
        // Writing the document directly also sidesteps the editor auto-closing the ${...} braces.
        bodyEditor.text = body

        commitEditorPanel(name)
    }

    /**
     * Writes the editor panel's fields back to the template they belong to.
     *
     * Editing the fields alone changes nothing in the model: the panel commits when the selection
     * changes, which is what happens when a user clicks a different template. Doing it explicitly
     * matters because the only other trigger is the field losing focus, and that depends on the
     * IDE window being active -- not guaranteed on an unattended machine, in a VM, or over a
     * remote session. Relying on it leaves a new template saved as "Unnamed" and an edited body
     * discarded, on some machines and not others.
     */
    fun commitEditorPanel(templateName: String) {
        val otherTemplate = templateList.items.firstOrNull { it != templateName }
            ?: error("need a second template to switch to; list was ${templateList.items}")

        selectTemplate(otherTemplate)
        templateList.shouldBe("'$templateName' should be committed to the list") {
            items.contains(templateName)
        }
        selectTemplate(templateName)
    }

    /** Selects a template in the list, which shows its body in the editor. */
    fun selectTemplate(name: String) = templateList.selectItem(name)

    /**
     * Reverts a modified built-in template, confirming the "Reset Template" dialog it opens.
     *
     * The confirmation is modal, so it is dismissed over a second connection -- invoking the action
     * is the call that blocks. See [withModalDialog].
     */
    fun revertToOriginal() {
        // Captured before the action runs: a disabled toolbar action ignores being activated
        // without complaining, and that is worth stating if the confirmation never appears.
        val enabledBeforeClick = revertToOriginalButton.isActionEnabled()

        driver.withModalDialog(
            openModal = { revertToOriginalButton.performAction() },
            handleDialog = { second ->
                // The confirmation's button is looked up by any of its plausible labels rather
                // than one hard-coded string, so a reworded dialog fails with what it did show.
                // "OK" is not among them: the Settings dialog underneath has one.
                val confirm = runCatching { second.awaitButton("Reset", "Yes", "Revert") }
                    .getOrElse { error("${it.message}; revert action enabled: $enabledBeforeClick") }
                confirm.press()
            },
        )
    }

    /**
     * Commits changes and closes the dialog.
     *
     * Deliberately does not assert the dialog has gone: the IDE keeps its Swing tree reachable
     * after it is dismissed, so the components still match a hierarchy query and a "no longer
     * present" wait would never come true. [open] waits for the reopened page instead, and the
     * tests prove the save by re-reading persisted state.
     */
    fun applyAndClose() = dialogOkButton.press()

    /** Closes the dialog without applying further changes. */
    fun close() = dialogCancelButton.press()
}
