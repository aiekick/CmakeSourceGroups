package com.aiekick.cmakesourcegroups

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Paths
import java.util.Locale

// In-memory tree model
data class SgNode(
    val label: String,
    val kind: Kind,
    val children: MutableList<SgNode> = mutableListOf()
) {
    enum class Kind { ROOT, SOL_FOLDER, TARGET, GROUP, FILE, UNGROUPED }
}

private data class DirInfo(val sourceAbs: String)

private data class TargetEntry(
    val file: File,
    val directoryIndex: Int?,
    val folderName: String?
)

class CMakeApiParser(private val project: Project) {
    private val gson = Gson()

    fun buildTree(): SgNode {
        val root = SgNode("CMake Source Groups", SgNode.Kind.ROOT)
        val replyDir = findReplyDir() ?: return root

        // codemodel
        val cmFile = replyDir.listFiles { _, n -> n.startsWith("codemodel-v2") && n.endsWith(".json") }
            ?.firstOrNull() ?: return root
        val cm = gson.fromJson(cmFile.readText(), JsonObject::class.java)

        // directories -> sourceAbs
        val dirInfoByIndex = loadDirectories(replyDir, cm)

        // targets
        val targets = collectTargets(replyDir, cm)

        // visual folders builder
        val folders = FolderIndex(root)

        // disk owner map: sourceAbs -> target node
        val ownerMap = linkedMapOf<String, SgNode>()

        // build targets + groups
        for (te in targets) {
            val tj = gson.fromJson(te.file.readText(), JsonObject::class.java)
            val tName = tj.get("name")?.asString ?: "<target>"

            val folderPath = te.folderName ?: tj.getAsJsonObject("folder")?.get("name")?.asString
            val parent = if (folderPath.isNullOrBlank()) root else folders.ensure(folderPath)

            val tNode = SgNode(tName, SgNode.Kind.TARGET)
            parent.children += tNode

            // owner (disk anchor -> this target)
            te.directoryIndex?.let { idx ->
                dirInfoByIndex[idx]?.sourceAbs?.let { src ->
                    if (src.isNotBlank()) ownerMap[normalizeAbs(src)] = tNode
                }
            }

            // include dirs
            val includeDirs = collectIncludeDirs(tj)
            if (includeDirs.isNotEmpty()) {
                val inc = SgNode("Include Dirs", SgNode.Kind.GROUP)
                includeDirs.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { p ->
                    inc.children += SgNode(p, SgNode.Kind.FILE)
                }
                tNode.children += inc
            }

            // source groups
            val sources = tj.getAsJsonArray("sources") ?: JsonArray()
            val groups = tj.getAsJsonArray("sourceGroups") ?: JsonArray()
            val pathsByIdx = ArrayList<String>(sources.size())
            for (i in 0 until sources.size()) {
                pathsByIdx += sources[i].asJsonObject.get("path")?.asString ?: ""
            }
            for (i in 0 until groups.size()) {
                val g = groups[i].asJsonObject
                val name = g.get("name")?.asString ?: "group"
                val parts = name.replace('\\','/').trim('/').split('/').filter { it.isNotEmpty() }
                val gNode = ensureGroupPath(tNode, parts)
                attachFilesOfGroup(project, g, pathsByIdx, gNode)
            }
            addUngroupedIfAny(project, pathsByIdx, groups, tNode)
        }

        // fallback owner: project root -> root node
        val projectBaseAbs = File(project.basePath ?: ".").absolutePath
        ownerMap[normalizeAbs(projectBaseAbs)] = root

        // cmakeFiles-v1: internal cmake scripts (absolute)
        val cmakeAbs = loadInternalCMakeFilesFromV1(replyDir, projectBaseAbs)

        // insert each cmake file under its visual owner, recreating disk subpath
        for (abs in cmakeAbs) {
            val ownerAbs = pickOwnerByLongestPrefix(ownerMap.keys, abs) ?: projectBaseAbs
            val ownerNode = ownerMap[ownerAbs] ?: root

            val rel = try {
                Paths.get(ownerAbs).relativize(Paths.get(abs)).toString().replace('\\','/')
            } catch (_: Exception) {
                File(abs).name
            }
            insertDiskPathUnder(ownerNode, rel, abs)
        }

        sortRec(root)
        return root
    }

    // ---------- load data ----------

