Type: task
Status: ready-for-human
Blocked by: 06

## What

Hover, go-to-definition and go-to-type-definition in the minimal server (ADR 0001, third amendment). This brings those features to parity with teal-language-server.

## Decisions (settled, don't re-open)

- **Always type-check, even when a file has a parse error.** `tl.check` runs on the partial AST (inside `pcall`), so the type report stays current while the user is typing. Diagnostics are unchanged: a parse error still hides type errors.
- **Resolving the type at the cursor:**
  - use `tl`'s `by_pos` type at the identifier's own position;
  - for `a.b` and `a:b`, `tl` records the member's type at the `.` or `:` position, so use that;
  - if `by_pos` has nothing, fall back to resolving the dotted name chain through `symbols_in_scope` and the globals, the way teal-language-server does.
- **Definition:**
  - a plain identifier jumps to its local declaration (`symbols_by_file`, found with a binary search), otherwise to the global's type position;
  - a member jumps to its type's declaration (functions, records), otherwise to the enclosing record;
  - positions in files that don't exist on disk (for example `stdlib.d.tl`) return `null`.
- **Type definition** follows nominal refs to the declared type.
- **Hover** is a markdown `teal` code block:
  - functions are shown with parameter names, read from the tokens at their declaration;
  - overloaded functions list each variant;
  - records and enums list their fields and values;
  - everything else is `name: type`.

## Acceptance

`TealServerTest` cases:
- hover on a local shows its type;
- hover on a function shows its parameter names;
- hover on a record member shows the member's type;
- hover works on a file with a parse error further down;
- hover on a keyword or whitespace returns `null`;
- definition on a local jumps to its declaration;
- definition on a function from a required module jumps into that module's file;
- definition on a stdlib global returns `null`;
- type definition on a variable jumps to its record type.

## Comments

**2026-09-29: implemented, not yet committed.**
- Code: `lookup.lua` (token and type-report queries) and `features.lua` (handlers). `workspace.lua` gained `tokens_of`, `location` and always-check-with-`pcall`.
- All acceptance cases pass in `TealServerTest`. Extra regression tests cover the bugs listed below.
- **Parity run against teal-language-server** on picolo-rpg: hover and definition on every non-keyword identifier, about 7,300 positions each.
  - **Hover:** we answer everywhere upstream does, plus 1,256 more positions (5,880 both, 0 upstream-only).
  - **Definition, upstream-only (538):** all point at files that don't exist (`stdlib.d.tl` for `string`, `require`, `math`...), so they're broken jumps. We return `null` for these on purpose.
  - **Definition, different target (237):** all were checked. In every case we land on the declaration of that exact name (the field, parameter or local), and upstream lands on the declaration's *type*. Type definition covers the latter.
- Bugs found by that run, all fixed:
  - **A `:` in a type annotation was read as a method call.** In `x: T`, it made `T` resolve as a member of `x`. `lookup.is_member_op` now needs call arguments after the name.
  - **Qualified types recorded whole at their first name.** In `love.graphics.Image`, definition on `love` jumped to `Image`.
  - **Assignment targets.** On the left of an assignment, `tl`'s type at the `.` is the assigned value's. Member definitions now go through the parent type's field instead.
  - **Empty tables mistaken for strings.** `tl.typecodes` gives `STRING` and `EMPTY_TABLE` the same code (8), so `local M = {}` looked like a string.
- **Fuzz:** every request type on every identifier in picolo-rpg (about 102k requests) gave 0 errors and 0 requests over 50 ms.
- **Known gaps:**
  - Record field *declarations* have no hover. About 3% of identifier tokens are unresolved, mostly those.
  - Overloaded functions jump to their first variant.
