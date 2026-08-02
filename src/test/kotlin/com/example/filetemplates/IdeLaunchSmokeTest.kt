package com.example.filetemplates

import com.intellij.driver.sdk.ui.components.common.welcomeScreen
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.project.NoProject
import com.example.filetemplates.support.IDE_VERSION
import com.example.filetemplates.support.IdeStarterTestBase
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * Smoke test: launch the pinned IDE, confirm it rendered, close it.
 *
 * Runs first, which is the point of it. It separates "is the rig sound" -- download, launch, Driver
 * connection -- from "is the feature working", and that only helps if a failure here arrives before
 * the feature tests rather than after them.
 */
@Order(1)
class IdeLaunchSmokeTest : IdeStarterTestBase() {

    @Test
    fun ideStartsAndCloses() {
        Starter.newContext(
            "ideStartsAndCloses",
            TestCase(IdeProductProvider.IU, projectInfo = NoProject).withVersion(IDE_VERSION),
        ).runIdeWithDriver().useDriverAndCloseIde {
            welcomeScreen {
                createNewProjectButton.shouldBe { present() }
            }
        }
    }
}
