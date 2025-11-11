package com.aiekick.cmakesourcegroups

import java.io.File

data class TargetEntry(
    val file: File,
    val directoryIndex: Int?,
    val folderName: String?
)
