package com.spacylab.teal.lsp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TealRequireTargetTest {

    @TempDir
    lateinit var root: File

    @Test
    fun `finds the module name anywhere from the opening to the closing quote`() {
        val line = """local run = require("src.engine.run")"""
        val open = line.indexOf('"')
        val close = line.lastIndexOf('"')

        assertEquals("src.engine.run", TealRequireTarget.moduleNameAt(line, open))
        assertEquals("src.engine.run", TealRequireTarget.moduleNameAt(line, open + 5))
        assertEquals("src.engine.run", TealRequireTarget.moduleNameAt(line, close))
        assertNull(TealRequireTarget.moduleNameAt(line, open - 1))
        assertNull(TealRequireTarget.moduleNameAt(line, close + 1))
        assertNull(TealRequireTarget.moduleNameAt(line, line.indexOf("require")))
    }

    @Test
    fun `handles the no-parens form and single quotes`() {
        assertEquals("a.b", TealRequireTarget.moduleNameAt("""require "a.b"""", 10))
        assertEquals("a.b", TealRequireTarget.moduleNameAt("require('a.b')", 10))
    }

    @Test
    fun `picks the require under the cursor when a line has several`() {
        val line = """local a, b = require("x.a"), require("x.b")"""
        assertEquals("x.b", TealRequireTarget.moduleNameAt(line, line.indexOf("x.b")))
    }

    @Test
    fun `ignores strings that are not a require argument`() {
        assertNull(TealRequireTarget.moduleNameAt("""print("src.engine.run")""", 10))
    }

    @Test
    fun `resolves a module name in priority order`() {
        write("a/b/init.tl")
        write("a/b.lua")
        assertEquals(File(root, "a/b/init.tl"), TealRequireTarget.resolve(root, "a.b"))

        write("a/b.d.tl")
        assertEquals(File(root, "a/b.d.tl"), TealRequireTarget.resolve(root, "a.b"))

        write("a/b.tl")
        assertEquals(File(root, "a/b.tl"), TealRequireTarget.resolve(root, "a.b"))
    }

    @Test
    fun `falls back to a lua file`() {
        write("vendor/json.lua")
        assertEquals(File(root, "vendor/json.lua"), TealRequireTarget.resolve(root, "vendor.json"))
    }

    @Test
    fun `returns null for a module outside the workspace`() {
        assertNull(TealRequireTarget.resolve(root, "string"))
    }

    private fun write(path: String): File =
        File(root, path).apply { parentFile.mkdirs(); writeText("") }
}
