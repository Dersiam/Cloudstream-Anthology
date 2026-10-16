@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.Calendar
import java.util.TimeZone

class Anizm : MainAPI() {
    override var mainUrl = "https://api.anizium.co"
    override var name = "Anizm"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val siteOrigin = "https://anizium.co"
    private val tokenKey = "hlxjl1c2w281ax473rt1ofgrvhyjvi"

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

    override val mainPage = mainPageOf(
        "settlement_middle" to "Son Eklenen Bölümler",
        "settlement_top" to "Öne Çıkan Animeler",
        "settlement_lower" to "Popüler Animeler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeItems = mutableListOf<SearchResponse>()
        try {
            val res = app.get("$mainUrl/page/home", headers = getHeaders()).text
            val json = JSONObject(res)
            val sec = request.data
            val arr = json.optJSONArray(sec) ?: json.optJSONArray("settlement_top")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("ID").ifEmpty { item.optString("id") }
                    val title = item.optString("name").ifEmpty { item.optString("title") }
                    if (id.isEmpty() || title.isEmpty()) continue
                    val poster = item.optString("poster").ifEmpty { item.optString("banner") }
                    val isTv = item.optString("type") != "movie"

                    if (isTv) {
                        homeItems.add(newTvSeriesSearchResponse(title, "$siteOrigin/anime/$id", TvType.Anime) {
                            this.posterUrl = poster
                        })
                    } else {
                        homeItems.add(newMovieSearchResponse(title, "$siteOrigin/anime/$id", TvType.AnimeMovie) {
                            this.posterUrl = poster
                        })
                    }
                }
            }
        } catch (e: Exception) {}

        return newHomePageResponse(request.name, homeItems.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val res = app.get("$mainUrl/page/search?value=${query.trim()}&page=1", headers = getHeaders()).text
            val json = JSONObject(res)
            val data = json.optJSONObject("page")?.optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val id = item.optString("ID").ifEmpty { item.optString("id") }
                    val title = item.optString("name").ifEmpty { item.optString("title") }
                    if (id.isEmpty() || title.isEmpty()) continue
                    val poster = item.optString("poster").ifEmpty { item.optString("banner") }
                    val isTv = item.optString("type") != "movie"

                    if (isTv) {
                        results.add(newTvSeriesSearchResponse(title, "$siteOrigin/anime/$id", TvType.Anime) {
                            this.posterUrl = poster
                        })
                    } else {
                        results.add(newMovieSearchResponse(title, "$siteOrigin/anime/$id", TvType.AnimeMovie) {
                            this.posterUrl = poster
                        })
                    }
                }
            }
        } catch (e: Exception) {}

        if (results.isEmpty()) {
            try {
                val home = getMainPage(1, MainPageRequest(name, "$mainUrl/page/home", false))
                return home.items.flatMap { it.list }
            } catch (e: Exception) {}
        }
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.removeSuffix("/").substringBefore("?")
        val animeId = if (cleanUrl.contains("/anime/")) {
            cleanUrl.substringAfterLast("/anime/").substringAfterLast("/").trim()
        } else {
            cleanUrl.substringAfterLast("/").trim()
        }
        val res = app.get("$mainUrl/anime/get?id=$animeId", headers = getHeaders()).text
        val json = JSONObject(res)
        val data = json.optJSONObject("data") ?: return null

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
        val obj = try { JSONObject(data) } catch (e: Exception) { return false }
        val animeId = obj.optString("id")
        val season = obj.optInt("season", 1)
        val episode = obj.optInt("episode", 1)

        val sourceUrl = "$mainUrl/anime/source?id=$animeId&site=main&server=1&season=$season&episode=$episode"
        val res = app.get(sourceUrl, headers = getHeaders()).text
        val json = JSONObject(res)
        if (!json.optBoolean("success", false)) return false

        // Subtitles
        val subsArr = json.optJSONArray("subtitles")
        if (subsArr != null) {
            for (i in 0 until subsArr.length()) {
                val sub = subsArr.optJSONObject(i) ?: continue
                val link = sub.optString("link")
                val langName = sub.optString("name", "Türkçe")
                if (link.isNotEmpty()) {
                    subtitleCallback.invoke(newSubtitleFile(langName, link))
                }
            }
        }

        // Videos
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
                        callback.invoke(
                            newExtractorLink(
                                name = "$name - $groupName ($qString)",
                                source = name,
                                url = videoLink,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.headers = mapOf(
                                    "Referer" to "$siteOrigin/",
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                )
                            }
                        )
                    }
                }
            }
        }

        return true
    }
}
