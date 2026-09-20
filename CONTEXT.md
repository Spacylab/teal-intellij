# Context

Domain glossary for the teal-intellij project: an IntelliJ-family plugin bringing [Teal](https://github.com/teal-language/tl) support to JetBrains IDEs.

## Terms

**Teal**
A statically-typed superset of Lua. The language itself — not the plugin, not any tool. Source files use the `.tl` extension.

**`tl`**
The reference Teal compiler and typechecker CLI. Runs `tl check` to typecheck a file. Does not speak the Language Server Protocol; its diagnostic output is plain-text, line:col-based, not machine-friendly JSON (see [teal-language/tl#441](https://github.com/teal-language/tl/issues/441), open/unresolved).

**`cyan`**
A separate Teal build-tool CLI, commonly used as a stricter/friendlier `tl check` wrapper in real projects. Relationship to `tl` (fork vs. wrapper vs. independent build tool) not yet verified — treat as unconfirmed until checked.

**teal-language-server**
An existing, actively-maintained third-party Language Server Protocol implementation for Teal ([teal-language/teal-language-server](https://github.com/teal-language/teal-language-server), MIT, LuaRocks-distributed). Supports diagnostics, hover, completion, go-to-definition. Its documented client today is Neovim via `lspconfig`. **Not** part of this project — an external dependency this project may or may not delegate to. Never call it "the plugin" or "the LSP4IJ plugin."

**LSP4IJ**
An existing, free/OSS JetBrains-family plugin ([redhat-developer/lsp4ij](https://github.com/redhat-developer/lsp4ij)) that lets any IntelliJ-platform IDE — including Community Edition — act as a generic LSP client, driven from Settings UI with no required plugin code for the base wiring. An external dependency, not this project's own code.

**the plugin** *(this project's deliverable — working name until one is chosen)*
The IntelliJ-family plugin this effort is scoping. Always distinguish it explicitly from `teal-language-server` and from LSP4IJ, both of which it may depend on but did not create.

**LSP-client architecture** *(one candidate shape for the plugin)*
The plugin is a thin companion around LSP4IJ, delegating all language intelligence (diagnostics, hover, completion, go-to-definition) to `teal-language-server`. The plugin's own code is limited to packaging/wiring — e.g. `.tl` file-type/icon registration — not language analysis.

**native-PSI architecture** *(the other candidate shape for the plugin)*
The plugin implements its own JFlex lexer, Grammar-Kit PSI/parser, and annotator, independent of any LSP server. Type diagnostics would still likely shell out to `tl`/`cyan` rather than reimplementing the type checker, but structure (PSI tree, navigation) is IntelliJ-native.

**MVP surface**
The feature set that validates whichever architecture is chosen and delivers real daily-use value: `.tl` file-type association, syntax highlighting, inline diagnostics, go-to-definition. Distinct from a **publishable release** (JetBrains Marketplace-ready — polish, docs, versioning), which is an eventual goal but explicitly out of scope for the MVP surface.
