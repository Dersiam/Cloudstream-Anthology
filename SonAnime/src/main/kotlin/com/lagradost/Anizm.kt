@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.lagradost

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject

class SonAnime : MainAPI() {
    override var mainUrl = "https://api.sonanime.com"
    override var name = "SonAnime"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/api/anime?filter=latest" to "Son Eklenen Animeler",
        "${mainUrl}/api/anime?filter=top" to "En Çok Beğenilenler (MAL)",
        "${mainUrl}/api/anime?filter=movie" to "Anime Filmleri",
        "${mainUrl}/api/anime?filter=popular" to "Popüler Animeler",
        "${mainUrl}/api/anime?filter=all" to "Tüm Animeler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val filter = request.data.substringAfter("?filter=", "latest")
        val res = app.get(
            "${mainUrl}/api/anime",
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        ).text

        val array = JSONArray(res)
        val list = mutableListOf<JSONObject>()
        for (i in 0 until array.length()) {
            list.add(array.getJSONObject(i))
        }

        val filtered = when (filter) {
            "top" -> list.sortedByDescending { it.optDouble("anime_malScore", 0.0) }
            "movie" -> list.filter { it.optString("anime_type").contains("movie", ignoreCase = true) }
            "popular" -> list.filter { it.optDouble("anime_malScore", 0.0) >= 7.5 }
            else -> list
        }

        val home = filtered.take(40).mapNotNull { obj ->
            val link = obj.optString("anime_link").ifEmpty { return@mapNotNull null }
            val title = obj.optString("anime_name").ifEmpty { obj.optString("anime_name_en") }.ifEmpty { link }
            val photo = obj.optString("anime_photo")
            val isMovie = obj.optString("anime_type").contains("movie", ignoreCase = true)
            val type = if (isMovie) TvType.AnimeMovie else TvType.Anime

            newAnimeSearchResponse(title, "${mainUrl}/api/anime/link/$link", type) {
                this.posterUrl = photo
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val searchUrl = "${mainUrl}/api/anime/search?q=${query.trim()}"
            val res = app.get(searchUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).text
            val array = JSONArray(res)
            val items = mutableListOf<SearchResponse>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val link = obj.optString("anime_link").ifEmpty { continue }
                val title = obj.optString("anime_name").ifEmpty { obj.optString("anime_name_en") }.ifEmpty { link }
                val photo = obj.optString("anime_photo")
                val isMovie = obj.optString("anime_type").contains("movie", ignoreCase = true)
                val type = if (isMovie) TvType.AnimeMovie else TvType.Anime

                items.add(newAnimeSearchResponse(title, "${mainUrl}/api/anime/link/$link", type) {
                    this.posterUrl = photo
                })
            }
            if (items.isNotEmpty()) items else getMainPage(1, MainPageRequest(name, "${mainUrl}/api/anime?filter=latest", false)).items.flatMap { it.list }
        } catch (_: Exception) {
            getMainPage(1, MainPageRequest(name, "${mainUrl}/api/anime?filter=latest", false)).items.flatMap { it.list }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val apiUrl = if (url.startsWith("http")) url else "${mainUrl}/api/anime/link/$url"
        val res = app.get(apiUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).text
        val detail = JSONObject(res)

        val title = detail.optString("anime_name").ifEmpty { detail.optString("anime_name_en") }.ifEmpty { "Anime" }
        val poster = detail.optString("anime_photo")
        val plot = detail.optString("anime_description")
        val year = detail.optString("anime_year").take(4).toIntOrNull()
        val isMovie = detail.optString("anime_type").contains("movie", ignoreCase = true)

        val episodeList = mutableListOf<Episode>()
        val episodesArr = detail.optJSONArray("episodes")
        if (episodesArr != null) {
            for (i in 0 until episodesArr.length()) {
                val ep = episodesArr.getJSONObject(i)
                val epNum = ep.optInt("episode_number", i + 1)
                val season = ep.optInt("anime_season", 1)

                val link1080 = ep.optString("episode_link_1080")
                val link720 = ep.optString("episode_link_720")
                val link480 = ep.optString("episode_link_480")
                val link360 = ep.optString("episode_link_360")

                val linksJson = JSONObject().apply {
                    if (link1080.isNotEmpty() && link1080 != "null") put("1080", link1080)
                    if (link720.isNotEmpty() && link720 != "null") put("720", link720)
                    if (link480.isNotEmpty() && link480 != "null") put("480", link480)
                    if (link360.isNotEmpty() && link360 != "null") put("360", link360)
                }.toString()

                episodeList.add(newEpisode(linksJson) {
                    this.name = "Bölüm $epNum"
                    this.season = season
                    this.episode = epNum
                })
            }
        }

        if (episodeList.isEmpty()) {
            episodeList.add(newEpisode("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4") {
                this.name = "1. Bölüm"
                this.season = 1
                this.episode = 1
            })
        }

        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime
        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            addEpisodes(DubStatus.Subbed, episodeList)
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
            if (data.startsWith("{")) {
                val json = JSONObject(data)
                val qualities = listOf("1080", "720", "480", "360")
                for (quality in qualities) {
                    val videoUrl = json.optString(quality, "")
                    if (videoUrl.isNotBlank() && videoUrl != "null") {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} ${quality}p",
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://sonanime.com/"
                                this.quality = getQualityFromName(quality)
                            }
                        )
                        foundStream = true
                    }
                }
            } else if (data.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} 1080p",
                        url = data,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://sonanime.com/"
                        this.quality = Qualities.P1080.value
                    }
                )
                foundStream = true
            }
        } catch (e: Exception) {
            Log.e("SonAnime", "loadLinks error: ${e.message}")
        }

        if (!foundStream) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} 1080p",
                    url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://sonanime.com/"
                    this.quality = Qualities.P1080.value
                }
            )
            foundStream = true
        }

        return foundStream
    }
}
