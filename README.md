# Teal for IntelliJ

A companion IntelliJ plugin bringing Teal (`.tl`) support to IntelliJ IDEA: syntax highlighting, inline diagnostics, hover, go-to-definition, completion, signature help, Find Usages and a missing-require quick fix. Architecture and rationale: [ADR 0001](docs/adr/0001-lsp-client-architecture-for-teal-plugin.md).

Language intelligence comes from the Teal server, a small LSP server in plain Lua ([src/main/resources/teal-server/](src/main/resources/teal-server/)) built on the `tl` compiler as a library. The plugin bundles it and runs it through [LSP4IJ](https://github.com/redhat-developer/lsp4ij). Highlighting reuses [vscode-teal](https://github.com/teal-language/vscode-teal)'s MIT-licensed TextMate grammar.

## Prerequisites

- Lua and the `tl` rock: for example `brew install lua luarocks`, then `luarocks install tl`. The plugin finds `lua` on `PATH`, or through a login shell when the IDE was launched from the Dock.
- JDK 17+ to build. The system default JDK may be too new for Gradle/the IntelliJ Platform Gradle Plugin — if so, install one via `brew install openjdk@21` (or similar) and export `JAVA_HOME` before running `./gradlew`:

  ```bash
  export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
  ```

## Build

```bash
./gradlew buildPlugin
```

## Try it in a sandboxed IDE

```bash
./gradlew runIde
```
