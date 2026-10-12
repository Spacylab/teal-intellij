Type: task
Status: ready-for-agent
Blocked by: 03

## What

Port `requires` and port `src/test/resources/teal-server/requires_test.lua` to `src/test/teal/requires_test.tl`. A Gradle test-side cyan task (`-s src/test/teal -I src/main/teal`) compiles it into test resources, and `TealServerLuaTest` keeps finding it at `/teal-server/requires_test.lua`. See [spec.md](../spec.md).

## Acceptance (every issue)

- `./gradlew test buildPlugin` passes, with `cyan build` reporting no errors or warnings.
- `TealServerTest` is unchanged, or any change is justified in this file's Comments.
- The ported `.lua` is deleted from `src/main/resources/teal-server/`.

## Comments

