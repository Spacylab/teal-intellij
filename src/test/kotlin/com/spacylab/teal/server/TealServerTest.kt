package com.spacylab.teal.server

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.spacylab.teal.lsp.LspFraming
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration test for the Lua Teal server in `src/main/resources/teal-server`
 * (ADR 0001, third amendment), driven as a real `lua server.lua` process over
 * raw stdio. Skipped if `lua` isn't on PATH or can't load the `tl` rock.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class TealServerTest {

    private val gson = Gson()
    private val nextId = AtomicInteger(1)
    private val incoming = LinkedBlockingQueue<JsonObject>()
    private lateinit var process: Process
    private lateinit var projectRoot: File
    private lateinit var scriptDir: File

    private fun start(env: Map<String, String> = emptyMap()): JsonObject {
        val lua = findOnPath("lua")
        assumeTrue(lua != null, "lua not found on PATH; skipping")
        val tlLoads = ProcessBuilder(lua, "-e", "require('tl')").start().waitFor() == 0
        assumeTrue(tlLoads, "the tl rock can't be loaded by lua; skipping")

        projectRoot = File.createTempFile("teal-server-test", "").apply { delete(); mkdirs() }
        scriptDir = File.createTempFile("teal-server-scripts", "").apply { delete() }
        val script = TealServerScripts.extractTo(scriptDir)

        process = ProcessBuilder(lua, script.absolutePath)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .apply { environment().putAll(env) }
            .start()
        Thread({
            while (true) {
                val raw = LspFraming.readMessage(process.inputStream) ?: break
                incoming.add(JsonParser.parseString(raw).asJsonObject)
            }
        }, "teal-server-test-reader").apply { isDaemon = true }.start()

        val response = request("initialize", mapOf(
            "processId" to null,
            "rootUri" to fileUri(projectRoot),
            "capabilities" to emptyMap<String, Any>(),
        ))
        notify("initialized", emptyMap<String, Any>())
        return response
    }

    @AfterEach
    fun tearDown() {
        if (::process.isInitialized) process.destroy()
        if (::projectRoot.isInitialized) projectRoot.deleteRecursively()
        if (::scriptDir.isInitialized) scriptDir.deleteRecursively()
    }

    @Test
    fun `initialize advertises full sync with save notifications and reports the tl version`() {
        val result = start().getAsJsonObject("result")
        val sync = result.getAsJsonObject("capabilities").getAsJsonObject("textDocumentSync")
        assertEquals(1, sync.get("change").asInt, "expected full-document sync")
        assertTrue(sync.get("openClose").asBoolean)
        assertTrue(sync.has("save"), "expected save notifications, which trigger env rebuilds")
        assertTrue(
            result.getAsJsonObject("serverInfo").get("version").asString.startsWith("tl "),
            "expected the tl version in serverInfo",
        )
    }

    @Test
    fun `a type error is published with its range, and clears once fixed`() {
        start()
        val uri = open("main.tl", "local x: integer = \"nope\"\nprint(x)\n")

        val errors = diagnosticsFor(uri)
        assertEquals(1, errors.size(), "expected one diagnostic, got $errors")
        val error = errors[0].asJsonObject
        assertEquals(1, error.get("severity").asInt)
        assertTrue(error.get("message").asString.contains("expected integer"), "got: $error")
        assertRange(error, line = 0, start = 19, end = 25) // the "nope" literal, quotes included

        change(uri, "local x: integer = 1\nprint(x)\n", version = 2)
        assertEquals(0, diagnosticsFor(uri).size())
    }

    @Test
    fun `a syntax error is published as a parse error`() {
        start()
        val uri = open("main.tl", "local x = \n")

        val errors = diagnosticsFor(uri)
        assertEquals(1, errors.size(), "got $errors")
        assertEquals(1, errors[0].asJsonObject.get("severity").asInt)
        assertTrue(errors[0].asJsonObject.get("message").asString.contains("expected an expression"))
    }

    @Test
    fun `an unused variable is published as a warning`() {
        start()
        val uri = open("main.tl", "local unused = 1\n")

        val warnings = diagnosticsFor(uri)
        assertEquals(1, warnings.size(), "got $warnings")
        val warning = warnings[0].asJsonObject
        assertEquals(2, warning.get("severity").asInt)
        assertTrue(warning.get("message").asString.contains("unused variable"))
        assertRange(warning, line = 0, start = 6, end = 12)
    }

    @Test
    fun `saving re-checks dependents against a changed dependency`() {
        start()
        writeDependency(returning = "integer")
        val uri = open("main.tl", MAIN_USING_DEP)
        assertEquals(0, diagnosticsFor(uri).size())

        writeDependency(returning = "string")
        change(uri, MAIN_USING_DEP, version = 2)
        assertEquals(0, diagnosticsFor(uri).size(), "the env still holds the old dependency until a save")

        notify("textDocument/didSave", mapOf("textDocument" to mapOf("uri" to fileUri(File(projectRoot, "dep.tl")))))
        val errors = diagnosticsFor(uri)
        assertEquals(1, errors.size(), "expected the saved dependency's new type to break main.tl, got $errors")
        assertTrue(errors[0].asJsonObject.get("message").asString.contains("got string"))
    }

    @Test
    fun `the env is rebuilt after N edits even without a save`() {
        start(mapOf("TEAL_SERVER_REBUILD_EVERY" to "2"))
        writeDependency(returning = "integer")
        val uri = open("main.tl", MAIN_USING_DEP)
        assertEquals(0, diagnosticsFor(uri).size())

        writeDependency(returning = "string")
        change(uri, MAIN_USING_DEP, version = 2)
        assertEquals(0, diagnosticsFor(uri).size(), "one edit is below the threshold")

        change(uri, MAIN_USING_DEP, version = 3)
        assertEquals(1, diagnosticsFor(uri).size(), "the second edit rebuilds the env and sees the new dependency")
    }

    @Test
    fun `an unknown request gets a method-not-found error`() {
        start()
        val response = request("textDocument/documentHighlight", emptyMap<String, Any>())
        assertEquals(-32601, response.getAsJsonObject("error").get("code").asInt)
    }

    @Test
    fun `shutdown then exit ends the process cleanly`() {
        start()
        request("shutdown", null)
        notify("exit", null)
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "expected the server to exit")
        assertEquals(0, process.exitValue())
    }

    // --- hover, definition, type definition ---------------------------------------

    @Test
    fun `hover on a local shows its type`() {
        val uri = startWithGeometry()
        val hover = hoverText(uri, line = 3, character = 6) // p
        assertTrue(hover.contains("p: Point") && hover.contains("x: number"), "got: $hover")
    }

    @Test
    fun `hover on a function shows its parameter names`() {
        val uri = startWithGeometry()
        val hover = hoverText(uri, line = 1, character = 19) // make
        assertTrue(hover.contains("function make(x: number, y: number): Point"), "got: $hover")
    }

    @Test
    fun `hover on a record member shows the member's type`() {
        val uri = startWithGeometry()
        assertTrue(hoverText(uri, line = 3, character = 8).contains("x: number")) // p.x
    }

    @Test
    fun `hover still works while a later line doesn't parse`() {
        val uri = startWithGeometry()
        change(uri, GEOMETRY_MAIN + "local broken = \n", version = 2)
        diagnosticsFor(uri)
        assertTrue(hoverText(uri, line = 3, character = 6).contains("p: Point"))
    }

    @Test
    fun `hover on a keyword returns null`() {
        val uri = startWithGeometry()
        assertTrue(at("textDocument/hover", uri, line = 0, character = 0).isJsonNull)
    }

    @Test
    fun `definition on a local jumps to its declaration`() {
        val uri = startWithGeometry()
        assertLocation(at("textDocument/definition", uri, line = 3, character = 6), uri, line = 1, character = 6)
    }

    @Test
    fun `definition on a required module's function jumps into that module`() {
        val uri = startWithGeometry()
        assertLocation(at("textDocument/definition", uri, line = 1, character = 19), geometryUri(), line = 5, character = 0)
    }

    @Test
    fun `definition on a standard library global returns null`() {
        val uri = startWithGeometry()
        assertTrue(at("textDocument/definition", uri, line = 3, character = 0).isJsonNull) // print
    }

    @Test
    fun `type definition on a variable jumps to its record type`() {
        val uri = startWithGeometry()
        assertLocation(at("textDocument/typeDefinition", uri, line = 3, character = 6), geometryUri(), line = 0, character = 0)
    }

    @Test
    fun `definition and hover on a type annotation reach the global type in another file`() {
        val uri = startWithGeometry()
        File(projectRoot, "shapes.tl").writeText("global record Shape\n   sides: integer\nend\n")
        change(uri, GEOMETRY_MAIN + "require(\"shapes\")\nlocal sq: Shape = { sides = 4 }\nprint(sq)\n", version = 2)
        assertEquals(0, diagnosticsFor(uri).size())

        // "local sq: Shape" -> Shape at 5:10. Its `:` is an annotation, not a method call.
        val location = at("textDocument/definition", uri, line = 5, character = 10).asJsonObject
        assertEquals(fileUri(File(projectRoot, "shapes.tl")), location.get("uri").asString)
        assertEquals(0, location.getAsJsonObject("range").getAsJsonObject("start").get("line").asInt)

        val hover = hoverText(uri, line = 5, character = 10)
        assertTrue(hover.contains("record Shape") && hover.contains("sides: integer"), "got: $hover")
    }

    @Test
    fun `completion in a type annotation offers type names`() {
        val uri = startWithGeometry()
        File(projectRoot, "shapes.tl").writeText("global record Shape\n   sides: integer\nend\n")
        change(uri, GEOMETRY_MAIN + "require(\"shapes\")\nlocal sq: Sh", version = 2)
        diagnosticsFor(uri)

        val labels = at("textDocument/completion", uri, line = 5, character = 12).asJsonArray
            .map { it.asJsonObject.get("label").asString }
        assertTrue("Shape" in labels, "got: $labels")
    }

    @Test
    fun `definition on a field being assigned jumps to the field, not the assigned value`() {
        start()
        val uri = open("main.tl", """
            local record Box
               items: {string}
            end
            local b: Box = { items = {} }
            local items: {string} = {}
            b.items = items
        """.trimIndent() + "\n")
        diagnosticsFor(uri)

        // "b.items = items" -> the field `items` at 5:2, declared at 1:3.
        val location = at("textDocument/definition", uri, line = 5, character = 2).asJsonObject
        assertEquals(1, location.getAsJsonObject("range").getAsJsonObject("start").get("line").asInt)
    }

    @Test
    fun `completion on an empty table doesn't offer string methods`() {
        start()
        val uri = open("main.tl", "local M = {}\nM.")
        diagnosticsFor(uri)

        val labels = at("textDocument/completion", uri, line = 1, character = 2).asJsonArray
            .map { it.asJsonObject.get("label").asString }
        assertTrue("upper" !in labels, "tl gives empty tables and strings the same typecode; got: $labels")
    }

    // --- completion, signature help ------------------------------------------------

    @Test
    fun `completion after a dot lists the record's fields, even though the line doesn't parse`() {
        val uri = startWithGeometry()
        change(uri, GEOMETRY_MAIN + "p.", version = 2)
        assertTrue(diagnosticsFor(uri).size() > 0, "expected `p.` to be a parse error")

        val items = at("textDocument/completion", uri, line = 4, character = 2).asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("x" to "number", "y" to "number"), items.map { it.get("label").asString to it.get("detail").asString })
    }

    @Test
    fun `completion after a colon on a string lists string methods`() {
        val uri = startWithGeometry()
        change(uri, GEOMETRY_MAIN + "s:", version = 2)
        diagnosticsFor(uri)

        val items = at("textDocument/completion", uri, line = 4, character = 2).asJsonArray.map { it.asJsonObject }
        assertTrue(items.any { it.get("label").asString == "upper" }, "got: $items")
        assertTrue(items.all { it.get("kind").asInt == 2 }, "expected only methods after `:`")
    }

    @Test
    fun `completion of a bare name lists locals in scope and globals`() {
        val uri = startWithGeometry()
        change(uri, GEOMETRY_MAIN + "ge", version = 2)
        diagnosticsFor(uri)

        val labels = at("textDocument/completion", uri, line = 4, character = 2).asJsonArray
            .map { it.asJsonObject.get("label").asString }
        assertTrue(labels.containsAll(listOf("geometry", "p", "s", "print")), "got: $labels")
        assertTrue("..." !in labels)
    }

    @Test
    fun `signature help shows the callee's signature and the active parameter`() {
        val uri = startWithGeometry()
        change(uri, GEOMETRY_MAIN + "geometry.make(1, ", version = 2)
        diagnosticsFor(uri)

        val help = at("textDocument/signatureHelp", uri, line = 4, character = 17).asJsonObject
        val signature = help.getAsJsonArray("signatures")[0].asJsonObject
        assertEquals("make(x: number, y: number): Point", signature.get("label").asString)
        assertEquals(1, help.get("activeParameter").asInt)
    }

    // --- helpers -----------------------------------------------------------------

    private companion object {
        const val MAIN_USING_DEP = "local dep = require(\"dep\")\nlocal n: integer = dep.value()\nprint(n)\n"

        val GEOMETRY = """
            local record Point
               x: number
               y: number
            end
            local M = {}
            function M.make(x: number, y: number): Point
               return { x = x, y = y }
            end
            return M
        """.trimIndent() + "\n"

        val GEOMETRY_MAIN = """
            local geometry = require("geometry")
            local p = geometry.make(1, 2)
            local s = "hi"
            print(p.x, s:upper())
        """.trimIndent() + "\n"
    }

    private fun startWithGeometry(): String {
        start()
        File(projectRoot, "geometry.tl").writeText(GEOMETRY)
        val uri = open("main.tl", GEOMETRY_MAIN)
        assertEquals(0, diagnosticsFor(uri).size(), "the fixture should type-check cleanly")
        return uri
    }

    private fun geometryUri() = fileUri(File(projectRoot, "geometry.tl"))

    private fun at(method: String, uri: String, line: Int, character: Int): JsonElement {
        val response = request(method, mapOf(
            "textDocument" to mapOf("uri" to uri),
            "position" to mapOf("line" to line, "character" to character),
        ))
        assertTrue(response.has("result"), "expected a result, got $response")
        return response.get("result")
    }

    private fun hoverText(uri: String, line: Int, character: Int): String {
        val hover = at("textDocument/hover", uri, line, character)
        assertTrue(hover.isJsonObject, "expected a hover at $line:$character")
        return hover.asJsonObject.getAsJsonObject("contents").get("value").asString
    }

    private fun assertLocation(location: JsonElement, uri: String, line: Int, character: Int) {
        assertTrue(location.isJsonObject, "expected a location, got $location")
        val obj = location.asJsonObject
        assertEquals(uri, obj.get("uri").asString)
        val start = obj.getAsJsonObject("range").getAsJsonObject("start")
        assertEquals(line to character, start.get("line").asInt to start.get("character").asInt)
    }

    private fun writeDependency(returning: String) {
        val value = if (returning == "integer") "1" else "\"one\""
        File(projectRoot, "dep.tl").writeText(
            "local M = {}\nfunction M.value(): $returning\n  return $value\nend\nreturn M\n",
        )
    }

    private fun open(name: String, text: String): String {
        val uri = fileUri(File(projectRoot, name))
        notify("textDocument/didOpen", mapOf(
            "textDocument" to mapOf("uri" to uri, "languageId" to "teal", "version" to 1, "text" to text),
        ))
        return uri
    }

    private fun change(uri: String, text: String, version: Int) {
        notify("textDocument/didChange", mapOf(
            "textDocument" to mapOf("uri" to uri, "version" to version),
            "contentChanges" to listOf(mapOf("text" to text)),
        ))
    }

    private fun diagnosticsFor(uri: String): JsonArray {
        val message = next { it.get("method")?.asString == "textDocument/publishDiagnostics" }
        val params = message.getAsJsonObject("params")
        assertEquals(uri, params.get("uri").asString)
        return params.getAsJsonArray("diagnostics")
    }

    private fun assertRange(diagnostic: JsonObject, line: Int, start: Int, end: Int) {
        val range = diagnostic.getAsJsonObject("range")
        assertEquals(line, range.getAsJsonObject("start").get("line").asInt)
        assertEquals(start, range.getAsJsonObject("start").get("character").asInt)
        assertEquals(end, range.getAsJsonObject("end").get("character").asInt)
    }

    private fun request(method: String, params: Any?): JsonObject {
        val id = nextId.getAndIncrement()
        send(mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params))
        return next { it.has("id") && it.get("id").asInt == id && !it.has("method") }
    }

    private fun notify(method: String, params: Any?) {
        send(mapOf("jsonrpc" to "2.0", "method" to method, "params" to params))
    }

    private fun send(message: Map<String, Any?>) {
        LspFraming.writeMessage(process.outputStream, gson.toJson(message))
    }

    private fun next(matching: (JsonObject) -> Boolean): JsonObject {
        while (true) {
            val message = incoming.poll(10, TimeUnit.SECONDS) ?: error("timed out waiting for a server message")
            if (matching(message)) return message
        }
    }

    private fun fileUri(file: File): String =
        URI("file", "", file.absolutePath.replace(File.separatorChar, '/'), null).toString()

    private fun findOnPath(name: String): String? =
        System.getenv("PATH")?.split(File.pathSeparatorChar)
            ?.map { File(it, name) }
            ?.firstOrNull { it.canExecute() }
            ?.absolutePath
}
