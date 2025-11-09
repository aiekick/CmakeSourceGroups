package com.aiekick.cmakesourcegroups

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import java.awt.Component
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer

class NodeRenderer(private val project: Project) : DefaultTreeCellRenderer() {

    init {
        // Clean look, no gray block behind text
        isOpaque = false
        backgroundNonSelectionColor = UIUtil.getTreeBackground()
        textNonSelectionColor = UIUtil.getTreeForeground()
        backgroundSelectionColor = UIUtil.getTreeSelectionBackground(true)
        textSelectionColor = UIUtil.getTreeSelectionForeground()
        borderSelectionColor = UIUtil.getTreeSelectionBorderColor()
    }

    private fun iconForTargetType(type: String): Icon = when (type) {
        "EXECUTABLE" -> AllIcons.RunConfigurations.Application
        "STATIC_LIBRARY" -> AllIcons.Nodes.Library
        "OBJECT_LIBRARY" -> AllIcons.Nodes.Library
        "SHARED_LIBRARY", "MODULE_LIBRARY" -> AllIcons.Nodes.Library
        "UTILITY" -> AllIcons.Actions.Execute
        else -> AllIcons.Nodes.Module
    }

    override fun getTreeCellRendererComponent(
        tree: JTree, value: Any, sel: Boolean, expanded: Boolean,
        leaf: Boolean, row: Int, hasFocus: Boolean
    ): Component {
        val c = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)

        val dm = value as? DefaultMutableTreeNode
        val ui = dm?.userObject as? UiNode

        text = ui?.text ?: dm?.userObject?.toString().orEmpty()

        val icon = when (ui?.kind) {
            SgNode.Kind.FILE -> {
                // Prefer real path → exact file type icon; else fallback by name only
                val abs = ui.absPath
                if (!abs.isNullOrBlank()) iconForAbsolutePath(project, abs)
                else iconForFileName(ui.text)
            }
            SgNode.Kind.SOL_FOLDER, SgNode.Kind.GROUP -> AllIcons.Nodes.Folder
            SgNode.Kind.TARGET -> iconForTargetType(ui.targetType ?: "")
            else -> null
        }

        icon?.let { setIcon(it) }
        return c
    }
}
