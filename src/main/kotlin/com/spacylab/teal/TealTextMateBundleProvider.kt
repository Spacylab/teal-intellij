package com.spacylab.teal

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider.PluginBundle
import java.nio.file.Files
import java.nio.file.Path

/**
 * Vendors vscode-teal's MIT-licensed TextMate grammar (see resources/teal-textmate-bundle/LICENSE-vscode-teal)
 * as a bundle the platform's TextMate Bundles support can load — the mechanism chosen in
 * .scratch/teal-plugin/spec.md for .tl syntax highlighting and file-type/icon association, since
 * teal-language-server has no semanticTokensProvider to drive LSP-based highlighting instead.
 *
 * TextMateBundleProvider needs a real filesystem directory, but plugin resources ship inside a jar,
 * so the bundle is extracted to a writable directory on every call rather than read in place.
 */
class TealTextMateBundleProvider : TextMateBundleProvider {

    private val logger = Logger.getInstance(TealTextMateBundleProvider::class.java)

    companion object {
        private val BUNDLE_FILES = listOf(
            "package.json",
            "language-configuration.json",
            "LICENSE-vscode-teal",
            "syntaxes/teal.tmLanguage.json",
            "assets/teal_icon_flat.svg",
        )
    }

    override fun getBundles(): List<PluginBundle> {
        val target = extractBundle() ?: return emptyList()
        return listOf(PluginBundle("Teal", target))
    }

    private fun extractBundle(): Path? {
        val targetDir = Path.of(PathManager.getSystemPath(), "teal-intellij", "textmate-bundle")
        return try {
            for (relativePath in BUNDLE_FILES) {
                val resource = javaClass.classLoader.getResourceAsStream("teal-textmate-bundle/$relativePath")
                    ?: run {
                        logger.warn("Missing bundled TextMate resource: $relativePath")
                        return@extractBundle null
                    }
                val destination = targetDir.resolve(relativePath)
                Files.createDirectories(destination.parent)
                resource.use { input ->
                    Files.copy(input, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
            targetDir
        } catch (e: Exception) {
            logger.warn("Failed to extract Teal TextMate bundle", e)
            null
        }
    }
}
