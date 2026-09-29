# Context

Domain glossary for the teal-intellij project: an IntelliJ-family plugin bringing [Teal](https://github.com/teal-language/tl) support to JetBrains IDEs.

## Language

**Teal**
A statically-typed superset of Lua. The language itself — not the plugin, not any tool. Source files use the `.tl` extension.

**`tl`**
The reference Teal compiler and typechecker, shipped as a LuaRocks rock (`luarocks install tl`). It is both a CLI (`tl check` typechecks a file) and a Lua library (`tl.check`, the type report). The [Teal server](#teal-server) uses the library. The CLI doesn't speak the Language Server Protocol, and its diagnostic output is plain line:col text, not machine-friendly JSON (see [teal-language/tl#441](https://github.com/teal-language/tl/issues/441), open/unresolved).

**`cyan`**
An independent Teal build-tool/project manager that depends on `tl` as a library (not a fork of `tl`). `tl`'s own README recommends it for whole-project builds instead of invoking `tl` per file.

**teal-language-server**
An existing third-party Language Server Protocol implementation for Teal ([teal-language/teal-language-server](https://github.com/teal-language/teal-language-server), MIT, LuaRocks-distributed). The companion plugin used to delegate to it. It was replaced by the [Teal server](#teal-server) (see the third amendment to [ADR 0001](docs/adr/0001-lsp-client-architecture-for-teal-plugin.md)), and it is no longer a dependency.
_Avoid_: "the server" (now means the [Teal server](#teal-server)).

**Teal server**
This project's own LSP server: plain Lua on top of the `tl` library, with no other dependency. It ships inside the companion plugin and runs with the user's `lua` interpreter. It provides all of the plugin's language intelligence: diagnostics, hover, go-to-definition (including [require targets](#require-target)), type definition, completion, signature help, Find Usages and the [missing-require quick fix](#missing-require-quick-fix).
_Avoid_: "teal-language-server" (the third-party server it replaced), "the LSP" (a protocol, not a program).

**`tl` env**
The Teal server's single `tl` environment, which every open document is checked against. It holds the project's predefined globals and caches every required module as it was on disk. It is rebuilt when any `.tl` file is saved, or after a fixed number of edits.

**LSP4IJ**
An existing, free/OSS JetBrains-family plugin ([redhat-developer/lsp4ij](https://github.com/redhat-developer/lsp4ij)) that lets any IntelliJ-platform IDE — including Community Edition — act as a generic LSP client. All IDE-side UX for the [Teal server](#teal-server)'s features is LSP4IJ's stock UX. An external dependency, not this project's own code.

**companion plugin**
This project's actual deliverable: an IntelliJ plugin that registers `.tl` files, bundles the [TextMate route](#textmate-route) for highlighting, and bundles and launches the [Teal server](#teal-server) through LSP4IJ. It has no custom PSI, lexer or parser. All language intelligence lives in the Teal server, as LSP answers.
_Avoid_: "the plugin" (ambiguous — could misread as the rejected native-PSI shape), "the IntelliJ plugin."

**LSP-client architecture**
The locked architectural shape of the companion plugin — see [ADR 0001](docs/adr/0001-lsp-client-architecture-for-teal-plugin.md). Contrast with the rejected native-PSI architecture.

**native-PSI architecture** *(rejected — out of scope, see the map's Out of scope section and [ADR 0001](docs/adr/0001-lsp-client-architecture-for-teal-plugin.md))*
The alternative shape considered and ruled out: a plugin implementing its own JFlex lexer, Grammar-Kit PSI/parser, and annotator, independent of any LSP server. Kept here only so the term is recognizable if it resurfaces; it is not being built.

**TextMate route**
The chosen mechanism for `.tl` syntax highlighting: reusing `vscode-teal`'s MIT-licensed grammar (`syntaxes/teal.tmLanguage.json`) via IntelliJ's bundled TextMate Bundles support, since neither teal-language-server nor the Teal server provides semantic tokens to drive LSP-based highlighting instead.
_Avoid_: "the grammar" alone (ambiguous with Teal's own language grammar).

**MVP surface**
The feature set that delivers real daily-use value for the locked LSP-client architecture: `.tl` file-type association, syntax highlighting (via the TextMate route), inline diagnostics, hover, and go-to-definition. Distinct from a **publishable release** (JetBrains Marketplace-ready — polish, docs, versioning), which is an eventual goal but explicitly out of scope for the MVP surface.

**LSP proxy** *(retired)*
The companion plugin's former man-in-the-middle between LSP4IJ and teal-language-server. It answered the requests that server lacked (references, code actions, require targets). Its features moved into the [Teal server](#teal-server), and it was deleted (see the third amendment to [ADR 0001](docs/adr/0001-lsp-client-architecture-for-teal-plugin.md)).
_Avoid_: "references proxy" (its original name).

**require**
Teal's only way to bring another module into scope; Teal has no `import` statement. A **bare require** (`require("src.entities.player")`, result unassigned) is loaded only for its side effect of declaring **global** types and values.
_Avoid_: "import" when precision matters (acceptable in casual UI wording).

**module name**
The dotted string passed to `require`, derived from a `.tl` file's path relative to the workspace root (`src/entities/player.tl` → `src.entities.player`).

**require target**
The workspace file a **require**'s module name resolves to. Go-to-definition on the require's string literal opens it. A module with no require target in the workspace (standard library, LuaRocks dependencies) has nothing to open.
_Avoid_: "import target," "module file" (ambiguous with any `.tl` file).

**missing-require quick fix**
The Alt+Enter fix offered on an `unknown type X` diagnostic: it adds a bare require of each module that declares a global `X`. It does not handle local, module-returned types; bringing those into scope would mean rewriting the usage, not just adding a require.
_Avoid_: "auto-import" (suggests IDE-wide import management that doesn't exist here).
