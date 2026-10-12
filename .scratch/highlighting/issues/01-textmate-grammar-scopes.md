Type: task
Status: resolved

## What

Edit the vendored grammar `src/main/resources/teal-textmate-bundle/syntaxes/teal.tmLanguage.json` so IntelliJ's TextMate support colors what can be told from text alone. See [spec.md](../spec.md).

## Decisions (settled, don't re-open)

- Edit our copy in place. List every change in a short note file next to `LICENSE-vscode-teal` (e.g. `CHANGES-vscode-teal.md`): which rules changed and why.
- Use scope names IntelliJ's TextMate color mapping actually recognises (e.g. `entity.name.function`, `entity.name.type`, `variable.other.property` / `variable.other.member`, `support.type`, `support.function`). Verify against the mapping in the IntelliJ TextMate plugin rather than guessing; scopes it ignores are useless.
- Targets, in order of value:
  1. Type annotations: the type names after `:` in parameters, return types, `local x: T`, and inside generics / function types / table types.
  2. Declared function names: `function name(`, `local function name(`, `function a.b:c(`.
  3. Declared `record` / `enum` / `interface` / `type` names.
  4. Field and method access after `.` / `:`.
- Don't try to distinguish locals, parameters or globals here; that's issue 02.

## Acceptance

- A fixture `.tl` file covering every target above (start from `getCenteredRowRect` in the spec), checked in under `src/test/resources/`, gets the intended scope for each target, asserted by a test. In the IDE, declared function names show the function-declaration color. Types use standard keys and look plain in default schemes (see spec).
- No regression on existing coloring (keywords, strings, numbers, comments) on picolo-rpg files.
- Spell-checking still covers only comments and strings (`TealSpellcheckingStrategyTest` passes).
- `./gradlew buildPlugin` and the test suite pass.

## Comments

**2026-10-12: implemented, implemented.**
- IntelliJ's TextMate scope→color table (from `TextMateDefaultColorsProvider`, IDE 2024.2) is prefix-based and fixed: `entity.name.function` → function declaration, `entity.name` → class name, `support.function` → function call, `support.type` → predefined symbol, `variable.parameter` → parameter, `variable` → local variable. **No scope maps to Instance field**, so target 4 (field access) can't be colored by TextMate; it stays `variable.other` and is left to issue 02.
- Grammar edits are listed in `src/main/resources/teal-textmate-bundle/CHANGES-vscode-teal.md`.
- `TealGrammarTest` runs the bundled grammar through the TextMate plugin's own lexer (no IDE fixture) over `src/test/resources/highlighting/sample.tl` and asserts each target's scope.
- Regression check: a throwaway test lexed all 60 picolo-rpg `.tl` files with the old and new grammar. The only scope changes were parameters (2,766 tokens), user types (3,027), function names (370), function-name qualifiers (221) and parameter commas (450). Keywords, strings, comments and numbers were untouched.
- Visible effect in the default Dark scheme: declared function names turn blue. Parameters and types get the right keys but those keys are uncolored by default (see the spec's type-color decision).

**2026-10-12: shipped in 0.6.0.** Manual IDE check done by the user: works well.
