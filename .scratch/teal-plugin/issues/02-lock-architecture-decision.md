Type: grilling
Status: resolved

Blocked by: 01

## Question

Given the findings from [Prototype the LSP-client route](01-prototype-lsp-client-route.md), lock the architecture decision: **LSP-client** (LSP4IJ wrapping `teal-language-server`) vs. **native-PSI** (full custom lexer/PSI/parser plugin).

Per charting: if the prototype worked cleanly or with only small patches/a thin wrapper, lock LSP-client. If it genuinely failed (e.g. `teal-language-server` would need a fork/major rewrite to behave, or LSP4IJ can't deliver a workable `.tl` file-type experience), this ticket should instead conclude native-PSI is needed — in which case, add a fresh prototype ticket for native-PSI (graduated from fog, not pre-specified) before this ticket can close, and record that reasoning here.

Resolving this ticket unblocks [Write the MVP spec](03-write-mvp-spec.md).

## Answer

**Locked: LSP-client architecture** (LSP4IJ wrapping `teal-language-server`).

Applying the rule set during charting mechanically: the prototype needed no patch or wrapper around `teal-language-server` at all, and its one real gap (a distinct `.tl` file icon) was already anticipated and is already scoped as its own MVP feature line item rather than a surprise — nowhere near the "fork/major rewrite" bar that would have triggered native-PSI.

Confirmed explicitly with the user, including the one open risk: LSP4IJ 0.21.0 has a live, unfixed upstream threading bug (cosmetic — settings persist, IDE stays responsive; see [lsp4ij#1672](https://github.com/redhat-developer/lsp4ij/issues/1672)). Decision: note it and move on — it doesn't block the MVP surface and isn't something a companion plugin would touch or fix, since it lives entirely in LSP4IJ's own settings UI.

Native-PSI is not being prototyped — see map's Out of scope.
