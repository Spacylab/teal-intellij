Type: task
Status: ready-for-agent
Blocked by: 08

## What

Port `server`: the dispatcher casts each decoded payload to its LSP record once. Add the startup check: if `tl.version()` is older than the pinned version, write a clear message to stderr and exit 1, like the missing-`tl` path. The pin reaches the server through the build (e.g. a generated constant). Then remove `include_dir` from `tlconfig.lua`, confirm `src/main/resources/teal-server/` is empty and delete it, and update the README and plugin description where they say the server is plain Lua. See [spec.md](../spec.md).

## Acceptance (every issue)

- `./gradlew test buildPlugin` passes, with `cyan build` reporting no errors or warnings.
- `TealServerTest` is unchanged, or any change is justified in this file's Comments.
- The ported `.lua` is deleted from `src/main/resources/teal-server/`.

## Comments

