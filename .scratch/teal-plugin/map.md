Label: wayfinder:map

## Destination

A locked architecture decision — **LSP-client** (LSP4IJ wrapping the existing `teal-language-server`) vs. **native-PSI** (full custom lexer/PSI/parser plugin) — validated by a working prototype, plus a scoped MVP spec (`.tl` file-type association, syntax highlighting, inline diagnostics, go-to-definition) ready to hand to a build session. Marketplace publishing is an eventual goal for the project, not a requirement of this map.

## Notes

- Domain vocabulary: see [CONTEXT.md](../../CONTEXT.md) (Teal, `tl`, `cyan`, teal-language-server, LSP4IJ, "the plugin", LSP-client architecture, native-PSI architecture, MVP surface).
- Validation bar for the architecture decision (settled during charting): prove `.tl` file-type association + inline diagnostics end-to-end. Syntax highlighting and go-to-definition are deferred to the MVP spec, not required to decide the architecture.
- Order of investigation: LSP-client first (cheaper, currently favored). Native-PSI is only prototyped if LSP-client genuinely fails — not built in parallel.
- Patch tolerance: small patches or a thin stdio wrapper around `teal-language-server` are in scope. A fork/major rewrite of it counts as failure and should push the decision toward native-PSI.
- Test bed: the user's real [picolo-rpg](../../../picolo-rpg) project (confirmed on disk: `.tl` sources under `src/`, `love.d.tl`, `tlconfig.lua`), on the user's current IntelliJ IDEA (latest version). No other JetBrains IDEs required for MVP.
- Research findings already gathered while charting (facts, not decisions — re-verify if anything here turns out stale):
  - `teal-language-server` (github.com/teal-language/teal-language-server) is a standalone Lua binary installed via `luarocks install teal-language-server`, communicates over stdio as a plain LSP server — no Neovim coupling. Depends on `tl` as a pinned library version, not by shelling out. Supports diagnostics, hover, completion, go-to-definition. MIT, actively maintained.
  - LSP4IJ (redhat-developer/lsp4ij) works on IntelliJ Community Edition (not Ultimate-gated), needs IntelliJ 2024.2+/JDK17+, and has a no-code "User-Defined Language Server" path (start command, file-name-pattern mapping). A real custom `.tl` FileType/icon likely still needs a thin companion plugin.
  - `vscode-teal` does **not** reuse `teal-language-server` — it's a separate, self-implemented Node.js LSP server that shells out to `tl check`. Not directly reusable as reference for this effort's LSP-client route, beyond general precedent.
  - `cyan` is an independent Teal project/build tool that depends on `tl` as a library (not a fork of `tl`).
  - No existing "Teal for IntelliJ" plugin found anywhere — this is fresh ground.
- Session default: call the Skill tool for "grilling" and "domain-modeling" when in doubt, per the wayfinder skill.
- Local environment (from the prototype): `teal-language-server` installs via `luarocks install teal-language-server`, but needs `cmake` on `PATH` first (a `luv` build dependency) — both now installed on the dev machine.
- LSP4IJ 0.21.0 has an active, unrelated threading bug (EDT violation on Settings dialog close, cosmetic — settings still persist). Known bug class upstream, not yet fixed as of 2026-09-20. Worth keeping an eye on for the MVP spec/build work, not a blocker.

## Decisions so far

- [Prototype the LSP-client route](issues/01-prototype-lsp-client-route.md): works, with two non-blocking caveats — `.tl` file-type association needs a companion plugin for a proper icon (diagnostics work fine without one), and LSP4IJ 0.21.0 has an active, unrelated EDT-threading bug on save (cosmetic, settings persist). No patch to `teal-language-server` was needed. Recommends locking LSP-client.

## Not yet specified

- Native-PSI prototype — only graduates into a ticket if [Lock the architecture decision](issues/02-lock-architecture-decision.md) concludes LSP-client failed.
- MVP spec details for syntax highlighting and go-to-definition specifically (deferred past the architecture decision; the spec ticket will flesh these out once written).
- Plugin naming, distribution scope beyond IntelliJ IDEA, and licensing — none of these block the architecture decision or MVP spec, revisit once those are settled.
- Whether `tl check` or `cyan check` is the right diagnostics driver if native-PSI is ever needed (moot under LSP-client, since `teal-language-server` handles this internally).

## Out of scope

- JetBrains Marketplace publishing / release packaging — the project's eventual goal, but this map ends at a spec, not a shipped release. Revisit as a fresh effort once the MVP is built.