    private fun collectTargets(replyDir: File, cm: JsonObject): List<TargetEntry> {
        val out = mutableListOf<TargetEntry>()

        cm.getAsJsonArray("objects")?.let { arr ->
            for (i in 0 until arr.size()) {
                val jf = arr[i].asJsonObject.get("jsonFile")?.asString ?: continue
                val f = replyDir.resolve(jf)
                if (f.isFile) out += TargetEntry(f, null, null)
            }
        }
        if (out.isNotEmpty()) return out

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

    private fun loadDirectories(replyDir: File, cm: JsonObject): Map<Int, DirInfo> {
        val out = HashMap<Int, DirInfo>()
        val arr = cm.getAsJsonArray("directories") ?: return out
        for (i in 0 until arr.size()) {
            val ref = arr[i].asJsonObject
            val jf = ref.get("jsonFile")?.asString ?: continue
            val f = replyDir.resolve(jf)
            if (!f.isFile) continue

            val dj = gson.fromJson(f.readText(), JsonObject::class.java)
            val sourceRel = dj.getAsJsonObject("paths")?.get("source")?.asString
                ?: dj.get("source")?.asString
                ?: ""
            val sourceAbs = toAbs(project.basePath ?: ".", sourceRel)
            out[i] = DirInfo(sourceAbs = normalizeAbs(sourceAbs))
        }
        return out
    }

    private fun loadInternalCMakeFilesFromV1(replyDir: File, projectBaseAbs: String): List<String> {
        val f = replyDir.listFiles { _, n -> n.startsWith("cmakeFiles-v1") && n.endsWith(".json") }
            ?.firstOrNull() ?: return emptyList()

        val root = gson.fromJson(f.readText(), JsonObject::class.java)
        val srcRoot = root.getAsJsonObject("paths")?.get("source")?.asString ?: projectBaseAbs
        val srcRootAbs = normalizeAbs(toAbs(projectBaseAbs, srcRoot))

        val keep = linkedSetOf<String>()
        root.getAsJsonArray("inputs")?.let { arr ->
            for (i in 0 until arr.size()) {
                val e = arr[i].asJsonObject
                val isCMake = e.get("isCMake")?.asBoolean == true
                val isExternal = e.get("isExternal")?.asBoolean == true
                val isGenerated = e.get("isGenerated")?.asBoolean == true
                if (isCMake || isExternal || isGenerated) continue

                val p = e.get("path")?.asString ?: continue
                keep += normalizeAbs(toAbs(srcRootAbs, p))
            }
        }
        return keep.toList()
    }

    // ---------- tree helpers ----------

    // Reuse existing folder (SOL_FOLDER or GROUP) else create GROUP
    private fun ensureFolderChild(parent: SgNode, name: String): SgNode {
        val label = name
        val hit = parent.children.firstOrNull {
            (it.kind == SgNode.Kind.SOL_FOLDER || it.kind == SgNode.Kind.GROUP) && it.label == label
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
                val node = ensureGroupPath(dst, name.replace('\\','/').trim('/').split('/').filter { it.isNotEmpty() })
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

    private fun insertDiskPathUnder(anchor: SgNode, relativeUnixPath: String, absPath: String) {
        val norm = relativeUnixPath.replace('\\','/').trim('/')
        val parts = if (norm.isEmpty()) emptyList() else norm.split('/')
        if (parts.isEmpty()) {
            val name = File(absPath).name
            ensureFile(anchor, name + " — " + absPath)
            return
        }
        var cur = anchor
        for (i in 0 until parts.size - 1) {
            cur = ensureFolderChild(cur, parts[i])
        }
        ensureFile(cur, parts.last() + " — " + absPath)
    }

    private fun ensureFile(parent: SgNode, label: String) {
        val hit = parent.children.firstOrNull { it.kind == SgNode.Kind.FILE && it.label == label }
        if (hit == null) parent.children += SgNode(label, SgNode.Kind.FILE)
    }

    // ---------- utils ----------

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
        val order = listOf("Debug","RelWithDebInfo","Release","MinSizeRel")
        val byName = HashMap<String, JsonObject>(cfgs.size())
        for (i in 0 until cfgs.size()) {
            val o = cfgs[i].asJsonObject
            byName[o.get("name")?.asString ?: ""] = o
        }
        for (n in order) byName[n]?.let { return it }
        return cfgs.firstOrNull()?.asJsonObject
    }

    private fun sortRec(node: SgNode) {
        node.children.sortWith(
            compareBy<SgNode> {
                when (it.kind) {
                    SgNode.Kind.SOL_FOLDER -> 0
                    SgNode.Kind.TARGET     -> 1
                    SgNode.Kind.GROUP      -> 2
                    SgNode.Kind.UNGROUPED  -> 3
                    else                   -> 4
                }
            }.thenBy { it.label.lowercase(Locale.ROOT) }
        )
        node.children.forEach { sortRec(it) }
    }

    private fun findReplyDir(): File? {
        val base = project.basePath ?: return null
        val root = File(base)
        val candidates = buildList {
            add(root)
            listOf("cmake-build-debug","cmake-build-release","build","out/build").forEach { add(File(root, it)) }
            root.listFiles { f -> f.isDirectory }?.forEach { dir ->
                if (File(dir, ".cmake/api/v1/reply").exists()) add(dir)
            }
        }.distinct()
        return candidates.firstNotNullOfOrNull { d ->
            val r = File(d, ".cmake/api/v1/reply")
            r.takeIf { it.isDirectory && it.listFiles()?.isNotEmpty() == true }
        }
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

    private fun pickOwnerByLongestPrefix(ownerKeys: Collection<String>, absPath: String): String? {
        val nPath = normalizeAbs(absPath)
        var best: String? = null
        var bestLen = -1
        for (key in ownerKeys) {
            val k = normalizeAbs(key).trimEnd('/', '\\')
            if (k.isEmpty()) continue
            if (startsWithPathSegment(nPath, k)) {
                if (k.length > bestLen) {
                    best = k
                    bestLen = k.length
                }
            }
        }
        return best
    }

    private fun startsWithPathSegment(full: String, prefix: String): Boolean {
        if (!full.startsWith(prefix)) return false
        if (full.length == prefix.length) return true
        val c = full[prefix.length]
        return c == '/' || c == '\\'
    }
}

/** Solution-folder builder that reuses existing folders if present. */
private class FolderIndex(private val root: SgNode) {
    fun ensure(path: String): SgNode {
        val parts = path.replace('\\','/').trim('/').split('/').filter { it.isNotEmpty() }
        var cur = root
        for (seg in parts) {
            val label = seg
            val hit = cur.children.firstOrNull {
                (it.kind == SgNode.Kind.SOL_FOLDER || it.kind == SgNode.Kind.GROUP) && it.label == label
            }
            cur = hit ?: SgNode(label, SgNode.Kind.SOL_FOLDER).also { cur.children += it }
        }
        return cur
    }
}
