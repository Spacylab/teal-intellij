# The Teal server is written in Teal, compiled at build time with cyan

Status: accepted

The Teal server (third amendment to [ADR 0001](0001-lsp-client-architecture-for-teal-plugin.md)) started as plain Lua. We are porting all of it to Teal. The main reason is type safety against `tl`'s library API: ADR 0001 notes that the type-report shape and `symbols_in_scope` are not documented as stable, and the `tl` rock ships `tl.tl`, so Teal code that requires `tl` is type-checked against the real API. A shape change in a `tl` upgrade then breaks the build instead of quietly breaking the IDE. The second reason is dogfooding: a Teal tool written in Teal hits the same problems its users do.

The plugin still ships only Lua. `cyan build` compiles `src/main/teal/` during the Gradle build, targeting Lua 5.1 with compat off, so the output runs on Lua 5.1 through 5.5 and LuaJIT with no `compat53` dependency. Users' install story doesn't change. Warnings are errors. A single root `tlconfig.lua` drives the build and is also read by the Teal server itself when this repo is opened in the IDE.

The build pins one `tl` version and fails on any other one, because type checking only covers the `tl` the build machine has. At startup the server refuses a `tl` older than the pinned version, with a clear error.

## Considered Options

- **Ship `.tl` and load it at runtime with `tl.loader()`.** Rejected. Users already have `tl`, so there'd be no build step, but the user's `tl` version would decide whether the server even parses, and the server would be recompiled on every start.
- **Compile with the `tl gen` CLI.** Rejected. The `tl` 0.24.8 CLI script fails at startup on Lua 5.5 (it assigns to a `for` loop variable), and `tl gen` emits code even when type checking fails. Cyan 0.4.1 runs on Lua 5.5 and exits non-zero on type errors.
- **A custom Lua build script calling `tl.check` and `tl.generate`.** Rejected in favour of cyan, the purpose-built Teal build tool that `tl`'s own README recommends.
- **Commit the generated `.lua`.** Rejected, to keep generated code out of the repo. Building already needs Lua for the server tests.
- **Untyped LSP payloads.** Rejected. Untyped JSON would spread through every handler. The messages the server handles get Teal records, cast once in the dispatcher.

## Consequences

- Building the plugin now needs `lua`, `tl` (at the pinned version) and `cyan`. A JDK alone is no longer enough.
- Bumping `tl` is a deliberate act: change the pin, rebuild, and fix whatever the new type report breaks.
- The Lua unit tests are Teal too, compiled with cyan against the server sources.
