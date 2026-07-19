package com.example.filetemplates

import com.intellij.driver.sdk.ui.components.common.welcomeScreen
import com.intellij.driver.sdk.ui.components.elements.accessibleTree
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.remote.SwingHierarchyService
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.project.NoProject
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tooling, not a test: the automated equivalent of the UI Inspector. It dumps the live Swing tree
 * to files under out/ui-dump/, so locators can be read from fact. Tagged "explore" and excluded
 * from the normal suite; run with -PincludeExplore.
 */
@Tag("explore")
class SettingsUiExplorer {

    @Test
    fun dumpFileAndCodeTemplatesUi() {
        Starter.newContext(
            "dumpFileAndCodeTemplatesUi",
            TestCase(IdeProductProvider.IU, projectInfo = NoProject).withVersion(IDE_VERSION),
        ).runIdeWithDriver().useDriverAndCloseIde {
            val hierarchyService = service(SwingHierarchyService::class)
            fun dumpDom(): String = hierarchyService.getSwingHierarchyAsDOM(null, false)

            val outDir = Path.of("out", "ui-dump")
            Files.createDirectories(outDir)
            Files.writeString(outDir.resolve("01-welcome-screen.xml"), dumpDom())

            // ShowSettings is async; poll the hierarchy until the dialog exists rather than sleep.
            welcomeScreen { openSettingsDialog() }
            val deadline = System.currentTimeMillis() + 60_000
            var dom = dumpDom()
            while (System.currentTimeMillis() < deadline && !dom.contains("MyDialog")) {
                Thread.sleep(500)
                dom = dumpDom()
            }
            val settingsDump = outDir.resolve("02-settings-open.xml")
            Files.writeString(settingsDump, dom)
            check(dom.contains("MyDialog")) { "Settings dialog never appeared within 60s. Dump: $settingsDump" }

            // "Editor" starts collapsed, so File and Code Templates isn't in dump 02 -- navigate to it.
            ui.accessibleTree { byAccessibleName("Settings categories") }
                .clickPath("Editor", "File and Code Templates", fullMatch = true)

            // Wait for a button we intend to click, not just the page: ActionToolbarImpl builds its
            // buttons lazily, so the page can be present while the toolbar is still empty.
            val pageDeadline = System.currentTimeMillis() + 60_000
            var pageDom = dumpDom()
            while (System.currentTimeMillis() < pageDeadline &&
                !pageDom.contains("accessiblename=\"Create Template\"")
            ) {
                Thread.sleep(500)
                pageDom = dumpDom()
            }
            Files.writeString(outDir.resolve("03-file-and-code-templates.xml"), pageDom)

            // A built-in template hides the Name/Extension fields; only a new custom template shows
            // them. Create one and capture that state for test 1's locators.
            ui.x { byAccessibleName("Create Template") }.click()
            val createDeadline = System.currentTimeMillis() + 30_000
            var createDom = dumpDom()
            while (System.currentTimeMillis() < createDeadline &&
                !createDom.contains("accessiblename=\"Reformat according to style\"")
            ) {
                Thread.sleep(500)
                createDom = dumpDom()
            }
            Files.writeString(outDir.resolve("04-create-template.xml"), createDom)
        }
    }
}
