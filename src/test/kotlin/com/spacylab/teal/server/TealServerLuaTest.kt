package com.spacylab.teal.server

import com.spacylab.teal.TealLuaRuntime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the unit tests in `src/test/teal/` (compiled to Lua test resources
 * under `/teal-server/`) against the server's modules, for logic too
 * fine-grained to reach through LSP requests. Finds lua the way the plugin
 * does ([TealLuaRuntime]); skipped only if no lua with the `tl` rock is found.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class TealServerLuaTest {

    @TempDir
    lateinit var tmp: File

    @Test
    fun `requires module`() = runLuaTest("requires_test.lua")

    private fun runLuaTest(name: String) {
        val result = TealLuaRuntime.resolve()
        assumeTrue(result is TealLuaRuntime.Result.Found, "no lua with the tl rock here ($result); skipping")
        val runtime = (result as TealLuaRuntime.Result.Found).runtime

        val scripts = File(tmp, "scripts")
        TealServerScripts.extractTo(scripts)
        val test = File(tmp, name)
        javaClass.getResourceAsStream("/teal-server/$name")!!.use { input -> test.outputStream().use { input.copyTo(it) } }
        val root = File(tmp, "root").apply { mkdirs() }

        val process = ProcessBuilder(runtime.lua, test.absolutePath, scripts.absolutePath, root.absolutePath)
            .redirectErrorStream(true)
            .apply { environment().putAll(runtime.environment) }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        assertEquals(0, process.exitValue(), output)
    }
}
