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

    companion object {
        private val libraryIcon: Icon by lazy {
            val iconNames = listOf("PpLib", "Static", "Library", "Artifact")
            for (iconName in iconNames) {
                try {
                    val field = AllIcons.Nodes::class.java.getField(iconName)
                    return@lazy field.get(null) as Icon
                } catch (e: Exception) {
                    // Continuer avec la suivante
                }
            }
            AllIcons.Nodes.Module
        }
    }

    private fun iconForTargetType(vType: SgNode.TargetType?): Icon = when (vType) {
        SgNode.TargetType.EXECUTABLE -> AllIcons.RunConfigurations.Application
        SgNode.TargetType.STATIC_LIBRARY -> libraryIcon
        SgNode.TargetType.OBJECT_LIBRARY -> libraryIcon
        SgNode.TargetType.SHARED_LIBRARY, SgNode.TargetType.MODULE_LIBRARY -> libraryIcon
        SgNode.TargetType.UTILITY -> AllIcons.Actions.Execute
        else -> AllIcons.Nodes.Module
    }

    override fun getTreeCellRendererComponent(
        vTree: JTree, vValue: Any, vSel: Boolean, vExpanded: Boolean,
        vLeaf: Boolean, vRow: Int, vHasFocus: Boolean
    ): Component {
        val c = super.getTreeCellRendererComponent(vTree, vValue, vSel, vExpanded, vLeaf, vRow, vHasFocus)

        val dm = vValue as? DefaultMutableTreeNode
        val ui = dm?.userObject as? UINode

        text = ui?.text ?: dm?.userObject?.toString().orEmpty()

        val icon = when (ui?.kind) {
            SgNode.Kind.FILE -> {
                val abs = ui.absPath
                if (!abs.isNullOrBlank()) Icons.iconForAbsolutePath(vProject, abs)
                else Icons.iconForFileName(ui.text)
            }
            SgNode.Kind.CMAKE_EXTRA -> AllIcons.Actions.Minimap
            SgNode.Kind.GROUP -> AllIcons.Nodes.Folder
            SgNode.Kind.TARGET -> iconForTargetType(ui.targetType)
            else -> null
        }

        icon?.let { setIcon(it) }
        return c
    }
}
