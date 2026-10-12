Type: task
Status: resolved
Blocked by: 01

## What

Port `json` and `rpc` to Teal. `json.null` and the array marker need explicit types. `decode` returns an untyped value, and that is the only place untyped JSON enters the server. See [spec.md](../spec.md).

## Acceptance (every issue)

- `./gradlew test buildPlugin` passes, with `cyan build` reporting no errors or warnings.
- `TealServerTest` is unchanged, or any change is justified in this file's Comments.
- The ported `.lua` is deleted from `src/main/resources/teal-server/`.

## Comments


**2026-10-12: implemented.**
- `json.null` is typed `any`. It's a sentinel that stands in for a null of any type (it's returned as a handler result and compared with untyped payloads), so a dedicated record type would only need casts everywhere.
- `json.array<T>(t?: {T}): {T}` is generic. `json.decode` returns `any`. `rpc.read` returns `any`, `rpc.write` takes `{string:any}`, and request ids are `rpc.Id = integer | string`.
- Behaviour is unchanged. A diff of the generated Lua against the old modules (comments and blank lines removed) shows only: `decode_error` lost its unused `str` parameter, `type(v)` checks come from `is`, and one local alias.
- **tl quirk:** `tl` compiles `-2 ^ 53` to `(-2) ^ 53`, but in Lua `^` binds tighter than unary minus. It was harmless here (odd exponent) but is now written `-(2 ^ 53)`. Watch for `-n ^ x` in later modules.
- **5.1 target risk:** `tl`'s standard library types are 5.3+/5.4, so it accepts calls like `math.tointeger` that don't exist on Lua 5.1 or LuaJIT, and with `gen_compat = "off"` nothing catches them at build time. I briefly used `math.tointeger` and replaced it with `as integer` casts. No 5.1 interpreter is installed here, so the generated code is only exercised on 5.5.
