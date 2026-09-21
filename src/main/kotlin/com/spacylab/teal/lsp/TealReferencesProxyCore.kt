package com.spacylab.teal.lsp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Sits between an LSP client (LSP4IJ, in production) and the real
 * `teal-language-server` process and fakes `textDocument/references` support on
 * top of it.
 *
 * teal-language-server doesn't implement `textDocument/references` (no
 * `referencesProvider` capability -- see server_state.lua upstream), so LSP4IJ's
 * generic Find Usages support never fires for .tl files. This proxy answers
 * references requests itself: it scans the requested document's identifier
 * tokens (a lightweight scanner, not real parsing -- see [TealIdentifierScanner]),
 * then probes each same-named candidate with the real, already-supported
 * `textDocument/definition` request, keeping the ones that resolve to the same
 * declaration as the symbol under the cursor. Every other message passes through
 * to the real process untouched.
 *
 * Deliberately has no IntelliJ/LSP4IJ platform dependency, so it can be exercised
 * with a plain JUnit test against the real server binary via raw process streams
 * -- [TealReferencesProxyConnectionProvider] is the thin adapter that wires this
 * up as an LSP4IJ `StreamConnectionProvider`.
 */
class TealReferencesProxyCore(
    private val realServerInput: InputStream,
    private val realServerOutput: OutputStream,
) {
    private companion object {
        val GSON = Gson()
        const val PROBE_TIMEOUT_SECONDS = 5L
    }

    // The client's own outgoing request id -> method, so a matching response
    // (e.g. to "initialize") can be recognized and patched before forwarding.
    private val outgoingRequestMethods = ConcurrentHashMap<String, String>()

    // Requests this proxy issues on its own (definition probes), correlated by a
    // high id range that can't collide with the client's own (small, sequential) ids.
    private val pendingProxyRequests = ConcurrentHashMap<String, CompletableFuture<JsonObject>>()
    private val nextProxyId = AtomicLong(1_000_000_000L)

    // Tracked from didOpen/didChange so references requests have something to
    // scan; teal-language-server advertises full-document sync, so every change
    // notification carries the complete text, not a diff.
    private val documentText = ConcurrentHashMap<String, String>()

    // What the client reads server messages from. Backed by java.nio.channels.Pipe
    // rather than java.io.Piped(In|Out)putStream: this proxy has more than one
    // thread writing here (the long-lived pumpFromServer thread, and a
    // short-lived thread per textDocument/references reply), and PipedInputStream
    // tracks a single "last writer" thread internally -- once a short-lived
    // writer thread exits, the next read can spuriously throw "Write end dead"
    // even though pumpFromServer is still alive and about to write. NIO pipes
    // have no such thread-identity tracking.
    private val toClientPipe = Pipe.open()
    val clientInput: InputStream = Channels.newInputStream(toClientPipe.source())
    private val toClientSink: OutputStream = Channels.newOutputStream(toClientPipe.sink())

    // What the client writes its own messages to.
    private val fromClientPipe = Pipe.open()
    val clientOutput: OutputStream = Channels.newOutputStream(fromClientPipe.sink())
    private val fromClientSource: InputStream = Channels.newInputStream(fromClientPipe.source())

    fun start() {
        Thread(::pumpFromClient, "teal-refs-proxy-outgoing").apply { isDaemon = true }.start()
        Thread(::pumpFromServer, "teal-refs-proxy-incoming").apply { isDaemon = true }.start()
    }

    @Volatile
    private var stopped = false

    fun stop() {
        stopped = true
        runCatching { toClientPipe.sink().close() }
        runCatching { fromClientPipe.source().close() }
    }

    // --- client -> real server -------------------------------------------------

    private fun pumpFromClient() {
        try {
            while (true) {
                val raw = LspFraming.readMessage(fromClientSource) ?: return
                val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                if (json == null) {
                    LspFraming.writeMessage(realServerOutput, raw)
                    continue
                }

                val method = json.get("method")?.takeIf { it.isJsonPrimitive }?.asString
                val idElement = json.get("id")
                if (method != null && idElement != null) {
                    outgoingRequestMethods[idKey(idElement)] = method
                }

                when (method) {
                    "textDocument/didOpen", "textDocument/didChange" -> {
                        trackDocumentText(json)
                        LspFraming.writeMessage(realServerOutput, GSON.toJson(json))
                    }
                    "textDocument/didClose" -> {
                        documentUri(json)?.let { documentText.remove(it) }
                        LspFraming.writeMessage(realServerOutput, GSON.toJson(json))
                    }
                    "textDocument/references" -> {
                        val requestId = idElement ?: JsonNull.INSTANCE
                        Thread({ handleReferences(requestId, json) }, "teal-refs-proxy-request")
                            .apply { isDaemon = true }
                            .start()
                    }
                    else -> LspFraming.writeMessage(realServerOutput, GSON.toJson(json))
                }
            }
        } catch (e: Throwable) {
            if (!stopped) {
                System.err.println("[teal-refs-proxy] outgoing pump died: $e")
                e.printStackTrace()
            }
        }
    }

    private fun documentUri(message: JsonObject): String? =
        message.getAsJsonObject("params")
            ?.getAsJsonObject("textDocument")
            ?.get("uri")?.takeIf { it.isJsonPrimitive }?.asString

    private fun trackDocumentText(message: JsonObject) {
        val params = message.getAsJsonObject("params") ?: return
        val uri = documentUri(message) ?: return
        val text = params.getAsJsonObject("textDocument")?.get("text")?.takeIf { it.isJsonPrimitive }?.asString
            ?: params.getAsJsonArray("contentChanges")
                ?.lastOrNull()
                ?.asJsonObject
                ?.get("text")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return
        documentText[uri] = text
    }

    // --- real server -> client -------------------------------------------------

    private fun pumpFromServer() {
        try {
            while (true) {
                val raw = LspFraming.readMessage(realServerInput) ?: return
                val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                if (json == null) {
                    LspFraming.writeMessage(toClientSink, raw)
                    continue
                }

                val idElement = json.get("id")
                if (idElement != null && !json.has("method")) {
                    val key = idKey(idElement)
                    val pending = pendingProxyRequests.remove(key)
                    if (pending != null) {
                        pending.complete(json)
                        continue // our own probe request; the client never sent it
                    }
                    if (outgoingRequestMethods.remove(key) == "initialize") {
                        patchInitializeResult(json)
                    }
                }

                LspFraming.writeMessage(toClientSink, GSON.toJson(json))
            }
        } catch (e: Throwable) {
            if (!stopped) {
                System.err.println("[teal-refs-proxy] incoming pump died: $e")
                e.printStackTrace()
            }
        }
    }

    private fun patchInitializeResult(response: JsonObject) {
        val result = response.getAsJsonObject("result") ?: return
        val capabilities = result.getAsJsonObject("capabilities")
            ?: JsonObject().also { result.add("capabilities", it) }
        capabilities.addProperty("referencesProvider", true)
    }

    // --- textDocument/references -----------------------------------------------

    private fun handleReferences(requestId: JsonElement, request: JsonObject) {
        try {
            handleReferencesUnsafe(requestId, request)
        } catch (e: Throwable) {
            System.err.println("[teal-refs-proxy] references handler died: $e")
            e.printStackTrace()
            replyResult(requestId, JsonNull.INSTANCE)
        }
    }

    private fun handleReferencesUnsafe(requestId: JsonElement, request: JsonObject) {
        val params = request.getAsJsonObject("params")
        val uri = documentUri(request)
        val position = params?.getAsJsonObject("position")
        val line = position?.get("line")?.asInt
        val character = position?.get("character")?.asInt

        if (uri == null || line == null || character == null) {
            replyResult(requestId, JsonNull.INSTANCE)
            return
        }

        val includeDeclaration = params.getAsJsonObject("context")
            ?.get("includeDeclaration")?.takeIf { it.isJsonPrimitive }?.asBoolean == true

        val text = documentText[uri]
        val target = text?.let { TealIdentifierScanner.scan(it) }?.firstOrNull { it.contains(line, character) }
        if (text == null || target == null) {
            replyResult(requestId, JsonNull.INSTANCE)
            return
        }

        val candidates = TealIdentifierScanner.scan(text).filter { it.name == target.name }
        val definitionFutures = candidates.associateWith { token ->
            sendProbeRequest("textDocument/definition", uri, token.line, token.character)
        }

        val targetDeclarationKey = try {
            definitionFutures[target]
                ?.get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                ?.let(::declarationKeyFromDefinitionResponse)
        } catch (e: Exception) {
            null
        }

        if (targetDeclarationKey == null) {
            replyResult(requestId, JsonNull.INSTANCE)
            return
        }

        // The scanner visits identifiers in source order, and Teal requires a
        // local to be declared before any use of it, so the first candidate that
        // resolves to the target's own declaration *is* the declaration site.
        val locations = JsonArray()
        var seenDeclaration = false
        for (token in candidates) {
            val declarationKey = try {
                definitionFutures[token]?.get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    ?.let(::declarationKeyFromDefinitionResponse)
            } catch (e: Exception) {
                null
            }
            if (declarationKey != targetDeclarationKey) continue

            val isDeclarationSite = !seenDeclaration
            seenDeclaration = true
            if (includeDeclaration || !isDeclarationSite) {
                locations.add(locationJson(uri, token.line, token.character, token.name.length))
            }
        }

        replyResult(requestId, locations)
    }

    private fun sendProbeRequest(method: String, uri: String, line: Int, character: Int): CompletableFuture<JsonObject> {
        val id = nextProxyId.getAndIncrement()
        val future = CompletableFuture<JsonObject>()
        pendingProxyRequests[id.toString()] = future

        val request = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", JsonObject().apply {
                add("textDocument", JsonObject().apply { addProperty("uri", uri) })
                add("position", JsonObject().apply {
                    addProperty("line", line)
                    addProperty("character", character)
                })
            })
        }
        LspFraming.writeMessage(realServerOutput, GSON.toJson(request))
        return future
    }

    // teal-language-server's definition handler replies with a single Location
    // (never an array/LocationLink), but this tolerates an array too in case
    // that ever changes upstream.
    private fun declarationKeyFromDefinitionResponse(response: JsonObject): String? {
        val result = response.get("result")?.takeUnless { it.isJsonNull } ?: return null
        val location = when {
            result.isJsonObject -> result.asJsonObject
            result.isJsonArray && result.asJsonArray.size() > 0 -> result.asJsonArray[0].asJsonObject
            else -> null
        } ?: return null

        val uri = location.get("uri")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val start = location.getAsJsonObject("range")?.getAsJsonObject("start") ?: return null
        val line = start.get("line")?.asInt ?: return null
        val character = start.get("character")?.asInt ?: return null
        return "$uri:$line:$character"
    }

    private fun locationJson(uri: String, line: Int, character: Int, length: Int): JsonObject = JsonObject().apply {
        addProperty("uri", uri)
        add("range", JsonObject().apply {
            add("start", JsonObject().apply { addProperty("line", line); addProperty("character", character) })
            add("end", JsonObject().apply { addProperty("line", line); addProperty("character", character + length) })
        })
    }

    private fun replyResult(requestId: JsonElement, result: JsonElement) {
        val response = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", requestId)
            add("result", result)
        }
        LspFraming.writeMessage(toClientSink, GSON.toJson(response))
    }

    private fun idKey(id: JsonElement): String =
        if (id.isJsonPrimitive) id.asJsonPrimitive.asString else id.toString()
}
