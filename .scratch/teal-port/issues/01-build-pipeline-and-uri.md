Type: task
Status: ready-for-agent

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

