package com.example.filetemplates.support

import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import org.junit.jupiter.api.fail
import org.kodein.di.DI
import org.kodein.di.bindSingleton

/**
 * Shared IDE Starter setup for tests that launch an IDE, configured once.
 *
 * By default IDE Starter only records IDE-side problems, so a broken IDE looks green. Overriding
 * reportTestFailure makes real IDE errors fail the test. isTestFailureShouldBeIgnored is the
 * framework's allowlist hook for the reverse case: known, unrelated log noise that would otherwise
 * make the suite flaky.
 */
abstract class IdeStarterTestBase {

    init {
        configureOnce()
    }

    companion object {
        // Narrow substrings of known IDE-side log errors that are unrelated to the feature and
        // safe to ignore. Kept specific so a real failure can't match by accident.
        private val IGNORED_ERROR_SIGNATURES = listOf(
            // A background metrics thread reads the Windows perf-counter registry via oshi; on a
            // machine whose registry is missing/corrupt it logs a SEVERE on every run.
            "Unable to locate English counter names in registry",
            "HkeyPerformanceDataUtil",
            // An Ultimate profiler bug: it fails to serialize its own run-configuration on project
            // save. Only appears in the project-based test.
            "JVMDTraceProfilerConfiguration",
            "ProfilerRunConfigurationsManager",
            // Full Line Completion cannot serialize its own settings when the project is saved,
            // which fails the project-based test intermittently. Same shape as the profiler bug:
            // a bundled plugin's own state, nothing to do with file templates.
            "MLServerCompletionSettings",
            "org.jetbrains.completion.full.line",
        )

        // di is process-global; bind it exactly once.
        @Volatile
        private var configured = false

        @Synchronized
        private fun configureOnce() {
            if (configured) return
            configured = true

            // Before anything is downloaded or launched, so a machine that cannot run GUI tests
            // says so in seconds instead of failing later as a component that never appeared.
            EnvironmentCheck.verify()

            di = DI {
                extend(di)
                bindSingleton<CIServer>(overrides = true) {
                    object : CIServer by NoCIServer {
                        override fun isTestFailureShouldBeIgnored(message: String): Boolean =
                            IGNORED_ERROR_SIGNATURES.any { message.contains(it) }

                        override fun reportTestFailure(
                            testName: String,
                            message: String,
                            details: String,
                            linkToLogs: String?,
                        ) {
                            fail { "$testName failed: $message\n$details" }
                        }
                    }
                }
            }
        }
    }
}
