package com.aiekick.cmakesourcegroups

data class UiNode(
    val text: String,           // label affiché
    val absPath: String?,       // chemin absolu si fichier connu, sinon null
    val kind: SgNode.Kind,       // ROOT / SOL_FOLDER / TARGET / GROUP / FILE / UNGROUPED
    val targetType: String? = null   // <- add this
)
