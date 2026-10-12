Type: task
Status: ready-for-agent
Blocked by: 07

## What

Port `features` (hover, definition, type definition, completion, signature help, references, code actions) onto the LSP records and `tl.tl` types. See [spec.md](../spec.md).

## Acceptance (every issue)

- `./gradlew test buildPlugin` passes, with `cyan build` reporting no errors or warnings.
- `TealServerTest` is unchanged, or any change is justified in this file's Comments.
- The ported `.lua` is deleted from `src/main/resources/teal-server/`.

## Comments

