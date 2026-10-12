Type: task
Status: ready-for-agent
Blocked by: 02

## What

Add a module of Teal records for the LSP messages the server handles (`Position`, `Range`, `Location`, `Diagnostic`, `TextDocumentPositionParams`, and the rest used by the handlers). Port `diagnostics` onto them and onto `tl.tl`'s error types. Only declare what the server uses, nothing speculative. See [spec.md](../spec.md).

## Acceptance (every issue)

- `./gradlew test buildPlugin` passes, with `cyan build` reporting no errors or warnings.
- `TealServerTest` is unchanged, or any change is justified in this file's Comments.
- The ported `.lua` is deleted from `src/main/resources/teal-server/`.

## Comments

