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
) {
    companion object {
        private const val ACTION_SKIP = "skip"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private const val HASH_PREFIX_LENGTH = 4
        private const val REQUEST_ORIGIN = "BBZQ"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android; Xposed; NkBe) BBZQ/1.0"

        private val BASE_URLS = listOf(
            "http://154.222.28.109/api/skipSegments/",
            "https://bsbsb.top/api/skipSegments/",
            "https://www.bsbsb.xyz/api/skipSegments/",
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
            val segments: List<Segment>?,
            val timestamp: Long,
        )
    }

    /**
     * 获取跳过片段，支持多节点轮询和缓存
     */
    suspend fun getSegments(): List<Segment>? = withContext(Dispatchers.IO) {
        val cacheKey = "${bvid}_$cid"

        // 1. 检查缓存
        cache[cacheKey]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < CACHE_TTL_MS) {
                return@withContext entry.segments
            }
        }

        // 2. 多节点轮询请求
        val prefix = bvid.trim().sha256().take(HASH_PREFIX_LENGTH)
        var lastException: Exception? = null

        for (baseUrl in BASE_URLS) {
            try {
                val url = "${baseUrl}${prefix}?category=sponsor"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Origin", REQUEST_ORIGIN)
                    .header("x-ext-version", "1.7.0")
                    .build()

                val segments = httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e("HTTP request failed with code: ${resp.code} for $baseUrl")
                        return@use null
                    }

                    val body = resp.body?.string()
                    if (body.isNullOrEmpty()) {
                        Log.e("HTTP request failed with empty body for $baseUrl")
                        return@use null
                    }

                    parseSegments(body)
                }

                // 请求成功，写入缓存并返回
                if (segments != null) {
                    cache[cacheKey] = CacheEntry(segments, System.currentTimeMillis())
                    return@withContext segments
                }

            } catch (e: Exception) {
                Log.e("Request failed for $baseUrl: ${e.message}")
                lastException = e
                // 继续尝试下一个节点
                continue
            }
        }

        // 3. 所有节点都失败了，尝试返回过期缓存（降级策略）
        cache[cacheKey]?.let { entry ->
            Log.e("All nodes failed, returning stale cache for $bvid")
            return@withContext entry.segments
        }

        // 4. 彻底失败
        lastException?.let { Log.e(it) }
        null
    }

    /**
     * 解析 JSON 响应，提取匹配当前 BV 号和 CID 的片段
     */
    private fun parseSegments(json: String): List<Segment>? {
        try {
            val jsonArray = JSONArray(json)
            val segments = mutableListOf<Segment>()
            if (jsonArray.length() == 0) return null

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.optString("videoID") != bvid) continue

                val segmentsArray = obj.getJSONArray("segments")
                for (j in 0 until segmentsArray.length()) {
                    val item = segmentsArray.getJSONObject(j)
                    val segmentArray = item.getJSONArray("segment")
                    val actionType = item.optString("actionType", ACTION_SKIP)

                    // 只处理 skip 类型的片段（跳过）
                    if (actionType != ACTION_SKIP) continue

                    segments.add(
                        Segment(
                            segment = floatArrayOf(
                                segmentArray.getDouble(0).toFloat(),
                                segmentArray.getDouble(1).toFloat()
                            ),
                            cid = item.optString("cid"),
                            UUID = item.optString("UUID"),
                            category = item.optString("category"),
                            actionType = actionType,
                            videoDuration = item.optInt("videoDuration")
                        )
                    )
                }
                break
            }

            return segments
                .filter { it.cid == this.cid }
                .sortedBy { it.segment[0] }
                .takeIf { it.isNotEmpty() }

        } catch (_: JSONException) {
            return null
        }
    }

    /**
     * 清理过期缓存
     */
    fun clearExpiredCache() {
        val now = System.currentTimeMillis()
        cache.entries.removeIf { (_, entry) ->
            now - entry.timestamp > CACHE_TTL_MS
        }
    }

    /**
     * 清空所有缓存
     */
    fun clearAllCache() {
        cache.clear()
    }

    private fun String.sha256(): String = sha256Digest
        .digest(toByteArray())
        .joinToString("") { "%02x".format(it) }

    data class Segment(
        val segment: FloatArray,
        val cid: String,
        val UUID: String,
        val category: String,
        val actionType: String,
        val videoDuration: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Segment
            return segment.contentEquals(other.segment)
                    && cid == other.cid
                    && UUID == other.UUID
                    && category == other.category
                    && actionType == other.actionType
                    && videoDuration == other.videoDuration
        }

        override fun hashCode(): Int {
            var result = segment.contentHashCode()
            result = 31 * result + cid.hashCode()
            result = 31 * result + UUID.hashCode()
            result = 31 * result + category.hashCode()
            result = 31 * result + actionType.hashCode()
            result = 31 * result + videoDuration
            return result
        }
    }
}
