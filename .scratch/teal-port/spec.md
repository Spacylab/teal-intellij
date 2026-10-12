# Spec: port the Teal server to Teal

Status: done (all issues resolved). Glossary in [CONTEXT.md](../../CONTEXT.md) (**Teal server**, **`tl`**, **`cyan`**). Decision recorded in [ADR 0002](../../docs/adr/0002-teal-server-written-in-teal.md).

## Problem

The Teal server is about 1.8k lines of plain Lua in `src/main/resources/teal-server/`. Its most fragile code reads `tl`'s type report, whose shape is undocumented and unstable (ADR 0001), and nothing checks that code against `tl`'s real types. A Teal tool that isn't written in Teal also misses the cheapest dogfooding available.

## Goal

Every server module and Lua unit test is Teal source, type-checked against `tl.tl` during the build and compiled to Lua that the plugin ships. Users' setup and behaviour don't change, apart from a clear error for a `tl` that is too old.

## Decisions (settled, don't re-open)

- **Full port.** All 9 modules (`uri`, `json`, `rpc`, `diagnostics`, `requires`, `lookup`, `workspace`, `semantic`, `features`, `server`) and `requires_test`.
- **Build-time compile with cyan.** Gradle runs `cyan build` before resources are processed. The generated `.lua` is not committed. A missing `cyan` or `lua` fails the build with an install hint.
- **One `tlconfig.lua` at the repo root**: `source_dir = "src/main/teal"`, a build dir under `build/`, `gen_target = "5.1"`, `gen_compat = "off"`, all warnings promoted to errors. The Teal server reads the same file when this repo is opened in the IDE, so `require("json")` resolves while editing the server.
- **Packaging is unchanged.** The generated files land on the classpath at `/teal-server/<name>.lua`, which is where `TealServerScripts` already extracts them from.
- **`tl` version pin.** One version is recorded in the repo (start at 0.24.8). The build fails unless `tl.version()` matches it. At startup the server exits with a clear stderr message when the user's `tl` is older than the pin, the same way it handles a missing `tl` today (`server.lua:16`).
- **LSP payloads get Teal records** for the messages the server actually handles (`Position`, `Range`, `TextDocumentPositionParams`, …), cast once in the dispatcher. `tl`'s own types come from `tl.tl` and are not redeclared.
- **Lua unit tests become Teal**, compiled with cyan's flags (`-s src/test/teal -b <test build dir> -I src/main/teal`) into test resources.
- **Sequencing:** one branch, leaves first, one commit per issue, all tests green after each. Until a module is ported, its `.lua` stays in `src/main/resources/teal-server/`, and `tlconfig.lua` temporarily lists that directory in `include_dir` so Teal modules can require it. The last issue removes both.
- **Behaviour-preserving.** The port changes no LSP behaviour. `TealServerTest` (real server over stdio) is the regression net and is not modified, except where a test was relying on a bug the types expose (note it in the issue).

## Issues

1. [01: Build pipeline, with `uri` as the first module](issues/01-build-pipeline-and-uri.md)
2. [02: `json` and `rpc`](issues/02-json-and-rpc.md)
3. [03: LSP records and `diagnostics`](issues/03-lsp-records-and-diagnostics.md)
4. [04: `requires` and its test](issues/04-requires-and-test.md)
5. [05: `lookup`](issues/05-lookup.md)
6. [06: `workspace`](issues/06-workspace.md)
7. [07: `semantic`](issues/07-semantic.md)
8. [08: `features`](issues/08-features.md)
9. [09: `server`, version check, and cleanup](issues/09-server-and-cleanup.md)
