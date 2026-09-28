package com.spacylab.teal.lsp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TealMissingRequireTest {

    @TempDir
    lateinit var root: File

    @Test
    fun `extracts the type name only from unknown type messages`() {
        assertEquals("Player", TealMissingRequire.unknownTypeName("unknown type Player"))
        assertNull(TealMissingRequire.unknownTypeName("unknown variable Player"))
        assertNull(TealMissingRequire.unknownTypeName("unknown type a.B"))
    }

    @Test
    fun `finds global declarations of any kind, skipping locals, hidden dirs and the current file`() {
        write("src/entities/player.tl", "global record Player\n  name: string\nend\n")
        write("src/entities/kinds.tl", "global enum Player\n  \"a\"\nend\n")
        write("src/local_player.tl", "local record Player\nend\nreturn Player\n")
        write(".scratch/player.tl", "global record Player\nend\n")
        write("src/commented.tl", "-- global record Player\n")
        val current = write("src/engine/run.tl", "global record Player\nend\n")

        val found = TealMissingRequire.findGlobalDeclarations(root, "Player", current)
            .map { TealMissingRequire.moduleName(root, it) }

        assertEquals(listOf("src.entities.kinds", "src.entities.player"), found)
    }

    @Test
    fun `does not match a longer name sharing the prefix`() {
        write("a.tl", "global record PlayerStats\nend\n")
        assertEquals(emptyList<File>(), TealMissingRequire.findGlobalDeclarations(root, "Player", null))
    }

    @Test
    fun `module name of a declaration file drops the whole d-tl suffix`() {
        assertEquals("types.love", TealMissingRequire.moduleName(root, write("types/love.d.tl", "")))
    }

    @Test
    fun `required modules ignore commented-out requires`() {
        val text = """
            require("src.engine.run_types")
            -- require("src.entities.player")
            local M = require 'src.data.decks'
        """.trimIndent()
        assertEquals(setOf("src.engine.run_types", "src.data.decks"), TealMissingRequire.requiredModules(text))
    }

    @Test
    fun `inserts after the last leading require, across blank and comment lines`() {
        val text = """
            -- header
            require("a")
            -- require("b")

            local C = require("c")

            local M = {}
            local late = require("late")
        """.trimIndent()
        assertEquals(5, TealMissingRequire.insertionLine(text))
    }

    @Test
    fun `inserts at line 0 when the file starts with no requires`() {
        assertEquals(0, TealMissingRequire.insertionLine("-- header\nlocal M = {}\nreturn M\n"))
    }

    private fun write(path: String, text: String): File =
        File(root, path).apply { parentFile.mkdirs(); writeText(text) }
}
