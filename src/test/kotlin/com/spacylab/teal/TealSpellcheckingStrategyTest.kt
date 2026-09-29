package com.spacylab.teal

import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateScope
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TealSpellcheckingStrategyTest {

    private fun scope(vararg names: String): TextMateScope =
        names.fold(TextMateScope.EMPTY) { parent, name -> parent.add(name) }

    @Test
    fun `comments and strings are checked, including their nested scopes`() {
        assertTrue(TealSpellcheckingStrategy.isCommentOrString(scope("source.teal", "comment.teal")))
        assertTrue(TealSpellcheckingStrategy.isCommentOrString(scope("source.teal", "comment.block.teal")))
        assertTrue(TealSpellcheckingStrategy.isCommentOrString(scope("source.teal", "string.quoted.double.teal")))
        assertTrue(TealSpellcheckingStrategy.isCommentOrString(
            scope("source.teal", "string.multiline.teal", "punctuation.definition.string.begin.teal"),
        ))
    }

    @Test
    fun `identifiers, keywords and names are not`() {
        assertFalse(TealSpellcheckingStrategy.isCommentOrString(scope("source.teal")))
        assertFalse(TealSpellcheckingStrategy.isCommentOrString(scope("source.teal", "keyword.local.teal")))
        assertFalse(TealSpellcheckingStrategy.isCommentOrString(scope("source.teal", "entity.name.function.teal")))
        assertFalse(TealSpellcheckingStrategy.isCommentOrString(scope("source.teal", "support.stringlike.teal")))
    }
}
