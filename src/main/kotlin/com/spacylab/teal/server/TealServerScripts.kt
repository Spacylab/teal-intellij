package com.spacylab.teal.server

import java.io.File

/**
 * The Lua sources of the Teal server (ADR 0001, third amendment), shipped as
 * resources under `/teal-server/`. `lua` can't run them from inside the plugin
 * jar, so [extractTo] copies them into a real directory first.
 */
object TealServerScripts {
    private const val RESOURCE_DIR = "/teal-server"

    // Listed explicitly: a directory inside a jar can't be enumerated portably.
    private val FILES = listOf("server.lua", "json.lua", "rpc.lua", "uri.lua", "diagnostics.lua", "workspace.lua", "lookup.lua", "features.lua", "requires.lua")

    /** Copies every server script into [dir] and returns the entry point, `server.lua`. */
    fun extractTo(dir: File): File {
        dir.mkdirs()
        for (name in FILES) {
            val stream = TealServerScripts::class.java.getResourceAsStream("$RESOURCE_DIR/$name")
                ?: error("missing server resource $RESOURCE_DIR/$name")
            stream.use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
        }
        return File(dir, "server.lua")
    }
}
