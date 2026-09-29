package com.spacylab.teal

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import com.spacylab.teal.server.TealServerConnectionProvider
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * How to run the Teal server: a `lua` interpreter that can load the `tl` rock,
 * plus any environment it needs to find it.
 *
 * IntelliJ launched from the Dock/Finder/Toolbox (not a terminal) inherits a
 * minimal environment on macOS: PATH is typically /usr/bin:/bin:/usr/sbin:/sbin,
 * missing Homebrew's /opt/homebrew/bin, and LUA_PATH/LUA_CPATH set by shell rc
 * files for `luarocks --local` installs are absent. Both fall back to asking a
 * real login shell, the same way a terminal would see them.
 */
object TealLuaRuntime {

    data class Runtime(val lua: String, val environment: Map<String, String>)

    sealed interface Result {
        data class Found(val runtime: Runtime) : Result
        data object LuaMissing : Result
        data class TlMissing(val lua: String) : Result
    }

    @Volatile
    private var cached: Runtime? = null

    /** Resolves the runtime, caching only success so an install is picked up on the next server start. */
    fun resolve(): Result {
        cached?.let { return Result.Found(it) }
        val lua = findOnRawPath("lua") ?: findViaLoginShell("lua") ?: return Result.LuaMissing

        val runtime = sequenceOf(emptyMap(), luaVariablesFromLoginShell())
            .distinct()
            .map { Runtime(lua, it) }
            .firstOrNull(::canLoadTl)
            ?: return Result.TlMissing(lua)
        cached = runtime
        return Result.Found(runtime)
    }

    private fun canLoadTl(runtime: Runtime): Boolean = try {
        val process = GeneralCommandLine(runtime.lua, "-e", "require('tl')")
            .withEnvironment(runtime.environment)
            .createProcess()
        process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (e: Exception) {
        false
    }

    private fun findOnRawPath(name: String): String? {
        val path = System.getenv("PATH") ?: return null
        return path.split(File.pathSeparatorChar)
            .map { File(it, name) }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
    }

    private fun findViaLoginShell(name: String): String? =
        loginShell("command -v $name")?.trim()?.takeIf { it.isNotEmpty() && File(it).canExecute() }

    // LUA_PATH, LUA_CPATH and their versioned forms (LUA_PATH_5_4, ...).
    private fun luaVariablesFromLoginShell(): Map<String, String> =
        loginShell("env")?.lineSequence()
            ?.filter { it.startsWith("LUA_") && '=' in it }
            ?.associate { it.substringBefore('=') to it.substringAfter('=') }
            ?: emptyMap()

    private fun loginShell(command: String): String? {
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
        return try {
            val process = ProcessBuilder(shell, "-lc", command).redirectErrorStream(false).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) output else null
        } catch (e: Exception) {
            null
        }
    }
}

class TealLanguageServerFactory : LanguageServerFactory {

    override fun createConnectionProvider(project: Project): StreamConnectionProvider {
        val runtime = when (val result = TealLuaRuntime.resolve()) {
            is TealLuaRuntime.Result.Found -> result.runtime
            TealLuaRuntime.Result.LuaMissing -> fail(
                project,
                "Lua not found on PATH",
                "The Teal server runs on Lua. Install it (for example <code>brew install lua luarocks</code>), " +
                    "then <code>luarocks install tl</code>, and restart the IDE.",
            )
            is TealLuaRuntime.Result.TlMissing -> fail(
                project,
                "The tl library isn't installed",
                "<code>${result.lua}</code> can't load <code>tl</code>. Install it with " +
                    "<code>luarocks install tl</code>, then restart the IDE.",
            )
        }
        return TealServerConnectionProvider(runtime.lua, runtime.environment, project.basePath)
    }

    // Fails fast with a clear message in the LSP console rather than silently doing nothing.
    private fun fail(project: Project, title: String, content: String): Nothing {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Teal")
            .createNotification(title, content, NotificationType.ERROR)
            .notify(project)
        error(title)
    }

    override fun createLanguageClient(project: Project): LanguageClientImpl = LanguageClientImpl(project)
}
