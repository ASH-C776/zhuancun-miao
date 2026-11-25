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
        /** 直接子级视频数达到此值即视为剧集目录（仅用于 toString 的 📁★ 标记） */
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
 * 文件名排序（国内网盘习惯序）：**数字开头 → 汉字开头 → 字母开头**，同类里数字按数值大小。
 *
 * 为什么要自己定序：
 * - 默认字符串序会把 `EP10` 排在 `EP2` 前面；
 * - 纯字符码序又让汉字（U+4E00 起）全部排到字母后面，中文剧集的目录显得支离破碎。
 *
 * 落地后的效果：
 * `1-200` < `20-4K` < `第01集` < `第10集` < `EP01` < `EP10` < `S01E02`
 * （数字段按数值比；汉字之间、字母之间各按字典序；数字段整体排在文字前面）
 */
object NaturalOrder : Comparator<String> {

    private data class Token(val text: String, val isNumber: Boolean)

    override fun compare(a: String, b: String): Int {
        val ta = tokens(a)
        val tb = tokens(b)
        val shared = minOf(ta.size, tb.size)
        for (i in 0 until shared) {
            val c = compareToken(ta[i], tb[i])
            if (c != 0) return c
        }
        if (ta.size != tb.size) return ta.size - tb.size
        return a.compareTo(b, ignoreCase = true)
    }

    /** 切成「数字段 / 文字段」的序列：`EP10.mkv` → [EP][10][.mkv] */
    private fun tokens(s: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < s.length) {
            val digit = s[i].isDigit()
            var j = i
            while (j < s.length && s[j].isDigit() == digit) j++
            out += Token(s.substring(i, j), digit)
            i = j
        }
        return out
    }

    private fun compareToken(x: Token, y: Token): Int {
        // 数字段整体优先于文字段
        if (x.isNumber != y.isNumber) return if (x.isNumber) -1 else 1
        if (x.isNumber) return compareNumber(x.text, y.text)
        val rx = rank(x.text.firstOrNull())
        val ry = rank(y.text.firstOrNull())
        if (rx != ry) return rx - ry
        return x.text.compareTo(y.text, ignoreCase = true)
    }

    /** 前导零去掉后先比长度再比字典：`9` < `10` < `0100` == `100` */
    private fun compareNumber(x: String, y: String): Int {
        val sx = x.trimStart('0').ifEmpty { "0" }
        val sy = y.trimStart('0').ifEmpty { "0" }
        if (sx.length != sy.length) return sx.length - sy.length
        val c = sx.compareTo(sy)
        if (c != 0) return c
        // 数值相同（`01` 与 `1`）：位数少的先
        return x.length - y.length
    }

    /** 文字段的类别：数字(0) → 汉字(1) → 字母(2) → 其它(3) */
    private fun rank(ch: Char?): Int = when {
        ch == null -> 0
        ch.isDigit() -> 0
        isCjk(ch) -> 1
        ch.isLetter() -> 2
        else -> 3
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF ||
            code in 0xF900..0xFAFF || code in 0xFF00..0xFFEF
    }
}

