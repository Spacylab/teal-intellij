Type: grilling
Status: open

Blocked by: 01

## Question

Given the findings from [Prototype the LSP-client route](01-prototype-lsp-client-route.md), lock the architecture decision: **LSP-client** (LSP4IJ wrapping `teal-language-server`) vs. **native-PSI** (full custom lexer/PSI/parser plugin).

Per charting: if the prototype worked cleanly or with only small patches/a thin wrapper, lock LSP-client. If it genuinely failed (e.g. `teal-language-server` would need a fork/major rewrite to behave, or LSP4IJ can't deliver a workable `.tl` file-type experience), this ticket should instead conclude native-PSI is needed — in which case, add a fresh prototype ticket for native-PSI (graduated from fog, not pre-specified) before this ticket can close, and record that reasoning here.

Resolving this ticket unblocks [Write the MVP spec](03-write-mvp-spec.md).
