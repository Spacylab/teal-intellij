Type: task
Status: resolved
Blocked by: 07

## What

Port `features` (hover, definition, type definition, completion, signature help, references, code actions) onto the LSP records and `tl.tl` types. See [spec.md](../spec.md).

## Acceptance (every issue)

- `./gradlew test buildPlugin` passes, with `cyan build` reporting no errors or warnings.
- `TealServerTest` is unchanged, or any change is justified in this file's Comments.
- The ported `.lua` is deleted from `src/main/resources/teal-server/`.

## Comments


**2026-10-12: implemented.**
- `lsp.tl` gained the records the handlers return or read: `Hover`, `MarkupContent`, `CompletionItem`, `SignatureHelp`, `SignatureInformation`, `ParameterInformation`, `CodeAction`, `CodeActionContext`, `WorkspaceEdit` and `TextEdit`. Each handler's signature now states its LSP result type.
- `code_action` still checks `context`'s shape at runtime (`type(context) ~= "table"`, `type(only)`, `type(diagnostic.message)`), because the dispatcher passes `params.context` straight from the client. Teal accepts `type(...)` comparisons on typed values without narrowing, so the guards stay as they were. Issue 09 casts payloads in the dispatcher; these guards can stay, since a cast doesn't validate.
- **No behaviour change, no bugs found.** The only edit the checker asked for: a loop variable named `arg` shadowed Lua's global `arg` (a `redeclaration` warning, which is an error here) and is now `param`. The rest of the generated-Lua diff is line wrapping. `sorted_keys` is generic over the map's values.
- Only `server.lua` remains in `src/main/resources/teal-server/`.
