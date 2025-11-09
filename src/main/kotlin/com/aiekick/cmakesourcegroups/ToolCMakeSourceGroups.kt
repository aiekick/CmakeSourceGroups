package com.aiekick.cmakesourcegroups

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JToolBar
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

class ToolCMakeSourceGroups : ToolWindowFactory {

    private lateinit var m_tree: JTree
    private lateinit var m_treeModel: DefaultTreeModel
    private lateinit var m_parser: CMakeApiParser
    private lateinit var m_project: Project

    override fun createToolWindowContent(vProject: Project, vToolWindow: ToolWindow) {
        m_project = vProject
        m_parser = CMakeApiParser(m_project)
        val contentFactory = ContentFactory.getInstance()
        val panel = createPanel()
        val content = contentFactory.createContent(panel, "", false)
        vToolWindow.contentManager.addContent(content)
    }

    private fun createPanel(): JPanel {
        val panel = JPanel(BorderLayout())

        // model + tree
        val rootSwing = DefaultMutableTreeNode("Loading...")
        m_treeModel = DefaultTreeModel(rootSwing)
        m_tree = JTree(m_treeModel).apply {
            showsRootHandles = true
        }
        m_tree.isOpaque = false
        m_tree.cellRenderer = NodeRenderer(m_project)

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

        // toolbar
        val toolbar = JToolBar().apply {
            isFloatable = false
            add(JButton("Refresh").apply {
                addActionListener { rebuildTree() }
            })
        }

        panel.add(JBScrollPane(m_tree), BorderLayout.CENTER)
        panel.add(toolbar, BorderLayout.SOUTH)

        // initial load
        rebuildTree()

        return panel
    }

    private fun rebuildTree() {
        // build in-memory tree
        val sgRoot = m_parser.buildTree()
        // convert to Swing
        val swingRoot = sgToSwing(sgRoot)
        m_treeModel.setRoot(swingRoot)
        collapseAll()
    }

    private fun sgToSwing(root: SgNode): DefaultMutableTreeNode {
        fun build(n: SgNode): DefaultMutableTreeNode {
            // label sans emojis éventuels au cas où le parser en a encore
            val cleanText = n.label
                .removePrefix("📁 ").removePrefix("📄 ").removePrefix("📜 ")
                .removePrefix("🔧 ").removePrefix("🧱 ").removePrefix("🧩 ")
                .removePrefix("▶️ ").removePrefix("⚙️ ")

            // Pour ouvrir/typer, on veut un chemin absolu si c’est un fichier.
            // Actuellement ton parser place l’abs path dans le label "name — ABS".
            val abs = if (n.kind == SgNode.Kind.FILE) {
                val idx = cleanText.lastIndexOf(" — ")
                if (idx > 0) cleanText.substring(idx + 3).trim().ifBlank { null } else null
            } else null

            val display = if (abs != null) cleanText.substringBefore(" — ").trim() else cleanText

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

    override fun shouldBeAvailable(project: Project): Boolean {
        return true
    }
}
