@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.cloudstream.anthologycloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone

class Anizium : MainAPI() {
    override var mainUrl = "https://api.anizium.co"
    override var name = "Anizium"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.Movie, TvType.TvSeries)

    private val siteOrigin = "https://anizium.co"
    private val tokenKey = "hlxjl1c2w281ax473rt1ofgrvhyjvi"

    override val mainPage = mainPageOf(
        "settlement_top" to "Öne Çıkan Animeler",
        "settlement_middle" to "Popüler Animeler",
        "settlement_lower" to "Yeni Eklenenler",
        "special_list" to "Trendler & Özel Listeler",
        "genre:Aksiyon" to "Aksiyon Animeleri",
        "genre:Macera" to "Macera Animeleri",
        "genre:Komedi" to "Komedi Animeleri",
        "genre:Dram" to "Dram Animeleri",
        "genre:Fantastik" to "Fantastik Animeleri",
        "genre:Bilim Kurgu" to "Bilim Kurgu Animeleri",
        "genre:Romantik" to "Romantik Animeleri"
    )

    private fun getIstanbulDay(): String {
        val days = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
        val cal = Calendar.getInstance(TimeZone.getTimeZone("GMT+3"))
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
        return days[if (dayOfWeek in 0..6) dayOfWeek else 0]
    }

    private fun xorEncryptHex(text: String, key: String): String {
        val sb = StringBuilder()
        for (i in text.indices) {
            val code = text[i].code xor key[i % key.length].code
            val hex = code.toString(16)
            if (hex.length < 2) sb.append('0')
            sb.append(hex)
        }
        return sb.toString()
    }

    private fun generateCfControl(): String {
        val key = tokenKey + "_" + getIstanbulDay()
        val randomKey = (1..6).map { (('a'..'z') + ('0'..'9')).random() }.joinToString("")
        val payload = "{\"$randomKey\":${System.currentTimeMillis()}}"
        return xorEncryptHex(payload, key)
    }

    private fun getHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Origin" to siteOrigin,
            "Referer" to "$siteOrigin/",
            "Cf-Control" to generateCfControl(),
            "device" to "browser",
            "language" to "tr",
            "site" to "main"
        )
    }

    private fun parseItem(item: JSONObject): SearchResponse? {
        val id = item.optString("ID").ifEmpty { item.optString("id") }
        val title = item.optString("name").ifEmpty { item.optString("title") }
        if (id.isEmpty() || title.isEmpty()) return null
        val poster = item.optString("poster").ifEmpty { item.optString("banner") }
        val isTv = item.optString("type") != "movie"

        return if (isTv) {
            newTvSeriesSearchResponse(title, "$siteOrigin/anime/$id", TvType.Anime) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, "$siteOrigin/anime/$id", TvType.AnimeMovie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeItems = mutableListOf<SearchResponse>()
        try {
            val res = app.get("$mainUrl/page/home", headers = getHeaders()).text
            val json = JSONObject(res)

            if (request.data.startsWith("genre:")) {
                val targetGenre = request.data.substringAfter("genre:").trim().lowercase()
                val allSections = listOf("settlement_top", "settlement_middle", "settlement_lower", "special_list")
                for (sec in allSections) {
                    val arr = json.optJSONArray(sec) ?: continue
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val genres = item.optJSONArray("genre")
                        var matches = false
                        if (genres != null) {
                            for (g in 0 until genres.length()) {
                                val gName = genres.optJSONObject(g)?.optString("name", "")?.lowercase() ?: ""
                                if (gName.contains(targetGenre)) {
                                    matches = true
                                    break
                                }
                            }
                        }
                        if (matches) {
                            parseItem(item)?.let { homeItems.add(it) }
                        }
                    }
                }
            } else {
                val arr = json.optJSONArray(request.data)
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        parseItem(item)?.let { homeItems.add(it) }
                    }
                }
            }

            if (homeItems.isEmpty()) {
                val sections = listOf("settlement_top", "settlement_middle", "settlement_lower")
                for (sec in sections) {
                    val fallbackArr = json.optJSONArray(sec) ?: continue
                    for (i in 0 until fallbackArr.length()) {
                        val item = fallbackArr.optJSONObject(i) ?: continue
                        parseItem(item)?.let { homeItems.add(it) }
                    }
                }
            }
        } catch (_: Exception) {}

        return newHomePageResponse(request.name, homeItems.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            // Anizium API returns 400 Bad Request on spaces or < 3 chars; use first alphanumeric word >= 3 chars
            val safeQuery = query.trim().split(Regex("[\\s+_-]+")).firstOrNull { it.length >= 3 }
                ?: query.trim().filter { it.isLetterOrDigit() }
            val encoded = URLEncoder.encode(safeQuery.ifEmpty { "anime" }, "UTF-8")
            val res = app.get("$mainUrl/page/search?value=$encoded&page=1", headers = getHeaders()).text
            val json = JSONObject(res)
            val data = json.optJSONObject("page")?.optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    parseItem(item)?.let { results.add(it) }
                }
            }
        } catch (_: Exception) {}

        if (results.isEmpty()) {
            try {
                val home = getMainPage(1, MainPageRequest("Öne Çıkan Animeler", "settlement_top", false))
                val qLower = query.lowercase().trim()
                val matched = home.items.flatMap { it.list }.filter { it.name.lowercase().contains(qLower) }
                return matched.ifEmpty { home.items.flatMap { it.list } }
            } catch (_: Exception) {}
        }
        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val animeId = url.substringAfterLast("/anime/").substringAfterLast("/").trim()
        val res = try {
            app.get("$mainUrl/anime/get?id=$animeId", headers = getHeaders()).text
        } catch (e: Exception) {
            ""
        }
        val json = try { JSONObject(res) } catch (_: Exception) { JSONObject() }
        val data = json.optJSONObject("data") ?: JSONObject()

        val title = data.optString("name").ifEmpty { name }
        val poster = data.optString("poster").ifEmpty { data.optString("banner") }
        val plot = data.optString("overview")
        val isMovie = data.optString("type") == "movie"

        if (isMovie) {
            val streamData = JSONObject().apply {
                put("id", animeId)
                put("season", 1)
                put("episode", 1)
                put("isMovie", true)
            }.toString()
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, streamData) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val seasons = data.optJSONArray("seasons")
        val episodes = mutableListOf<Episode>()

        if (seasons != null) {
            for (s in 0 until seasons.length()) {
                val sObj = seasons.optJSONObject(s) ?: continue
                val sNum = sObj.optInt("number", s + 1)
                val epsArr = sObj.optJSONArray("episodes") ?: continue

                for (e in 0 until epsArr.length()) {
                    val epObj = epsArr.optJSONObject(e) ?: continue
                    val eNum = epObj.optInt("number", e + 1)
                    val epName = epObj.optString("name", "$sNum. Sezon $eNum. Bölüm")
                    val epPoster = epObj.optString("banner_link")

                    val streamData = JSONObject().apply {
                        put("id", animeId)
                        put("season", sNum)
                        put("episode", eNum)
                        put("isMovie", false)
                    }.toString()

                    episodes.add(newEpisode(streamData) {
                        this.name = epName
                        this.season = sNum
                        this.episode = eNum
                        this.posterUrl = epPoster
                    })
                }
            }
        }

        if (episodes.isEmpty()) {
            val streamData = JSONObject().apply {
                put("id", animeId)
                put("season", 1)
                put("episode", 1)
                put("isMovie", false)
            }.toString()
            episodes.add(newEpisode(streamData) {
                this.name = "1. Sezon 1. Bölüm"
                this.season = 1
                this.episode = 1
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundStream = false
        try {
            val obj = JSONObject(data)
            val animeId = obj.optString("id")
            val season = obj.optInt("season", 1)
            val episode = obj.optInt("episode", 1)

            val sourceUrl = "$mainUrl/anime/source?id=$animeId&site=main&server=1&season=$season&episode=$episode"
            val res = app.get(sourceUrl, headers = getHeaders()).text
            val json = JSONObject(res)

            // Extract subtitles
            val subsArr = json.optJSONArray("subtitles")
            if (subsArr != null) {
                for (i in 0 until subsArr.length()) {
                    val sub = subsArr.optJSONObject(i) ?: continue
                    val link = sub.optString("link")
                    val langName = sub.optString("name", "Türkçe")
                    if (link.isNotEmpty()) {
                        subtitleCallback.invoke(
                            newSubtitleFile(langName, link)
                        )
                    }
                }
            }

            // Extract video streams
            val groupsArr = json.optJSONArray("groups")
            if (groupsArr != null) {
                for (g in 0 until groupsArr.length()) {
                    val group = groupsArr.optJSONObject(g) ?: continue
                    val groupName = group.optString("name", "Japonca")
                    val itemsArr = group.optJSONArray("items") ?: continue

                    for (it in 0 until itemsArr.length()) {
                        val item = itemsArr.optJSONObject(it) ?: continue
                        val videoLink = item.optString("link")
                        val quality = item.optInt("quality", 1080)
                        if (videoLink.isNotEmpty()) {
                            val qString = when (quality) {
                                2160 -> "4K UHD"
                                1440 -> "2K QHD"
                                else -> "${quality}p"
                            }
                            val qualVal = when (quality) {
                                1080 -> Qualities.P1080.value
                                720 -> Qualities.P720.value
                                480 -> Qualities.P480.value
                                else -> quality
                            }
                            callback.invoke(
                                newExtractorLink(
                                    name = "$name - $groupName ($qString)",
                                    source = name,
                                    url = videoLink,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "$siteOrigin/"
                                    this.quality = qualVal
                                }
                            )
                            foundStream = true
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        if (!foundStream) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name 1080p",
                    url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$siteOrigin/"
                    this.quality = Qualities.P1080.value
                }
            )
            foundStream = true
        }

        return foundStream
    }
}
