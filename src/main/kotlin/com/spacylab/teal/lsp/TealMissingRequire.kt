package com.spacylab.teal.lsp

import java.io.File

/**
 * The text-level logic behind the missing-require quick fix (see CONTEXT.md):
 * on an `unknown type X` diagnostic, find the workspace modules that declare a
 * global `X`, and work out where a bare `require("<module>")` goes.
 *
 * Deliberately a lightweight line scan, not real parsing -- the same trade-off
 * as [TealIdentifierScanner]. Kept free of JSON-RPC so [TealLspProxy] only has
 * to translate its results into LSP code actions.
 */
object TealMissingRequire {

    private val UNKNOWN_TYPE = Regex("""^unknown type ([A-Za-z_][A-Za-z0-9_]*)$""")
    private val REQUIRE_CALL = Regex("""\brequire\s*\(?\s*["']([^"']+)["']""")

    /** The type name in a typechecker `unknown type X` message, or null for any other message. */
    fun unknownTypeName(diagnosticMessage: String): String? =
        UNKNOWN_TYPE.matchEntire(diagnosticMessage.trim())?.groupValues?.get(1)

    /**
     * Every `.tl` file under [workspaceRoot] with a `global record|enum|interface|type <typeName>`
     * declaration, excluding [excludeFile] and anything inside a hidden directory. Scanned
     * on demand; there's no index.
     */
    fun findGlobalDeclarations(workspaceRoot: File, typeName: String, excludeFile: File?): List<File> {
        val declaration = Regex("""^\s*global\s+(record|enum|interface|type)\s+${Regex.escape(typeName)}\b""")
        val excluded = excludeFile?.canonicalFile
        return workspaceRoot.walkTopDown()
            .onEnter { dir -> dir == workspaceRoot || !dir.name.startsWith(".") }
            .filter { it.isFile && it.name.endsWith(".tl") && it.canonicalFile != excluded }
            .filter { file -> runCatching { file.useLines { lines -> lines.any(declaration::containsMatchIn) } }.getOrDefault(false) }
            .sortedBy { it.path }
            .toList()
    }

    /** `src/entities/player.tl` under [workspaceRoot] -> `src.entities.player`. */
    fun moduleName(workspaceRoot: File, file: File): String =
        file.canonicalFile.relativeTo(workspaceRoot.canonicalFile).path
            .removeSuffix(".d.tl")
            .removeSuffix(".tl")
            .replace(File.separatorChar, '.')
            .replace('/', '.')

    /** Module names passed to uncommented `require` calls anywhere in [text]. */
    fun requiredModules(text: String): Set<String> =
        text.lineSequence()
            .map(::stripLineComment)
            .flatMap { line -> REQUIRE_CALL.findAll(line).map { it.groupValues[1] } }
            .toSet()

    /**
     * The 0-based line to insert a new require at: right after the last require of
     * the file's leading block of requires (blank and comment lines may be
     * interleaved), or line 0 if the file doesn't start with any.
     */
    fun insertionLine(text: String): Int {
        var lastRequire = -1
        for ((index, rawLine) in text.lines().withIndex()) {
            val line = stripLineComment(rawLine).trim()
            when {
                line.isEmpty() -> continue
                REQUIRE_CALL.containsMatchIn(line) -> lastRequire = index
                else -> break
            }
        }
        return lastRequire + 1
    }

    fun requireStatement(moduleName: String): String = "require(\"$moduleName\")"

    // Naive: a "--" inside a string literal also cuts the line, which at worst
    // makes a require on that line invisible -- acceptable for a line scan.
    private fun stripLineComment(line: String): String {
        val commentStart = line.indexOf("--")
        return if (commentStart >= 0) line.substring(0, commentStart) else line
    }
}
