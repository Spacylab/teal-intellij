Type: task
Status: ready-for-agent
Blocked by: 01, 02

## What

Ship issues 01 and 02 as plugin version 0.6.0.

## Acceptance

- `pluginVersion` bumped to `0.6.0` in its own commit ("Bump version to 0.6.0"), matching previous releases.
- README / plugin description mention richer highlighting (types, function names, locals vs parameters vs globals).
- `./gradlew buildPlugin` produces the 0.6.0 zip; full test suite passes.

## Comments
