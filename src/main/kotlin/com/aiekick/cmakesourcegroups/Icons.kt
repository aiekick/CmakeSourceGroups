package com.aiekick.cmakesourcegroups

// comments in English only
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.IconUtil
import java.io.File
import javax.swing.Icon

object Icons {
    @JvmField
    val CMakeIcon = IconLoader.getIcon("/META-INF/pluginIcon.svg", javaClass)

    /** Best-effort icon for an absolute file or directory path. */
    fun iconForAbsolutePath(vProject: Project, vAbsPath: String): Icon {
        val vf = LocalFileSystem.getInstance().findFileByPath(vAbsPath)
        if (vf != null) {
            // IDE-native icon (includes read-only/external overlays, etc.)
            IconUtil.getIcon(vf, Iconable.ICON_FLAG_READ_STATUS, vProject)?.let { return it }
            return if (vf.isDirectory) AllIcons.Nodes.Folder else AllIcons.FileTypes.Any_type
        }

        // Fallback by file name/extension
        val f = File(vAbsPath)
        if (f.isDirectory) return AllIcons.Nodes.Folder
        val ft = FileTypeManager.getInstance().getFileTypeByFileName(f.name)
        return ft.icon ?: AllIcons.FileTypes.Any_type
    }

    /** Icon from file name only (no VFS, no absolute path). */
    fun iconForFileName(name: String): Icon {
        val ft = FileTypeManager.getInstance().getFileTypeByFileName(name)
        return ft.icon ?: AllIcons.FileTypes.Any_type
    }
}