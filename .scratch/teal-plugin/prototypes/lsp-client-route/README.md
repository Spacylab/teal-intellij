THROWAWAY — prototype scripts for [Prototype the LSP-client route](../../issues/01-prototype-lsp-client-route.md). Not production code, not part of the eventual plugin.

- `lsp_probe.py` — scripts a raw LSP `initialize` → `initialized` → `didOpen`/`didSave` exchange with `teal-language-server` over stdio against `picolo-rpg/src/conf.tl`, to check whether diagnostics get published without needing IntelliJ/LSP4IJ in the loop.
- `tl_check_probe.lua` — calls `tl.check()` directly (bypassing the LSP entirely) to isolate whether a missing diagnostic is a `teal-language-server`/LSP issue or a `tl`-level type-checking characteristic.

Both assume `teal-language-server` is on `PATH` (`luarocks install teal-language-server`, needs `cmake` for one of its dependencies) and are hardcoded to the local `picolo-rpg` checkout path.
