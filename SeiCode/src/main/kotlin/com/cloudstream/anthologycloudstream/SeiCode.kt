@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.cloudstream.anthologycloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class SeiCode : MainAPI() {
    override var mainUrl = "https://seicode.net"
    private val apiUrl = "https://next.seicode.net"
    override var name = "SeiCode"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.Movie)

    data class AnimePicture(
        @JsonProperty("banner") val banner: String? = null,
        @JsonProperty("avatar") val avatar: String? = null
    )

    data class AnimeItem(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("pictures") val pictures: AnimePicture? = null
    )

    data class EpisodeItem(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("video_links") val videoLinks: Map<String, String>? = null
    )

    data class SeasonItem(
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("episodes") val episodes: List<EpisodeItem>? = null
    )

    data class AnimeDetail(
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("summary") val summary: String? = null,
        @JsonProperty("pictures") val pictures: AnimePicture? = null,
        @JsonProperty("seasons") val seasons: List<SeasonItem>? = null
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val json = app.get("$apiUrl/anime/latest", headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).parsedSafe<List<AnimeItem>>()
        val items = json?.mapNotNull { item ->
            val slug = item.slug ?: return@mapNotNull null
            val title = item.english ?: slug
            val poster = item.pictures?.avatar ?: item.pictures?.banner
            newAnimeSearchResponse(title, "$apiUrl/anime/$slug", TvType.Anime) {
                this.posterUrl = poster
            }
        } ?: emptyList()
        return newHomePageResponse(name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val json = app.get("$apiUrl/anime/search?q=$query", headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).parsedSafe<List<AnimeItem>>()
            val results = json?.mapNotNull { item ->
                val slug = item.slug ?: return@mapNotNull null
                val title = item.english ?: slug
                val poster = item.pictures?.avatar ?: item.pictures?.banner
                newAnimeSearchResponse(title, "$apiUrl/anime/$slug", TvType.Anime) {
                    this.posterUrl = poster
                }
            } ?: emptyList()

            if (results.isNotEmpty()) results else {
                getMainPage(1, MainPageRequest(name, mainUrl, false))?.items?.flatMap { it.list } ?: emptyList()
            }
        } catch (e: Exception) {
            getMainPage(1, MainPageRequest(name, mainUrl, false))?.items?.flatMap { it.list } ?: emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/")
        val fetchUrl = if (url.startsWith("http")) url else "$apiUrl/anime/$slug"
        val detail = app.get(fetchUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).parsedSafe<AnimeDetail>()

        val title = detail?.english ?: slug
        val poster = detail?.pictures?.avatar ?: detail?.pictures?.banner
        val plot = detail?.summary

        val eps = mutableListOf<Episode>()
        detail?.seasons?.forEach { s ->
            val sNum = s.seasonNumber ?: 1
            s.episodes?.forEach { ep ->
                val epNum = ep.episodeNumber ?: (eps.size + 1)
                val links = ep.videoLinks?.values?.joinToString(";") ?: ""
                val epData = if (links.isNotEmpty()) links else fetchUrl
                eps.add(newEpisode(epData) {
                    this.name = "$sNum. Sezon $epNum. Bölüm"
                    this.season = sNum
                    this.episode = epNum
                })
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
        try {
            val links = data.split(";")
            for (link in links) {
                val cleanLink = link.trim()
                if (cleanLink.startsWith("http")) {
                    loadExtractor(cleanLink, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return true
    }
}
