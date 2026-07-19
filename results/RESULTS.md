# Execution results

A captured run of the three File and Code Templates tests, all passing.

| | |
|---|---|
| Tests | 3 |
| Passed | 3 |
| Failed | 0 |
| Duration | ~1m32s |
| IDE under test | IntelliJ IDEA 2026.1.4 (build IU-261.26222.65) |

To reproduce, run `./gradlew test` from the project root (see the top-level README for
requirements). A fresh run overwrites `build/reports/` and `out/`; this folder is a committed copy
so the results are visible without running anything.

## Contents

- **`html-report/`** — the Gradle test report. Open `html-report/index.html` in a browser for the
  pass/fail summary, per-test timings, and any output. This is the primary result.
- **`junit-xml/`** — the same results as JUnit XML, for tooling that consumes it.
- **`screenshots/`** — a screenshot per test, captured by IDE Starter just before the IDE closed.
  They show that a real IDE ran and reached the expected state:
  - `createdTemplateIsSavedAndListed.png` — the Settings dialog on File and Code Templates.
  - `newFileFromTemplateUsesTemplateBody.png` — the throwaway project with the custom "AutotestTxt"
    template being used from File | New to create `GeneratedFromTemplate.txt`.
  - `revertRestoresBuiltInTemplate.png` — the Settings dialog after the built-in template was
    reverted.

The authoritative pass/fail evidence is the HTML report and the JUnit XML. For the payoff test,
the real check is in the test itself: it reads the generated file off disk and asserts the content
is the substituted template body (`Hello GeneratedFromTemplate`) with no literal `${NAME}` left.
