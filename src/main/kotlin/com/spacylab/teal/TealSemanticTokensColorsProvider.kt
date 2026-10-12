package com.spacylab.teal

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.features.semanticTokens.SemanticTokensColorsProvider

/**
 * Colors the Teal server's semantic tokens (teal-server/semantic.lua) with the IDE's standard Language
 * Defaults keys, so .tl files follow the user's color scheme the way Lua files do. LSP4IJ's stock mapping
 * can't do that: it has no global-variable key, colors every plain `variable` as a reassigned local, and
 * honors `defaultLibrary` on functions only. `global` is the server's own modifier.
 */
class TealSemanticTokensColorsProvider : SemanticTokensColorsProvider {

    override fun getTextAttributesKey(tokenType: String, tokenModifiers: List<String>, file: PsiFile): TextAttributesKey? =
        colorFor(tokenType, tokenModifiers)

    companion object {
        fun colorFor(tokenType: String, modifiers: List<String>): TextAttributesKey? = when {
            "defaultLibrary" in modifiers -> Colors.PREDEFINED_SYMBOL
            tokenType == "parameter" -> Colors.PARAMETER
            tokenType == "variable" -> when {
                "global" in modifiers -> Colors.GLOBAL_VARIABLE
                "readonly" in modifiers -> Colors.CONSTANT
                else -> Colors.LOCAL_VARIABLE
            }
            tokenType == "property" -> Colors.INSTANCE_FIELD
            tokenType == "function" || tokenType == "method" ->
                if ("declaration" in modifiers) Colors.FUNCTION_DECLARATION else Colors.FUNCTION_CALL
            tokenType == "interface" -> Colors.INTERFACE_NAME
            tokenType == "class" || tokenType == "enum" || tokenType == "type" -> Colors.CLASS_NAME
            else -> null
        }
    }
}
