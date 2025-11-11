package com.aiekick.cmakesourcegroups


/** Solution-folder builder that reuses existing folders if present. */
class FolderIndex(private val root: SgNode) {
    fun ensure(path: String): SgNode {
        val parts = path.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() }
        var cur = root
        for (seg in parts) {
            val label = seg
            val hit = cur.children.firstOrNull {
                (it.kind == SgNode.Kind.GROUP) && it.label == label
            }
            cur = hit ?: SgNode(label, SgNode.Kind.GROUP).also { cur.children += it }
        }
        return cur
    }
}
