@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.lagradost

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

    private data class AnimeItem(
        @JsonProperty("anime_id") val animeId: Int? = null,
        @JsonProperty("anime_name") val animeName: String? = null,
        @JsonProperty("anime_name_en") val animeNameEn: String? = null,
        @JsonProperty("anime_type") val animeType: String? = null,
        @JsonProperty("anime_description") val animeDescription: String? = null,
        @JsonProperty("anime_year") val animeYear: String? = null,
        @JsonProperty("anime_malScore") val animeMalScore: Double? = null,
        @JsonProperty("anime_photo") val animePhoto: String? = null,
        @JsonProperty("anime_link") val animeLink: String? = null
    )

    private data class EpisodeItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("anime_id") val animeId: Int? = null,
        @JsonProperty("anime_season") val animeSeason: Int? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("episode_link_1080") val link1080: String? = null,
        @JsonProperty("episode_link_720") val link720: String? = null,
        @JsonProperty("episode_link_480") val link480: String? = null,
        @JsonProperty("episode_link_360") val link360: String? = null
    )

    private data class AnimeDetail(
        @JsonProperty("anime_id") val animeId: Int? = null,
        @JsonProperty("anime_name") val animeName: String? = null,
        @JsonProperty("anime_description") val animeDescription: String? = null,
        @JsonProperty("anime_year") val animeYear: String? = null,
        @JsonProperty("anime_photo") val animePhoto: String? = null,
        @JsonProperty("anime_link") val animeLink: String? = null,
        @JsonProperty("episodes") val episodes: List<EpisodeItem>? = null
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/api/anime" to "Son Eklenen Animeler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val animes = app.get(request.data).parsedSafe<List<AnimeItem>>() ?: emptyList()
        val home = animes.mapNotNull { item ->
            val link = item.animeLink ?: return@mapNotNull null
            val title = item.animeName ?: item.animeNameEn ?: link
            val url = "${mainUrl}/api/anime/link/$link"

            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = item.animePhoto
            }
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "${mainUrl}/api/anime/search?q=${query.trim()}"
        val animes = app.get(searchUrl).parsedSafe<List<AnimeItem>>() ?: emptyList()
        return animes.mapNotNull { item ->
            val link = item.animeLink ?: return@mapNotNull null
            val title = item.animeName ?: item.animeNameEn ?: link
            val url = "${mainUrl}/api/anime/link/$link"

            newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = item.animePhoto
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val apiUrl = if (url.startsWith("http")) url else "${mainUrl}/api/anime/link/$url"
        val detail = app.get(apiUrl).parsedSafe<AnimeDetail>() ?: return null

        val title = detail.animeName ?: "Anime"
        val poster = detail.animePhoto
        val plot = detail.animeDescription
        val year = detail.animeYear?.toIntOrNull()

        val episodeList = detail.episodes?.mapNotNull { ep ->
            val epNum = ep.episodeNumber ?: return@mapNotNull null
            val season = ep.animeSeason ?: 1

            // Store links as JSON in episode data
            val linksJson = JSONObject().apply {
                ep.link1080?.let { put("1080", it) }
                ep.link720?.let { put("720", it) }
                ep.link480?.let { put("480", it) }
                ep.link360?.let { put("360", it) }
            }.toString()

            newEpisode(linksJson) {
                this.name = "Bölüm $epNum"
                this.season = season
                this.episode = epNum
            }
        } ?: emptyList()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
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
        try {
            if (data.startsWith("{")) {
                val json = JSONObject(data)
                listOf("1080", "720", "480", "360").forEach { quality ->
                    val videoUrl = json.optString(quality, null)
                    if (!videoUrl.isNullOrBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} $quality",
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://sonanime.com/"
                                this.quality = getQualityFromName(quality)
                            }
                        )
                    }
                }
            } else if (data.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = data,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://sonanime.com/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("SonAnime", "loadLinks error: ${e.message}")
        }
        return true
    }
}
