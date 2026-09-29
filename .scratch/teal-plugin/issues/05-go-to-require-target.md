Type: task
Status: ready-for-agent

## What

Cmd+Click (go-to-definition) on a `require("src.engine.run")` string literal should open its **require target**, `src/engine/run.tl`. Today nothing opens. teal-language-server's `textDocument/definition` only handles identifiers and returns `null` for a string literal. IntelliJ then falls back to Find Usages and shows "No usages found in Project Files".

## Decisions (settled, don't re-open)

- Implemented in the LSP proxy by intercepting `textDocument/definition` ([ADR 0001](../../../docs/adr/0001-lsp-client-architecture-for-teal-plugin.md), amendments). If the cursor is inside a require's string literal, the proxy answers itself. Otherwise the request passes through to the server unchanged.
- "Inside the string literal" means from the opening quote to the closing quote, inclusive. The `require` keyword itself stays with the server. Covers both `require("x")` and `require "x"`, with single or double quotes.
- Resolution is relative to the workspace root only: replace `.` with `/`, then try `<path>.tl`, `<path>.d.tl`, `<path>/init.tl`, `<path>.lua`, in that order. The first existing file wins.
- No `tlconfig.lua` `include_dir`/`source_dir` support yet.
- A module with no match (stdlib, luarocks deps) returns `null`. Nothing is looked up via `package.path` or the luarocks tree.
- The jump lands at line 1, col 1 of the target file.
- Out of scope: the alias case (`local run = require(...)`, then Cmd+Click on `run.start()`). That goes through the server's own definition handling.

## Acceptance

- Integration test in the style of `TealLspProxyTest`, run against the real `teal-language-server` binary with a fixture project:
  - definition on a require string returns the `.tl` target at 0:0;
  - `.d.tl`, `/init.tl` and `.lua` fallbacks each resolve, with priority respected when several exist;
  - an unresolvable module returns `null`;
  - the no-parens `require "x"` form resolves;
  - definition on an ordinary identifier still returns the server's answer, so pass-through is intact.
- Existing Find Usages and missing-require tests still pass.
- Manual check in picolo-rpg: Cmd+Click on `require("src.engine.run")` opens `src/engine/run.tl`.

## Comments

**2026-09-29: implemented, not yet committed.**
- The logic lives in `TealRequireTarget` (`moduleNameAt`, `resolve`), with unit tests in `TealRequireTargetTest`.
- `TealLspProxy` intercepts `textDocument/definition`, answers on a require string, and otherwise forwards to the server.
- All automated acceptance cases pass against teal-language-server 0.2.1.
- Alias case checked: on `local run = require("src.engine.run")`, definition on the `start` in a later `run.start()` already resolves into `src/engine/run.tl` via the server. No follow-up needed.
- Still to do: the manual check in picolo-rpg.
