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
            lastEpisode = parseEpisode(body.getAsJsonObject("last_episode_to_air")),
            nextEpisode = parseEpisode(body.getAsJsonObject("next_episode_to_air")),
            numberOfSeasons = body.get("number_of_seasons")?.asInt ?: 0,
        )
    }

    /** 查询某一季的集数列表（用于「只追这一季」的口径） */
    suspend fun fetchSeason(tvId: Long, season: Int, language: String = "zh-CN"): Result<List<TmdbEpisode>> = runCatching {
        val body = get("/3/tv/$tvId/season/$season", language)
        val arr = body.getAsJsonArray("episodes") ?: return@runCatching emptyList()
        arr.mapNotNull { el -> el.takeIf { it.isJsonObject }?.let { parseEpisode(it.asJsonObject, season) } }
    }

    /** 设置页「测试」：拿一个稳定存在的剧（1399 = 权力的游戏）探活 */
    suspend fun ping(): Result<String> = runCatching {
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
                    seasons = obj.get("number_of_seasons")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                    overview = obj.get("overview")?.asString.orEmpty(),
                )
            }
        }.take(20)
    }

    // ---------------- 内部 --------------

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

/** 剧集维度的关键信息 */
data class TmdbTvInfo(
    val id: Long,
    val name: String,
    val lastEpisode: TmdbEpisode?,
    val nextEpisode: TmdbEpisode?,
    val numberOfSeasons: Int,
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

    /** 推断更新日：优先下周播出日，其次上一集播出日 */
    fun weekdayHint(): Int? {
        val date = nextEpisode?.airDate ?: lastEpisode?.airDate ?: return null
        return weekdayOf(date)
    }
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
