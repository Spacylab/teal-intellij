# Spec: better `.tl` highlighting

Status: ready for a build session. Glossary in [CONTEXT.md](../../CONTEXT.md) (**TextMate route**, **semantic highlighting**). Decision recorded as the fourth amendment to [ADR 0001](../../docs/adr/0001-lsp-client-architecture-for-teal-plugin.md).

## Problem

A `.tl` file is far less colorful than the same code compiled to Lua and opened with the Lua plugin. Today only keywords, numbers, strings and comments get color. Declared function names, field access, type annotations (`: number`) and the local/parameter/global distinction are all plain text.

Reference comparison: `getCenteredRowRect` in picolo-rpg, in Teal vs. its compiled Lua. In the Lua version, the declared function name is a function color, `.graphics` is a field color, locals and the global `love` have their own style, and parameters are plain.

## Goal

`.tl` files look at least as rich as the equivalent Lua, plus color for Teal-only syntax (types).

## Decisions (settled, don't re-open)

- **Two layers.** The bundled TextMate grammar is the instant, text-only base. Semantic highlighting from the Teal server (`textDocument/semanticTokens`) refines it with scope-aware classification.
- **Colors come from the IDE's standard Language Defaults keys** (function declaration, local variable, parameter, global variable, instance field, class/type name, constant, predefined/default-library symbol), through a small `SemanticTokensColorsProvider` registered for the Teal server (LSP4IJ's stock mapping has no global-variable key and treats every plain variable as a reassigned local; found while implementing 02). No Teal-specific color settings page. Nothing hard-coded (e.g. no forced underline): whatever the user's scheme does for Lua, Teal inherits.
- **Classified identifiers:** locals, parameters, globals, fields/methods, function declarations, type names (annotations, `record`/`enum`/`interface` names, generic arguments).
  - `<const>` locals: **Constant**. `<close>` locals: plain local.
  - Standard-library names (`print`, `string`, `math.floor`, …): **default library** style, distinct from user globals.
  - A local holding a `require(...)` result stays a plain **local** (no module styling).
- **Types use standard keys even though default schemes leave them plain** (decided while implementing 01): user types → *Class name*, built-in types → *Predefined symbol*. In IntelliJ's default Dark, Darcula and Light schemes both inherit the plain identifier color (Light makes predefined symbols italic), just as in Kotlin and Java. Users who want colored types set *Language Defaults → Classes → Class name* once.
- **Broken code.** Classify whatever `tl` can still make sense of; leave the rest to TextMate. If `tl` yields nothing for the file, keep the last good classification shifted to follow the edits, rather than dropping colors (no flicker).
- **The vendored grammar is edited in place** (scopes renamed/added so IntelliJ colors them), with the changes listed in a note next to `LICENSE-vscode-teal`. Upstreaming to `vscode-teal` is optional, later, and not a blocker.
- **One release, 0.6.0**, built as separate commits per issue.

## Issues

1. [01 — TextMate grammar scopes](issues/01-textmate-grammar-scopes.md)
2. [02 — Semantic highlighting in the Teal server](issues/02-semantic-highlighting.md)
3. [03 — Release 0.6.0](issues/03-release-0-6-0.md)
