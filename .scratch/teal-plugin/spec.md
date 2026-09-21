# MVP spec: Teal companion plugin for IntelliJ

Status: ready for a build session. Architecture locked in [ADR 0001](../../docs/adr/0001-lsp-client-architecture-for-teal-plugin.md); glossary in [CONTEXT.md](../../CONTEXT.md).

## What this is

A thin IntelliJ companion plugin. It owns no lexer, parser, or PSI. Its only real jobs are (1) registering `.tl` files with a bundled syntax-highlighting grammar and (2) registering `teal-language-server` with LSP4IJ. Everything else — diagnostics, hover, go-to-definition — is LSP4IJ's stock behavior once a server is registered; this was proven live against the real `picolo-rpg` project during charting (see [Prototype the LSP-client route](issues/01-prototype-lsp-client-route.md)).

## Scaffolding

- IntelliJ Platform Plugin Template (Gradle-based), Kotlin, IntelliJ Platform Gradle Plugin 2.x.
- Target IntelliJ 2024.2+ (LSP4IJ's own minimum) and JDK 17+.
- Plugin dependency on LSP4IJ (`com.redhat.devtools.lsp4ij`) — declared as a plugin dependency, not vendored.
- Runtime prerequisite, documented in the plugin's own description/README, not automated in MVP: `teal-language-server` must be on `PATH` (`luarocks install teal-language-server`; needs `cmake` for one of its dependencies — see [Prototype the LSP-client route](issues/01-prototype-lsp-client-route.md) for the exact install path that worked). Bundling the server binary itself is future work, not MVP — packaging a Lua interpreter + LuaRocks binary cross-platform inside a JVM plugin is real effort with no validated need yet.

## Feature 1 + 2 collapse into one mechanism: file recognition and highlighting

Register `vscode-teal`'s TextMate grammar ([`syntaxes/teal.tmLanguage.json`](https://github.com/teal-language/vscode-teal/blob/master/syntaxes/teal.tmLanguage.json), MIT — copy the file plus its license notice into the plugin's resources with attribution) as a bundled TextMate bundle for the `.tl` extension.

This one registration is expected to deliver **both** the file-type/icon association and syntax highlighting — the prototype's "generic icon" caveat happened specifically because we used LSP4IJ's raw file-name-pattern mapping with no TextMate bundle attached; the TextMate bundle registration is what actually claims the file type. Try registering it declaratively via `plugin.xml` first. If IntelliJ's TextMate Bundles plugin has no confirmed `plugin.xml` extension point for bundling a grammar at install time (this was unconfirmed by research at spec time), fall back to documenting a one-time manual step (Settings → Editor → TextMate Bundles → point at the plugin's bundled grammar folder) in the plugin's README — acceptable for MVP since this is for the author's own daily use first, not a stranger's first-run experience. Flag a custom JFlex lexer (referencing [EmmyLua's Apache-2.0 lexer](https://github.com/EmmyLua/IntelliJ-EmmyLua) as a base) as a future upgrade only if the manual step proves annoying in practice.

Do **not** additionally register a full custom `com.intellij.fileType` for `.tl` — LSP4IJ's own guidance is that this can override/lose TextMate-provided coloring. Map `.tl` to the language server by file-name pattern only (see Feature 3).

## Feature 3: language server registration (diagnostics, hover, go-to-definition)

Register `teal-language-server` with LSP4IJ via its language-server extension point (the coded equivalent of the "User-Defined Language Server" Settings UI used in the prototype), mapped to the `*.tl` file-name pattern. Resolve the binary from `PATH` at startup; if not found, surface a clear IDE notification pointing at the install prerequisite rather than failing silently.

No further plugin code is needed for diagnostics, hover, or go-to-definition/type-definition — `teal-language-server` already advertises all of these (`hoverProvider`, `definitionProvider`, `typeDefinitionProvider`) and LSP4IJ serves them automatically once the server is registered, confirmed in the prototype.

**Find Usages — plugin-side exception to "no plugin code needed".** `teal-language-server`'s advertised capabilities (`server_state.lua`) don't include `referencesProvider`, so LSP4IJ's generic Find Usages support (`LSPFindUsagesHandlerFactory`, driven off `textDocument/references`) silently returns nothing — a `teal-language-server` gap, not a plugin bug. Rather than patch that upstream project, this is solved entirely inside the plugin: `TealLanguageServerFactory` wraps the real process in `TealReferencesProxyConnectionProvider` (a `StreamConnectionProvider` adapter) around `TealReferencesProxyCore`, a JSON-RPC man-in-the-middle that (1) patches `referencesProvider: true` into the `initialize` response, and (2) answers `textDocument/references` itself — scanning the document's identifier tokens (`TealIdentifierScanner`, a lightweight lexer, not real parsing) and probing each same-named candidate via the real, already-supported `textDocument/definition`, keeping the ones that resolve to the same declaration as the cursor. Everything else passes through untouched. This is a deliberate, narrow departure from the "thin plugin, zero native intelligence" bet in [ADR 0001](../../docs/adr/0001-lsp-client-architecture-for-teal-plugin.md) — worth calling out there, not just here. Verified against the real server binary (`TealReferencesProxyCoreTest`, plus a standalone JVM harness bypassing the IDE test sandbox): correctly scopes to same-document, excludes/includes the declaration per `includeDeclaration`, doesn't leak into same-named locals in unrelated scopes, and degrades gracefully (no crash, no false matches) on field access, which the scanner doesn't understand structurally.

## Acceptance criteria

Verify against the real `picolo-rpg` project (or any real `.tl` project with a `tlconfig.lua`):

1. Opening a `.tl` file shows a distinct file type/icon (not plain text).
2. The file shows syntax coloring (keywords, strings, types) beyond plain text.
3. Introducing a real type error (e.g. `local x: string = 5`) shows an inline diagnostic with the correct message and range.
4. Hovering a typed symbol shows its type.
5. Go-to-definition on a symbol reference jumps to its declaration.
6. Find Usages on a symbol reference finds its other same-document usages (declaration excluded by default).

## Explicitly out of scope for this MVP

- JetBrains Marketplace packaging/publishing (see map's Out of scope).
- Bundling `teal-language-server` itself — PATH-based resolution only.
- Any JetBrains IDE other than IntelliJ IDEA.
- Native-PSI anything (lexer, parser, PSI, annotator) — rejected architecture, see [ADR 0001](../../docs/adr/0001-lsp-client-architecture-for-teal-plugin.md).
- Completion and signature help polish — LSP4IJ will surface whatever `teal-language-server` provides by default; no plugin-side tuning in MVP.
- Find Usages across files (workspace-wide) — the references proxy (see Feature 3) is deliberately scoped to the current document only, matching how `definitions.tl`'s own symbol lookups are file-local.
