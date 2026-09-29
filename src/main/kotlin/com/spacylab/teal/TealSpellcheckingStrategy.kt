package com.spacylab.teal

import com.intellij.psi.PsiElement
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.Tokenizer
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateElementType
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateScope

/**
 * Spell-checks only comments and strings in `.tl` files. The TextMate
 * plugin's own strategy checks every token of a TextMate file, so identifiers
 * like `BobTheBerserker` were flagged as typos. Each token of a `.tl` file
 * carries its grammar scope (`comment.teal`, `string.quoted.double.teal`...),
 * which is what decides here. Other TextMate files keep the default behavior.
 */
class TealSpellcheckingStrategy : SpellcheckingStrategy() {

    override fun isMyContext(element: PsiElement): Boolean =
        element.containingFile?.name?.endsWith(".tl") == true

    override fun getTokenizer(element: PsiElement): Tokenizer<*> {
        val type = element.node?.elementType as? TextMateElementType ?: return EMPTY_TOKENIZER
        return if (isCommentOrString(type.scope)) TEXT_TOKENIZER else EMPTY_TOKENIZER
    }

    companion object {
        /** True if [scope] or any scope enclosing it is a comment or a string. */
        fun isCommentOrString(scope: TextMateScope): Boolean {
            var current: TextMateScope? = scope
            while (current != null && !current.isEmpty) {
                val kind = current.scopeName?.toString().orEmpty().substringBefore('.')
                if (kind == "comment" || kind == "string") return true
                current = current.parent
            }
            return false
        }
    }
}
