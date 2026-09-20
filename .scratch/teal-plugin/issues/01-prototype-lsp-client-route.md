Type: prototype
Status: resolved

## Question

Build a minimal working prototype of the **LSP-client architecture**: a thin companion IntelliJ plugin (or, if possible, a no-code LSP4IJ "User-Defined Language Server" configuration) that wires LSP4IJ to the existing `teal-language-server` binary, targeting the real [picolo-rpg](../../../../picolo-rpg) project on disk.

Validation bar (settled during charting — see map Notes): the prototype only needs to prove two things end-to-end, not the full MVP surface:

1. **`.tl` file-type association** — IntelliJ recognizes `.tl` files as Teal (not plain text), with at least a distinct icon/file type, against picolo-rpg's real sources (`src/main.tl`, `src/conf.tl`, `love.d.tl`, etc.).
2. **Inline diagnostics** — LSP4IJ successfully launches `teal-language-server` as a stdio LSP process (research confirmed it's a standalone binary via `luarocks install teal-language-server`, no Neovim coupling required) against picolo-rpg (which has its own `tlconfig.lua`), and a real type error shows up as an inline diagnostic in the IntelliJ editor.

Syntax highlighting and go-to-definition are explicitly **not** required from this prototype — they're deferred to the MVP spec once the architecture is locked.

Per charting: small patches or a thin stdio wrapper around `teal-language-server` are in scope if needed to make it behave as a well-formed LSP server for LSP4IJ. A fork or major rewrite of `teal-language-server` is out of scope for this ticket — that outcome should be reported as a failure, not worked around, since it would change the answer to the architecture decision (see [Lock the architecture decision](02-lock-architecture-decision.md)).

Report back: what worked, what didn't, any patches/wrapper code needed and where they live, and a clear recommendation (works cleanly / works with caveats / doesn't work) for the architecture decision ticket to consume.

## Answer

**Verdict: works, with two caveats — neither a dealbreaker.** No patch or wrapper around `teal-language-server` was needed; the only environment gap was a missing build tool (`cmake`), a one-time local install.

**What worked:**

- `luarocks install teal-language-server` succeeded cleanly after installing `cmake` (`brew install cmake`) — one of its dependencies (`luv`) needs it to build. No other missing prerequisites.
- Confirmed via a scripted raw LSP session ([lsp_probe.py](prototypes/lsp-client-route/lsp_probe.py)) that `teal-language-server` is a genuine standalone stdio LSP server — no Neovim coupling, handshakes correctly, and publishes proper `textDocument/publishDiagnostics` notifications with correct ranges and messages.
- Configured LSP4IJ's no-code "User-Defined Language Server" (Settings → Languages & Frameworks → Language Servers → `+`) pointing at the installed binary, mapped to `*.tl`, against the real `picolo-rpg` project. Opened `src/conf.tl`, introduced a real type error (`local x: string = 5`), and it showed up inline with the correct message (`in local declaration: x: got integer, expected string`) — confirmed by the user directly in IntelliJ IDEA 2026.2, LSP4IJ 0.21.0. **Diagnostics criterion: met.**

**Caveat 1 — `.tl` file-type/icon**: the no-code file-name-pattern mapping is enough to route `.tl` files to the language server (diagnostics work), but the editor tab still shows a generic file icon, not a distinct Teal one — confirms the research prediction that a proper `.tl` FileType (icon, recognized language) needs a small companion plugin. This isn't a failure of the route; it's already accounted for as its own MVP feature line item in [Write the MVP spec](02-lock-architecture-decision.md) → [Write the MVP spec](03-write-mvp-spec.md).

**Caveat 2 — LSP4IJ has an active, unrelated bug**: closing the Language Servers settings dialog after saving threw `RuntimeExceptionWithAttachments: Access from Event Dispatch Thread (EDT) is not allowed` (full trace in ticket comments/session log) — a threading bug in LSP4IJ's own `JsonTextField`/`SchemaBackedJsonTextField` dispose path, not in `teal-language-server` or anything we wrote. IntelliJ stayed responsive and the settings persisted correctly (`disposeUIResources()` fires after `apply()`), so this was cosmetic in practice. It's a known *class* of bug in LSP4IJ ([lsp4ij#696](https://github.com/redhat-developer/lsp4ij/issues/696) is the same code path fixed a year ago; [lsp4ij#1672](https://github.com/redhat-developer/lsp4ij/issues/1672), filed the same day as this test, shows the class is still live on 0.21.0/IntelliJ 2026.2.2). Worth being aware of, not worth blocking on — it's an upstream LSP4IJ maturity risk to track, not an architecture blocker.

**One investigation detour worth recording**: an early test using `t.window.resizable = "not a boolean"` (a nested record-field mutation) produced zero diagnostics, initially looking like a `teal-language-server` bug. Isolated with [tl_check_probe.lua](prototypes/lsp-client-route/tl_check_probe.lua) and cross-checked against `cyan check` directly (bypassing the LSP entirely) — same result both ways. Root cause: `picolo-rpg`'s `love.d.tl` types `Configuration`/`WindowSetting` as Teal `interface`s, and `tl` 0.24.8 doesn't check field mutations through interface-typed values as strictly as top-level declarations. This is a `tl`/Teal-language characteristic that would affect a native-PSI route identically (it would also shell out to `tl`/`cyan`), so it has no bearing on the architecture decision — noted here so it isn't re-discovered as a surprise later.

**Recommendation for [Lock the architecture decision](02-lock-architecture-decision.md): lock LSP-client.** Prototype scripts captured as throwaway artifacts at [.scratch/teal-plugin/prototypes/lsp-client-route/](prototypes/lsp-client-route/).
