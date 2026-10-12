package com.spacylab.teal

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.spacylab.teal.TealSemanticTokensColorsProvider.Companion.colorFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TealSemanticTokensColorsProviderTest {

    @Test
    fun `each token kind gets its Language Defaults key`() {
        assertEquals(Colors.LOCAL_VARIABLE, colorFor("variable", listOf("declaration")))
        assertEquals(Colors.CONSTANT, colorFor("variable", listOf("readonly")))
        assertEquals(Colors.GLOBAL_VARIABLE, colorFor("variable", listOf("global")))
        assertEquals(Colors.PARAMETER, colorFor("parameter", listOf("declaration")))
        assertEquals(Colors.INSTANCE_FIELD, colorFor("property", emptyList()))
        assertEquals(Colors.FUNCTION_DECLARATION, colorFor("method", listOf("declaration")))
        assertEquals(Colors.FUNCTION_CALL, colorFor("function", emptyList()))
        assertEquals(Colors.CLASS_NAME, colorFor("class", emptyList()))
        assertEquals(Colors.CLASS_NAME, colorFor("enum", listOf("declaration")))
        assertEquals(Colors.INTERFACE_NAME, colorFor("interface", emptyList()))
    }

    @Test
    fun `the standard library is a predefined symbol, whatever its kind`() {
        assertEquals(Colors.PREDEFINED_SYMBOL, colorFor("function", listOf("global", "defaultLibrary")))
        assertEquals(Colors.PREDEFINED_SYMBOL, colorFor("variable", listOf("global", "defaultLibrary")))
    }
}
