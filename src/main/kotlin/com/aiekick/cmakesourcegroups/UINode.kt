package com.aiekick.cmakesourcegroups

data class UiNode(
    val text: String,           // label
    val absPath: String?,       // chemin absolu ou null
    val kind: SgNode.Kind,       // ROOT / SOL_FOLDER / TARGET / GROUP / FILE / UNGROUPED
    val targetType: String? = null   // <- add this
)
