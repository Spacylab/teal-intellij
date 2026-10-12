Type: task
Status: resolved
Blocked by: 08

## What

Port `server`: the dispatcher casts each decoded payload to its LSP record once. Add the startup check: if `tl.version()` is older than the pinned version, write a clear message to stderr and exit 1, like the missing-`tl` path. The pin reaches the server through the build (e.g. a generated constant). Then remove `include_dir` from `tlconfig.lua`, confirm `src/main/resources/teal-server/` is empty and delete it, and update the README and plugin description where they say the server is plain Lua. Consider generating `TealServerScripts.FILES` from the build output instead of maintaining it by hand (issue 03 had to add `lsp.lua` manually). See [spec.md](../spec.md).

## Acceptance (every issue)

- `./gradlew test buildPlugin` passes, with `cyan build` reporting no errors or warnings.
- `TealServerTest` is unchanged, or any change is justified in this file's Comments.
- The ported `.lua` is deleted from `src/main/resources/teal-server/`.

## Comments


**2026-10-12: implemented. The port is complete.**
- `server.tl`: every handler is a `function(params: any): any` that casts its params once to an `lsp` record (`InitializeParams`, `TextDocumentPositionParams`, `ReferenceParams`, `CodeActionParams`, `DidOpen…`/`DidChange…`, `TextDocumentParams`). The runtime shape guards stay, because a cast doesn't validate. Incoming messages are a `Message` record. The `initialize` result (capabilities) stays an untyped table literal: it's written once, and declaring every capability record would add only noise.
- `tl` is loaded through `pcall(require, "tl")` for the error message, then `require("tl")` again (from `package.loaded`) so the rest of the file keeps `tl.tl`'s types.
- **Version check:** `compileTeal` writes `tl_pin.lua` (`return "<tlVersion>"`) after cyan runs, and the committed `src/main/teal/tl_pin.d.tl` declares its type. At startup the server compares version numbers numerically and, if `tl` is older than the pin, writes `tl X is too old, this server needs tl <pin> or newer` and exits 1. Newer versions are accepted. New test `a tl older than the pinned version is refused at startup` uses a fake `tl` module on `LUA_PATH`; it fails if the fake reports the pinned version, and passes with 0.0.1. I also checked by hand against fakes for 0.24.7, 0.9.99, 0.24.8, 0.25.0 and 1.0.0.
- **`TealServerScripts.FILES` is gone.** `compileTeal` writes `files.txt`, listing every `.lua` in the output, and `TealServerScripts` extracts what it lists. A new module no longer needs a Kotlin edit.
- `include_dir` removed from `tlconfig.lua`; `src/main/resources/teal-server/` deleted; the `CompileTeal` inputs no longer include it.
- `TealServerTest` changes: the new version-check test, and the class doc now points at `src/main/teal`.
- Docs: README (the server is written in Teal; minimum `tl` 0.24.8, refused below), `plugin.xml` (both descriptions name the 0.24.8 minimum). These copies of the version are prose, so bumping `tlVersion` means updating them by hand.
- Dogfooding: the built server, run on this repo, reports zero diagnostics on all of `src/main/teal/`, resolving sibling modules through the root `tlconfig.lua`.
- User-visible changes for the next release notes: lex errors show tl's message (issue 03), and `tl` older than 0.24.8 is refused at startup.
