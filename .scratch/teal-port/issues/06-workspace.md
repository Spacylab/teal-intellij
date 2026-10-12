Type: task
Status: resolved
Blocked by: 05

## What

Port `workspace`, including the `tlconfig.lua` loading, the `tl` env lifecycle and the `TEAL_SERVER_REBUILD_EVERY` handling. Type the loaded config as a record of the fields actually read. See [spec.md](../spec.md).

## Acceptance (every issue)

- `./gradlew test buildPlugin` passes, with `cyan build` reporting no errors or warnings.
- `TealServerTest` is unchanged, or any change is justified in this file's Comments.
- The ported `.lua` is deleted from `src/main/resources/teal-server/`.

## Comments


**2026-10-12: implemented.**
- `Workspace` is a record with nested `Config`, `Check`, `Document` and `Options` records. `Check` and the warning config moved here from `diagnostics` (issue 03 left that open). `diagnostics` now uses `local type Workspace = require("workspace")`, which emits no runtime `require`, so there is no load-order coupling.
- `Config` declares only the tlconfig fields the server reads. `load_config` casts the user's table to it; tl gets the values as before, so a malformed tlconfig behaves as it did.
- `Document.semantic_tokens` is declared here as `lsp.SemanticTokens` (`{data: {integer}}`), since `semantic` (issue 07) caches it on the document. `lsp` also gained `Location`.
- **No behaviour change, no bugs found.** Mechanical differences in the generated Lua: methods come from a separate metatable (`Workspace_mt`) instead of `Workspace.__index = Workspace`; the parameter `uri`, which shadowed the `uri` module, is now `u`; `tokens_of` reads its `{Token} | false` cache through a local narrowed with `is`; `tl.check`'s options are built as an annotated `tl.CheckOptions` local, because the inline `lax and "on" or "off"` widened to `string` inside `pcall`.
- `pcall` erases types, so `tl.check`'s result is cast back to `tl.Result`.
