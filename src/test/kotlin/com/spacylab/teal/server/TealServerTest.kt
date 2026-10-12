package com.spacylab.teal.server

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.spacylab.teal.TealLuaRuntime
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
 * Integration test for the Teal server in `src/main/teal` (ADR 0001, third
 * amendment; compiled to Lua, ADR 0002), driven as a real `lua server.lua` process over
 * raw stdio. Finds lua the way the plugin does ([TealLuaRuntime]), so a Gradle
 * daemon started by an IDE still runs it; skipped only if no lua with the `tl`
 * rock can be found at all.
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
        val runtime = luaRuntime()

        projectRoot = File.createTempFile("teal-server-test", "").apply { delete(); mkdirs() }
        scriptDir = File.createTempFile("teal-server-scripts", "").apply { delete() }
        val script = TealServerScripts.extractTo(scriptDir)

        process = ProcessBuilder(runtime.lua, script.absolutePath)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .apply { environment().putAll(runtime.environment + env) }
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
    fun `a tl older than the pinned version is refused at startup`() {
        val runtime = luaRuntime()
        scriptDir = File.createTempFile("teal-server-scripts", "").apply { delete() }
        val script = TealServerScripts.extractTo(scriptDir)
        val fakeTl = File(scriptDir, "fake").apply { mkdirs() }
        File(fakeTl, "tl.lua").writeText("return { version = function() return \"0.0.1\" end }\n")

        process = ProcessBuilder(runtime.lua, script.absolutePath)
            .redirectErrorStream(true)
            .apply { environment().putAll(runtime.environment + ("LUA_PATH" to "${fakeTl.absolutePath}/?.lua;;")) }
            .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()

        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(1, process.exitValue(), output)
        assertTrue(output.contains("tl 0.0.1 is too old"), output)
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
    fun `a lex error is published with tl's message`() {
        start()
        val uri = open("main.tl", "local y = 1 $ 2\n")

        val errors = diagnosticsFor(uri)
        assertEquals(1, errors.size(), "got $errors")
        val error = errors[0].asJsonObject
        assertEquals("invalid token '$'", error.get("message").asString)
        assertEquals(12, error.getAsJsonObject("range").getAsJsonObject("start").get("character").asInt)
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

    // --- references (Find Usages) ------------------------------------------------

    @Test
    fun `references of a local variable find its usages, excluding the declaration by default`() {
        start()
        val uri = open("refs.tl", """
            local function f(): number
              local x = 1
              return x
            end
            local function g(): number
              local x = 2
              return x + x
            end
        """.trimIndent())
        diagnosticsFor(uri)

        // "  local x = 1" -> 'x' at 1:8
        val locations = references(uri, line = 1, character = 8, includeDeclaration = false)
        assertEquals(listOf(2), locations.map { it.first }, "expected only the usage inside f, not g's unrelated x")
    }

    @Test
    fun `references include the declaration when asked`() {
        start()
        val uri = open("refs.tl", "local x = 1\nprint(x)\n")
        diagnosticsFor(uri)

        assertEquals(listOf(0 to 6, 1 to 6), references(uri, line = 1, character = 6, includeDeclaration = true))
    }

    @Test
    fun `references of an unused local function are empty, not just the declaration`() {
        start()
        val uri = open("refs.tl", """
            local function f(): number
              return 1
            end
            local function unused(): number
              return 2
            end
            return f
        """.trimIndent())
        diagnosticsFor(uri)

        assertEquals(emptyList<Pair<Int, Int>>(), references(uri, line = 3, character = 15, includeDeclaration = false))
    }

    @Test
    fun `references of a field access find the same field on the same type only`() {
        start()
        val uri = open("refs.tl", """
            local record Point
              x: number
            end
            local record Other
              x: number
            end
            local p: Point = { x = 1 }
            local o: Other = { x = 2 }
            print(p.x, o.x, p.x)
        """.trimIndent())
        diagnosticsFor(uri)

        // "print(p.x, o.x, p.x)" -> the first p.x at 8:8
        assertEquals(listOf(8 to 8, 8 to 18), references(uri, line = 8, character = 8, includeDeclaration = false))
    }

    // --- missing-require quick fix ---------------------------------------------------

    @Test
    fun `offers one add-require action for a global record declared in another module`() {
        start()
        writeFile("src/engine/types.tl", "global record Unrelated\nend\n")
        writeFile("src/entities/player.tl", PLAYER_RECORD)
        val (uri, diagnostics) = openWorkspaceDocument("src/engine/run.tl", USES_PLAYER)

        val actions = codeActions(uri, unknownTypeDiagnostic(diagnostics))

        assertEquals(1, actions.size)
        val action = actions[0]
        assertEquals("Add require(\"src.entities.player\")", action.get("title").asString)
        assertEquals("quickfix", action.get("kind").asString)
        val edit = singleEdit(action, uri)
        assertEquals("require(\"src.entities.player\")\n", edit.get("newText").asString)
        // Right after the last real require (line 0); the commented-out one doesn't count.
        assertEquals(1, edit.getAsJsonObject("range").getAsJsonObject("start").get("line").asInt)
    }

    @Test
    fun `offers one action per module when several declare the same global`() {
        start()
        writeFile("src/engine/types.tl", "")
        writeFile("src/entities/player.tl", PLAYER_RECORD)
        writeFile("src/legacy/player.tl", PLAYER_RECORD)
        val (uri, diagnostics) = openWorkspaceDocument("src/engine/run.tl", USES_PLAYER)

        assertEquals(
            listOf("Add require(\"src.entities.player\")", "Add require(\"src.legacy.player\")"),
            codeActions(uri, unknownTypeDiagnostic(diagnostics)).map { it.get("title").asString },
        )
    }

    @Test
    fun `offers nothing for a local record returned by its module, or from a hidden directory`() {
        start()
        writeFile("src/engine/types.tl", "")
        writeFile("src/entities/player.tl", "local record Player\n  name: string\nend\nreturn Player\n")
        writeFile(".scratch/player.tl", PLAYER_RECORD)
        val (uri, diagnostics) = openWorkspaceDocument("src/engine/run.tl", USES_PLAYER)

        assertEquals(0, codeActions(uri, unknownTypeDiagnostic(diagnostics)).size)
    }

    @Test
    fun `does not offer a module the file already requires`() {
        start()
        writeFile("src/engine/types.tl", "")
        writeFile("src/entities/player.tl", PLAYER_RECORD)
        val (uri, _) = openWorkspaceDocument(
            "src/engine/run.tl",
            USES_PLAYER.replace("-- require(\"src.entities.player\")", "require(\"src.entities.player\")"),
        )
        // The file now type-checks, so feed a synthetic diagnostic: the module
        // must still be skipped because it is already required.
        val synthetic = gson.toJsonTree(mapOf(
            "range" to mapOf(
                "start" to mapOf("line" to 3, "character" to 24),
                "end" to mapOf("line" to 3, "character" to 30),
            ),
            "message" to "unknown type Player",
        )).asJsonObject

        assertEquals(0, codeActions(uri, synthetic).size)
    }

    @Test
    fun `applying the action clears the unknown type diagnostic`() {
        start()
        writeFile("src/engine/types.tl", "")
        writeFile("src/entities/player.tl", PLAYER_RECORD)
        val (uri, diagnostics) = openWorkspaceDocument("src/engine/run.tl", USES_PLAYER)
        val edit = singleEdit(codeActions(uri, unknownTypeDiagnostic(diagnostics))[0], uri)

        val lines = USES_PLAYER.lines().toMutableList()
        lines.add(edit.getAsJsonObject("range").getAsJsonObject("start").get("line").asInt, edit.get("newText").asString.removeSuffix("\n"))
        change(uri, lines.joinToString("\n"), version = 2)

        val after = diagnosticsFor(uri).map { it.asJsonObject.get("message").asString }
        assertTrue(after.none { it.startsWith("unknown type") }, "got: $after")
    }

    // --- go to require target ----------------------------------------------------------

    @Test
    fun `definition on a require string jumps to the top of the required module`() {
        start()
        val target = writeFile("src/engine/run.tl", "local M = {}\nreturn M\n")
        val text = "local run = require(\"src.engine.run\")\nreturn run\n"
        val (uri, _) = openWorkspaceDocument("src/main.tl", text)

        assertLocation(at("textDocument/definition", uri, line = 0, character = text.indexOf("engine")), fileUri(target), 0, 0)
        // The quotes count as part of the string.
        assertLocation(at("textDocument/definition", uri, line = 0, character = text.indexOf('"')), fileUri(target), 0, 0)
    }

    @Test
    fun `definition on a no-parens require resolves the init file`() {
        start()
        val target = writeFile("src/engine/init.tl", "global record Engine\nend\n")
        val text = "require \"src.engine\"\n"
        val (uri, _) = openWorkspaceDocument("src/main.tl", text)

        assertLocation(at("textDocument/definition", uri, line = 0, character = text.indexOf('"')), fileUri(target), 0, 0)
    }

    @Test
    fun `require targets resolve in priority order`() {
        start()
        writeFile("a/b.lua", "")
        writeFile("a/b/init.tl", "")
        val dtl = writeFile("a/b.d.tl", "")
        val text = "require(\"a.b\")\n"
        val (uri, _) = openWorkspaceDocument("main.tl", text)

        assertLocation(at("textDocument/definition", uri, line = 0, character = 10), fileUri(dtl), 0, 0)
    }

    @Test
    fun `definition on an unresolvable require returns null`() {
        start()
        val text = "local s = require(\"not.here\")\nreturn s\n"
        val (uri, _) = openWorkspaceDocument("src/main.tl", text)

        assertTrue(at("textDocument/definition", uri, line = 0, character = text.indexOf("not")).isJsonNull)
    }

    @Test
    fun `definition on a string that is not a require argument returns null`() {
        start()
        val text = "print(\"src.engine.run\")\n"
        val (uri, _) = openWorkspaceDocument("src/main.tl", text)

        assertTrue(at("textDocument/definition", uri, line = 0, character = 10).isJsonNull)
    }

    // --- semantic highlighting ----------------------------------------------------------

    @Test
    fun `initialize advertises full semantic tokens with the legend`() {
        val provider = start().getAsJsonObject("result").getAsJsonObject("capabilities")
            .getAsJsonObject("semanticTokensProvider")
        assertTrue(provider.get("full").asBoolean)
        val legend = provider.getAsJsonObject("legend")
        assertTrue(legend.getAsJsonArray("tokenTypes").map { it.asString }.containsAll(
            listOf("class", "parameter", "variable", "property", "function", "method"),
        ))
        assertTrue(legend.getAsJsonArray("tokenModifiers").map { it.asString }.containsAll(
            listOf("declaration", "readonly", "defaultLibrary", "global"),
        ))
    }

    @Test
    fun `semantic tokens classify each kind of identifier`() {
        val tokens = startWithSemantic()
        fun check(word: String, occurrence: Int, type: String, vararg modifiers: String) =
            assertEquals(type to modifiers.toSet(), tokens.at(word, occurrence), "$word #$occurrence")

        check("Player", 1, "class", "declaration")
        check("name", 1, "property", "declaration")
        check("update", 1, "method", "declaration")
        check("LIMIT", 1, "variable", "declaration", "readonly")
        check("counter", 1, "variable", "declaration", "global")
        check("heal", 1, "function", "declaration")
        check("p", 1, "parameter", "declaration")
        check("Player", 3, "class")
        check("total", 1, "variable", "declaration")
        check("amount", 2, "parameter")
        check("LIMIT", 2, "variable", "readonly")
        check("counter", 2, "variable", "global")
        check("print", 1, "function", "global", "defaultLibrary")
        check("p", 2, "parameter")
        check("name", 2, "property")
        check("string", 2, "variable", "global", "defaultLibrary")
        check("format", 1, "function")
        check("update", 2, "method")
        check("Player", 4, "class")
        check("grow", 1, "method", "declaration")
        check("self", 2, "parameter")
        check("love", 1, "variable", "global")
        check("graphics", 1, "property")
        check("Image", 1, "class")
    }

    @Test
    fun `semantic tokens still classify the valid part of a file that doesn't parse`() {
        val tokens = startWithSemantic(SEMANTIC_MAIN + "local broken = \n")
        assertEquals("function" to setOf("declaration"), tokens.at("heal", 1))
        assertEquals("variable" to setOf("readonly"), tokens.at("LIMIT", 2))
        assertEquals("property", tokens.at("name", 2)?.first)
    }

    // --- helpers -----------------------------------------------------------------

    private companion object {
        val SEMANTIC_LIB = """
            global type love = record
               type graphics = record
                  type Image = record
                     width: number
                  end
                  getDimensions: function(): number, number
               end
            end
        """.trimIndent() + "\n"

        val SEMANTIC_MAIN = """
            require("lib")
            local record Player
              name: string
              update: function(self: Player, dt: number)
            end
            local LIMIT <const> = 10
            global counter: integer = 0
            local function heal(p: Player, amount: number): boolean
              local total = amount + LIMIT + counter
              print(p.name, string.format("%d", total))
              p:update(1)
              return true
            end
            function Player:grow(x: number): number
              return x + #self.name
            end
            local w, _h = love.graphics.getDimensions()
            local function width(img: love.graphics.Image): number
              return img.width + w
            end
            print(heal, width)
        """.trimIndent() + "\n"

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

    private val PLAYER_RECORD = "global record Player\n  name: string\nend\n"

    private val USES_PLAYER = """
        require("src.engine.types")
        -- require("src.entities.player")

        local function greet(p: Player): string
          return p.name
        end
        return greet
    """.trimIndent()

    private class SemanticTokens(private val text: String, private val byStart: Map<Pair<Int, Int>, Pair<String, Set<String>>>) {
        /** Type and modifiers of the [occurrence]-th (1-based) `\bword\b` in the text, or null if unclassified. */
        fun at(word: String, occurrence: Int): Pair<String, Set<String>>? {
            val match = Regex("\\b${Regex.escape(word)}\\b").findAll(text).elementAtOrNull(occurrence - 1)
                ?: error("'$word' #$occurrence not in the text")
            val before = text.substring(0, match.range.first)
            return byStart[before.count { it == '\n' } to match.range.first - (before.lastIndexOf('\n') + 1)]
        }
    }

    private fun startWithSemantic(text: String = SEMANTIC_MAIN): SemanticTokens {
        val provider = start().getAsJsonObject("result").getAsJsonObject("capabilities")
            .getAsJsonObject("semanticTokensProvider").getAsJsonObject("legend")
        val types = provider.getAsJsonArray("tokenTypes").map { it.asString }
        val modifiers = provider.getAsJsonArray("tokenModifiers").map { it.asString }
        File(projectRoot, "lib.d.tl").writeText(SEMANTIC_LIB)
        val uri = open("main.tl", text)
        diagnosticsFor(uri)

        val response = request("textDocument/semanticTokens/full", mapOf("textDocument" to mapOf("uri" to uri)))
        val data = response.getAsJsonObject("result").getAsJsonArray("data").map { it.asInt }
        val byStart = mutableMapOf<Pair<Int, Int>, Pair<String, Set<String>>>()
        var line = 0
        var char = 0
        for (k in data.indices step 5) {
            if (data[k] > 0) char = 0
            line += data[k]
            char += data[k + 1]
            val mods = modifiers.indices.filter { data[k + 4] and (1 shl it) != 0 }.map { modifiers[it] }.toSet()
            byStart[line to char] = types[data[k + 3]] to mods
        }
        return SemanticTokens(text, byStart)
    }

    private fun writeFile(path: String, text: String): File =
        File(projectRoot, path).apply { parentFile.mkdirs(); writeText(text) }

    // Like open, but the file also exists on disk under the workspace, so
    // requires and workspace scans see it; returns its first diagnostics too.
    private fun openWorkspaceDocument(path: String, text: String): Pair<String, List<JsonObject>> {
        writeFile(path, text)
        val uri = open(path, text)
        return uri to diagnosticsFor(uri).map { it.asJsonObject }
    }

    private fun unknownTypeDiagnostic(diagnostics: List<JsonObject>): JsonObject =
        diagnostics.singleOrNull { it.get("message").asString == "unknown type Player" }
            ?: error("expected exactly one 'unknown type Player' diagnostic, got: $diagnostics")

    private fun codeActions(uri: String, diagnostic: JsonObject): List<JsonObject> {
        val response = request("textDocument/codeAction", mapOf(
            "textDocument" to mapOf("uri" to uri),
            "range" to diagnostic.get("range"),
            "context" to mapOf("diagnostics" to listOf(diagnostic)),
        ))
        return response.getAsJsonArray("result").map { it.asJsonObject }
    }

    private fun singleEdit(action: JsonObject, uri: String): JsonObject =
        action.getAsJsonObject("edit").getAsJsonObject("changes").getAsJsonArray(uri).single().asJsonObject

    private fun references(uri: String, line: Int, character: Int, includeDeclaration: Boolean): List<Pair<Int, Int>> {
        val response = request("textDocument/references", mapOf(
            "textDocument" to mapOf("uri" to uri),
            "position" to mapOf("line" to line, "character" to character),
            "context" to mapOf("includeDeclaration" to includeDeclaration),
        ))
        return response.getAsJsonArray("result").map {
            val start = it.asJsonObject.getAsJsonObject("range").getAsJsonObject("start")
            start.get("line").asInt to start.get("character").asInt
        }
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

    private fun luaRuntime(): TealLuaRuntime.Runtime {
        val result = TealLuaRuntime.resolve()
        assumeTrue(result is TealLuaRuntime.Result.Found, "no lua with the tl rock here ($result); skipping")
        return (result as TealLuaRuntime.Result.Found).runtime
    }
}
