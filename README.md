# Teal for IntelliJ

A thin companion IntelliJ plugin bringing Teal (`.tl`) support to IntelliJ IDEA: syntax highlighting, inline diagnostics, hover, and go-to-definition. Architecture and rationale: [ADR 0001](docs/adr/0001-lsp-client-architecture-for-teal-plugin.md). MVP scope: [.scratch/teal-plugin/spec.md](.scratch/teal-plugin/spec.md).

All language intelligence is delegated to [LSP4IJ](https://github.com/redhat-developer/lsp4ij) wrapping [teal-language-server](https://github.com/teal-language/teal-language-server); this plugin's own code is limited to `.tl` file-type/highlighting registration (reusing [vscode-teal](https://github.com/teal-language/vscode-teal)'s MIT-licensed TextMate grammar) and registering the language server with LSP4IJ.

## Prerequisites

- `teal-language-server` on `PATH`: `luarocks install teal-language-server` (needs `cmake` for one of its dependencies).
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
