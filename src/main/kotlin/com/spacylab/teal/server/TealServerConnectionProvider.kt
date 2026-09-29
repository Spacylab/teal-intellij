package com.spacylab.teal.server

import com.intellij.execution.configurations.GeneralCommandLine
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import java.io.File
import java.nio.file.Files

/**
 * Runs the Teal server (ADR 0001, third amendment) as `lua server.lua`. The
 * scripts are copied out of the plugin jar into a fresh directory at each start,
 * so a plugin update never runs against stale copies, and deleted at stop.
 */
class TealServerConnectionProvider(
    private val lua: String,
    private val environment: Map<String, String>,
    private val workingDirectory: String?,
) : OSProcessStreamConnectionProvider() {

    private var scriptDir: File? = null

    override fun start() {
        val dir = Files.createTempDirectory("teal-server").toFile()
        scriptDir = dir
        val server = TealServerScripts.extractTo(dir)
        commandLine = GeneralCommandLine(lua, server.absolutePath)
            .withEnvironment(environment)
            .withWorkDirectory(workingDirectory)
        super.start()
    }

    override fun stop() {
        super.stop()
        scriptDir?.deleteRecursively()
        scriptDir = null
    }
}
