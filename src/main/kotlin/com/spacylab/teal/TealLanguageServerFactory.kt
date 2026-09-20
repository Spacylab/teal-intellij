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

/** Resolves the `teal-language-server` executable installed via `luarocks install teal-language-server`. */
object TealLanguageServerLocator {
    private const val EXECUTABLE_NAME = "teal-language-server"

    fun find(): String? {
        val path = System.getenv("PATH") ?: return null
        return path.split(File.pathSeparatorChar)
            .map { File(it, EXECUTABLE_NAME) }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
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
