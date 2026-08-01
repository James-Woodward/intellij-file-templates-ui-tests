# IntelliJ File and Code Templates — UI test suite

Automated UI tests for the **Settings | Editor | File and Code Templates** page in IntelliJ IDEA.
The suite launches a real IDE, drives its settings UI, and checks that templates save, take effect
when a file is generated, and can be reverted to their original.

Built on JetBrains' own integration stack: **IDE Starter** (downloads, configures and launches a
real IDE) and the **Driver** (a Kotlin DSL for querying and clicking the live UI), with JUnit 5 as
the runner.

## Running it

```
./gradlew test
```

That is the whole thing. The build fetches its own Gradle, its own JDK 21 and the IDE under test,
so nothing has to be installed or configured first — any recent JDK is enough to start it.

The first run downloads an IntelliJ IDEA build (~1.5 GB) and takes a few minutes; later runs take
about two. Four real IDE windows open and close, and the HTML report opens when it finishes.

**While it runs, leave the mouse and keyboard alone.** These tests drive the real cursor, so
competing input misdirects them. It does not matter what is on screen when the run starts — the
suite brings the IDE window to the front itself.

**On macOS, once:** grant the downloaded IDE Accessibility permission, or the operating system will
silently discard everything the tests do — see [macOS](#macos) below.

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

## What the machine needs

A **desktop session** — these are GUI tests, so they need a real display, or a virtual one such as
Xvfb on CI. Roughly **5–10 GB free**, and network access on the first run, for the IDE build cached
under `out/`.

Everything else the build provides for itself. The suite also checks the display and free space
before downloading anything, so a machine that cannot run GUI tests says so in seconds rather than
failing as a UI timeout minutes later.

### macOS

macOS only allows an application to synthesise mouse and keyboard input once it has been granted
Accessibility permission, and it refuses silently — the events are discarded with no error. This
applies to any tool that drives a UI. The IDE under test is downloaded into this project, so
permission held by an installed IntelliJ IDEA does not cover it.

Run the suite once, then add the downloaded IDE under **System Settings → Privacy & Security →
Accessibility** (press `+`, then `Cmd+Shift+G` and paste):

```
<project>/out/ide-tests/cache/builds/<build>/IntelliJ IDEA.app
```

Without it the IDE opens and nothing happens. Rather than let that surface as a component that
never appeared, the suite checks before its first click and stops with these instructions.

## Running a single test

```
./gradlew test --tests "com.example.filetemplates.FileAndCodeTemplatesTest.newFileFromTemplateUsesTemplateBody"
```

Results land in `build/reports/tests/test/` (HTML) and `build/test-results/test/` (JUnit XML). A
captured passing run is committed under `results/`.

## If a run fails

GUI tests fail for environmental reasons as often as for real defects, so the suite is set up to
say which it was.

- **The failure message names the check that timed out** (`Settings dialog should be open`, and so
  on), rather than reporting a bare missing component.
- **A screenshot of the moment of failure** is written to
  `out/ide-tests/tests/<build>/<test-name>/log/screenshots/`, and the driver error prints its path.
  This is usually enough on its own: it shows whether the IDE was even in front.
- **The full Swing tree at the point of failure** is saved next to it under `log/ui-hierarchy/`.
  Locators can be checked against it directly.
- **The IDE's own log** for the run is in the same `log/` directory.

Most common causes, in the order worth checking:

| Symptom | Cause |
|---|---|
| Fails immediately with a message about a display or disk space | Preflight — the message says what to fix |
| Components time out; screenshot shows another application in front | The session was used during the run — the cursor was moved or another window was clicked |
| Everything times out on a CI agent | No virtual display: use `xvfb-run -a ./gradlew test` |
| First run fails while fetching the IDE | No network access to the JetBrains download service |
| `ZipException: zip file is empty` during setup | A previous run was interrupted and left a half-extracted IDE. IDE Starter reuses that directory rather than re-extracting it, so delete `out/` and run again |
| macOS: the IDE opens and nothing happens | Accessibility permission has not been granted to the downloaded IDE — see the macOS section under Requirements |
| `BUILD SUCCESSFUL` but nothing ran | Gradle considered `test` up to date; use `./gradlew cleanTest test` |

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
- **Edits are committed explicitly, not by side effect.** The template panel writes its fields back
  to the model when the list selection changes; typing alone changes nothing. The other trigger is
  the field losing focus, which happens to occur when a click lands elsewhere — so a test can pass
  by accident on a machine where the IDE window is active, and silently save a template called
  "Unnamed" on one where it is not. `commitEditorPanel` performs the selection change deliberately,
  which removes the dependency on window focus and on click timing.
- **The environment is checked before the IDE is downloaded.** `EnvironmentCheck` verifies a usable
  display and enough disk, and explains what to do when either is missing. It turns the most common
  reason a GUI suite fails on someone else's machine into an immediate, readable error.
- **The IDE window is raised before anything is clicked.** A click is delivered to whatever is on
  top at that screen position, so a maximised window covering the IDE silently sends every click in
  the run somewhere else, and the tests fail as components that "never appeared". `raiseOwningWindow`
  lifts the IDE window first, from inside the IDE — a click cannot reveal a window that is not
  visible to begin with. This was reproduced deliberately: with another application maximised, all
  three tests failed at their first click; with the window raised, all three pass and run roughly
  twice as fast, because clicks no longer retry until they time out.
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
  FileAndCodeTemplatesTest.kt      The three deliverable tests
  IdeLaunchSmokeTest.kt            Launch-and-close smoke test
  pages/
    FileAndCodeTemplatesPage.kt    Page object: locators + actions for the settings page
    NewFileFromTemplatePage.kt     Page object: the File | New flow
  support/
    IdeStarterTestBase.kt          Shared IDE Starter setup and failure policy
    IdeVersion.kt                  The pinned IDE version, in one place
    SampleProject.kt               Generates the throwaway project for test 2
    EnvironmentCheck.kt            Preflight: display and disk checks with actionable messages
    WindowFocus.kt                 Raises the IDE window so clicks reach it
  explore/
    SettingsUiExplorer.kt          Tooling: dumps the settings UI (tagged "explore")
    NewFileFlowExplorer.kt         Tooling: dumps the File | New flow (tagged "explore")
results/                           A committed run's HTML report and screenshots
```

Tests sit at the top of the package; everything they lean on is grouped by role beneath it —
`pages` for the UI surface, `support` for the rig, `explore` for the capture tooling.

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
