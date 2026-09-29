Type: task
Status: ready-for-human
Blocked by: 07

## What

Completion and signature help in the minimal server (ADR 0001, third amendment). This brings those features to parity with teal-language-server.

## Decisions (settled, don't re-open)

- **Trigger characters** are `.` and `:` for completion, and `(` and `,` for signature help.
- **Member completion** (after `a.` or `a:`, or typing after them) lists the fields of the chain's resolved type. After `:` it lists only functions. Strings complete with the `string` library.
- **Scope completion** (a bare identifier) lists `symbols_in_scope` at the cursor plus globals. Filtering by prefix is left to the client.
- **Items** carry a kind (function, method, field, variable, module) and the type as `detail`.
- **Signature help:**
  - find the enclosing unclosed `(` by scanning the tokens backward, and count top-level commas to get the active parameter;
  - resolve the callee's type, and list one signature per variant for overloaded functions;
  - parameter labels come from the argument types, with names when the declaration has them.

## Acceptance

`TealServerTest` cases:
- completion after `p.` lists a record's fields with their types;
- after `s:` it lists string methods;
- a bare identifier completes locals in scope and globals;
- completion works while the line doesn't parse (`p.` with nothing after);
- signature help inside `f(1, |` shows `f`'s signature with active parameter 1.

## Comments

**2026-09-29: implemented, not yet committed.** Built alongside 07, in `features.lua`.
- All acceptance cases pass in `TealServerTest`.
- Also covered: type names in an annotation (`local sq: Sh|` offers `Shape`), and no string methods on `M.` for `local M = {}`.
- **Completion after `:`.** Mid-edit, a method call can't be told apart from a type annotation. So `:` is tried as a method call first, and falls back to scope completion (which includes type names) when that finds nothing.
- **Signature help** uses the declared parameter names when they're available. It drops `self` for `a:b(` calls and lists every variant of an overloaded function. Parameter labels are `[start, end)` offsets, so repeated types (`number, number`) highlight the right one.
- Not compared head-to-head with teal-language-server: its completion results are shaped too differently for a meaningful per-position diff. It needs a manual pass in the IDE once the server is wired in.
