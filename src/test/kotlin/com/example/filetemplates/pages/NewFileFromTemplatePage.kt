package com.example.filetemplates.pages

import com.example.filetemplates.support.raiseOwningWindow
import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.toolwindows.projectView
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.popupMenu
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.driver.sdk.ui.ui

/**
 * Page object for generating a file from a template through the Project view's **File | New** menu.
 *
 * Kept separate from [FileAndCodeTemplatesPage] because it drives a different part of the IDE (the
 * project frame and its context menu) rather than the Settings dialog.
 */
class NewFileFromTemplatePage(private val driver: Driver) {

    /** The Project view's file tree. A getter, so it re-resolves rather than going stale. */
    private val projectTree
        get() = driver.ideFrame().projectView().projectViewTree

    /**
     * Creates a file from [templateName] in the project root, naming it [fileName].
     *
     * The root row is selected before being right-clicked: doing both makes the context menu
     * appear reliably. The row index is used rather than a name because the generated project's
     * folder name varies per run.
     */
    fun createInProjectRoot(templateName: String, fileName: String) {
        projectTree.shouldBe("project view tree should be present") { present() }
        // As in the Settings page: the frame has to be on top or the clicks land elsewhere.
        projectTree.raiseOwningWindow()
        projectTree.clickRow(0)
        projectTree.rightClickRow(0)

        // Navigate the context menu path New -> <template>; the popup helper handles submenus.
        driver.ui.popupMenu().select("New", templateName)

        // The "New <template>" dialog opens with a single auto-focused name field.
        driver.ui.dialog({ byTitle("New $templateName") }) {
            keyboard { typeText(fileName) }
            okButton.click()
        }
    }
}
