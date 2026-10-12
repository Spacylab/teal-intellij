Type: task
Status: resolved
Blocked by: 03

## What

Port `requires` and port `src/test/resources/teal-server/requires_test.lua` to `src/test/teal/requires_test.tl`. A Gradle test-side cyan task (`-s src/test/teal -I src/main/teal`) compiles it into test resources, and `TealServerLuaTest` keeps finding it at `/teal-server/requires_test.lua`. See [spec.md](../spec.md).

## Acceptance (every issue)

- `./gradlew test buildPlugin` passes, with `cyan build` reporting no errors or warnings.
- `TealServerTest` is unchanged, or any change is justified in this file's Comments.
- The ported `.lua` is deleted from `src/main/resources/teal-server/`.

## Comments


**2026-10-12: implemented.**
- `requires.tl`: the generated Lua is identical to the old module, plus a `require("tl")` used only for the `tl.Token` type (harmless, since the server loads `tl` anyway).
- `requires_test.tl` lives in `src/test/teal/`. `compileTealTest` (the same `CompileTeal` task class with `-s src/test/teal -b build/generated/teal-test/teal-server -I src/main/teal`) compiles it into test resources, so `TealServerLuaTest` still loads `/teal-server/requires_test.lua`. Its only behaviour change is a doc comment.
- Teal's arity check flagged three calls passing `line:find(...)`'s extra return values into a 2-parameter helper (silently dropped in Lua). They're wrapped in parentheses now.
- **Build fix:** cyan's incremental check compares mtimes at one-second resolution. An edit saved within the same second as the last compile was skipped, so Gradle reran `compileTeal*` but the stale `.lua` was kept, and from then on looked up to date to Gradle. Both tasks now pass `--update-all`, since Gradle already decides when to recompile. Verified by breaking and reverting a test assertion twice in a row: red, green, red, green.
