# Changes to the vendored vscode-teal grammar

`syntaxes/teal.tmLanguage.json` is copied from [teal-language/vscode-teal](https://github.com/teal-language/vscode-teal) (MIT, see `LICENSE-vscode-teal`) and edited in place.

IntelliJ colors a TextMate token only by a fixed set of scope prefixes, for example `entity.name.function` → *Function declaration*, `entity.name` → *Class name*, `support.type` → *Predefined symbol*, `variable.parameter` → *Parameter*, `variable` → *Local variable*. Scopes outside that set get no color. The edits rename scopes so each token lands on the matching standard color key.

| Rule | Upstream | Here |
|---|---|---|
| `function-name` | every identifier in `function a.b:c(` is `support.function.name` (→ *Function call*) | only the last one is `entity.name.function`; the qualifiers are `variable.other`, the `.`/`:` are `punctuation.accessor` |
| `function-arg-name` | `name: function.argument` (no color), which also covers the separating comma | `contentName: variable.parameter`, so the comma isn't a parameter |
| `function-type` | parameter names are `function.argument`; generic parameters are `support.type` | `variable.parameter`; `entity.name.type` |
| `type-name` | every type is `support.type` | built-in types (`any boolean integer nil number string thread userdata`) stay `support.type`; all others are `entity.name.type` |
| `type-arg` | every identifier between `<` `>` is `support.type` | `is` is a keyword; the rest goes through `type`, like any type |
| `new-type-declaration` | the declared name is `support.type` | `entity.name.type` |
| `enum-block` | the enum's name is `support.type` | `entity.name.type` |

Not changed: field access (`a.b`) stays `variable.other`. No TextMate scope maps to IntelliJ's *Instance field* color, so fields get their color from the Teal server's semantic highlighting.
