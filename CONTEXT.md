# Context

Domain glossary for the teal-intellij project: an IntelliJ-family plugin bringing [Teal](https://github.com/teal-language/tl) support to JetBrains IDEs.

## Language

**Teal**
A statically-typed superset of Lua. The language itself — not the plugin, not any tool. Source files use the `.tl` extension.

**`tl`**
The reference Teal compiler and typechecker CLI. Runs `tl check` to typecheck a file. Does not speak the Language Server Protocol; its diagnostic output is plain-text, line:col-based, not machine-friendly JSON (see [teal-language/tl#441](https://github.com/teal-language/tl/issues/441), open/unresolved).

**`cyan`**
An independent Teal build-tool/project manager that depends on `tl` as a library (not a fork of `tl`). `tl`'s own README recommends it for whole-project builds instead of invoking `tl` per file.

**teal-language-server**
An existing, actively-maintained third-party Language Server Protocol implementation for Teal ([teal-language/teal-language-server](https://github.com/teal-language/teal-language-server), MIT, LuaRocks-distributed). A standalone stdio LSP binary — no Neovim coupling, despite Neovim/`lspconfig` being its most commonly documented client. Supports diagnostics, hover, completion, go-to-definition, type-definition. **Not** part of this project — an external dependency the companion plugin delegates to.
_Avoid_: "the plugin," "the LSP4IJ plugin" (both mean [companion plugin](#companion-plugin), a different thing).

**LSP4IJ**
An existing, free/OSS JetBrains-family plugin ([redhat-developer/lsp4ij](https://github.com/redhat-developer/lsp4ij)) that lets any IntelliJ-platform IDE — including Community Edition — act as a generic LSP client. Configured from Settings UI with no plugin code required for the base wiring (diagnostics, hover, go-to-definition all ride on this once a server is registered). An external dependency, not this project's own code.

**companion plugin**
This project's actual deliverable: a thin IntelliJ plugin whose own code is limited to `.tl` file-type registration and bundling the [TextMate route](#textmate-route) for highlighting. It has no custom PSI, lexer, or parser of its own — all language intelligence (diagnostics, hover, completion, go-to-definition, type-definition) is delegated to LSP4IJ + teal-language-server via configuration, not code the companion plugin owns.
_Avoid_: "the plugin" (ambiguous — could misread as the rejected native-PSI shape), "the IntelliJ plugin."

**LSP-client architecture**
The locked architectural shape of the companion plugin — see [ADR 0001](docs/adr/0001-lsp-client-architecture-for-teal-plugin.md). Contrast with the rejected native-PSI architecture.

**native-PSI architecture** *(rejected — out of scope, see the map's Out of scope section and [ADR 0001](docs/adr/0001-lsp-client-architecture-for-teal-plugin.md))*
The alternative shape considered and ruled out: a plugin implementing its own JFlex lexer, Grammar-Kit PSI/parser, and annotator, independent of any LSP server. Kept here only so the term is recognizable if it resurfaces; it is not being built.

**TextMate route**
The chosen mechanism for `.tl` syntax highlighting: reusing `vscode-teal`'s MIT-licensed grammar (`syntaxes/teal.tmLanguage.json`) via IntelliJ's bundled TextMate Bundles support, since `teal-language-server` has no `semanticTokensProvider` to drive LSP-based highlighting instead.
_Avoid_: "the grammar" alone (ambiguous with Teal's own language grammar).

**MVP surface**
The feature set that delivers real daily-use value for the locked LSP-client architecture: `.tl` file-type association, syntax highlighting (via the TextMate route), inline diagnostics, hover, and go-to-definition. Distinct from a **publishable release** (JetBrains Marketplace-ready — polish, docs, versioning), which is an eventual goal but explicitly out of scope for the MVP surface.
