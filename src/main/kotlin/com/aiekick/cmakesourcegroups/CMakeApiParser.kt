package com.aiekick.cmakesourcegroups

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import java.io.File
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.pathString

class CMakeApiParser(private val vProject: Project) {
    private val gson = Gson()

    fun parse(vBuildDir: String?): SgNode {
        val root = SgNode(vProject.name, SgNode.Kind.ROOT)
        val replyDir = if (vBuildDir != null) {
            getReplyDir(vBuildDir) ?: return root
        } else {
            findReplyDir() ?: return root
        }

        // --- codemodel
        val cmFile = replyDir.listFiles { _, n -> n.startsWith("codemodel-v2") && n.endsWith(".json") }
            ?.firstOrNull() ?: return root
        val cm = gson.fromJson(cmFile.readText(), JsonObject::class.java)

        // --- project absolute root
        val projectBaseAbs = File(vProject.basePath ?: ".").absolutePath

        // --- targets
        val targets = collectTargets(replyDir, cm)

        // --- visual folders
        val folders = FolderIndex(root)

        // --- owner map: disk anchor -> visual TARGET node
        val ownerMap = linkedMapOf<String, SgNode>()

        // --- per-target
        for (te in targets) {
            val tj = gson.fromJson(te.file.readText(), JsonObject::class.java)
            val tName = tj.get("name")?.asString ?: "<target>"

            // visual parent (solution folder)
            val folderPath = te.folderName ?: tj.getAsJsonObject("folder")?.get("name")?.asString
            val parent = if (folderPath.isNullOrBlank()) root else folders.ensure(folderPath)

            // TARGET node (label includes resolved anchor for inspection)
            var targetTypeString = tj.get("type")?.asString
            var targetType = if (targetTypeString != null) {
                enumValueOf<SgNode.TargetType>(targetTypeString)
            } else {
                null
            }
            val targetLabel = "$tName"
            val tNode = SgNode(targetLabel, SgNode.Kind.TARGET, targetType)
            parent.children += tNode

            val extraNode = SgNode("CMakeExtras", SgNode.Kind.CMAKE_EXTRA)
            tNode.children += extraNode

            // Include dirs
            val includeDirs = collectIncludeDirs(tj)
            if (includeDirs.isNotEmpty()) {
                val inc = SgNode("Include Dirs", SgNode.Kind.GROUP)
                includeDirs.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { p ->
                    inc.children += SgNode(p, SgNode.Kind.FILE)
                }
                extraNode.children += inc
            }

            // Defines
            val defines = collectDefines(tj)
            if (defines.isNotEmpty()) {
                val defNode = SgNode("Defines", SgNode.Kind.GROUP)
                defines.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { d ->
                    defNode.children += SgNode(d, SgNode.Kind.FILE)
                }
                extraNode.children += defNode
            }

            // Dependencies (raw ids)
            val deps = collectDependencies(tj)
            if (deps.isNotEmpty()) {
                val dn = SgNode("Dependencies", SgNode.Kind.GROUP)
                deps.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { id ->
                    dn.children += SgNode(id, SgNode.Kind.FILE)
                }
                extraNode.children += dn
            }

            val flagsNode = SgNode("Flags", SgNode.Kind.GROUP)
            extraNode.children += flagsNode

            // Compile flags (fragments)
            val compileFrags = collectCompileFragments(tj)
            if (compileFrags.isNotEmpty()) {
                val cmpNode = SgNode("Compile", SgNode.Kind.GROUP)
                compileFrags.forEach { f -> cmpNode.children += SgNode(f, SgNode.Kind.FILE) }
                flagsNode.children += cmpNode
            }

            // Link / Archive flags
            val linkFrags = collectCommandFragments(tj.getAsJsonObject("link"))
            if (linkFrags.isNotEmpty()) {
                val ln = SgNode("Link", SgNode.Kind.GROUP)
                linkFrags.forEach { f -> ln.children += SgNode(f, SgNode.Kind.FILE) }
                flagsNode.children += ln
            }
            val archFrags = collectCommandFragments(tj.getAsJsonObject("archive"))
            if (archFrags.isNotEmpty()) {
                val an = SgNode("Archive", SgNode.Kind.GROUP)
                archFrags.forEach { f -> an.children += SgNode(f, SgNode.Kind.FILE) }
                flagsNode.children += an
            }

            // Source groups
            val sources = tj.getAsJsonArray("sources") ?: JsonArray()
            val groups = tj.getAsJsonArray("sourceGroups") ?: JsonArray()
            val pathsByIdx = ArrayList<String>(sources.size())
            for (i in 0 until sources.size()) {
                pathsByIdx += sources[i].asJsonObject.get("path")?.asString ?: ""
            }
            for (i in 0 until groups.size()) {
                val g = groups[i].asJsonObject
                val name = g.get("name")?.asString ?: "group"
                val parts = name.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() }
                val gNode = ensureGroupPath(tNode, parts)
                attachFilesOfGroup(vProject, g, pathsByIdx, gNode)
            }
            addUngroupedIfAny(vProject, pathsByIdx, groups, tNode)
        }

