package com.aiekick.cmakesourcegroups

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class ToolCMakeSourceGroups : ToolWindowFactory {
    private lateinit var m_tree: Tree
    private lateinit var m_treeModel: DefaultTreeModel
    private lateinit var m_parser: CMakeApiParser
    private lateinit var m_project: Project
    private var m_selectedBuildType: String = "Auto"

    override fun createToolWindowContent(vProject: Project, vToolWindow: ToolWindow) {
        m_project = vProject
        val panel = createPanel()
        val contentManager = vToolWindow.contentManager
        val content = contentManager.factory.createContent(panel, "", false)
        contentManager.addContent(content)
        val toolbarActions = ToolbarActions()
        vToolWindow.setTitleActions(listOf(refreshAction, expandAllAction, collapseAllAction, toolbarActions))
        rebuildTree()
    }

    private inner class ToolbarActions : ComboBoxAction(), DumbAware {
        private val buildTypes = listOf("Auto", "Debug", "Release", "RelWithDebInfo", "MinSizeRel")

        override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
            val group = DefaultActionGroup()
            buildTypes.forEach { type ->
                // combobox for select mode
                group.add(object : ToggleAction(type), DumbAware {
                    override fun isSelected(e: AnActionEvent): Boolean {
                        return m_selectedBuildType == type
                    }

                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        if (state && m_selectedBuildType != type) {
                            m_selectedBuildType = type
                            rebuildTree()
                        }
                    }
                })
            }
            return group
        }

        override fun update(e: AnActionEvent) {
            super.update(e)
            // Keep the combo label short for the title bar
            e.presentation.text = "$m_selectedBuildType"
            e.presentation.icon = null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private fun createPanel(): JPanel {
        val root = DefaultMutableTreeNode("CMake Source Groups")
        m_treeModel = DefaultTreeModel(root)
        m_tree = Tree(m_treeModel)
        m_tree.cellRenderer = NodeRenderer(m_project)

        val scrollPane = JBScrollPane(m_tree)
        val panel = JPanel(BorderLayout())
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    private fun rebuildTree() {
        m_parser = CMakeApiParser(m_project)
        val buildDir = if (m_selectedBuildType == "Auto") {
            null  // Let the parser auto-discover
        } else {
            "cmake-build-${m_selectedBuildType.lowercase()}"
        }
        val root = m_parser.parse(buildDir)
        val swingRoot = sgToSwing(root)
        m_treeModel.setRoot(swingRoot)
        m_treeModel.reload()
    }

    private fun sgToSwing(root: SgNode): DefaultMutableTreeNode {
        val dm = DefaultMutableTreeNode(UINode(root.label, null, root.kind, root.targetType))
        for (child in root.children) {
            dm.add(sgToSwing(child))
        }
        return dm
    }

    private fun expandAll() {
        for (i in 0 until m_tree.rowCount) {
            m_tree.expandRow(i)
        }
    }

    private fun collapseAll() {
        for (i in m_tree.rowCount - 1 downTo 0) {
            m_tree.collapseRow(i)
        }
    }

    private val refreshAction = object : AnAction("Refresh", "Rebuild the tree", AllIcons.Actions.Refresh), DumbAware {
        override fun actionPerformed(e: AnActionEvent) = rebuildTree()
    }

    private val collapseAllAction =
        object : AnAction("Collapse All", "Collapse all nodes", AllIcons.Actions.Collapseall), DumbAware {
            override fun actionPerformed(e: AnActionEvent) = collapseAll()
        }

    private val expandAllAction =
        object : AnAction("Expand All", "Expand all nodes", AllIcons.Actions.Expandall), DumbAware {
            override fun actionPerformed(e: AnActionEvent) = expandAll()
        }

    override fun shouldBeAvailable(vProject: Project): Boolean = true
}
