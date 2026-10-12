Type: task
Status: resolved

## What

Set up the Teal build and prove it end to end by porting the smallest leaf module, `uri`. See [spec.md](../spec.md).

## Decisions (settled, don't re-open)

- Root `tlconfig.lua` as described in the spec, with `include_dir = { "src/main/resources/teal-server" }` for the modules not yet ported.
- A Gradle task runs `cyan build` and is wired before `processResources`, with its output added as a resource directory so files land at `/teal-server/<name>.lua`. Declare inputs and outputs so it stays incremental.
- The task first checks the `tl` version against the pin, recorded in one place (e.g. `gradle.properties`, `tlVersion=0.24.8`). On a mismatch or a missing tool, it fails with a message naming the fix (`luarocks install tl <ver>`, `luarocks install cyan`).
- `uri.lua` becomes `src/main/teal/uri.tl`.
- README: add a contributor section listing the build requirements (`lua`, `tl` at the pinned version, `cyan`).

## Verify

- Introduce a deliberate type error in `uri.tl`. The Gradle build fails and shows cyan's message. Revert it.
- `TealServerScripts` extracts a `uri.lua` that came from the build output, not from the source resources.

## Acceptance (every issue)

- `./gradlew test buildPlugin` passes, with `cyan build` reporting no errors or warnings.
- `TealServerTest` is unchanged, or any change is justified in this file's Comments.
- The ported `.lua` is deleted from `src/main/resources/teal-server/`.

## Comments


**2026-10-12: implemented.**
- `tlconfig.lua` can't say "all warnings": cyan validates `warning_error` against `tl.warning_kinds`, so it lists them all (`branch debug hint redeclaration unknown unread unused`). A new `tl` warning kind needs adding by hand.
- `CompileTeal` (in `build.gradle.kts`) runs `lua -e "io.write(require('tl').version())"` and compares the result with `tlVersion`, then runs `cyan build --prune`. Its output dir `build/generated/teal` is a resource dir, and cyan writes into its `teal-server/` leaf, so files land at `/teal-server/<name>.lua`. That path appears in both `tlconfig.lua` and the task.
- The version check uses the `lua` on `PATH`, while `cyan` runs on whichever Lua its LuaRocks install uses. On a machine where those differ, the pin checks one interpreter and cyan compiles with another.
- Verified: a type error in `uri.tl` fails the build with cyan's message; `-PtlVersion=0.99.0` fails with the pin message; with no `cyan` on `PATH` the build says `luarocks install cyan`; the plugin jar's `teal-server/uri.lua` is the generated file. `./gradlew test buildPlugin` passes (55 tests), with `TealServerTest` unchanged.
