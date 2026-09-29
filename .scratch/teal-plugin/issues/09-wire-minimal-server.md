Type: task
Status: ready-for-agent
Blocked by: 06, 07, 08

## What

Switch the plugin from teal-language-server plus the LSP proxy to the minimal server (ADR 0001, third amendment). Move the proxy's three features into the server, then delete the proxy.

## Decisions (settled, don't re-open)

- **Behavior of the moved features is unchanged.** Tickets 04 and 05 define them:
  - Find Usages stays current-document only.
  - The missing-require quick fix still scans the workspace for `global record|enum|interface|type X` declarations, skipping hidden directories and the current file.
  - Require targets still resolve relative to the workspace root, trying `.tl`, `.d.tl`, `/init.tl`, `.lua` in that order.
- **References use the server's own definition logic** instead of one definition request per token: a same-named identifier is a reference when it resolves to the same place.
- **Listing workspace files uses `find`** (`dir /s /b` on Windows) through `io.popen`, because plain Lua can't list directories.
- **Launching the server:**
  - The plugin runs `lua <scripts>/server.lua`, with the project as the working directory.
  - The scripts are copied to a fresh temporary directory at each start and deleted at stop.
  - `lua` is found on PATH, falling back to a login shell, the same way `teal-language-server` was found.
- **Checking for `tl` before starting.** If `lua` can't load `tl`, the plugin shows a notification that says to run `luarocks install tl`. First it retries with `LUA_PATH` and `LUA_CPATH` taken from a login shell, which covers `luarocks --local` installs when the IDE is launched from the Dock.
- **The LSP4IJ server id stays `tealLanguageServer`**, so users' existing LSP4IJ settings keep applying.

## Acceptance

- The proxy's integration cases are ported to `TealServerTest` and pass against the Lua server: references, code actions, require targets.
- Removed: `TealLspProxy`, `TealLspProxyConnectionProvider`, `TealIdentifierScanner`, `TealMissingRequire`, `TealRequireTarget` and their tests.
- The plugin builds (`./gradlew buildPlugin`), and the full test suite passes.
- CONTEXT.md, plugin.xml and the README describe the new setup.
- Manual check in the IDE on picolo-rpg: diagnostics, hover, Cmd+Click (identifier and require), Find Usages, the missing-require quick fix, completion.

## Comments

**2026-09-29: implemented, not yet committed. Manual IDE check still to do.**
- The proxy's features are now in the server:
  - `requires.lua` holds the missing-require scan and require-target resolution;
  - `features.lua` gained `references`, `code_action` and require targets in `definition`.
- References reuse `definition_at` for every same-named identifier. Declarations are snapped to the declared name (tl records `local function f` at `local`), so go-to-definition lands on the name too.
- Launch path:
  - `TealLuaRuntime` finds `lua` on PATH or via a login shell, checks that `tl` loads, and retries with the login shell's `LUA_*` variables;
  - `TealServerConnectionProvider` subclasses LSP4IJ's `OSProcessStreamConnectionProvider`, extracts the scripts per start and deletes them on stop.
- Deleted: the proxy, its connection provider, `TealIdentifierScanner`, `TealMissingRequire`, `TealRequireTarget` and their tests. `LspFraming` moved to the test sources, and Gson is now test-only, so the plugin jar is smaller.
- Tests (41, none skipped), all passing:
  - `TealServerTest` (39) includes the ported proxy integration cases.
  - `TealServerLuaTest` runs `requires_test.lua` (20 checks), which ports the Kotlin unit tests for the scan and the require-target rules.
  - `TealServerConnectionProviderTest` covers the real launch path headlessly: start, `initialize`, and cleanup on stop.
- The references field-access case now expects real results: `p.x` finds the other `p.x`, but not `o.x` of another record type. The proxy could only return nothing there.
- Updated CONTEXT.md, plugin.xml and README. The LSP4IJ server id stays `tealLanguageServer`, with the display name now "Teal".
- Still to do:
  - the manual IDE check on picolo-rpg (`./gradlew runIde`);
  - a version bump and change notes.
