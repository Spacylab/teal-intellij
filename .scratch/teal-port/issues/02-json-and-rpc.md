Type: task
Status: ready-for-agent
Blocked by: 01

## What

Port `json` and `rpc` to Teal. `json.null` and the array marker need explicit types. `decode` returns an untyped value, and that is the only place untyped JSON enters the server. See [spec.md](../spec.md).

## Acceptance (every issue)

- `./gradlew test buildPlugin` passes, with `cyan build` reporting no errors or warnings.
- `TealServerTest` is unchanged, or any change is justified in this file's Comments.
- The ported `.lua` is deleted from `src/main/resources/teal-server/`.

## Comments

