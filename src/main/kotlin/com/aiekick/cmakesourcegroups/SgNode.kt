package com.aiekick.cmakesourcegroups

data class SgNode(
    val label: String,
    val kind: Kind,
    var absPath: String? = null,
    val targetType: TargetType? = null,
    val children: MutableList<SgNode> = mutableListOf()
) {
    enum class TargetType {
        EXECUTABLE,
        STATIC_LIBRARY,
        OBJECT_LIBRARY,
        SHARED_LIBRARY,
        MODULE_LIBRARY,
        UTILITY
    }

    enum class Kind { ROOT, CMAKE_EXTRA, FLAG, TARGET, GROUP, FILE, UNGROUPED }
}
