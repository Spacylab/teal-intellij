Type: task
Status: ready-for-agent
Blocked by: 06

## What

Port `semantic` against the type report. Keep the token legend and the encoding byte-identical: the semantic highlighting tests must pass unchanged. See [spec.md](../spec.md).

## Acceptance (every issue)

- `./gradlew test buildPlugin` passes, with `cyan build` reporting no errors or warnings.
- `TealServerTest` is unchanged, or any change is justified in this file's Comments.
- The ported `.lua` is deleted from `src/main/resources/teal-server/`.

## Comments

