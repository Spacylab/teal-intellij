Type: grilling
Status: resolved

Blocked by: 02

## Question

For the locked architecture (see [Lock the architecture decision](02-lock-architecture-decision.md)), write the MVP spec: `.tl` file-type association, syntax highlighting, inline diagnostics, and go-to-definition, scoped for a build session to pick up directly. Targets IntelliJ IDEA (latest version), no other JetBrains IDEs required for MVP. Marketplace packaging/publishing is explicitly out of the MVP spec (see map's Out of scope).

## Answer

Spec written: [spec.md](../spec.md). Hover was added to the MVP surface (confirmed free from the same LSP4IJ registration as diagnostics/go-to-definition, no reason to exclude it).

Key calls made while writing it:

- **File-type association and syntax highlighting collapse into one mechanism**: bundling `vscode-teal`'s MIT-licensed TextMate grammar (`syntaxes/teal.tmLanguage.json`) is expected to deliver both the distinct `.tl` icon *and* highlighting, since the prototype's "generic icon" caveat was a side effect of using a bare file-name-pattern mapping with no TextMate bundle attached — not a hard limit. Per LSP4IJ's own guidance, the spec explicitly avoids registering a separate custom `com.intellij.fileType`, since that would override TextMate-provided coloring.
- **Highlighting has a documented fallback**: whether a plugin can register a TextMate bundle declaratively via `plugin.xml` was unconfirmed by research at spec time. The spec asks the build session to try that first and falls back to a one-time manual Settings step (MVP-acceptable for personal daily use) rather than blocking on it — with a custom JFlex lexer flagged as a future upgrade only if that manual step proves annoying.
- **`teal-language-server` distribution stays out of MVP**: PATH-based resolution only; bundling the binary itself is real, unvalidated effort deferred to future work.
- Diagnostics, hover, and go-to-definition need no plugin code beyond registering the server with LSP4IJ — all three were already proven working together in the prototype.

Also did a final domain-modeling pass: [CONTEXT.md](../../../CONTEXT.md) now uses **companion plugin** as the canonical term (replacing the placeholder "the plugin," to stay unambiguous now that native-PSI is rejected) and adds **TextMate route** as the canonical name for the highlighting mechanism. Wrote [ADR 0001](../../../docs/adr/0001-lsp-client-architecture-for-teal-plugin.md) recording the LSP-client-over-native-PSI decision — it passed all three tests (hard to reverse, surprising without the prototype's context, a real evaluated trade-off).

This closes the map: destination reached (architecture locked, MVP spec written and ready for a build session).
