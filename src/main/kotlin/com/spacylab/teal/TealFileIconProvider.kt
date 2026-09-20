package com.spacylab.teal

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * Shows vscode-teal's icon (icons/teal.svg, vendored under the same MIT license as the TextMate
 * bundle — see resources/teal-textmate-bundle/LICENSE-vscode-teal) for .tl files in the project
 * tree, editor tabs, etc. Icon selection only — doesn't touch file-type/language association, so
 * it can't conflict with the TextMate-driven highlighting the way a custom FileType would.
 */
class TealFileIconProvider : FileIconProvider {

    private val icon: Icon by lazy { IconLoader.getIcon("/icons/teal.svg", TealFileIconProvider::class.java) }

    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        if (file.extension == "tl") icon else null
}
