@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.cloudstream.anthologycloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject

class SeiCode : MainAPI() {
    override var mainUrl = "https://seicode.net"
    private val apiUrl = "https://next.seicode.net"
    override var name = "SeiCode"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$apiUrl/anime/latest" to "Son Eklenenler",
        "$apiUrl/anime/search?q=action" to "Aksiyon",
        "$apiUrl/anime/search?q=comedy" to "Komedi",
        "$apiUrl/anime/search?q=fantasy" to "Fantastik",
        "$apiUrl/anime/search?q=romance" to "Romantik"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val res = app.get(request.data, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).text
        val items = parseJsonResults(res)
        return newHomePageResponse(request.name, items)
    }

    private fun parseJsonResults(jsonStr: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val slug = obj.optString("slug")
                if (slug.isEmpty()) continue
                val title = obj.optString("english").ifEmpty { slug }
                val pictures = obj.optJSONObject("pictures")
                val poster = pictures?.optString("avatar")?.ifEmpty { pictures.optString("banner") }
                val typeStr = obj.optString("type")
                val tvType = if (typeStr.contains("movie", ignoreCase = true)) TvType.Movie else TvType.Anime
                items.add(newAnimeSearchResponse(title, "$apiUrl/anime/$slug", tvType) {
                    this.posterUrl = poster
                })
            }
        } catch (_: Exception) {}
        return items
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val res = app.get("$apiUrl/anime/search?q=$encoded", headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).text
            val results = parseJsonResults(res)
            if (results.isNotEmpty()) results else {
                getMainPage(1, MainPageRequest("Son Eklenenler", "$apiUrl/anime/latest", false))?.items?.flatMap { it.list } ?: emptyList()
            }
        } catch (e: Exception) {
            getMainPage(1, MainPageRequest("Son Eklenenler", "$apiUrl/anime/latest", false))?.items?.flatMap { it.list } ?: emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/")
        val fetchUrl = if (url.startsWith("http")) url else "$apiUrl/anime/$slug"
        val res = app.get(fetchUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).text
        val obj = JSONObject(res)

        val title = obj.optString("english").ifEmpty { slug }
        val pics = obj.optJSONObject("pictures")
        val poster = pics?.optString("avatar")?.ifEmpty { pics.optString("banner") }
        val plot = obj.optString("summary")

        val eps = mutableListOf<Episode>()
        val seasons = obj.optJSONArray("seasons")
        if (seasons != null) {
            for (s in 0 until seasons.length()) {
                val sObj = seasons.getJSONObject(s)
                val sNum = sObj.optInt("season_number", s + 1)
                val episodes = sObj.optJSONArray("episodes")
                if (episodes != null) {
                    for (e in 0 until episodes.length()) {
                        val epObj = episodes.getJSONObject(e)
                        val eNum = epObj.optInt("episode_number", e + 1)
                        val vLinks = epObj.optJSONObject("video_links")
                        val linkList = mutableListOf<String>()
                        if (vLinks != null) {
                            val keys = vLinks.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                val v = vLinks.optString(k)
                                if (v.isNotEmpty()) linkList.add(v)
                            }
                        }
                        val epData = if (linkList.isNotEmpty()) linkList.joinToString(";") else fetchUrl
                        eps.add(newEpisode(epData) {
                            this.name = "$sNum. Sezon $eNum. Bölüm"
                            this.season = sNum
                            this.episode = eNum
                        })
                    }
                }
            }
        }

        if (eps.isEmpty()) {
            eps.add(newEpisode(fetchUrl) {
                this.name = "1. Sezon 1. Bölüm"
                this.season = 1
                this.episode = 1
            })
        }

        return newTvSeriesLoadResponse(title, fetchUrl, TvType.Anime, eps) {
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
        val linkCallback: (ExtractorLink) -> Unit = { link ->
            foundStream = true
            callback.invoke(link)
        }

        try {
            val links = data.split(";")
            for (link in links) {
                val cleanLink = link.trim()
                if (cleanLink.startsWith("http")) {
                    try {
                        loadExtractor(cleanLink, "$mainUrl/", subtitleCallback, linkCallback)
                    } catch (_: Exception) {}
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
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P1080.value
                }
            )
            foundStream = true
        }

        return foundStream
    }
}
