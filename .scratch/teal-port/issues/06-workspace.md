Type: task
Status: ready-for-agent
Blocked by: 05

## What

Port `workspace`, including the `tlconfig.lua` loading, the `tl` env lifecycle and the `TEAL_SERVER_REBUILD_EVERY` handling. Type the loaded config as a record of the fields actually read. See [spec.md](../spec.md).

## Acceptance (every issue)

- `./gradlew test buildPlugin` passes, with `cyan build` reporting no errors or warnings.
- `TealServerTest` is unchanged, or any change is justified in this file's Comments.
- The ported `.lua` is deleted from `src/main/resources/teal-server/`.

## Comments