        // --- fallback owner
        ownerMap[normalizeAbs(projectBaseAbs)] = root

        // --- sort: folders/group/ungrouped → targets → files
        sortRec(root)
        return root
    }

    // ------------------- load data -------------------

    private fun collectTargets(replyDir: File, cm: JsonObject): List<TargetEntry> {
        val out = mutableListOf<TargetEntry>()

        // legacy
        cm.getAsJsonArray("objects")?.let { arr ->
            for (i in 0 until arr.size()) {
                val jf = arr[i].asJsonObject.get("jsonFile")?.asString ?: continue
                val f = replyDir.resolve(jf)
                if (f.isFile) out += TargetEntry(f, null, null)
            }
        }
        if (out.isNotEmpty()) return out

        // modern
        cm.getAsJsonArray("configurations")?.let { cfgs ->
            val chosen = pickConfiguration(cfgs) ?: return out
            chosen.getAsJsonArray("targets")?.let { tarr ->
                for (i in 0 until tarr.size()) {
                    val o = tarr[i].asJsonObject
                    val jf = o.get("jsonFile")?.asString ?: continue
                    val f = replyDir.resolve(jf)
                    if (!f.isFile) continue
                    val dirIdx = o.get("directoryIndex")?.asInt
                    val folderName = o.get("folderName")?.asString
                        ?: o.getAsJsonObject("folder")?.get("name")?.asString
                    out += TargetEntry(f, dirIdx, folderName)
                }
            }
        }
        return out
    }

    // ------------------- per-target extra sections -------------------

    private fun collectDefines(tj: JsonObject): Set<String> {
        val out = linkedSetOf<String>()
        tj.getAsJsonArray("compileGroups")?.let { groups ->
            for (i in 0 until groups.size()) {
                val cg = groups[i].asJsonObject
                cg.getAsJsonArray("defines")?.let { defs ->
                    for (j in 0 until defs.size()) {
                        defs[j].asJsonObject.get("define")?.asString?.let { d ->
                            if (d.isNotBlank()) out += d
                        }
                    }
                }
            }
        }
        return out
    }

    private fun collectCompileFragments(tj: JsonObject): List<String> {
        val out = mutableListOf<String>()
        tj.getAsJsonArray("compileGroups")?.let { groups ->
            for (i in 0 until groups.size()) {
                val cg = groups[i].asJsonObject
                cg.getAsJsonArray("compileCommandFragments")?.let { frags ->
                    for (j in 0 until frags.size()) {
                        frags[j].asJsonObject.get("fragment")?.asString?.let { f ->
                            if (f.isNotBlank()) out += f
                        }
                    }
                }
            }
        }
        return out
    }

    private fun collectCommandFragments(obj: JsonObject?): List<String> {
        if (obj == null) return emptyList()
        val out = mutableListOf<String>()
        obj.getAsJsonArray("commandFragments")?.let { frags ->
            for (i in 0 until frags.size()) {
                frags[i].asJsonObject.get("fragment")?.asString?.let { f ->
                    if (f.isNotBlank()) out += f
                }
            }
        }
        return out
    }

    private fun collectDependencies(tj: JsonObject): List<String> {
        val out = mutableListOf<String>()
        tj.getAsJsonArray("dependencies")?.let { deps ->
            for (i in 0 until deps.size()) {
                val d = deps[i].asJsonObject
                val id = d.get("id")?.asString
                if (!id.isNullOrBlank()) out += id
            }
        }
        return out
    }

    // ------------------- tree helpers -------------------

    // Reuse existing folder (SOL_FOLDER or GROUP) else create GROUP
    private fun ensureFolderChild(parent: SgNode, name: String): SgNode {
        val label = name
        val hit = parent.children.firstOrNull {
            (it.kind == SgNode.Kind.GROUP) && it.label == label
        }
        if (hit != null) return hit
        val created = SgNode(label, SgNode.Kind.GROUP)
        parent.children += created
        return created
    }

    private fun ensureGroupPath(parent: SgNode, parts: List<String>): SgNode {
        var cur = parent
        for (seg in parts) cur = ensureFolderChild(cur, seg)
        return cur
    }

    private fun attachFilesOfGroup(project: Project, groupJson: JsonObject, pathsByIdx: List<String>, dst: SgNode) {
        val idxArr = groupJson.getAsJsonArray("sourceIndexes") ?: JsonArray()
        for (k in 0 until idxArr.size()) {
            val i = idxArr[k].asInt
            val p = pathsByIdx.getOrNull(i) ?: continue
            val abs = normalizeAbs(toAbs(project.basePath ?: ".", p))
            dst.children += SgNode(File(abs).name + " — " + abs, SgNode.Kind.FILE)
        }
        groupJson.getAsJsonArray("sourceGroups")?.let { sub ->
            for (m in 0 until sub.size()) {
                val sg = sub[m].asJsonObject
                val name = sg.get("name")?.asString ?: "group"
                val node = ensureGroupPath(dst, name.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() })
                attachFilesOfGroup(project, sg, pathsByIdx, node)
            }
        }
    }

    private fun addUngroupedIfAny(project: Project, pathsByIdx: List<String>, groups: JsonArray, target: SgNode) {
        if (pathsByIdx.isEmpty()) return
        val grouped = hashSetOf<Int>()
        fun collect(g: JsonObject) {
            g.getAsJsonArray("sourceIndexes")?.let { arr ->
                for (i in 0 until arr.size()) grouped += arr[i].asInt
            }
            g.getAsJsonArray("sourceGroups")?.let { sub ->
                for (i in 0 until sub.size()) collect(sub[i].asJsonObject)
            }
        }
        for (i in 0 until groups.size()) collect(groups[i].asJsonObject)
        if (grouped.size >= pathsByIdx.size) return

        val un = SgNode("Ungrouped", SgNode.Kind.UNGROUPED)
        for (i in pathsByIdx.indices) if (i !in grouped) {
            val abs = normalizeAbs(toAbs(project.basePath ?: ".", pathsByIdx[i]))
            un.children += SgNode(File(abs).name + " — " + abs, SgNode.Kind.FILE)
        }
        if (un.children.isNotEmpty()) target.children += un
    }
    // ------------------- utils -------------------

    private fun collectIncludeDirs(tj: JsonObject): Set<String> {
        val out = linkedSetOf<String>()
        tj.getAsJsonArray("compileGroups")?.let { groups ->
            for (i in 0 until groups.size()) {
                val cg = groups[i].asJsonObject
                cg.getAsJsonArray("includes")?.let { incs ->
                    for (j in 0 until incs.size()) {
                        incs[j].asJsonObject.get("path")?.asString?.let { p ->
                            if (p.isNotBlank()) out += normalizeAbs(p)
                        }
                    }
                }
            }
        }
        return out
    }

    private fun pickConfiguration(cfgs: JsonArray): JsonObject? {
        val order = listOf("Debug", "RelWithDebInfo", "Release", "MinSizeRel")
        val byName = HashMap<String, JsonObject>(cfgs.size())
        for (i in 0 until cfgs.size()) {
            val o = cfgs[i].asJsonObject
            byName[o.get("name")?.asString ?: ""] = o
        }
        for (n in order) byName[n]?.let { return it }
        return cfgs.firstOrNull()?.asJsonObject
    }

    private fun kindRank(k: SgNode.Kind): Int = when (k) {
        SgNode.Kind.CMAKE_EXTRA, ->0
        SgNode.Kind.GROUP, SgNode.Kind.UNGROUPED -> 1
        SgNode.Kind.TARGET -> 2
        SgNode.Kind.FILE -> 3
        SgNode.Kind.ROOT -> -1
    }

    private fun sortRec(node: SgNode) {
        node.children.sortWith(
            compareBy<SgNode>(
                { kindRank(it.kind) },
                { it.label.lowercase(Locale.ROOT) }
            )
        )
        node.children.forEach { sortRec(it) }
    }

    private fun getReplyDir(vBuildDir: String?): File? {
        val projectPath = vProject.basePath ?: return null
        var buildDir = vBuildDir ?: return null
        val path = Path(projectPath, buildDir, ".cmake/api/v1/reply")
        if (path.exists()) {
            return File(path.pathString)
        } else {
            return null
        }
    }

    private fun findReplyDir(): File? {
        listOf(
            "cmake-build-debug",
            "cmake-build-release",
            "cmake-build-relwithdebinfo",
            "cmake-build-minsizerel"
        ).forEach {
            var replyDir = getReplyDir(it)
            if (replyDir != null) {
                return replyDir
            }
        }
        return null
    }

    private fun toAbs(baseDir: String, relOrAbs: String): String {
        if (relOrAbs.isBlank()) return File(baseDir).absolutePath
        if (isAbs(relOrAbs)) return File(relOrAbs).absolutePath
        return File(baseDir, relOrAbs).absolutePath
    }

    private fun isAbs(p: String): Boolean {
        return p.startsWith("/") ||
                Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(p) ||
                p.startsWith("\\\\")
    }

    private fun normalizeAbs(p: String): String = File(p).absolutePath
}

