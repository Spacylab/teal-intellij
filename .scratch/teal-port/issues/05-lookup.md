Type: task
Status: ready-for-agent
Blocked by: 04

## What

Port `lookup` against `tl.tl`'s token and type-report types. This module and the next three are where the port pays off: record in Comments any place where the types disagreed with how the Lua code was using the type report. See [spec.md](../spec.md).

## Acceptance (every issue)

- `./gradlew test buildPlugin` passes, with `cyan build` reporting no errors or warnings.
- `TealServerTest` is unchanged, or any change is justified in this file's Comments.
- The ported `.lua` is deleted from `src/main/resources/teal-server/`.

## Comments

