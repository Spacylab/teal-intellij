Type: task
Status: ready-for-human

## What

This is the first slice of the minimal server from [ADR 0001](../../../docs/adr/0001-lsp-client-architecture-for-teal-plugin.md) (third amendment). It is a pure-Lua LSP server over stdio that loads `tl` as a library and publishes diagnostics. It isn't wired into the plugin yet: teal-language-server plus the proxy stay in production until the server reaches parity (hover, definition, type definition, completion, signature help).

## Decisions (settled, don't re-open)

- **Plain Lua, not Teal.** Shipping Teal would need a `tl gen` build step, and the `tl` CLI crashes on Lua 5.5. The sources live in `src/main/resources/teal-server/` so they ship inside the plugin jar.
- **The only runtime dependency is `tl`.** No `luv`, `lusc`, `ltreesitter` or `cjson`: a small JSON module and the stdio framing are written in-repo.
- **The main loop is blocking and single-threaded**, reading one message at a time. There is no debouncing, because the prototype showed re-checks are fast enough.
- **Env policy from the ADR:** reuse one `tl` env, and rebuild it when any `.tl` document is saved or after N `didChange`s. N defaults to 100 and can be overridden with `TEAL_SERVER_REBUILD_EVERY`. A rebuild re-checks every open document so dependents pick up changes.
- **Module search uses `tl.path`**, not `package.path`. It is built from the workspace root and `tlconfig.lua`'s `source_dir` and `include_dir`. `global_env_def` is loaded as a predefined module.
- **Diagnostics follow teal-language-server's rules.** Lex errors take priority over parse errors, which take priority over type errors and warnings. Errors from other files (required modules) are dropped. A range spans the token at the error position.
- **Columns are byte offsets.** LSP expects UTF-16, so the two agree only for ASCII text. This is a known limitation for now.

## Acceptance

- JUnit integration test driving `lua server.lua` over raw stdio; skipped if `lua` or the `tl` rock is missing:
  - `initialize` advertises full-document sync and save notifications, and reports the `tl` version in `serverInfo`;
  - a type error publishes an Error diagnostic with the right range and message;
  - fixing it through `didChange` publishes an empty list;
  - a syntax error publishes a parse error;
  - a warning (an unused variable) publishes a Warning diagnostic;
  - a dependency edited on disk and then saved triggers a re-check of the file that requires it;
  - with `TEAL_SERVER_REBUILD_EVERY=2`, a dependency edited on disk is picked up after two edits with no save;
  - unknown requests get a `-32601` error;
  - `shutdown` followed by `exit` ends the process with code 0.
- Existing proxy tests still pass.

## Comments

**2026-09-29: implemented, not yet committed.**
- Server: `src/main/resources/teal-server/`, about 640 lines of Lua across 6 files:
  - `server.lua`: entry point and dispatch loop;
  - `workspace.lua`: tlconfig, open documents, env and rebuild policy;
  - `diagnostics.lua`;
  - `json.lua`, `rpc.lua`, `uri.lua`: no native dependencies.
- `TealServerScripts.extractTo(dir)` copies the scripts out of the jar, because `lua` can't run them from inside it. The tests use it now, and the plugin will use it when the server gets wired in.
- `TealServerTest` covers all acceptance cases and passes (8/8) on Lua 5.5.1 with tl 0.24.8. The existing proxy tests still pass.
- Manual run against picolo-rpg: all 41 `src/` files open with no diagnostics. `love` globals resolve via `global_env_def`. An injected error in `main.tl` is reported, with a re-check in about 17 ms.
- Only tested on Lua 5.5: no other interpreter is installed here. The code avoids 5.3+-only features (`utf8`, `//`, `goto`, integer subtypes), but 5.1 and LuaJIT are unverified.
- Not wired into the plugin. Next slices: hover, definition and type definition, completion and signature help; then switching `TealLanguageServerFactory` over and retiring the proxy.
