package com.example.filetemplates.pages

import com.example.filetemplates.support.raiseOwningWindow
import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.elements.JListUiComponent
import com.intellij.driver.sdk.ui.components.elements.JTextFieldUI
import com.intellij.driver.sdk.ui.components.elements.WindowUiComponent
import com.intellij.driver.sdk.ui.components.elements.accessibleTree
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
    val createTemplateButton get() = driver.ui.x { byAccessibleName("Create Template") }

    /** Toolbar action that restores a modified built-in template to its original. */
    val revertToOriginalButton get() = driver.ui.x { byAccessibleName("Revert to Original Template") }

    /** Confirm button of the "Reset Template" dialog that revert opens. */
    val resetConfirmButton get() = driver.ui.x { byAccessibleName("Reset") }

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
        // Raise the IDE first: an action is resolved against a DataContext taken from the focused
        // window, and a window that is neither focused nor frontmost yields nothing, so
        // "ShowSettings" reports itself disabled before it ever runs.
        mainWindow.shouldBe("the IDE window should be present") { present() }
        mainWindow.raiseOwningWindow()

        // now = false is what makes this work rather than a preference. Executed immediately, the
        // action system runs the action's update() on the spot and refuses it as disabled while
        // the frame is not yet the active window -- which is normal for an IDE launched by a test
        // harness. Queued instead, the check happens once the EDT has settled and the action runs.
        // This is also how the Driver's own welcomeScreen/ideFrame helpers open Settings.
        //
        // It is fire-and-forget either way, so the dialog is waited for rather than assumed.
        driver.invokeAction("ShowSettings", now = false)
        settingsCategories.shouldBe("Settings dialog should be open") { present() }
        // Clicks go to whatever is on top at that screen position, so make sure that is the
        // Settings dialog and not some other application that happens to be covering it.
        settingsCategories.raiseOwningWindow()
        settingsCategories.clickPath("Editor", "File and Code Templates", fullMatch = true)
        // Wait for a button that will actually be used: ActionToolbarImpl builds its buttons
        // lazily, so the page can be present while the toolbar is still empty.
        createTemplateButton.shouldBe("File and Code Templates page should be shown") { present() }
    }

    /** Creates a template via the toolbar "+" and fills in its name, body and optional extension. */
    fun createTemplate(name: String, body: String, extension: String? = null) {
        val templateCountBefore = templateList.items.size

        createTemplateButton.click()

        // Wait for the new row and its panel rather than assuming they are ready: filling the name
        // too early writes into the previously selected template's panel, and the new template is
        // then left called "Unnamed".
        templateList.shouldBe("a new template row should be added") {
            items.size > templateCountBefore
        }
        nameField.shouldBe("Name field should appear for a new template") { present() }

        nameField.text = name
        if (extension != null) extensionField.text = extension
        // setText avoids keyboard focus and auto-close-brace issues when typing ${...}.
        bodyEditor.text = body

        commitEditorPanel(name)
    }

    /**
     * Writes the editor panel's fields back to the template they belong to.
     *
     * Editing the fields alone changes nothing in the model: the panel commits when the selection
     * changes, which is what happens when a user clicks a different template. Doing it explicitly
     * matters because the alternative trigger is the field losing focus, and that depends on the
     * IDE window being the active window -- which is not guaranteed on an unattended machine, in a
     * VM, or over a remote session. Relying on it makes a new template silently persist as
     * "Unnamed" and an edited body silently revert, on some machines and not others.
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
    fun selectTemplate(name: String) = templateList.clickItem(name)

    /** Reverts a modified built-in template, confirming the "Reset Template" dialog it opens. */
    fun revertToOriginal() {
        revertToOriginalButton.click()
        resetConfirmButton.shouldBe("Reset Template confirmation should open") { present() }
        resetConfirmButton.click()
    }

    /** Commits changes and closes the dialog. */
    fun applyAndClose() = dialogOkButton.click()

    /** Closes the dialog without applying further changes. */
    fun close() = dialogCancelButton.click()
}
