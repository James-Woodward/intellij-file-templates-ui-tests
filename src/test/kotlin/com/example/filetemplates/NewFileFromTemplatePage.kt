package com.example.filetemplates

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.toolwindows.projectView
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.popupMenu
import com.intellij.driver.sdk.ui.ui

/**
 * Page object for generating a file from a template through the Project view's **File | New** menu.
 *
 * Kept separate from [FileAndCodeTemplatesPage] because it drives a different part of the IDE (the
 * project frame and its context menu) rather than the Settings dialog.
 */
class NewFileFromTemplatePage(private val driver: Driver) {

    /**
     * Creates a file from [templateName] in the project root, naming it [fileName].
     */
    fun createInProjectRoot(templateName: String, fileName: String) {
        driver.ideFrame {
            projectView {
                projectViewTree.clickRow(0)
                projectViewTree.rightClickRow(0)
            }
        }

        // Navigate the context menu path New -> <template>; the popup helper handles submenus.
        driver.ui.popupMenu().select("New", templateName)

        // The "New <template>" dialog opens with a single auto-focused name field.
        driver.ui.dialog({ byTitle("New $templateName") }) {
            keyboard { typeText(fileName) }
            okButton.click()
        }
    }
}
