package com.ashchan.cloudsaver.core.tmdb

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * TMDB 查询（只读，三个用途）：
 * 1. 任务绑了 TMDB ID 时，取「最新集号」给卡片做缺少集数判断；
 * 2. 推断每周更新日（next_episode_to_air 的星期几，没有下周就退回上一集日期）；
 * 3. 设置页「保存并测试」验证凭证是否可用。
 *
 * 凭证兼容两种：
 * - v3 API Key（32 位十六进制，或任何不含点的短串）→ 走 `?api_key=`
 * - v4 Read Access Token（JWT，`eyJ...`，含两个点）→ 走 `Authorization: Bearer`
 *
 * 所有方法都返回 Result，失败不会抛给调用方；调用方（TaskRunner）按「失败就当没绑定」处理，
 * 保证 TMDB 挂了不影响转存主流程。
 */
class TmdbClient(
    private val credential: String,
    /** 接口地址，默认官方域名；国内直连不通时可换成镜像/自建反代 */
    private val baseUrl: String = "https://api.themoviedb.org",
) {

    private val gson = com.google.gson.Gson()

    private val root: String get() = baseUrl.trim().trimEnd('/').ifBlank { "https://api.themoviedb.org" }

    private val isBearer: Boolean get() = credential.count { it == '.' } >= 2

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 查询剧集基本信息（含最新集 / 下一集） */
    suspend fun fetchTv(tvId: Long, language: String = "zh-CN"): Result<TmdbTvInfo> = runCatching {
        val body = get("/3/tv/$tvId", language)
        TmdbTvInfo(
            id = tvId,
            name = body.get("name")?.asString.orEmpty(),
            lastEpisode = parseEpisode(body.obj("last_episode_to_air")),
            nextEpisode = parseEpisode(body.obj("next_episode_to_air")),
            numberOfSeasons = body.get("number_of_seasons")?.asInt ?: 0,
            /** 全剧总集数（含未播的已排期集），用于判断「整部剧是不是收齐了」 */
            totalEpisodes = body.get("number_of_episodes")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
            seasons = body.getAsJsonArray("seasons")?.mapNotNull { el ->
                el.takeIf { it.isJsonObject }?.asJsonObject?.let { obj ->
                    val no = obj.get("season_number")?.takeIf { it.isJsonPrimitive }?.asInt
                        ?: return@let null
                    TmdbSeason(
                        seasonNumber = no,
                        name = obj.get("name")?.asString.orEmpty(),
                        episodeCount = obj.get("episode_count")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                        airDate = obj.get("air_date")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    )
                }
            }.orEmpty(),
        )
    }

    /**
     * 某一季已经播到第几集（季内集号）。
     *
     * 不能只看 `last_episode_to_air`：它只代表「最新播出的那一集」，
     * 查第 2 季时它可能落在第 4 季，直接返回 0 会让卡片显示 E—。
     * 这里改成：季对上就直接用，季对不上就拉这一季的集表，按播出日期过滤到今天为止。
     */
    suspend fun latestAiredEpisode(tvId: Long, season: Int, info: TmdbTvInfo, language: String = "zh-CN"): Int {
        val last = info.lastEpisode
        if (last != null && last.season == season) return last.episode
        val list = fetchSeason(tvId, season, language).getOrNull().orEmpty()
        val today = java.time.LocalDate.now().toString()
        val aired = list.filter { ep ->
            val d = ep.airDate?.takeIf { it.isNotBlank() } ?: return@filter true
            d <= today
        }
        return aired.maxOfOrNull { it.episode } ?: 0
    }

    /** 查询某一季的集数列表（用于「只追这一季」的口径） */
    suspend fun fetchSeason(tvId: Long, season: Int, language: String = "zh-CN"): Result<List<TmdbEpisode>> = runCatching {
        val body = get("/3/tv/$tvId/season/$season", language)
        val arr = body.getAsJsonArray("episodes") ?: return@runCatching emptyList()
        arr.mapNotNull { el -> el.takeIf { it.isJsonObject }?.let { parseEpisode(it.asJsonObject, season) } }
    }

    /**
     * 目标集数：任务指定了季度就取该季的集数，否则取全剧总集数。
     *
     * 「转存完」判断的分母。取该季集数是因为网盘按季各存一份，
     * 只追第 2 季的任务拿全剧总数去比，永远不会齐。
     */
    suspend fun totalEpisodes(tvId: Long, season: Int?, info: TmdbTvInfo? = null, language: String = "zh-CN"): Int {
        if (season != null && season >= 0) {
            // 先信 /tv/{id} 里的 seasons[]，没有再拉这一季的集表去数
            val tv = info ?: fetchTv(tvId, language).getOrNull()
            val fromSummary = tv?.seasonCount(season) ?: 0
            if (fromSummary > 0) return fromSummary
            val list = fetchSeason(tvId, season, language).getOrNull().orEmpty()
            val distinct = list.mapNotNull { it.episode.takeIf { n -> n > 0 } }.toSortedSet().size
            if (distinct > 0) return distinct
        }
        val tv = info ?: fetchTv(tvId, language).getOrNull()
        return tv?.totalEpisodes ?: 0
    }

    /**
     * 推断一週里的更新日（可多天），对齐原项目口径。
     *
     * 样本来源：最后一季的逐集播出日期（去重、按日期排序、最多取最近 100 条）
     * + `last_episode_to_air` + `next_episode_to_air`，
     * 再交给 [inferWeekdaysFromSamples] 按时间加权投票。
     *
     * 注意：这里会额外请求一次 `/tv/{id}/season/{n}`，只在「需要推断更新日」时调用。
     */
    suspend fun inferWeekdays(
        tvId: Long,
        info: TmdbTvInfo? = null,
        language: String = "zh-CN",
    ): List<Int> = runCatching {
        val tv = info ?: fetchTv(tvId, language).getOrThrow()
        // 原项目优先用 last_episode_to_air 的季号，拿不到就用总季数
        val season = tv.lastEpisode?.season?.takeIf { it > 0 } ?: tv.numberOfSeasons
        val dates = mutableListOf<String>()
        if (season > 0) {
            fetchSeason(tvId, season, language).getOrNull()?.forEach { ep ->
                ep.airDate?.takeIf { it.isNotBlank() }?.let { dates += it }
            }
        }
        dates += listOfNotNull(tv.lastEpisode?.airDate, tv.nextEpisode?.airDate)
        val sorted = dates.filter { it.isNotBlank() }.distinct().sorted()
        val tail = if (sorted.size > 100) sorted.takeLast(100) else sorted
        inferWeekdaysFromSamples(tail.mapNotNull { weekdayOf(it) })
    }.getOrDefault(emptyList())

    /** 设置页「测试」：拿一个稳定存在的剧（1399 = 权力的游戏）探活 */    suspend fun ping(): Result<String> = runCatching {
        val body = get("/3/tv/1399", "zh-CN")
        body.get("name")?.asString?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("TMDB 返回内容异常")
    }

    /**
     * 按剧名搜索（新建任务时选剧用）。
     *
     * `/3/search/tv` 的返回按相关度排序，同名的中/英/日文标题会混在一起，
     * 这里把「名称、原始名称、首播年份、季数、简介前 60 字」一并给出去，
     * 让用户自己按年份区分（翻拍剧同名的很常见）。
     */
    suspend fun searchTv(query: String, language: String = "zh-CN"): Result<List<TmdbSearchItem>> = runCatching {
        val q = query.trim()
        if (q.isBlank()) throw IllegalArgumentException("请输入剧名")
        val body = get("/3/search/tv", language, extra = "&query=${enc(q)}")
        val arr = body.getAsJsonArray("results") ?: return@runCatching emptyList()
        arr.mapNotNull { el ->
            el.takeIf { it.isJsonObject }?.asJsonObject?.let { obj ->
                val id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: return@mapNotNull null
                TmdbSearchItem(
                    id = id,
                    name = obj.get("name")?.asString.orEmpty(),
                    originalName = obj.get("original_name")?.asString.orEmpty(),
                    firstAirDate = obj.get("first_air_date")?.asString.orEmpty(),
                    posterPath = obj.get("poster_path")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    seasons = obj.get("number_of_seasons")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                    overview = obj.get("overview")?.asString.orEmpty(),
                )
            }
        }.take(20)
    }

    // ---------------- 内部 --------------

    /**
     * 取子对象，容忍 `null`。
     *
     * Gson 里字段值是 JSON `null` 时得到的是 [JsonNull]，`asJsonObject` 会直接抛
     * `JsonNull cannot be cast to JsonObject`。剧集完结（没有下一集）时
     * `next_episode_to_air` 就是 null，之前正是这里把整次 fetchTv 打挂，
     * 任务卡片于是永远显示"未绑定 TMDB"。
     */
    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private suspend fun get(path: String, language: String, extra: String = ""): JsonObject = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO
    ) {
        val url = StringBuilder("$root$path?language=${enc(language)}$extra")
        val builder = Request.Builder().url(url.toString()).get()
        if (isBearer) {
            builder.header("Authorization", "Bearer ${credential.trim()}")
        } else {
            url.append("&api_key=${enc(credential.trim())}")
            builder.url(url.toString())
        }
        builder.header("accept", "application/json")
        val resp = http.newCall(builder.build()).execute()
        val text = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            throw IllegalStateException(httpMessage(resp.code))
        }
        JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalStateException("TMDB 返回内容无法解析")
    }

    private fun httpMessage(code: Int): String = when (code) {
        401 -> "TMDB 凭证无效（401），请检查 API Key / Read Access Token"
        404 -> "TMDB 找不到该剧（404），请检查 TMDB ID"
        429 -> "TMDB 请求过于频繁（429），稍后再试"
        in 500..599 -> "TMDB 服务器异常（$code），稍后再试"
        else -> "TMDB 请求失败（$code）"
    }

    private fun parseEpisode(obj: JsonObject?, fallbackSeason: Int = 0): TmdbEpisode? {
        if (obj == null || obj.isJsonNull) return null
        val ep = obj.get("episode_number")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
        val season = obj.get("season_number")?.takeIf { it.isJsonPrimitive }?.asInt
            ?.takeIf { it > 0 } ?: fallbackSeason
        val air = obj.get("air_date")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        return TmdbEpisode(season, ep, air)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/** 搜索结果里的一条剧（新建任务时给用户挑） */
data class TmdbSearchItem(
    val id: Long,
    val name: String,
    val originalName: String,
    val firstAirDate: String,
    val seasons: Int,
    val overview: String,
    /** TMDB 海报相对路径（`/xxx.jpg`），没有海报时为空串 */
    val posterPath: String = "",
) {
    /** 首播年份，取不到就是空串 */
    val year: String get() = firstAirDate.take(4).takeIf { it.length == 4 } ?: ""

    /** 列表主标题：中文名 + 年份，方便区分同名翻拍 */
    fun title(): String = if (year.isNotBlank()) "$name ($year)" else name
}

/** 一条集信息（season/episode/播出日期 yyyy-MM-dd） */
data class TmdbEpisode(    val season: Int,
    val episode: Int,
    val airDate: String?,
)

/** 某一季的官方集数（0 = 特别版）；TMDB 没列出这一季就是 0 */
fun TmdbTvInfo.seasonCount(season: Int): Int =
    seasons.firstOrNull { it.seasonNumber == season }?.episodeCount ?: 0

/** 多季口径的总集数：只累加正季（S1 起），特别版另算 */
fun TmdbTvInfo.totalOfProperSeasons(): Int =
    seasons.filter { it.seasonNumber >= 1 }.sumOf { it.episodeCount }

/** 一季的汇总信息（TMDB `/tv/{id}` 的 seasons[] 里给的就是这些） */
data class TmdbSeason(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int,
    val airDate: String,
)

/** 剧集维度的关键信息 */
data class TmdbTvInfo(
    val id: Long,
    val name: String,
    val lastEpisode: TmdbEpisode?,
    val nextEpisode: TmdbEpisode?,
    val numberOfSeasons: Int,
    /** 全剧总集数（TMDB `number_of_episodes`，含已排期未播的） */
    val totalEpisodes: Int = 0,
    /** 每季的官方集数；绑定时展示、按季取数都靠它，不用再逐季拉列表 */
    val seasons: List<TmdbSeason> = emptyList(),
) {
    /**
     * 判定「最新集号」的口径：
     * - 指定了季（任务绑到某一季）→ 该季已播出的最大集号；
     * - 否则用整部剧 last_episode_to_air 的集号。
     *
     * 注意：TMDB 的 episode_number 是**季内集号**，不是全剧累计集号，
     * 与网盘里「第 N 集」的编号一致（网盘资源通常按季各自从 1 开始）。
     */
    fun latestEpisodeNumber(season: Int? = null): Int {
        if (season != null && season > 0) {
            return lastEpisode?.takeIf { it.season == season }?.episode ?: 0
        }
        return lastEpisode?.episode ?: 0
    }

    /** 推断更新日：优先下周播出日，其次上一集播出日（单日；多天更新请用 [inferWeekdays]） */
    fun weekdayHint(): Int? {
        val date = nextEpisode?.airDate ?: lastEpisode?.airDate ?: return null
        return weekdayOf(date)
    }
}

/**
 * 更新日推断（与 CASX 原项目 `_infer_weekdays_from_weekday_samples` 同口径）。
 *
 * 为什么不是一个星期几：很多剧/综艺一周播多天（周一+周二）或天天播，
 * 只取 `next_episode_to_air` 会被压成单日，于是「每周几更新」不准。
 *
 * 口径：
 * 1. 样本按时间顺序排（旧 → 新），越新权重越高（0.9 的年龄次方）
 * 2. 样本 ≥4 且最高权重占比 ≥60% → 单日
 * 3. 否则取「出现次数 ≥2 且权重占比 ≥20%」的日子，按权重降序，最多 3 个
 * 4. 一个都不满足 → 全部出现过的日子
 * 5. 兜底：样本 ≥7、出现 ≥5 个不同日子、最高占比 ≤40% → 全返回（天天播）
 */
fun inferWeekdaysFromSamples(samples: List<Int>): List<Int> {
    val valid = samples.filter { it in 1..7 }
    if (valid.isEmpty()) return emptyList()
    val unique = valid.toSortedSet()
    val total = valid.size
    val decay = 0.9

    val rawCounts = mutableMapOf<Int, Int>()
    val weightedCounts = mutableMapOf<Int, Double>()
    var weightedTotal = 0.0
    valid.forEachIndexed { index, day ->
        val age = (total - 1) - index
        val weight = Math.pow(decay, age.toDouble())
        weightedTotal += weight
        rawCounts[day] = (rawCounts[day] ?: 0) + 1
        weightedCounts[day] = (weightedCounts[day] ?: 0.0) + weight
    }

    val mostDay = weightedCounts.maxByOrNull { it.value }
    val mostWeight = mostDay?.value ?: 0.0
    val picked = mutableListOf<Int>()
    if (total >= 4 && weightedTotal > 0 && mostWeight / weightedTotal >= 0.6) {
        mostDay?.let { picked += it.key }
    } else {
        weightedCounts.entries
            .sortedByDescending { it.value }
            .forEach { (day, weight) ->
                if ((rawCounts[day] ?: 0) >= 2 && weightedTotal > 0 && weight / weightedTotal >= 0.2) {
                    picked += day
                }
            }
        if (picked.isEmpty()) picked += unique
    }

    val sorted = picked.filter { it in 1..7 }.toSortedSet().toList()
    if (total >= 7 && unique.size >= 5 && weightedTotal > 0 && mostWeight / weightedTotal <= 0.4) {
        return unique.toList()
    }
    return sorted.take(3)
}

/** yyyy-MM-dd → ISO 星期（1=周一 … 7=周日），无法解析返回 null */
fun weekdayOf(isoDate: String): Int? = runCatching {
    val parts = isoDate.split("-")
    if (parts.size != 3) return@runCatching null
    val y = parts[0].toInt()
    val m = parts[1].toInt()
    val d = parts[2].toInt()
    if (y !in 1900..2200 || m !in 1..12 || d !in 1..31) return@runCatching null
    // 必须关掉宽松模式：GregorianCalendar 默认会把 13 月 45 日「滚动」成某个真实日期
    val cal = java.util.GregorianCalendar().apply {
        isLenient = false
        clear()
        set(y, m - 1, d, 12, 0, 0)
    }
    // Calendar: 1=周日 … 7=周六 → 转成 1=周一 … 7=周日
    (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1
}.getOrNull()

