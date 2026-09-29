THROWAWAY — probes for the proposed third amendment to [ADR 0001](../../../../docs/adr/0001-lsp-client-architecture-for-teal-plugin.md) (a minimal Lua server built on `tl`). Not production code.

- `probe.lua <root> <file.tl>...` — loads `tl` as a library and reads `tlconfig.lua`. For each file it times a cold check, a warm re-check and 20 simulated edits. It also resolves every identifier to its declaration in-process, the way a references handler would.
- `env_growth.lua <root> <file.tl>` — compares reusing one `tl` env across 50 edits with building a fresh env per edit, then sanity-checks that an injected type error is reported.

Run with Homebrew Lua 5.5.1 and tl 0.24.8 against the local `picolo-rpg` checkout, plus `tl.tl` (15k lines) as a stress case.

Findings (2026-09-29):
- **`tl` loads fine as a library on Lua 5.5.** The `tl` CLI crash comes from the CLI script, which assigns to a `for` loop variable (read-only in 5.5). It isn't in `tl.lua`.
- **Checks are fast enough to skip debouncing.** On a reused env, re-checks take about 17 ms for `main.tl` (924 lines), 9 ms for `cards.tl` and 2 ms for `combat.tl`. Only the 15k-line `tl.tl` is slow, at about 350 ms.
- **References can be answered in-process from `symbols_by_file`.** Resolving every identifier in `main.tl` (2.2k tokens) takes 6.6 ms. The proxy needs one definition request per token for the same job. The linear scan borrowed from teal-language-server is quadratic (670 ms on `tl.tl`) and needs a binary search.
- **A reused env grows on every edit.** `symbols_by_file` is replaced correctly, but the type table keeps growing: 4.8k → 22.5k entries after 50 edits of `main.tl`. teal-language-server reuses one env for the whole session, so it probably has the same growth. A fresh env per edit has no growth but costs about 88 ms per edit, mostly bootstrapping the `love` env and reloading required modules. The env lifecycle is the real design question, because it is also the cache for required modules.
- **Type errors come back as usable positions**, for example `925:25 in local declaration: broken: got string "nope", expected integer`.
- **The upstream server also depends on tree-sitter.** It uses `ltreesitter`, a native library, to find the node under the cursor. `tl.lex` tokens and `tl.get_token_at` look like enough to replace it.
- `env_rebuild_n.lua <root> <file.tl>` measures the live heap and per-edit time over 400 edits on one reused env, to choose N for the env-rebuild policy. On `main.tl`, the heap grows about 180 KB per edit and check time stays flat at about 17–20 ms, so N only bounds memory. The ADR sets N = 100.
