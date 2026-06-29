package me.iacn.biliroaming.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class BilibiliSponsorBlock(
    private val bvid: String,
    private val cid: String,
    private val enabledCategories: Set<String> = emptySet(),
) {
    companion object {
        private const val ACTION_SKIP = "skip"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val HASH_PREFIX_LENGTH = 4
        private const val REQUEST_ORIGIN = "BBZQ"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android; Xposed; NkBe) BBZQ/1.0"
        private const val EXT_VERSION = "1.7.0"

        private val BASE_URLS = listOf(
            "https://bsbsb.top/api/skipSegments/",
            "https://www.bsbsb.xyz/api/skipSegments/",
            "http://154.222.28.109/api/skipSegments/",
        )

        private val cache = ConcurrentHashMap<String, CacheEntry>()

        private val httpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        private val sha256Digest by lazy {
            java.security.MessageDigest.getInstance("SHA-256")
        }

        private data class CacheEntry(
            val result: FetchResult,
            val timestamp: Long,
        )
    }

    /**
     * 获取跳过片段，支持多节点轮询、缓存、按类别/CID过滤
     */
    suspend fun getSegments(): FetchResult = withContext(Dispatchers.IO) {
        val cacheKey = "${bvid}_$cid"

        // 1. 检查有效缓存（缓存的是原始结果，过滤在返回前执行）
        cache[cacheKey]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) {
                return@withContext entry.result
                    .filterByCategories(enabledCategories)
                    .filterByCid(cid)
            }
        }

        val trimmedBvid = bvid.trim()
        if (trimmedBvid.isEmpty()) {
            return@withContext FetchResult(FetchStatus.FAILED, emptyList())
        }

        val hashPrefix = trimmedBvid.sha256().take(HASH_PREFIX_LENGTH)

        // 2. 多节点轮询：收集所有节点结果，优先取成功/空响应
        val results = BASE_URLS.map { baseUrl ->
            fetchSegments(buildRequest(baseUrl, hashPrefix), trimmedBvid)
        }

        val result = results.firstOrNull {
            it.status == FetchStatus.SUCCESS || it.status == FetchStatus.EMPTY
        } ?: results.lastOrNull() ?: FetchResult(FetchStatus.FAILED, emptyList())

        // 3. 缓存成功/空结果（存储过滤前的原始结果）
        if (result.status == FetchStatus.SUCCESS || result.status == FetchStatus.EMPTY) {
            cache[cacheKey] = CacheEntry(result, System.currentTimeMillis())
        }

        // 4. 降级策略：当前请求失败时，尝试返回过期缓存
        if (result.status != FetchStatus.SUCCESS && result.status != FetchStatus.EMPTY) {
            cache[cacheKey]?.let { entry ->
                Log.e("All nodes failed, returning stale cache for $bvid")
                return@withContext entry.result
                    .filterByCategories(enabledCategories)
                    .filterByCid(cid)
            }
        }

        return@withContext result
            .filterByCategories(enabledCategories)
            .filterByCid(cid)
    }

    private fun buildRequest(baseUrl: String, hashPrefix: String): Request =
        Request.Builder()
            .url("$baseUrl$hashPrefix")
            .header("accept", "application/json")
            .header("origin", REQUEST_ORIGIN)
            .header("user-agent", USER_AGENT)
            .header("x-ext-version", EXT_VERSION)
            .build()

    private fun fetchSegments(request: Request, targetBvid: String): FetchResult {
        return try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> FetchResult(FetchStatus.NOT_FOUND, emptyList(), response.code)
                    !response.isSuccessful -> FetchResult(FetchStatus.FAILED, emptyList(), response.code)
                    else -> {
                        val body = response.body?.string()
                        if (body.isNullOrBlank()) {
                            FetchResult(FetchStatus.EMPTY, emptyList(), response.code)
                        } else {
                            parseSegments(body, targetBvid, response.code)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Request failed: ${e.message}")
            FetchResult(FetchStatus.FAILED, emptyList())
        }
    }

    private fun parseSegments(json: String, targetBvid: String, statusCode: Int): FetchResult {
        return try {
            val payload = JSONArray(json)
            if (payload.length() == 0) {
                return FetchResult(FetchStatus.EMPTY, emptyList(), statusCode)
            }

            for (index in 0 until payload.length()) {
                val videoEntry = payload.optJSONObject(index) ?: continue
                if (videoEntry.optString("videoID") != targetBvid) continue

                val segments = videoEntry.optJSONArray("segments")
                    ?.toSegmentList()
                    .orEmpty()
                    .filter(::isSkippableSegment)
                    .sortedBy { it.segment[0] }

                return if (segments.isEmpty()) {
                    FetchResult(FetchStatus.EMPTY, emptyList(), statusCode)
                } else {
                    FetchResult(FetchStatus.SUCCESS, segments, statusCode)
                }
            }

            FetchResult(FetchStatus.NOT_FOUND, emptyList(), statusCode)
        } catch (_: JSONException) {
            FetchResult(FetchStatus.FAILED, emptyList(), statusCode)
        }
    }

    private fun JSONArray.toSegmentList(): List<Segment> {
        val items = ArrayList<Segment>(length())
        for (index in 0 until length()) {
            val segment = optJSONObject(index)?.toSegment() ?: continue
            items += segment
        }
        return items
    }

    private fun JSONObject.toSegment(): Segment? {
        val segmentArray = optJSONArray("segment") ?: return null
        if (segmentArray.length() < 2) return null

        val start = segmentArray.optDouble(0, Double.NaN)
        val end = segmentArray.optDouble(1, Double.NaN)
        if (!start.isFinite() || !end.isFinite() || end <= start) return null

        return Segment(
            segment = floatArrayOf(start.toFloat(), end.toFloat()),
            cid = optString("cid"),
            uuid = optString("UUID"),
            category = optString("category"),
            actionType = optString("actionType"),
            videoDuration = optInt("videoDuration"),
            locked = optInt("locked"),
            votes = optInt("votes"),
        )
    }

    private fun isSkippableSegment(segment: Segment): Boolean =
        segment.actionType.equals(ACTION_SKIP, ignoreCase = true)

    /** 按类别过滤 */
    private fun FetchResult.filterByCategories(categories: Set<String>): FetchResult {
        if (segments.isEmpty()) return this
        if (categories.isEmpty()) {
            return copy(status = FetchStatus.FILTERED_BY_CATEGORY, segments = emptyList())
        }

        val filtered = segments.filter { it.category in categories }
        return copy(
            status = if (filtered.isEmpty() && status == FetchStatus.SUCCESS) {
                FetchStatus.FILTERED_BY_CATEGORY
            } else {
                status
            },
            segments = filtered,
        )
    }

    /** 按 CID 过滤 */
    private fun FetchResult.filterByCid(targetCid: String): FetchResult {
        if (segments.isEmpty()) return this
        if (targetCid.isBlank()) return this

        val filtered = segments.filter { it.cid.isBlank() || it.cid == targetCid }
        return copy(
            status = if (filtered.isEmpty() && status == FetchStatus.SUCCESS) {
                FetchStatus.FILTERED_BY_CID
            } else {
                status
            },
            segments = filtered,
        )
    }

    /** 清理过期缓存 */
    fun clearExpiredCache() {
        val now = System.currentTimeMillis()
        cache.entries.removeIf { (_, entry) ->
            now - entry.timestamp > CACHE_TTL_MS
        }
    }

    /** 清空所有缓存 */
    fun clearAllCache() {
        cache.clear()
    }

    private fun String.sha256(): String = sha256Digest
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

    data class Segment(
        val segment: FloatArray,
        val cid: String,
        val uuid: String,
        val category: String,
        val actionType: String,
        val videoDuration: Int,
        val locked: Int,
        val votes: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Segment
            return segment.contentEquals(other.segment)
                    && cid == other.cid
                    && uuid == other.uuid
                    && category == other.category
                    && actionType == other.actionType
                    && videoDuration == other.videoDuration
                    && locked == other.locked
                    && votes == other.votes
        }

        override fun hashCode(): Int {
            var result = segment.contentHashCode()
            result = 31 * result + cid.hashCode()
            result = 31 * result + uuid.hashCode()
            result = 31 * result + category.hashCode()
            result = 31 * result + actionType.hashCode()
            result = 31 * result + videoDuration
            result = 31 * result + locked
            result = 31 * result + votes
            return result
        }
    }

    data class FetchResult(
        val status: FetchStatus,
        val segments: List<Segment>,
        val httpStatusCode: Int? = null,
    )

    enum class FetchStatus {
        SUCCESS,
        EMPTY,
        FILTERED_BY_CATEGORY,
        FILTERED_BY_CID,
        NOT_FOUND,
        FAILED,
    }
}
