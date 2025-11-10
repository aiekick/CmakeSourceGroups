package com.aiekick.cmakesourcegroups

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import java.awt.Component
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer

class NodeRenderer(private val vProject: Project) : DefaultTreeCellRenderer() {
    init {
        // Clean look, no gray block behind text
        isOpaque = false
        backgroundNonSelectionColor = UIUtil.getTreeBackground()
        textNonSelectionColor = UIUtil.getTreeForeground()
        backgroundSelectionColor = UIUtil.getTreeSelectionBackground(true)
        textSelectionColor = UIUtil.getTreeSelectionForeground()
        borderSelectionColor = UIUtil.getTreeSelectionBorderColor()
    }

    private fun iconForTargetType(vType: String): Icon = when (vType) {
        "EXECUTABLE" -> AllIcons.RunConfigurations.Application
        "STATIC_LIBRARY" -> AllIcons.Nodes.Library
        "OBJECT_LIBRARY" -> AllIcons.Nodes.Library
        "SHARED_LIBRARY", "MODULE_LIBRARY" -> AllIcons.Nodes.Library
        "UTILITY" -> AllIcons.Actions.Execute
        else -> AllIcons.Nodes.Module
    }

    override fun getTreeCellRendererComponent(
        vTree: JTree, vValue: Any, vSel: Boolean, vExpanded: Boolean,
        vLeaf: Boolean, vRow: Int, vHasFocus: Boolean
    ): Component {
        val c = super.getTreeCellRendererComponent(vTree, vValue, vSel, vExpanded, vLeaf, vRow, vHasFocus)

        val dm = vValue as? DefaultMutableTreeNode
        val ui = dm?.userObject as? UiNode

        text = ui?.text ?: dm?.userObject?.toString().orEmpty()
        text += " " + ui?.absPath

        val icon = when (ui?.kind) {
            SgNode.Kind.FILE -> {
                val abs = ui.absPath
                if (!abs.isNullOrBlank()) iconForAbsolutePath(vProject, abs)
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
