package com.example.senkurofarm

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ViewerInfo(
    val name: String,
    val slug: String,
    val email: String,
    val userId: String,
    val sessionId: String,
    val country: String,
    val avatarUrl: String = "",
    val level: Int = 0
)

data class ApiManga(
    val id: String,
    val slug: String,
    val title: String,
    val branchId: String
)

data class ApiChapter(
    val id: String,
    val number: Int
)

enum class ClaimResult {
    DROP, NO_CARD, COOLDOWN, ERROR
}

object SenkuroApi {
    private const val GRAPHQL_URL = "https://api.senkuro.me/graphql"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
    private const val TAG = "SenkuroApi"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    // ─── Token management ───
    fun extractAccessToken(context: Context): String? {
        val cookieManager = CookieManager.getInstance()
        val domains = listOf("https://senkuro.me", "https://sso.senkuro.net", "https://api.senkuro.me")
        val allCookies = domains.joinToString("; ") { cookieManager.getCookie(it).orEmpty() }
        for (cookie in allCookies.split(";")) {
            val t = cookie.trim()
            if (t.startsWith("access_token=")) {
                val token = t.removePrefix("access_token=").trim()
                Log.d(TAG, "extracted access_token from cookies (${token.take(30)}...)")
                return token
            }
        }
        Log.w(TAG, "no access_token found in cookies")
        return null
    }

    fun saveAccessToken(context: Context, token: String) {
        context.getSharedPreferences("senkuro_farm", Context.MODE_PRIVATE)
            .edit().putString("access_token", token).apply()
        Log.d(TAG, "access_token saved (${token.take(30)}...)")
    }

    fun loadAccessToken(context: Context): String =
        context.getSharedPreferences("senkuro_farm", Context.MODE_PRIVATE)
            .getString("access_token", "").orEmpty()

    fun hasAccessToken(context: Context): Boolean = loadAccessToken(context).isNotBlank()

