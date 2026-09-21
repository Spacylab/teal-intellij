package com.spacylab.teal.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.redhat.devtools.lsp4ij.server.CannotStartProcessException
import com.redhat.devtools.lsp4ij.server.LanguageServerLogErrorHandler
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CopyOnWriteArrayList

/**
 * LSP4IJ `StreamConnectionProvider` adapter: launches the real
 * `teal-language-server` process and wires it up through [TealReferencesProxyCore],
 * which fakes `textDocument/references` support on top of it (see its doc for
 * why). All the actual proxying logic lives there, kept free of IntelliJ/LSP4IJ
 * types so it can be tested directly against the real server binary.
 */
class TealReferencesProxyConnectionProvider(
    private val commandLine: GeneralCommandLine,
) : StreamConnectionProvider {

    private var process: Process? = null
    private var core: TealReferencesProxyCore? = null
    private val logErrorHandlers = CopyOnWriteArrayList<LanguageServerLogErrorHandler>()

    override fun start() {
        val proc = try {
            commandLine.createProcess()
        } catch (e: Exception) {
            throw CannotStartProcessException(e)
        }
        process = proc
        core = TealReferencesProxyCore(proc.inputStream, proc.outputStream).also { it.start() }

        // The child's stderr must be drained continuously: if it's never read and
        // the OS pipe buffer fills (teal-language-server does log warnings/traces
        // in some configurations), the server blocks writing to it -- which stalls
        // its own stdin/stdout processing too, silently hanging every LSP request.
        // OSProcessStreamConnectionProvider/OSProcessHandler normally do this for
        // free; this proxy owns the process directly, so it has to do it itself.
        Thread({ drainStderr(proc) }, "teal-refs-proxy-stderr").apply { isDaemon = true }.start()
    }

    override fun getInputStream() = core!!.clientInput
    override fun getOutputStream() = core!!.clientOutput

    override fun addLogErrorHandler(handler: LanguageServerLogErrorHandler) {
        logErrorHandlers.add(handler)
    }

    override fun isAlive(): Boolean = process?.isAlive == true

    override fun stop() {
        process?.destroy()
        core?.stop()
    }

    private fun drainStderr(proc: Process) {
        try {
            BufferedReader(InputStreamReader(proc.errorStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: return
                    logErrorHandlers.forEach { it.logError(line) }
                }
            }
        } catch (e: Exception) {
            // Process torn down; nothing to do.
        }
    }
}
