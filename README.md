# Teal for IntelliJ

A companion IntelliJ plugin bringing Teal (`.tl`) support to IntelliJ IDEA: syntax and semantic highlighting, inline diagnostics, hover, go-to-definition, completion, signature help, Find Usages and a missing-require quick fix. Architecture and rationale: [ADR 0001](docs/adr/0001-lsp-client-architecture-for-teal-plugin.md).

Language intelligence comes from the Teal server, a small LSP server in plain Lua ([src/main/resources/teal-server/](src/main/resources/teal-server/)) built on the `tl` compiler as a library. The plugin bundles it and runs it through [LSP4IJ](https://github.com/redhat-developer/lsp4ij). Highlighting has two layers: [vscode-teal](https://github.com/teal-language/vscode-teal)'s MIT-licensed TextMate grammar colors keywords, literals, function names and types as you type, and the Teal server's semantic highlighting then tells locals, parameters, globals, the standard library and fields apart. Both use the IDE's standard Language Defaults colors, so `.tl` files follow your color scheme the way Lua files do.

## Prerequisites

- Lua and the `tl` rock: for example `brew install lua luarocks`, then `luarocks install tl`. The plugin finds `lua` on `PATH`, or through a login shell when the IDE was launched from the Dock.
- JDK 17+ to build. The system default JDK may be too new for Gradle/the IntelliJ Platform Gradle Plugin — if so, install one via `brew install openjdk@21` (or similar) and export `JAVA_HOME` before running `./gradlew`:

  ```bash
  export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
  ```

## Build

The Teal server is written in Teal ([src/main/teal/](src/main/teal/), [ADR 0002](docs/adr/0002-teal-server-written-in-teal.md)), and the build compiles it to Lua with [cyan](https://github.com/teal-language/cyan). Building needs, on `PATH`:

- `lua` with the `tl` rock at the version pinned by `tlVersion` in [gradle.properties](gradle.properties): `luarocks install tl 0.24.8`. The build fails on any other version.
- `cyan`: `luarocks install cyan`.

```bash
./gradlew buildPlugin
```

Type errors and warnings in the server fail the build. To check the server without Gradle, run `cyan build` at the repo root.

## Try it in a sandboxed IDE

```bash
./gradlew runIde
```
