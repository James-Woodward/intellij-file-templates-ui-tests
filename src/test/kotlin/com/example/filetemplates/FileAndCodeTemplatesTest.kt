package com.example.filetemplates

import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.waitForProjectOpen
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.project.NoProject
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

/**
 * The three tests for Settings > Editor > File and Code Templates.
 *
 * Failure policy lives in [IdeStarterTestBase]; every locator lives in [FileAndCodeTemplatesPage]
 * and [NewFileFromTemplatePage], so these tests read as behaviour rather than clicks.
 */
class FileAndCodeTemplatesTest : IdeStarterTestBase() {

    /** Test 1: a created template persists -- still listed with the same body after a reopen. */
    @Test
    fun createdTemplateIsSavedAndListed() {
        Starter.newContext(
            "createdTemplateIsSavedAndListed",
            TestCase(IdeProductProvider.IU, projectInfo = NoProject).withVersion(IDE_VERSION),
        ).runIdeWithDriver().useDriverAndCloseIde {
            val page = FileAndCodeTemplatesPage(this)
            // Unique name: the IDE config dir is reused between runs, so a fixed name could collide.
            val templateName = "Autotest_" + System.currentTimeMillis()
            val typedBody = "// autotest body for \${NAME}"

            page.open()
            page.createTemplate(templateName, typedBody)

            // Read back what actually landed; this is the source of truth for the later comparison.
            val savedBody = page.bodyEditor.text
            assertTrue(savedBody.contains("\${NAME}"), "template body should contain \${NAME}, was: $savedBody")

            page.applyAndClose()

            // Reopen from scratch: this re-reads the persisted state.
            page.open()
            assertTrue(
                page.templateList.items.contains(templateName),
                "template '$templateName' should be listed after reopening; list was ${page.templateList.items}",
            )

            page.selectTemplate(templateName)
            assertEquals(savedBody, page.bodyEditor.text, "persisted body should match what was saved")

            page.close()
        }
    }

    /**
     * Test 3: a modified built-in template can be reverted to its original.
     *
     * The original body is read live rather than hard-coded, so the test survives IDE version
     * changes.
     */
    @Test
    fun revertRestoresBuiltInTemplate() {
        Starter.newContext(
            "revertRestoresBuiltInTemplate",
            TestCase(IdeProductProvider.IU, projectInfo = NoProject).withVersion(IDE_VERSION),
        ).runIdeWithDriver().useDriverAndCloseIde {
            val page = FileAndCodeTemplatesPage(this)
            val builtInTemplate = "Class"
            val editMarker = "AUTOTEST_EDIT_MARKER"

            page.open()
            page.selectTemplate(builtInTemplate)
            page.bodyEditor.shouldBe("built-in template body should load") { text.isNotEmpty() }
            val originalBody = page.bodyEditor.text

            // Any change works; the body only needs to differ from the original.
            val editor = page.bodyEditor
            editor.click()
            editor.keyboard { typeText(editMarker) }
            page.bodyEditor.shouldBe("editing should change the body") {
                text != originalBody && text.contains(editMarker)
            }

            // Commit, reopen, and confirm the edit persisted before reverting.
            page.applyAndClose()
            page.open()
            page.selectTemplate(builtInTemplate)
            page.bodyEditor.shouldBe("edit should have persisted after reopening") { text.contains(editMarker) }

            page.revertToOriginal()
            page.bodyEditor.shouldBe("reverting should restore the original body") { text == originalBody }

            page.applyAndClose()
        }
    }

    /**
     * Test 2: a file created from a template uses the template body, with ${'$'}{NAME}
     * substituted by the file name.
     */
    @Test
    fun newFileFromTemplateUsesTemplateBody() {
        val sample = createSampleProject()
        val templateName = "AutotestTxt"
        val fileBaseName = "GeneratedFromTemplate"

        Starter.newContext(
            "newFileFromTemplateUsesTemplateBody",
            TestCase(IdeProductProvider.IU, projectInfo = sample.info).withVersion(IDE_VERSION),
        ).runIdeWithDriver().useDriverAndCloseIde {
            val settings = FileAndCodeTemplatesPage(this)
            val newFile = NewFileFromTemplatePage(this)

            // File | New only offers template actions once the project has finished indexing.
            waitForProjectOpen()
            waitForIndicators(5.minutes)

            // 1. Create a custom .txt template whose body uses the NAME variable.
            settings.open()
            settings.createTemplate(templateName, "Hello \${NAME}", extension = "txt")
            settings.applyAndClose()

            // 2. Generate a file from it via the Project view's New menu.
            newFile.createInProjectRoot(templateName, fileBaseName)

            // 3. The file is created on disk under the project; check its content.
            val createdFile = sample.projectDir.resolve("$fileBaseName.txt")
            val deadline = System.currentTimeMillis() + 15_000
            while (!Files.exists(createdFile) && System.currentTimeMillis() < deadline) {
                Thread.sleep(300)
            }
            assertTrue(Files.exists(createdFile), "file $createdFile should have been created from the template")

            val content = Files.readString(createdFile)
            assertTrue(content.contains("Hello $fileBaseName"), "generated file should contain 'Hello $fileBaseName', was: $content")
            assertFalse(content.contains("\${NAME}"), "NAME should have been substituted, not left literal, was: $content")

            // Deliberate evidence, taken at the success point (see results/).
            takeScreenshot("generated-file-from-template")
        }
    }
}
