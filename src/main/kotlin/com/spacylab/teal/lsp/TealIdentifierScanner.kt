package com.spacylab.teal.lsp

/**
 * A minimal Teal/Lua lexical scanner that finds identifier occurrences and their
 * (0-indexed, LSP-style) positions, skipping string and comment contents. This is
 * not a real parser -- just enough to enumerate candidate reference sites for
 * [TealReferencesProxyConnectionProvider], which disambiguates candidates by
 * asking the real language server whether each one resolves to the same
 * declaration (via textDocument/definition), rather than by understanding scope
 * itself.
 */
object TealIdentifierScanner {

    data class IdentifierToken(val line: Int, val character: Int, val name: String) {
        fun contains(atLine: Int, atCharacter: Int): Boolean =
            atLine == line && atCharacter >= character && atCharacter <= character + name.length
    }

    fun scan(text: String): List<IdentifierToken> {
        val tokens = mutableListOf<IdentifierToken>()
        val n = text.length
        var i = 0
        var line = 0
        var col = 0

        fun advance(count: Int = 1) {
            repeat(count) {
                if (i < n) {
                    if (text[i] == '\n') {
                        line++
                        col = 0
                    } else {
                        col++
                    }
                    i++
                }
            }
        }

        // If `text[at]` is '[' opening a long bracket (`[[`, `[=[`, `[==[`, ...),
        // returns its `=` level; otherwise null.
        fun longBracketLevel(at: Int): Int? {
            var j = at + 1
            var level = 0
            while (j < n && text[j] == '=') {
                level++
                j++
            }
            return if (j < n && text[j] == '[') level else null
        }

        // Consumes up to and including the matching closing bracket, from the
        // current position (just after the opening bracket was consumed).
        fun skipLongBracket(level: Int) {
            val closer = "]" + "=".repeat(level) + "]"
            val idx = text.indexOf(closer, i)
            val end = if (idx < 0) n else idx + closer.length
            while (i < end) advance()
        }

        fun isIdentifierStart(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_'
        fun isIdentifierPart(c: Char) = isIdentifierStart(c) || c in '0'..'9'

        while (i < n) {
            val c = text[i]
            when {
                c == '-' && i + 1 < n && text[i + 1] == '-' -> {
                    advance(2)
                    val level = if (i < n && text[i] == '[') longBracketLevel(i) else null
                    if (level != null) {
                        advance(2 + level)
                        skipLongBracket(level)
                    } else {
                        while (i < n && text[i] != '\n') advance()
                    }
                }
                c == '[' && longBracketLevel(i) != null -> {
                    val level = longBracketLevel(i)!!
                    advance(2 + level)
                    skipLongBracket(level)
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    advance()
                    while (i < n && text[i] != quote) {
                        if (text[i] == '\\' && i + 1 < n) advance(2) else advance()
                    }
                    if (i < n) advance()
                }
                isIdentifierStart(c) -> {
                    val startLine = line
                    val startCol = col
                    val start = i
                    while (i < n && isIdentifierPart(text[i])) advance()
                    tokens.add(IdentifierToken(startLine, startCol, text.substring(start, i)))
                }
                else -> advance()
            }
        }
        return tokens
    }
}
