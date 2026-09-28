Type: task
Status: ready-for-agent

## What

Implement the **missing-require quick fix** described in [spec.md](../spec.md)'s Feature 4. On an `unknown type X` diagnostic, Alt+Enter offers `Add require("<module name>")` for each workspace module that declares a global `X`. Applying it inserts the bare require after the file's leading requires.

Motivating case: picolo-rpg's `src/engine/run.tl` uses `Player` (a `global record` in `src/entities/player.tl`), but its `require("src.entities.player")` is commented out.

## Decisions (settled, don't re-open)

- Implemented in the LSP proxy by answering `textDocument/codeAction`, not IntelliJ-native, not upstream ([ADR 0001](../../../docs/adr/0001-lsp-client-architecture-for-teal-plugin.md), second amendment).
- Rename `TealReferencesProxyCore` → `TealLspProxy` and `TealReferencesProxyConnectionProvider` → `TealLspProxyConnectionProvider` (and the test) as part of this work.
- Triggers only on `unknown type <Name>`. `unknown variable` is out of scope.
- Global declarations only (`global record|enum|interface|type`). Local, module-returned types are out of scope.
- Module name = path relative to the workspace root, with `/` replaced by `.` and no `.tl` extension.
- Insert after the last leading `require(...)` line, or at line 1 if there are none. Never un-comment a commented-out require. Skip modules that are already required.
- One action per candidate module when several declare the same global.
- Candidates come from an on-demand scan of the workspace's `.tl` files (skipping hidden dirs and the current file). No index.

## Acceptance

- Integration test in the style of the existing proxy test, run against the real `teal-language-server` binary with a small fixture project, covering:
  - a global record in one file and its unrequired use in another returns exactly one action with the right title and edit;
  - two modules declaring the same global return two actions;
  - a local (non-global) record returns no action;
  - an already-required module isn't offered;
  - after applying the edit, the `unknown type` diagnostic is gone.
- `initialize` response advertises `codeActionProvider`. Existing Find Usages tests still pass after the rename.
- Manual check: spec.md acceptance criterion 7 in picolo-rpg.

## Comments

**2026-09-28: implemented, not yet committed.**
- Proxy renamed to `TealLspProxy` / `TealLspProxyConnectionProvider`, and the test to `TealLspProxyTest`.
- Scanning, module-name and insertion logic is in `TealMissingRequire`, with unit tests in `TealMissingRequireTest`.
- The proxy reads the workspace root from the client's `initialize` request (`rootUri`, then `workspaceFolders[0]`, then `rootPath`).
- All automated acceptance cases pass against teal-language-server 0.2.1, including the diagnostic clearing after the edit.
- Still to do: the manual check in picolo-rpg (spec acceptance criterion 7).
