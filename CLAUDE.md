# Delegation rules: agy-bridge

You have agy-bridge MCP tools that delegate heavy work to the Antigravity CLI
(Gemini). Delegation keeps large content OUT of your context — only answers
come back. Prefer delegating over doing it yourself when:

- **Any file >200 lines** you'd otherwise read → `analyze_files`
- **More than 3 files** in one analysis/comparison → `analyze_files`
- **Git history or repo-wide searches** (git log/diff/blame, broad greps) → `deep_search`
- **Web/documentation lookups** → `web_lookup`
- **Plan critique or code review** → `adversarial_review` (always — a second
  model family catches what you miss)
- **Short validation** (a single `./gradlew` build or unit-test run, expected
  under ~5 min) → `delegate`. Run Gradle from the repo root (single module `app`)
  and return only: pass/fail per step, failing test names with the key
  error lines, and a verdict. Never ask it to change code or settings, and
  don't paste raw build or logcat output into context. If it reports failures,
  `follow_up` on the same session to dig in, fix the code yourself, then
  re-test.
- **Long validation** (any emulator/device walkthrough, build + test + device)
  → background `agy -p`, NOT
  `delegate`. See "Long runs" below.
- **Follow-up question on a prior delegation** → `follow_up` with the returned
  session id (never resend the context)

**Model: Gemini 4 Hydrogen only, effort chosen per job.** Always pass the
model explicitly, on every agy-bridge call and every `agy -p` run; never rely
on the tool's automatic routing and never use another model family.

| Job | Model |
| --- | --- |
| Emulator/device work: screen walkthroughs, tap-and-check, running `connectedDebugAndroidTest` and reporting | `gemini-4-hydrogen-medium` |
| Everything else: code review, `adversarial_review`, `analyze_files`, `deep_search`, `web_lookup`, Gradle build/unit-test runs | `gemini-4-hydrogen-high` |

If the needed Hydrogen model is unavailable, stop and say so instead of
falling back. Check the `[agy-bridge] model:` footer on each result.

`follow_up` drops the `model` override and runs on agy's own default (footer
reads "agy default"). That default is `"model": "Gemini 4 Hydrogen (High)"` in
`~/.gemini/antigravity-cli/settings.json` (checked 2026-11-17), so `follow_up`
is fine to use. If that setting changes, make fresh calls with the model set
instead. Note that a `follow_up` on an emulator session therefore runs on
High, not Medium; for more emulator work, start a fresh run on Medium.

Do NOT delegate: small single-file edits, questions you can answer from
context already loaded, or tasks needing tools only you have.

## Long runs: background `agy -p`, not agy-bridge

Choose by expected duration:

- **Under ~5 min** (reading or analyzing files, searches, reviews, a single
  `./gradlew` test run, a follow-up question) → agy-bridge tools as above.
- **Over ~5 min, or unsure** (any emulator/device walkthrough, build + test +
  device validation, a brief with more than ~4 UI checks) → run `agy -p` as a
  background Bash job (`run_in_background: true`), never `delegate`. It has no
  tool-call timeout, and you keep working meanwhile. (Logged `delegate` runs
  over 5 min either hit the 600s client timeout and returned nothing, or ran
  out of time with most checks unverified.)

How:
1. Write the brief to `<scratchpad>/agy-brief.md`: steps, rules (no code or
   settings changes, `emulator-5554` only, never the physical phone), and the
   exact report format (PASS / FAIL / COULD-NOT-VERIFY per item, defects with
   file:line).
2. Run from `/Users/cxx/Projects/HealthAnalyzer`:
   `agy -p "$(cat <scratchpad>/agy-brief.md)" --model <model from the table above; gemini-4-hydrogen-medium for emulator runs> --settings /Users/cxx/Projects/HealthAnalyzer/.claude/agy-settings.json --print-timeout 30m > <scratchpad>/agy-report.txt 2> <scratchpad>/agy-err.txt`
3. When notified that it finished, read `agy-err.txt` first, then the report.
   Don't poll or sleep while it runs.

Don't pass `--mode auto` (not available on this account). Headless agy cannot
ask for permission: anything the allow-list in `.claude/agy-settings.json`
doesn't cover is silently denied, often with empty output. If that happens,
read `agy-err.txt` for the denied tool and widen the allow-list narrowly.
Never use `--dangerously-skip-permissions` for device runs.

Prefer scripted checks (Compose UI tests, an adb script) over having agy tap
through screens. Tapping through screens is the slowest, flakiest option.

## Compose UI tests for every feature

When you build or change a user-visible feature, also write or update a
Compose UI test for it in `app/src/androidTest/` (package
`com.healthanalyzer.app`), using `createAndroidComposeRule<MainActivity>()` or
`createComposeRule()` with the screen composable and fake data. Assert on
text, content descriptions and test tags (add `Modifier.testTag` where a node
has no stable text). Never depend on the user's real health data: seed what
the test needs, and never clear app data on the physical phone.

First time only: the module has no instrumented tests yet. Add
`testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` and
the `androidx.compose.ui:ui-test-junit4` (androidTestImplementation, via the
Compose BOM) and `ui-test-manifest` (debugImplementation) dependencies.

Validation then means: agy runs
`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<TestClass>`
against `emulator-5554` only (start agy with `ANDROID_SERIAL=emulator-5554`
in its environment, e.g. `ANDROID_SERIAL=emulator-5554 agy -p …`, so Gradle
inherits it and the allow-list's `./gradlew *` rule still matches) and reports
pass/fail per test with the key failure lines. Use screen-tapping walkthroughs
only for what the tests can't cover (visual look, animations), and keep those
short.
