package com.spacylab.teal.lsp

import java.io.File

/**
 * The text-level logic behind go-to-definition on a require (see "require target"
 * in CONTEXT.md): find the require string literal under the cursor, and resolve its
 * module name to a workspace file.
 *
 * A single-line regex scan, not real parsing -- the same trade-off as
 * [TealMissingRequire]. Kept free of JSON-RPC so [TealLspProxy] only has to
 * translate the result into an LSP location.
 */
object TealRequireTarget {

    // Group 1 is the opening quote (backreferenced to find the closing one), group 2 the module name.
    private val REQUIRE_STRING = Regex("""\brequire\s*\(?\s*(["'])([^"']+)\1""")

    // Tried in order, relative to the workspace root; the first existing file wins.
    private val CANDIDATE_SUFFIXES = listOf(".tl", ".d.tl", "/init.tl", ".lua")

    /**
     * The module name of the require whose string literal covers [character] in
     * [line], quotes included, or null if the cursor isn't on a require string.
     */
    fun moduleNameAt(line: String, character: Int): String? =
        REQUIRE_STRING.findAll(line).firstOrNull { match ->
            val openQuote = match.groups[1]!!.range.first
            val closeQuote = match.range.last
            character in openQuote..closeQuote
        }?.groupValues?.get(2)

    /** `src.engine.run` -> the first of `src/engine/run.tl`, `.d.tl`, `/init.tl`, `.lua` under [workspaceRoot]. */
    fun resolve(workspaceRoot: File, moduleName: String): File? {
        val path = moduleName.replace('.', '/')
        return CANDIDATE_SUFFIXES.map { File(workspaceRoot, path + it) }.firstOrNull { it.isFile }
    }
}
