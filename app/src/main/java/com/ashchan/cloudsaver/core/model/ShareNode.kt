package com.ashchan.cloudsaver.core.model

/**
 * 分享内目录树节点。
 *
 * 为什么要树：资源发布者的实际结构常是 `根 / [剧名] / [第01季] / 视频文件`，
 * 视频可能埋在二、三级目录。扁平的一层列表无法判断"哪一层才是剧集目录"，
 * 也无法把深层文件纳入改名计划。此模型用于承载递归扫描结果。
 *
 * 约定：
 * - [file] 复用现有 [DriveFile]，不新造字段，避免与适配器层重复。
 * - [depth] 由构建方赋值：根 = 0，往下一层 +1。
 * - 叶子节点（文件）[children] 恒为空列表。
 */
data class ShareNode(
    val file: DriveFile,
    val children: List<ShareNode> = emptyList(),
    val depth: Int = 0,
) {
    val fid: String get() = file.fid
    val name: String get() = file.name
    val isDir: Boolean get() = file.isDir

    val isVideo: Boolean get() = !isDir && VideoTypes.isVideo(name)

    /** 自己 + 所有后代的总节点数（含目录） */
    val totalCount: Int
        get() = 1 + children.sumOf { it.totalCount }

    /** 自身及后代中视频文件的总数（用于判断"这一层是不是剧集目录"） */
    val videoCount: Int
        get() = (if (isVideo) 1 else 0) + children.sumOf { it.videoCount }

    /** 直接子节点里的视频文件数（判断"我这一层是不是剧集目录"的关键指标） */
    val directVideoCount: Int
        get() = children.count { it.isVideo }

    /** 深度优先展开所有文件节点（不含目录），按自然序 */
    fun flattenFiles(): List<ShareNode> {
        if (!isDir) return listOf(this)
        return children
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .flatMap { it.flattenFiles() }
    }

    /**
     * 找出「剧集目录」：直接包含 ≥ [minVideos] 个视频文件的目录。
     *
     * 只按直接子级计数，不做后代累计——否则 `[剧名]` 这种上层容器会因为
     * 子孙视频多而被误判成剧集目录，导致改名层级错位。
     */
    fun findVideoDirs(minVideos: Int = DEFAULT_MIN_VIDEOS): List<ShareNode> {
        val hits = if (isDir && directVideoCount >= minVideos) listOf(this) else emptyList()
        return hits + children.flatMap { it.findVideoDirs(minVideos) }
    }

    /** 以当前节点为根的子树里，所有剧集目录的直接视频文件（用于转存单元） */
    fun videoFilesInDirs(minVideos: Int = DEFAULT_MIN_VIDEOS): List<ShareNode> =
        findVideoDirs(minVideos).flatMap { dir ->
            dir.children.filter { it.isVideo }.sortedWith(compareBy(NaturalOrder) { it.name })
        }

    /** 整棵树的最大深度（根=0） */
    fun maxDepth(): Int = children.maxOfOrNull { it.maxDepth() }?.let { maxOf(depth, it) } ?: depth

    override fun toString(): String {
        val mark = when {
            !isDir -> if (isVideo) "🎬" else "📄"
            directVideoCount >= DEFAULT_MIN_VIDEOS -> "📁★"
            else -> "📁"
        }
        val kids = if (children.isEmpty()) "" else children.joinToString(", ", "[", "]") { it.toString() }
        return "$mark$name$kids"
    }

    companion object {
        /** 直接子级视频数达到此值即视为剧集目录。资源以剧集为主，2 集起步合理。 */
        const val DEFAULT_MIN_VIDEOS: Int = 2
    }
}

/** 视频扩展名识别。集中在此，避免各适配器各写一套。 */
object VideoTypes {
    private val EXTENSIONS = setOf(
        "mkv", "mp4", "ts", "avi", "rmvb", "rm", "mov", "wmv", "flv", "m4v", "webm", "mpg", "mpeg", "iso"
    )

    /** 明显不是正片的（样本/预告/花絮），用于后续过滤，暂不参与剧集目录判定 */
    private val NOISE_MARKERS = listOf("sample", "trailer", "预告", "花絮", "彩蛋", "幕后")

    fun isVideo(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in EXTENSIONS
    }

    fun isNoise(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return NOISE_MARKERS.any { lower.contains(it) }
    }
}

/**
 * 自然序比较：让 `EP2` 排在 `EP10` 前面（字符串序会排反）。
 * 规则：数字段按数值比较，非数字段按字典序；数字段优先于非数字段。
 */
object NaturalOrder : Comparator<String> {
    private val TOKEN = Regex("(\\d+)")

    override fun compare(a: String, b: String): Int {
        val sa = TOKEN.split(a).filter { it.isNotEmpty() }
        val sb = TOKEN.split(b).filter { it.isNotEmpty() }
        val na = TOKEN.findAll(a).map { it.value }.toList()
        val nb = TOKEN.findAll(b).map { it.value }.toList()

        val minSeg = minOf(sa.size, sb.size)
        for (i in 0 until minSeg) {
            val c = sa[i].compareTo(sb[i], ignoreCase = true)
            if (c != 0) return c
        }
        val minNum = minOf(na.size, nb.size)
        for (i in 0 until minNum) {
            val c = na[i].toLongOrNull()?.compareTo(nb[i].toLongOrNull() ?: 0L) ?: 0
            if (c != 0) return c
        }
        // 段数不同时，先短后长（EP1 在 EP1-番外 之前）
        if (sa.size != sb.size) return sa.size - sb.size
        if (na.size != nb.size) return na.size - nb.size
        return a.compareTo(b, ignoreCase = true)
    }
}
