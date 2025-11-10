package com.aiekick.cmakesourcegroups

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction

class ToolCMakeSourceGroups : ToolWindowFactory {
    private lateinit var m_tree: Tree
    private lateinit var m_treeModel: DefaultTreeModel
    private lateinit var m_parser: CMakeApiParser
    private lateinit var m_project: Project
    private var m_sortTopLevelByName: Boolean = false

    override fun createToolWindowContent(vProject: Project, vToolWindow: ToolWindow) {
        m_project = vProject
        m_parser = CMakeApiParser(m_project)
        val contentFactory = ContentFactory.getInstance()
        val panel = createPanel()
        val content = contentFactory.createContent(panel, "", false)
        vToolWindow.contentManager.addContent(content)

        // >>> Boutons dans l’entête du Tool Window
        (vToolWindow as? ToolWindowEx)?.apply {
            setTitleActions(listOf(refreshAction, collapseAllAction, expandAllAction))
            setAdditionalGearActions(gearGroup) // optionnel
        }
    }

    private fun createPanel(): JPanel {
        val panel = JPanel(BorderLayout())

        // model + tree
        val rootSwing = DefaultMutableTreeNode("Loading...")
        m_treeModel = DefaultTreeModel(rootSwing)
        m_tree = Tree(m_treeModel).apply {
            showsRootHandles = true
            isOpaque = false
            cellRenderer = NodeRenderer(m_project)
        }

        // open file on double click
        m_tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.clickCount != 2) return
                val row = m_tree.getRowForLocation(e.x, e.y)
                if (row < 0) return
                val path = m_tree.getPathForLocation(e.x, e.y) ?: return
                val dm = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val ui = dm.userObject as? UiNode ?: return
                if (ui.kind != SgNode.Kind.FILE) return
                val abs = ui.absPath ?: return
                val vf = LocalFileSystem.getInstance().findFileByPath(abs) ?: return
                FileEditorManager.getInstance(m_project).openFile(vf, true)
            }
        })

        panel.add(JBScrollPane(m_tree), BorderLayout.CENTER)

        // initial load
        rebuildTree()

        return panel
    }

    private fun rebuildTree() {
        val sgRoot = m_parser.buildTree()
        if (m_sortTopLevelByName) {
            sgRoot.children.sortBy { it.label.lowercase(java.util.Locale.ROOT) }
        }
        val swingRoot = sgToSwing(sgRoot)
        m_treeModel.setRoot(swingRoot)
        collapseAll()
    }

    private fun sgToSwing(root: SgNode): DefaultMutableTreeNode {
        fun build(n: SgNode): DefaultMutableTreeNode {
            // Pour ouvrir/typer, on veut un chemin absolu si c’est un fichier.
            // Actuellement ton parser place l’abs path dans le label "name — ABS".
            val abs = if (n.kind == SgNode.Kind.FILE) {
                val idx = n.label.lastIndexOf(" — ")
                if (idx > 0) n.label.substring(idx + 3).trim().ifBlank { null } else null
            } else null

            val display = if (abs != null) n.label.substringBefore(" — ").trim() else n.label

            val ui = UiNode(text = display, absPath = abs, kind = n.kind)
            val dn = DefaultMutableTreeNode(ui)
            n.children.forEach { child -> dn.add(build(child)) }
            return dn
        }
        return build(root)
    }

    private fun expandAll() {
        var i = 0
        while (i < m_tree.rowCount) {
            m_tree.expandRow(i)
            i++
        }
    }

    private fun collapseAll() {
        var i = m_tree.rowCount - 1
        while (i >= 0) {
            m_tree.collapseRow(i)
            i--
        }
    }

    private val refreshAction = object : AnAction("Refresh", "Rebuild the tree", AllIcons.Actions.Refresh), DumbAware {
        override fun actionPerformed(e: AnActionEvent) = rebuildTree()
    }

    private val collapseAllAction = object : AnAction("Collapse All", "Collapse all nodes", AllIcons.Actions.Collapseall), DumbAware {
        override fun actionPerformed(e: AnActionEvent) = collapseAll()
    }

    private val expandAllAction = object : AnAction("Expand All", "Expand all nodes", AllIcons.Actions.Expandall), DumbAware {
        override fun actionPerformed(e: AnActionEvent) = expandAll()
    }

    private val gearGroup = DefaultActionGroup().apply {
        // add(collapseAllAction(...)) etc. si tu veux
    }

    override fun shouldBeAvailable(vProject: Project): Boolean {
        return true
    }
}
