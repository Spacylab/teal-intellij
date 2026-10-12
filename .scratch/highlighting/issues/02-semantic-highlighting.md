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

**2026-10-12: implemented, not yet committed. Manual IDE check still to do.**
- `teal-server/semantic.lua` answers `textDocument/semanticTokens/full`. It re-parses the document's tokens and records tl's parser node kinds by position, which separates value names (`variable`), fields and declared names (`identifier`), parameters (`argument`) and type annotations (a `nominal` type at the first name). Values are then resolved through `symbols_by_file` (via `lookup.declaration`, which now also returns the symbol index), the globals, and field types.
- **LSP4IJ 0.21.0's stock mapping doesn't fit the spec.** It has no global-variable key, it maps a plain `variable` to *Reassigned local variable*, and it honors `defaultLibrary` only on functions. So the plugin registers `TealSemanticTokensColorsProvider` (LSP4IJ's `semanticTokensColorsProvider` extension point, keyed by server id) with this table:

  | Token | Key |
  |---|---|
  | any + `defaultLibrary` | Predefined symbol |
  | `parameter` | Parameter |
  | `variable` + `global` | Global variable |
  | `variable` + `readonly` (`<const>`) | Constant |
  | `variable` | Local variable |
  | `property` | Instance field |
  | `function` / `method` + `declaration` | Function declaration |
  | `function` / `method` | Function call |
  | `interface` | Interface name |
  | `class` / `enum` / `type` | Class name |

  `global` is a custom modifier that only our provider reads.
- `getCenteredRowRect` in picolo-rpg classifies exactly like the compiled-Lua screenshot: the declared name is a function declaration, parameters are parameters, locals are locals, `love` is a global, `graphics` is a field, `getDimensions()` is a function call.
- Performance on picolo-rpg's `main.tl` (1,655 lines, 4,041 identifiers classified): 17 ms per request (about 5 ms re-parse, 7 ms scope lookups, 5 ms node walk), against about 40 ms for the `tl` check of the same file on the same machine. That meets the 20 ms target, though not with much room.
- The last-good fallback only kicks in when nothing at all could be classified. LSP4IJ doesn't shift a stale result to follow edits, so the "shifted" part of the spec isn't implemented; with `tl`'s error-tolerant parser the fallback is rarely needed.
- Not classified (left to TextMate): generic type parameters (`<T>`), parameter names inside function *types*, built-in type names.
