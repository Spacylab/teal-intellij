package com.spacylab.teal

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import java.io.File

/**
 * Resolves the `teal-language-server` executable installed via `luarocks install teal-language-server`.
 *
 * IntelliJ launched from the Dock/Finder/Toolbox (not a terminal) inherits a minimal PATH
 * (typically just /usr/bin:/bin:/usr/sbin:/sbin on macOS) that doesn't include Homebrew's
 * /opt/homebrew/bin or other shell-rc-configured directories where luarocks installs binaries.
 * When the direct PATH lookup fails, fall back to asking a real login shell to resolve it,
 * the same way a terminal would.
 */
object TealLanguageServerLocator {
    private const val EXECUTABLE_NAME = "teal-language-server"

    private val resolved: String? by lazy { findOnRawPath() ?: findViaLoginShell() }

    fun find(): String? = resolved

    private fun findOnRawPath(): String? {
        val path = System.getenv("PATH") ?: return null
        return path.split(File.pathSeparatorChar)
            .map { File(it, EXECUTABLE_NAME) }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
    }

    private fun findViaLoginShell(): String? {
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
        return try {
            val process = ProcessBuilder(shell, "-lc", "command -v $EXECUTABLE_NAME")
                .redirectErrorStream(false)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.takeIf { it.isNotEmpty() && File(it).canExecute() }
        } catch (e: Exception) {
            null
        }
    }
}

class TealLanguageServerFactory : LanguageServerFactory {

    override fun createConnectionProvider(project: Project): StreamConnectionProvider {
        val executable = TealLanguageServerLocator.find()
        if (executable == null) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Teal")
                .createNotification(
                    "teal-language-server not found on PATH",
                    "Install it with `luarocks install teal-language-server` (needs cmake for one of its dependencies), then restart the IDE.",
                    NotificationType.ERROR,
                )
                .notify(project)
            // Fails fast with a clear message in the LSP console rather than silently doing nothing.
            error("teal-language-server not found on PATH")
        }
        return OSProcessStreamConnectionProvider(GeneralCommandLine(executable))
    }

    override fun createLanguageClient(project: Project): LanguageClientImpl = LanguageClientImpl(project)
}
