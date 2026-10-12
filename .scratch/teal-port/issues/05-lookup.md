Type: task
Status: resolved
Blocked by: 04

## What

Port `lookup` against `tl.tl`'s token and type-report types. This module and the next three are where the port pays off: record in Comments any place where the types disagreed with how the Lua code was using the type report. See [spec.md](../spec.md).

## Acceptance (every issue)

- `./gradlew test buildPlugin` passes, with `cyan build` reporting no errors or warnings.
- `TealServerTest` is unchanged, or any change is justified in this file's Comments.
- The ported `.lua` is deleted from `src/main/resources/teal-server/`.

## Comments


**2026-10-12: implemented.**
- Typed against `tl.Token`, `tl.TypeReport` (with its `Symbol` tuple `{y, x, name, type id}`), `tl.TypeInfo` and the `tl.TokenKind` enum. Comparisons such as `kind == "$EOF$"` are now checked against that enum.
- **No disagreements:** every use of the type report and tokens in `lookup` matched `tl.tl`'s declarations. The generated Lua is identical to the old module (one comment dropped). The header comment now names the `tl` types and documents that a `"@}"` symbol's 4th slot is the index of its `"@{"`, which the `Symbol` type doesn't say.
- `lookup.by_pos` is assigned after the fact (`lookup.by_pos = by_pos`), so it had to be declared as a field of the `lookup` record.