    // ─── GraphQL helper ───
    private fun graphql(token: String, query: String, variables: JSONObject = JSONObject()): JSONObject {
        val payload = JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }
        val request = Request.Builder()
            .url(GRAPHQL_URL)
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("Origin", "https://senkuro.me")
            .header("Referer", "https://senkuro.me/")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception("GraphQL HTTP ${response.code}: ${body.take(200)}")
            }
            val json = JSONObject(body)
            if (json.has("errors")) {
                val errors = json.getJSONArray("errors")
                val msgs = (0 until errors.length()).joinToString("; ") { i ->
                    errors.getJSONObject(i).optString("message", "unknown")
                }
                throw Exception(msgs)
            }
            return json.optJSONObject("data") ?: JSONObject()
        }
    }

    // ─── Viewer ───
    fun fetchViewer(token: String): ViewerInfo? {
        Log.d(TAG, "fetchViewer called")
        return try {
            val data = graphql(
                token,
                """query { viewer { sessionId country user { id name firstName lastName slug email level avatar { original { url } } } } }"""
            )
            val viewer = data.optJSONObject("viewer") ?: return null
            val user = viewer.optJSONObject("user") ?: return null
            val name = listOf(user.optString("firstName", ""), user.optString("lastName", ""))
                .filter { it.isNotBlank() }.joinToString(" ")
                .ifBlank { user.optString("name", "").ifBlank { user.optString("slug", "???") } }
            val info = ViewerInfo(
                name = name,
                slug = user.optString("slug", ""),
                email = user.optString("email", "—"),
                userId = user.optString("id", ""),
                sessionId = viewer.optString("sessionId", ""),
                country = viewer.optString("country", ""),
                avatarUrl = user.optJSONObject("avatar")?.optJSONObject("original")?.optString("url", "").orEmpty(),
                level = user.optInt("level", 0)
            )
            Log.d(TAG, "viewer fetched: ${info.name} (${info.userId})")
            info
        } catch (e: Exception) {
            Log.e(TAG, "fetchViewer failed: ${e.message}")
            null
        }
    }

    // ─── Popular manga (fallback list) ───
    fun fetchPopularManga(token: String): List<ApiManga> {
        Log.d(TAG, "fetchPopularManga called")
        return try {
            val data = graphql(
                token,
                """query(${"$"}period: MangaPopularPeriod!) {
                    mangaPopularByPeriod(period: ${"$"}period) {
                        slug titles { lang content } score status
                    }
                }""",
                JSONObject().put("period", "WEEK")
            )
            val list = data.optJSONArray("mangaPopularByPeriod") ?: return emptyList()
            val items = (0 until list.length()).mapNotNull { i ->
                val item = list.getJSONObject(i)
                val slug = item.optString("slug", "")
                if (slug.isBlank()) return@mapNotNull null
                val titles = item.optJSONArray("titles")
                val title = readLocalizedTitle(titles)
                ApiManga(id = slug, slug = slug, title = title, branchId = "")
            }
            Log.d(TAG, "popular manga fetched: ${items.size} items")
            items
        } catch (e: Exception) {
            Log.e(TAG, "fetchPopularManga failed: ${e.message}")
            emptyList()
        }
    }

    // ─── Resolve manga by slug (fills id + branchId) ───
    fun resolveMangaBySlug(token: String, slug: String): ApiManga? {
        Log.d(TAG, "resolveMangaBySlug: $slug")
        return try {
            val data = graphql(
                token,
                """query(${"$"}slug: String!) {
                    manga(slug: ${"$"}slug) { id slug titles { lang content } branches { id } }
                }""",
                JSONObject().put("slug", slug)
            )
            val manga = data.optJSONObject("manga") ?: return null
            val titles = manga.optJSONArray("titles")
            val title = readLocalizedTitle(titles)
            val branches = manga.optJSONArray("branches")
            val branchId = if (branches != null && branches.length() > 0)
                branches.getJSONObject(0).optString("id", "") else ""
            ApiManga(id = manga.optString("id", ""), slug = manga.optString("slug", ""), title = title, branchId = branchId)
        } catch (e: Exception) {
            Log.e(TAG, "resolveMangaBySlug failed: ${e.message}")
            null
        }
    }

    // ─── Resolve multiple manga slugs (batch for popular list) ───
    fun resolveMangaBatch(token: String, slugs: List<String>): List<ApiManga> {
        return slugs.mapNotNull { resolveMangaBySlug(token, it) }
    }

    // ─── Chapters ───
    fun fetchChapters(token: String, branchId: String): List<ApiChapter> {
        Log.d(TAG, "fetchChapters called, branchId=$branchId")
        fun parse(data: JSONObject): List<ApiChapter> {
            val edges = data.optJSONObject("mangaChapters")?.optJSONArray("edges") ?: return emptyList()
            return (0 until edges.length()).mapNotNull { i ->
                val node = edges.getJSONObject(i).optJSONObject("node") ?: return@mapNotNull null
                ApiChapter(id = node.optString("id", ""), number = node.optInt("number", 0))
            }.filter { it.id.isNotBlank() && it.number > 0 }.distinctBy { it.id }.sortedBy { it.number }
        }
        try {
            val data = graphql(
                token,
                """query(${"$"}branchId: ID!, ${"$"}last: Int!) {
                    mangaChapters(branchId: ${"$"}branchId, last: ${"$"}last) {
                        edges { node { id number } }
                    }
                }""",
                JSONObject().put("branchId", branchId).put("last", 100)
            )
            val chapters = parse(data)
            if (chapters.isNotEmpty()) {
                Log.d(TAG, "chapters fetched with last: ${chapters.size}, first=${chapters.first().number}")
                return chapters
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchChapters last fallback: ${e.message}")
        }
        return try {
            val data = graphql(
                token,
                """query(${"$"}branchId: ID!, ${"$"}first: Int!) {
                    mangaChapters(branchId: ${"$"}branchId, first: ${"$"}first) {
                        edges { node { id number } }
                    }
                }""",
                JSONObject().put("branchId", branchId).put("first", 100)
            )
            val chapters = parse(data)
            Log.d(TAG, "chapters fetched with first: ${chapters.size}, first=${chapters.firstOrNull()?.number}")
            chapters
        } catch (e: Exception) {
            Log.e(TAG, "fetchChapters failed: ${e.message}")
            emptyList()
        }
    }

    // ─── Farm mutations ───
    fun readChapter(token: String, mangaId: String, branchId: String, chapterId: String): Boolean {
        Log.d(TAG, "readChapter: manga=$mangaId ch=$chapterId")
        return try {
            val input = JSONObject().apply { put("mangaId", mangaId); put("branchId", branchId); put("chapterId", chapterId) }
            val data = graphql(
                token,
                """mutation(${"$"}input: ReadMangaChapterInput!) {
                    readMangaChapter(input: ${"$"}input) { __typename success }
                }""",
                JSONObject().put("input", input)
            )
            val ok = data.optJSONObject("readMangaChapter")?.optBoolean("success", false) == true
            Log.d(TAG, "readChapter result: $ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "readChapter failed: ${e.message}")
            false
        }
    }

    fun claimCard(token: String): ClaimResult {
        return try {
            graphql(
                token,
                """mutation(${"$"}input: ClaimCardDropInput!) {
                    claimCardDrop(input: ${"$"}input) { __typename }
                }""",
                JSONObject().put("input", JSONObject())
            )
            Log.d(TAG, "claimCard: DROP!")
            ClaimResult.DROP
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            val result = when {
                msg.contains("No cards for drop", ignoreCase = true) ||
                    msg.contains("No cards", ignoreCase = true) -> ClaimResult.NO_CARD
                msg.contains("operation in progress", ignoreCase = true) -> ClaimResult.COOLDOWN
                else -> ClaimResult.ERROR
            }
            Log.d(TAG, "claimCard result: $result ($msg)")
            result
        }
    }

    // ─── Slug helpers ───
    fun extractSlugFromUrl(raw: String): String? {
        val trimmed = raw.trim()
        Regex("senkuro\\.me/manga/([^/\\s?#]+)").find(trimmed)?.let { return it.groupValues[1] }
        if (Regex("^[a-zA-Z0-9_-]+$").matches(trimmed)) return trimmed
        return null
    }

    // ─── Internal ───
    private fun readLocalizedTitle(titles: org.json.JSONArray?): String {
        if (titles == null) return "???"
        for (i in 0 until titles.length()) {
            val t = titles.getJSONObject(i)
            if (t.optString("lang", "") == "RU") return t.optString("content", "???")
        }
        for (i in 0 until titles.length()) {
            val t = titles.getJSONObject(i)
            if (t.optString("lang", "") == "EN") return t.optString("content", "???")
        }
        return if (titles.length() > 0) titles.getJSONObject(0).optString("content", "???") else "???"
    }
}
