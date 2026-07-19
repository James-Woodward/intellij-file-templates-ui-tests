# IntelliJ File and Code Templates — UI test suite

Automated UI tests for the **Settings | Editor | File and Code Templates** page in IntelliJ IDEA.
The suite launches a real IDE, drives its settings UI, and checks that templates save, take effect
when a file is generated, and can be reverted to their original.

Built on JetBrains' own integration stack: **IDE Starter** (downloads, configures and launches a
real IDE) and the **Driver** (a Kotlin DSL for querying and clicking the live UI), with JUnit 5 as
the runner.

## What the tests cover

Three tests, ordered create → take effect → restore.

**`createdTemplateIsSavedAndListed`** creates a custom template with `${NAME}` in its body, saves,
then closes and reopens Settings and checks it is still listed with the same body. Reopening forces
a re-read of persisted state, so a pass means the template was genuinely written.

**`newFileFromTemplateUsesTemplateBody`** creates a `.txt` template with body `Hello ${NAME}`,
generates a file from it via **File | New**, and reads that file off disk — asserting the content is
`Hello <filename>` with no literal `${NAME}` left. It is the only test that checks an artifact
independent of the settings UI, and the only one needing an open project.

**`revertRestoresBuiltInTemplate`** edits the built-in "Class" template, reopens to confirm the edit
stuck, then uses **Revert to Original Template** and checks the original body returns. The original
is read live from the IDE, not hard-coded, so it survives template changes between IDE versions.

A fourth class, `IdeLaunchSmokeTest`, is not one of the three: it only launches the IDE and confirms
the Welcome screen renders, separating "is the rig sound" from "is the feature working".

## Requirements

- **JDK 21**, any distribution.
- **A desktop session.** These are GUI tests: they open a visible IDE window and drive a real mouse
  and keyboard, so the machine needs a display (or Xvfb on CI) and should be left alone during a run.
- **~5–10 GB free disk.** IDE Starter downloads a full IDE build (~1.5 GB) on first run, cached
  under `out/`.
- Network access on the first run.

Gradle is not required; the wrapper provides it.

## Running

```
./gradlew test
```

Runs the three tests plus the smoke test. A real IntelliJ window opens and closes for each; do not
touch the mouse or keyboard. The HTML report at `build/reports/tests/test/index.html` opens
automatically on local runs (skipped on CI).

Single test:

```
./gradlew test --tests "com.example.filetemplates.FileAndCodeTemplatesTest.newFileFromTemplateUsesTemplateBody"
```

Results land in `build/reports/tests/test/` (HTML) and `build/test-results/test/` (JUnit XML). A
captured passing run is committed under `results/`.

## Versions

Pinned for reproducibility:

| Component | Version |
|---|---|
| IDE under test | IntelliJ IDEA 2026.1.4 (build 261.26222.65) |
| IDE Starter + Driver | 261.26222.65 |
| Gradle | 9.6.1 |
| Kotlin | 2.3.21 |
| JDK | 21 |
| JUnit | 5.10.2 |

The IDE and the Starter/Driver share one build number on purpose: the Driver talks to an agent
inside the running IDE, so matching builds avoids protocol drift.

**Why this IDE version.** IDE Starter installs IDEs by product code through the public JetBrains
download service. Community (`IC`) stops at 2025.3, so no Community 2026.x build can be installed;
pinning 2025.3 as `IC` also fails, because the unified 2025.3+ distribution reports code `IU`. That
leaves `IU` as the only installable current build. File and Code Templates is identical in both
editions, and the suite runs with no licence — the settings surface needs none.

## Design decisions

- **Locators are read from the UI, never guessed.** Each part of the UI was dumped to a snapshot
  first — the `*Explorer` classes read the same source the Driver queries — and locators written
  against it. That is how the revert action turned out to be labelled **Revert to Original
  Template**, not "Reset to Default", with a separate **Reset Template** confirmation. Guessing would
  have produced a plausible test that failed.
- **Locators live in page objects, not tests.** `FileAndCodeTemplatesPage` and
  `NewFileFromTemplatePage` each declare their locators once, so tests read as behaviour and a UI
  change is a one-line fix. Locators use `get()`, not `val`, because the dialog is reopened mid-test
  and a captured component would go stale.
- **Tests wait for state, never sleep.** Every step uses `shouldBe { … }`, which polls for the exact
  condition and fails after ~15s with a clear message — no flaky-or-slow `Thread.sleep`.
- **The payoff test checks an independent artifact.** `newFileFromTemplateUsesTemplateBody` reads the
  generated file off disk rather than the settings UI that produced it: a test that asserts against
  the surface it just wrote to can pass while proving nothing.
- **The rig is proven separately from the feature.** `IdeLaunchSmokeTest` only launches and closes
  the IDE, so a failure there points at the infrastructure, not the templates.
- **Genuine IDE errors fail the test; known noise does not.** `IdeStarterTestBase` turns IDE-log
  errors into failures but narrowly allowlists a couple of documented, unrelated telemetry errors,
  so the suite does not go red at random.

## Project layout

```
build.gradle.kts        Dependencies, pinned versions, the test task, report auto-open
settings.gradle.kts
gradle/                 Wrapper, pinned to Gradle 9.6.1 with a checksum
src/test/kotlin/com/example/filetemplates/
  FileAndCodeTemplatesTest.kt   The three deliverable tests
  IdeLaunchSmokeTest.kt         Launch-and-close smoke test
  IdeStarterTestBase.kt         Shared IDE Starter setup and failure policy
  IdeVersion.kt                 The pinned IDE version, in one place
  FileAndCodeTemplatesPage.kt   Page object: locators + actions for the settings page
  NewFileFromTemplatePage.kt    Page object: the File | New flow
  SampleProject.kt              Generates the throwaway project for test 2
  SettingsUiExplorer.kt         Tooling: dumps the settings UI (tagged "explore")
  NewFileFlowExplorer.kt        Tooling: dumps the File | New flow (tagged "explore")
results/                        A committed run's HTML report and screenshots
```

The `*Explorer` classes are tooling, not tests: tagged `explore`, excluded from a normal run, and
re-runnable with `-PincludeExplore` if the UI ever needs re-capturing.

## Notes on the framework

- Starter and Driver artifacts come from `jetbrains.com/intellij-repository/releases`, not the
  `intellij-dependencies` cache-redirector, which 404s for them. Both repositories are declared; the
  second supplies transitive dependencies.
- `ide-starter-driver` is a separate artifact from `ide-starter-squashed`, providing
  `runIdeWithDriver()` and `useDriverAndCloseIde()`; the code does not compile without it.
- Under Gradle 9 the JUnit Platform launcher is not added to the test runtime automatically, so it is
  declared explicitly.
