package com.spacylab.teal.server

import com.google.gson.JsonParser
import com.spacylab.teal.TealLuaRuntime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The plugin's launch path, end to end: resolve `lua` and `tl` the way the
 * factory does, start the server through the real LSP4IJ connection provider,
 * answer `initialize`, and clean up the extracted scripts on stop.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class TealServerConnectionProviderTest {

    @TempDir
    lateinit var project: File

    @Test
    fun `starts the bundled server with the resolved runtime and cleans up on stop`() {
        val result = TealLuaRuntime.resolve()
        assumeTrue(result is TealLuaRuntime.Result.Found, "no lua with the tl rock here ($result); skipping")
        val runtime = (result as TealLuaRuntime.Result.Found).runtime

        val provider = TealServerConnectionProvider(runtime.lua, runtime.environment, project.absolutePath)
        provider.start()
        try {
            assertTrue(provider.isAlive)
            val scriptDir = File(provider.commandLine.parametersList.list.single()).parentFile
            assertTrue(File(scriptDir, "server.lua").isFile)

            LspFraming.writeMessage(provider.outputStream, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"rootUri":null,"capabilities":{}}}""")
            val response = JsonParser.parseString(LspFraming.readMessage(provider.inputStream)).asJsonObject
            assertEquals(1, response.get("id").asInt)
            assertTrue(response.getAsJsonObject("result").getAsJsonObject("capabilities").get("hoverProvider").asBoolean)

            provider.stop()
            assertFalse(scriptDir.exists(), "expected the extracted scripts to be deleted on stop")
        } finally {
            provider.stop()
        }
    }
}
