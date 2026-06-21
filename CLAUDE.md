# MusicResync — agent instructions

Android/Kotlin app (Jetpack Compose; AGP 8.7.1, Gradle 8.9, JDK 17, compileSdk 35, minSdk 21).
Forked from SongSync (GPL-3.0). Active work: smarter lyrics provider matching
(`util/matching/`, `domain/model/Song.kt`, `HomeViewModel`, `LyricsUtils`).

## Build / test / verify (always verify before claiming success)
- Build: `./gradlew assembleDebug`   ·   Lint: `./gradlew lintDebug`
- Unit tests (run the single relevant module, not the whole suite): `./gradlew :app:testDebugUnitTest`
- After code changes that should compile, run the relevant task and read the result.
  No build/test run = say "unverified". Show the command + output, don't assert success.

## Cloud/web environment (Claude Code on the web — Linux sandbox)
Shell is **bash on Linux** here (paths use `/`; `/dev/null`, `2>&1`, `&&`/`||` all work).
These env vars are provided — use them, never print their values:
- **`GH_TOKEN`** — `gh` and `git` push/pull over HTTPS are already authenticated. Use `gh`
  for all GitHub work (PRs, issues, CI). Failed CI → `gh run view --log-failed` first.
  Never ask for a token; never fall back to the web UI when `gh` can do it.
- **`JAVA_HOME`** — JDK 17. Gradle/`./gradlew` pick it up automatically; don't override it.
- **`ANDROID_HOME`** — Android SDK (platform-tools, platforms;android-35, build-tools;35.0.0).
  `adb`, `sdkmanager` are on PATH. Don't re-download SDK components that are already present.
- **`CONTEXT7_API_KEY`** — context7 docs lookup is authenticated via this key; just call the
  context7 tool (see "Docs" below). Don't hardcode or echo the key.

## CLI toolkit (preinstalled by setup script; prefer over reading files by hand)
Built-in **Grep/Glob/Read** stay first-choice for plain search/find/read. Shell out to these
for what built-ins don't do: counts, AST matching, JSON/YAML extraction, bulk replace, LOC maps.
- **`rg`** — counts/lists/context. `rg -c "TODO" --glob "*.kt"` · `rg -n -C2 "fun matchSong" app/`
- **`fd`** — find by name/ext. `fd -e kt` · `fd Song -x rg "confidence" {}`
- **`ast-grep`/`sg`** — structural search/rewrite (no comment/string false hits).
  `ast-grep -p 'Log.d($$$)' --lang kotlin` · rewrite `-p '...' -r '...' --lang kotlin`
- **`jq`** / **`yq`** — JSON / YAML-TOML-XML extraction. `gh api … | jq '.[].title'` ·
  `yq -p toml '.versions.agp' gradle/libs.versions.toml` (never run on source code)
- **`sd`** — repo-wide find/replace (preview with `-p` first; for 1–2 spots use Edit).
- **`tokei`** — instant LOC map to size up code without reading.
- **`codegraph`** — semantic code index (symbol/relationship navigation).
- **`headroom`** — compress long log/command output before it floods context.

## Behavioral rules (where agents most often fail — follow exactly)
- **Root cause, never suppress.** Don't swallow exceptions, add empty catches, or hardcode
  around a failure to make it "pass". Fix the cause or surface it.
- **Stay in scope.** Do what was asked. No speculative abstractions, flags, or "while I'm here"
  refactors. Spotted something extra? Mention it in one line — don't build it.
- **Edit > create.** Prefer editing existing files; reuse existing components. Match the
  surrounding Kotlin/Compose style, naming, and comment density. This is GPL-3.0 — keep it so.
- **No sycophancy.** If an approach is wrong, say so with the reason.
- **Don't re-read after editing** to double-check — Edit/Write already confirm the change.
- **Parallelize** independent tool calls; don't serialize what has no dependency.

## Docs: use context7 when unsure
Call context7 for library/framework/API specifics where memory may be stale — Compose, AGP,
Gradle, Android SDK behavior, Kotlin coroutines, third-party libs, version-specific config.
Quota is ample; prefer it over guessing an API. Don't spend a call on basics you reliably
know (plain Kotlin/Java stdlib, basic git/shell). Rule: if a wrong guess would cost a build
cycle, look it up; if you'd bet you're right, just answer.

## Response economy
Lead with the outcome. One line per change; don't echo edited file contents. Show error lines
+ diagnosis, not full logs (pipe noisy output through `headroom`). No preamble/apology padding.
