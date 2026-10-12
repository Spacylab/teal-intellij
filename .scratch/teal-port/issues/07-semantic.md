Type: task
Status: resolved
Blocked by: 06

## What

Port `semantic` against the type report. Keep the token legend and the encoding byte-identical: the semantic highlighting tests must pass unchanged. See [spec.md](../spec.md).

## Acceptance (every issue)

- `./gradlew test buildPlugin` passes, with `cyan build` reporting no errors or warnings.
- `TealServerTest` is unchanged, or any change is justified in this file's Comments.
- The ported `.lua` is deleted from `src/main/resources/teal-server/`.

## Comments


**2026-10-12: implemented.**
- The legend's token types are a `semantic.TokenType` enum. Every classification path returns one, and the index table is `{TokenType:integer}`, so a misspelled token type fails the build. The legend order and encoding are unchanged; the semantic tests in `TealServerTest` pass unmodified.
- **tl keeps `NodeKind` and `TypeName` private** (its exported `Node` is an opaque interface), so the AST node kinds this module tests (`argument`, `variable`, `literal_table_item`, …) can't be checked against tl. They're a local `NodeKind` enum instead, so they're at least one list, and a typo fails the build (verified with `k.argumnt`). If tl renames a node kind, nothing catches it at build time; the semantic tests in `TealServerTest` remain the guard.
- The AST walk stays untyped (`{any:any}`, with a `NodeView` cast for the four fields it reads).
- `pcall(tl.parse_program, …)` keeps the `Node` type for its second value, so the non-table guard (needed only when it returns an error) goes through `as any`.
- No behaviour change, no bugs found. The diff of the generated Lua is mechanical: a separate metatable for `Classifier`, renamed locals in the walk, and line wrapping.
