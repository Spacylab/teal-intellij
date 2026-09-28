package com.spacylab.teal.lsp

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration test against the real `teal-language-server` binary (skipped if
 * it isn't on PATH). Verifies [TealLspProxy]: fakes referencesProvider and
 * codeActionProvider in the initialize response, answers textDocument/references
 * itself by probing textDocument/definition, and answers textDocument/codeAction
 * with the missing-require quick fix -- the only parts of this that are actually
 * novel versus the real server's own behavior. No IntelliJ/LSP4IJ types involved, so
 * this runs as a plain JUnit test with no platform sandbox required.
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class TealLspProxyTest {

    private val gson = Gson()
    private val nextId = AtomicInteger(1)
    private lateinit var process: Process
    private lateinit var core: TealLspProxy
    private lateinit var projectRoot: File

    @BeforeEach
    fun setUp() {
        val executable = findOnPath("teal-language-server")
        assumeTrue(executable != null, "teal-language-server not found on PATH; skipping")

        projectRoot = File.createTempFile("teal-lsp-proxy-test", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }

        process = ProcessBuilder(executable).redirectErrorStream(false).start()
        core = TealLspProxy(process.inputStream, process.outputStream).also { it.start() }

        sendRequest("initialize", mapOf(
            "processId" to null,
            "rootUri" to fileUri(projectRoot),
            "capabilities" to emptyMap<String, Any>(),
        ))
        val initResponse = readResponse()
        send(mapOf("jsonrpc" to "2.0", "method" to "initialized", "params" to emptyMap<String, Any>()))

        // The capabilities this proxy exists to fake.
        val capabilities = initResponse.getAsJsonObject("result").getAsJsonObject("capabilities")
        assertTrue(
            capabilities.get("referencesProvider").asBoolean,
            "expected the proxy to add referencesProvider: true to the initialize response",
        )
        assertTrue(
            capabilities.get("codeActionProvider").asBoolean,
            "expected the proxy to add codeActionProvider: true to the initialize response",
        )
    }

    @AfterEach
    fun tearDown() {
        if (::core.isInitialized) core.stop()
        if (::process.isInitialized) process.destroy()
    }

    @Test
    fun `references of a local variable finds usages, excluding the declaration by default`() {
        val uri = openDocument(
            "refs_1.tl",
            """
            local function f(): number
              local x = 1
              return x
            end
            local function g(): number
              local x = 2
              return x + x
            end
            """.trimIndent(),
        )

        // "  local x = 1" -> 'x' at line 1, col 8
        val locations = getReferences(uri, line = 1, character = 8, includeDeclaration = false)
        assertEquals(1, locations.size(), "expected exactly the usage inside f, not the declaration or g's unrelated x")
        val range = locations[0].asJsonObject.getAsJsonObject("range")
        assertEquals(2, range.getAsJsonObject("start").get("line").asInt)
    }

    @Test
    fun `references of a local variable includes the declaration when requested`() {
        val uri = openDocument(
            "refs_2.tl",
            """
            local x = 1
            print(x)
            """.trimIndent(),
        )

        val locations = getReferences(uri, line = 1, character = 6, includeDeclaration = true)
        assertEquals(2, locations.size(), "expected both the declaration and the usage")
    }

    @Test
    fun `references of an unused local function returns an empty result, not just the declaration`() {
        val uri = openDocument(
            "refs_3.tl",
            """
            local function f(): number
              return 1
            end
            local function unused(): number
              return 2
            end
            """.trimIndent(),
        )

        // "local function unused()" -> 'unused' at line 3, col 15
        val locations = getReferences(uri, line = 3, character = 15, includeDeclaration = false)
        assertEquals(0, locations.size(), "the sole occurrence is the declaration itself, so it must be excluded")
    }

    @Test
    fun `references of a field access degrades gracefully to no result`() {
        val uri = openDocument(
            "refs_4.tl",
            """
            local record Point
              x: number
              y: number
            end
            local function make(): Point
              return { x = 1, y = 2 }
            end
            local p = make()
            print(p.x)
            """.trimIndent(),
        )

        // "print(p.x)" -> 'x' at line 8, col 8. The proxy resolves the cursor's own
        // declaration via the real (field-access-aware) textDocument/definition,
        // but none of the other "x" occurrences (the record field name, the table
        // constructor key) resolve to that same declaration, so this comes back
        // empty rather than null -- gracefully finding no further usages, not a
        // crash, which is what matters here.
        val locations = getReferences(uri, line = 8, character = 8, includeDeclaration = false)
        assertEquals(0, locations.size(), "field-access reference resolution isn't exact, but must not crash or false-match")
    }

    // --- missing-require quick fix -------------------------------------------

    private val playerRecord = "global record Player\n  name: string\nend\n"

    private val usesPlayer = """
        require("src.engine.types")
        -- require("src.entities.player")

        local function greet(p: Player): string
          return p.name
        end
        return greet
    """.trimIndent()

    @Test
    fun `offers one add-require action for a global record declared in another module`() {
        writeFile("src/engine/types.tl", "global record Unrelated\nend\n")
        writeFile("src/entities/player.tl", playerRecord)
        val (uri, diagnostics) = openWorkspaceDocument("src/engine/run.tl", usesPlayer)

        val actions = getCodeActions(uri, unknownTypeDiagnostic(diagnostics))

        assertEquals(1, actions.size())
        val action = actions[0].asJsonObject
        assertEquals("Add require(\"src.entities.player\")", action.get("title").asString)
        assertEquals("quickfix", action.get("kind").asString)
        val edit = singleEdit(action, uri)
        assertEquals("require(\"src.entities.player\")\n", edit.get("newText").asString)
        // Right after the last real require (line 0); the commented-out one doesn't count.
        assertEquals(1,edit.getAsJsonObject("range").getAsJsonObject("start").get("line").asInt)
    }

    @Test
    fun `offers one action per module when several declare the same global`() {
        writeFile("src/engine/types.tl", "")
        writeFile("src/entities/player.tl", playerRecord)
        writeFile("src/legacy/player.tl", playerRecord)
        val (uri, diagnostics) = openWorkspaceDocument("src/engine/run.tl", usesPlayer)

        val titles = getCodeActions(uri, unknownTypeDiagnostic(diagnostics))
            .map { it.asJsonObject.get("title").asString }

        assertEquals(
            listOf("Add require(\"src.entities.player\")", "Add require(\"src.legacy.player\")"),
            titles,
        )
    }

    @Test
    fun `offers nothing for a local record returned by its module`() {
        writeFile("src/engine/types.tl", "")
        writeFile("src/entities/player.tl", "local record Player\n  name: string\nend\nreturn Player\n")
        val (uri, diagnostics) = openWorkspaceDocument("src/engine/run.tl", usesPlayer)

        assertEquals(0, getCodeActions(uri, unknownTypeDiagnostic(diagnostics)).size())
    }

    @Test
    fun `does not offer a module the file already requires`() {
        writeFile("src/engine/types.tl", "")
        writeFile("src/entities/player.tl", playerRecord)
        val (uri, _) = openWorkspaceDocument(
            "src/engine/run.tl",
            usesPlayer.replace("-- require(\"src.entities.player\")", "require(\"src.entities.player\")"),
        )
        // The server may not report the error here, so feed a synthetic one:
        // the proxy must still skip the already-required module.
        val synthetic = gson.toJsonTree(mapOf(
            "range" to mapOf(
                "start" to mapOf("line" to 3, "character" to 24),
                "end" to mapOf("line" to 3, "character" to 30),
            ),
            "message" to "unknown type Player",
        )).asJsonObject

        assertEquals(0, getCodeActions(uri, synthetic).size())
    }

    @Test
    fun `applying the action clears the unknown type diagnostic`() {
        writeFile("src/engine/types.tl", "")
        writeFile("src/entities/player.tl", playerRecord)
        val (uri, diagnostics) = openWorkspaceDocument("src/engine/run.tl", usesPlayer)
        val edit = singleEdit(getCodeActions(uri, unknownTypeDiagnostic(diagnostics))[0].asJsonObject, uri)

        val lines = usesPlayer.lines().toMutableList()
        val insertAt = edit.getAsJsonObject("range").getAsJsonObject("start").get("line").asInt
        lines.add(insertAt, edit.get("newText").asString.removeSuffix("\n"))
        send(mapOf(
            "jsonrpc" to "2.0",
            "method" to "textDocument/didChange",
            "params" to mapOf(
                "textDocument" to mapOf("uri" to uri, "version" to 2),
                "contentChanges" to listOf(mapOf("text" to lines.joinToString("\n"))),
            ),
        ))
        val after = readDiagnostics(uri)

        assertTrue(
            after.none { it.get("message").asString.startsWith("unknown type") },
            "expected no unknown type diagnostics after the edit, got: $after",
        )
    }

    // --- helpers -----------------------------------------------------------

    private fun writeFile(path: String, text: String): File =
        File(projectRoot, path).apply { parentFile.mkdirs(); writeText(text) }

    // Like openDocument, but the file also exists on disk under the workspace
    // (so the server can resolve requires relative to it) and the diagnostics are kept.
    private fun openWorkspaceDocument(path: String, text: String): Pair<String, List<JsonObject>> {
        val uri = fileUri(writeFile(path, text))
        send(mapOf(
            "jsonrpc" to "2.0",
            "method" to "textDocument/didOpen",
            "params" to mapOf(
                "textDocument" to mapOf(
                    "uri" to uri, "languageId" to "teal", "version" to 1, "text" to text,
                ),
            ),
        ))
        return uri to readDiagnostics(uri)
    }

    private fun readDiagnostics(uri: String): List<JsonObject> =
        readUntil {
            it.get("method")?.asString == "textDocument/publishDiagnostics" &&
                it.getAsJsonObject("params").get("uri").asString == uri
        }.getAsJsonObject("params").getAsJsonArray("diagnostics").map { it.asJsonObject }

    private fun unknownTypeDiagnostic(diagnostics: List<JsonObject>): JsonObject =
        diagnostics.singleOrNull { it.get("message").asString == "unknown type Player" }
            ?: error("expected exactly one 'unknown type Player' diagnostic, got: $diagnostics")

    private fun getCodeActions(uri: String, diagnostic: JsonObject) =
        readResponse(sendRequest("textDocument/codeAction", mapOf(
            "textDocument" to mapOf("uri" to uri),
            "range" to diagnostic.get("range"),
            "context" to mapOf("diagnostics" to listOf(diagnostic)),
        ))).get("result").asJsonArray

    private fun singleEdit(action: JsonObject, uri: String): JsonObject =
        action.getAsJsonObject("edit").getAsJsonObject("changes").getAsJsonArray(uri).single().asJsonObject

    private fun findOnPath(name: String): String? =
        System.getenv("PATH")?.split(File.pathSeparatorChar)
            ?.map { File(it, name) }
            ?.firstOrNull { it.canExecute() }
            ?.absolutePath

    // File.toURI() doesn't reliably produce the "file://" double-slash form;
    // teal-language-server's uri.lua parser naively splits on a literal "://",
    // so it needs that form explicitly (see LspFraming for the framing side of
    // the same "match the real server's expectations exactly" theme).
    private fun fileUri(file: File): String = "file://" + file.absolutePath.replace(File.separatorChar, '/')

    private fun openDocument(fileName: String, text: String): String {
        val uri = fileUri(File(projectRoot, fileName))
        send(mapOf(
            "jsonrpc" to "2.0",
            "method" to "textDocument/didOpen",
            "params" to mapOf(
                "textDocument" to mapOf(
                    "uri" to uri, "languageId" to "teal", "version" to 1, "text" to text,
                ),
            ),
        ))
        // Drain the publishDiagnostics notification the server sends after opening.
        readUntil { it.get("method")?.asString == "textDocument/publishDiagnostics" }
        return uri
    }

    private fun getReferences(uri: String, line: Int, character: Int, includeDeclaration: Boolean) =
        readResponse(sendReferencesRequest(uri, line, character, includeDeclaration))
            .get("result").asJsonArray

    private fun sendReferencesRequest(uri: String, line: Int, character: Int, includeDeclaration: Boolean): Int =
        sendRequest("textDocument/references", mapOf(
            "textDocument" to mapOf("uri" to uri),
            "position" to mapOf("line" to line, "character" to character),
            "context" to mapOf("includeDeclaration" to includeDeclaration),
        ))

    private fun sendRequest(method: String, params: Any?): Int {
        val id = nextId.getAndIncrement()
        send(mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params))
        return id
    }

    private fun send(message: Map<String, Any?>) {
        LspFraming.writeMessage(core.clientOutput, gson.toJson(message))
    }

    private fun readResponse(id: Int? = null): JsonObject =
        readUntil { json -> json.has("id") && !json.has("method") && (id == null || json.get("id").asInt == id) }

    private fun readUntil(predicate: (JsonObject) -> Boolean): JsonObject {
        while (true) {
            val raw = LspFraming.readMessage(core.clientInput) ?: error("proxy stream closed unexpectedly")
            val json = JsonParser.parseString(raw).asJsonObject
            if (predicate(json)) return json
        }
    }
}
