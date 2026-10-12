Type: task
Status: resolved
Blocked by: 02

## What

Add a module of Teal records for the LSP messages the server handles (`Position`, `Range`, `Location`, `Diagnostic`, `TextDocumentPositionParams`, and the rest used by the handlers). Port `diagnostics` onto them and onto `tl.tl`'s error types. Only declare what the server uses, nothing speculative. See [spec.md](../spec.md).

## Acceptance (every issue)

- `./gradlew test buildPlugin` passes, with `cyan build` reporting no errors or warnings.
- `TealServerTest` is unchanged, or any change is justified in this file's Comments.
- The ported `.lua` is deleted from `src/main/resources/teal-server/`.

## Comments


**2026-10-12: implemented.**
- **Bug found by the types, fixed:** `tl.lex` returns `{tl.Error}` (`y`, `x`, `msg`), but the Lua code read `tk.tk` as if lex errors were tokens, so every lex error was published as `unexpected token 'nil'`. Lex errors now carry `tl`'s own message (e.g. `invalid token '$'`). New test in `TealServerTest`: `a lex error is published with tl's message` (red before, green after). This is a user-visible fix worth a line in the next release notes.
- `lsp.tl` declares only what's used so far: `Position`, `Range` (with a quoted `["end"]` field), `Diagnostic`, and a `DiagnosticSeverity` string enum mapped to wire integers through `lsp.severity`. Later issues add records as their modules need them, rather than declaring every handler's types up front.
- `diagnostics.Check` (what workspace's check produces) and `diagnostics.WarningConfig` (the two tlconfig fields read here) live in `diagnostics` for now. Issue 06 owns workspace and its config record, so it should decide where they end up (Teal records are nominal, so workspace has to build these types or they move to workspace).
- `lsp` is a new module, so it had to be added to `TealServerScripts.FILES` by hand. Forgetting a file there only fails at runtime.
