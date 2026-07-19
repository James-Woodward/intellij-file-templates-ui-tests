package com.example.filetemplates

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.elements.JListUiComponent
import com.intellij.driver.sdk.ui.components.elements.JTextFieldUI
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
 * Locators use `get()` rather than plain `val` on purpose: the Settings dialog is closed and
 * reopened during a test, so a component captured eagerly would go stale. A getter re-resolves
 * against the current UI on every access.
 */
class FileAndCodeTemplatesPage(private val driver: Driver) {

    // ---------------------------------------------------------------- locators

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
        // "ShowSettings" works with or without a project open, and is asynchronous, so the
        // dialog's contents are waited for rather than assumed.
        driver.invokeAction("ShowSettings")
        settingsCategories.shouldBe("Settings dialog should be open") { present() }
        settingsCategories.clickPath("Editor", "File and Code Templates", fullMatch = true)
        createTemplateButton.shouldBe("File and Code Templates page should be shown") { present() }
    }

    /** Creates a template via the toolbar "+" and fills in its name, body and optional extension. */
    fun createTemplate(name: String, body: String, extension: String? = null) {
        createTemplateButton.click()
        nameField.shouldBe("Name field should appear for a new template") { present() }
        nameField.text = name
        if (extension != null) extensionField.text = extension
        // setText avoids keyboard focus and auto-close-brace issues when typing ${...}.
        bodyEditor.text = body
    }

    /** Selects a template in the list, which shows its body in the editor. */
    fun selectTemplate(name: String) = templateList.clickItem(name)

    /** Reverts a modified built-in template, confirming the "Reset Template" dialog it opens. */
    fun revertToOriginal() {
        revertToOriginalButton.click()
        resetConfirmButton.click()
    }

    /** Commits changes and closes the dialog. */
    fun applyAndClose() = dialogOkButton.click()

    /** Closes the dialog without applying further changes. */
    fun close() = dialogCancelButton.click()
}
