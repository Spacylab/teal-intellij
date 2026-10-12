package com.spacylab.teal

import com.intellij.util.containers.Interner
import org.jetbrains.plugins.textmate.language.TextMateLanguageDescriptor
import org.jetbrains.plugins.textmate.language.syntax.TextMateSyntaxTable
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateLexer
import org.jetbrains.plugins.textmate.plist.JsonPlistReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.ArrayDeque

/**
 * Runs the bundled grammar through the TextMate plugin's own lexer and checks
 * the scope each identifier gets. IntelliJ colors a TextMate token by scope
 * prefix only (`entity.name.function` → function declaration, `entity.name` →
 * class name, `support.type` → predefined symbol, `variable.parameter` →
 * parameter...), so the scope is what decides the color.
 */
class TealGrammarTest {

    private val text = resource("highlighting/sample.tl")
    private val tokens = lex(text)

    /** Innermost scope of the [occurrence]-th (1-based) `\bword\b` in the fixture. */
    private fun scopeOf(word: String, occurrence: Int = 1): String {
        val match = Regex("\\b${Regex.escape(word)}\\b").findAll(text).elementAtOrNull(occurrence - 1)
            ?: error("'$word' #$occurrence not in fixture")
        val token = tokens.first { match.range.first >= it.first.first && match.range.first < it.first.last + 1 }
        return token.second
    }

    @Test
    fun `declared function names are function declarations`() {
        assertEquals("entity.name.function.teal", scopeOf("getCenteredRowRect"))
        assertEquals("entity.name.function.teal", scopeOf("heal"))
        assertEquals("entity.name.function.teal", scopeOf("area"))
    }

    @Test
    fun `the qualifier of a declared function name is not part of the name`() {
        assertEquals("variable.other.teal", scopeOf("geometry"))
        assertEquals("variable.other.teal", scopeOf("shapes"))
        assertEquals("variable.other.teal", scopeOf("Player", 3))
    }

    @Test
    fun `parameters are parameters`() {
        assertEquals("variable.parameter.teal", scopeOf("index"))
        assertEquals("variable.parameter.teal", scopeOf("count"))
        assertEquals("variable.parameter.teal", scopeOf("amount"))
        assertEquals("variable.parameter.teal", scopeOf("dt"))
        assertEquals("variable.parameter.teal", scopeOf("self"))
    }

    @Test
    fun `the comma between parameters is not a parameter`() {
        val comma = text.indexOf(", count")
        assertEquals("function.arguments.teal", tokens.first { comma in it.first }.second)
    }

    @Test
    fun `built-in types are predefined symbols`() {
        assertEquals("support.type.teal", scopeOf("number"))
        assertEquals("support.type.teal", scopeOf("number", 5))
        assertEquals("support.type.teal", scopeOf("string"))
        assertEquals("support.type.teal", scopeOf("integer"))
        assertEquals("support.type.teal", scopeOf("boolean"))
        assertEquals("support.type.teal", scopeOf("string", 2))
        assertEquals("support.type.teal", scopeOf("integer", 2))
    }

    @Test
    fun `user types are class names`() {
        assertEquals("entity.name.type.teal", scopeOf("Player"))
        assertEquals("entity.name.type.teal", scopeOf("Player", 2))
        assertEquals("entity.name.type.teal", scopeOf("Color"))
        assertEquals("entity.name.type.teal", scopeOf("Point"))
        assertEquals("entity.name.type.teal", scopeOf("Player", 4))
        assertEquals("entity.name.type.teal", scopeOf("T"))
        assertEquals("entity.name.type.teal", scopeOf("T", 2))
        assertEquals("entity.name.type.teal", scopeOf("Point", 2))
    }

    @Test
    fun `existing coloring is unchanged`() {
        assertEquals("comment.teal", scopeOf("Shared"))
        assertEquals("storage.modifier.teal", scopeOf("local"))
        assertEquals("keyword.control.teal", scopeOf("return"))
        assertEquals("constant.numeric.integer.teal", scopeOf("2"))
        assertEquals("string.quoted.double.teal", scopeOf("red"))
        assertEquals("constant.language.teal", scopeOf("true"))
        assertEquals("variable.other.teal", scopeOf("graphics"))
        assertEquals("variable.other.teal", scopeOf("name"))
    }

    private companion object {
        fun resource(path: String): String =
            TealGrammarTest::class.java.classLoader.getResource(path)!!.readText()

        /** Tokens as (offset range, innermost named scope). */
        fun lex(text: String): List<Pair<IntRange, String>> {
            val grammar = resource("teal-textmate-bundle/syntaxes/teal.tmLanguage.json")
            val plist = grammar.byteInputStream().use { JsonPlistReader().read(it) }
            val table = TextMateSyntaxTable()
            val scopeName = table.loadSyntax(plist, Interner.createInterner())!!
            val lexer = TextMateLexer(TextMateLanguageDescriptor(scopeName, table.getSyntax(scopeName)), -1)
            lexer.init(text, 0)
            val queue = ArrayDeque<TextMateLexer.Token>()
            while (lexer.currentOffset < text.length) lexer.advanceLine(queue)
            return queue.map { token ->
                var scope = token.scope
                while (scope.scopeName == null && !scope.isEmpty) scope = scope.parent!!
                (token.startOffset until token.endOffset) to scope.scopeName.toString()
            }
        }
    }
}
