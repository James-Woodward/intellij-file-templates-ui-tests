package com.example.filetemplates

import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.toolwindows.projectView
import com.intellij.driver.sdk.ui.components.elements.popupMenu
import com.intellij.driver.sdk.ui.remote.SwingHierarchyService
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.driver.sdk.ui.ui
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tooling for test 2's File | New flow (see SettingsUiExplorer). Opens a project, creates a custom
 * template, then drives the Project view context menu and dumps the resulting UI so the menu and
 * dialog locators can be read rather than guessed. Tagged "explore", excluded from the normal suite.
 */
@Tag("explore")
class NewFileFlowExplorer {

    @Test
    fun dumpNewFileFlow() {
        Starter.newContext(
            "dumpNewFileFlow",
            TestCase(IdeProductProvider.IU, projectInfo = createSampleProject().info).withVersion(IDE_VERSION),
        ).runIdeWithDriver().useDriverAndCloseIde {
            val hierarchyService = service(SwingHierarchyService::class)
            fun dumpDom(): String = hierarchyService.getSwingHierarchyAsDOM(null, false)
            val outDir = Path.of("out", "ui-dump")
            Files.createDirectories(outDir)

            // A custom template so it appears as a "New" menu entry.
            val settings = FileAndCodeTemplatesPage(this)
            settings.open()
            settings.createTemplate("AutotestTxt", "Hello \${NAME}", extension = "txt")
            settings.applyAndClose()

            ideFrame {
                projectView {
                    projectViewTree.shouldBe("project view tree should be present") { present() }
                }
            }
            Files.writeString(outDir.resolve("05-project-frame.xml"), dumpDom())

            // Select then right-click the root (row 0); selecting first makes the right-click reliable.
            ideFrame {
                projectView {
                    projectViewTree.clickRow(0)
                    projectViewTree.rightClickRow(0)
                }
            }
            ui.popupMenu().select("New", "AutotestTxt")
            Thread.sleep(1500)
            Files.writeString(outDir.resolve("08-new-file-dialog.xml"), dumpDom())
        }
    }
}
