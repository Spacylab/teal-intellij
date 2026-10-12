Type: task
Status: ready-for-agent

## What

Add semantic highlighting to the Teal server: advertise `semanticTokensProvider` and answer `textDocument/semanticTokens/full` (plus `/delta` or `/range` only if LSP4IJ needs them for responsiveness) from `tl`'s type report. See [spec.md](../spec.md) for the classification rules.

## Decisions (settled, don't re-open)

- Classification source is what the server already has: the document's tokens, `symbols_by_file` declarations, `tl.symbols_in_scope`, and the env's globals. No new dependency, no text-level guessing where the type report knows.
- Use standard LSP token types/modifiers only, chosen so LSP4IJ maps them onto the Language Defaults keys from the spec: `function` (+ `declaration`), `method`, `parameter`, `variable` (+ `readonly` for `<const>`), global variables, `property`, `type` / `class` / `enum` / `interface`, `defaultLibrary` for the standard library. Check LSP4IJ 0.21.0's actual mapping (`SemanticTokensHighlightingColors` or equivalent) and pick types that land on the intended keys; note the final table in Comments.
- `<close>` locals and `require` result locals are plain `variable`.
- Broken code: return tokens for whatever `tl` could parse/check. If nothing usable, return the last good result for the document (LSP4IJ's re-request shifts it); never return an empty set just because the file has errors.
- Once shipped, add semantic highlighting to the **Teal server** entry's feature list in CONTEXT.md 

## Acceptance

- `TealServerTest` (raw-stdio integration) covers: the capability is advertised; a fixture with a local, a parameter, a user global, a stdlib global, a field, a method call, a function declaration, a `<const>` local, a type annotation, and a record name yields the expected token type for each; a file with a syntax error still yields tokens for its valid part.
- Performance: full tokens for picolo-rpg's `main.tl` in well under the per-edit check time (target < 20 ms in-process).
- Manual check in the IDE on picolo-rpg: `getCenteredRowRect` looks at least like the compiled-Lua screenshot in the spec, plus colored types. No flicker while typing an incomplete line.
- `./gradlew buildPlugin` and the full test suite pass.

## Comments
