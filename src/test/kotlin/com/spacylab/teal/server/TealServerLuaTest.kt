package com.spacylab.teal.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the Lua-level unit tests in `src/test/resources/teal-server/` against
 * the server's modules, for logic too fine-grained to reach through LSP
 * requests. Skipped if `lua` isn't on PATH or can't load the `tl` rock.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class TealServerLuaTest {

    @TempDir
    lateinit var tmp: File

    @Test
    fun `requires module`() = runLuaTest("requires_test.lua")

    private fun runLuaTest(name: String) {
        val lua = System.getenv("PATH")?.split(File.pathSeparatorChar)
            ?.map { File(it, "lua") }?.firstOrNull { it.canExecute() }?.absolutePath
        assumeTrue(lua != null, "lua not found on PATH; skipping")
        assumeTrue(ProcessBuilder(lua, "-e", "require('tl')").start().waitFor() == 0, "tl rock missing; skipping")

        val scripts = File(tmp, "scripts")
        TealServerScripts.extractTo(scripts)
        val test = File(tmp, name)
        javaClass.getResourceAsStream("/teal-server/$name")!!.use { input -> test.outputStream().use { input.copyTo(it) } }
        val root = File(tmp, "root").apply { mkdirs() }

        val process = ProcessBuilder(lua, test.absolutePath, scripts.absolutePath, root.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        assertEquals(0, process.exitValue(), output)
    }
}
