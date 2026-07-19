package com.example.filetemplates

import com.intellij.ide.starter.project.LocalProjectInfo
import java.nio.file.Files
import java.nio.file.Path

/** A generated throwaway project plus its directory, so test 2 can read created files back. */
data class SampleProject(val info: LocalProjectInfo, val projectDir: Path)

/**
 * Generates a minimal project for test 2 (File | New needs an open project). IDE Starter opens the
 * folder in place, so files the test creates land under [SampleProject.projectDir] on disk.
 */
fun createSampleProject(): SampleProject {
    val fixturesRoot = Path.of("build", "test-fixtures")

    // Drop fixtures from previous runs so this directory doesn't accumulate (best-effort:
    // a directory still locked by a prior IDE is skipped rather than failing the test).
    if (Files.isDirectory(fixturesRoot)) {
        Files.newDirectoryStream(fixturesRoot, "sample-project-*").use { stale ->
            stale.forEach { runCatching { it.toFile().deleteRecursively() } }
        }
    }

    val projectDir = Files.createDirectories(
        fixturesRoot.resolve("sample-project-" + System.currentTimeMillis()),
    ).toAbsolutePath()
    Files.writeString(projectDir.resolve("README.txt"), "Sample project for File and Code Templates UI tests.\n")

    return SampleProject(LocalProjectInfo(projectDir = projectDir), projectDir)
}
