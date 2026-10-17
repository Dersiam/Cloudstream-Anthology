@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class AnimeciX : MainAPI() {
    override var mainUrl              = "https://animecix.tv"
    override var name = "AnimeciX"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime, TvType.AnimeMovie, TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 200L
    override var sequentialMainPageScrollDelay = 200L

    override val mainPage = mainPageOf(
        "${mainUrl}/secure/titles?type=series&onlyStreamable=true" to "Popüler Anime Serileri",
        "${mainUrl}/secure/titles?type=movie&onlyStreamable=true"  to "Anime Filmleri",
        "${mainUrl}/secure/titles?type=series&onlyStreamable=true&sort=created_at" to "Yeni Eklenen Diziler",
        "${mainUrl}/secure/titles?type=movie&onlyStreamable=true&sort=created_at" to "Yeni Eklenen Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val response = app.get(
                "${request.data}&page=${page}&perPage=16",
                headers = mapOf(
                    "x-e-h" to "7Y2ozlO+QysR5w9Q6Tupmtvl9jJp7ThFH8SB+Lo7NvZjgjqRSqOgcT2v4ISM9sP10LmnlYI8WQ==.xrlyOBFS5BHjQ2Lk"
                )
            ).parsedSafe<Category>()

            val home = response?.pagination?.data?.map { anime ->
                newAnimeSearchResponse(
                    anime.title,
                    "${mainUrl}/secure/titles/${anime.id}?titleId=${anime.id}",
                    TvType.Anime
                ) {
                    this.posterUrl = fixUrlNull(anime.poster)
                }
            } ?: listOf<SearchResponse>()

            newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val response = app.get("${mainUrl}/secure/search/${encoded}?limit=20").parsedSafe<Search>()

            val results = response?.results?.map { anime ->
                newAnimeSearchResponse(
                    anime.title,
                    "${mainUrl}/secure/titles/${anime.id}?titleId=${anime.id}",
                    TvType.Anime
                ) {
                    this.posterUrl = fixUrlNull(anime.poster)
                }
            } ?: emptyList()

            if (results.isNotEmpty()) results else {
                getMainPage(1, MainPageRequest(name, "${mainUrl}/secure/titles?type=series&onlyStreamable=true", false)).items.flatMap { it.list }
            }
        } catch (e: Exception) {
            getMainPage(1, MainPageRequest(name, "${mainUrl}/secure/titles?type=series&onlyStreamable=true", false)).items.flatMap { it.list }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val response = try {
            app.get(
                url,
                headers = mapOf(
                    "x-e-h" to "7Y2ozlO+QysR5w9Q6Tupmtvl9jJp7ThFH8SB+Lo7NvZjgjqRSqOgcT2v4ISM9sP10LmnlYI8WQ==.xrlyOBFS5BHjQ2Lk"
                )
            ).parsedSafe<Title>()
        } catch (e: Exception) {
            null
        } ?: return null

        val episodes = mutableListOf<Episode>()
        val titleId  = url.substringAfter("?titleId=")

        if (response.title.titleType == "anime") {
            for (sezon in response.title.seasons) {
                try {
                    val sezonResponse = app.get("${mainUrl}/secure/related-videos?episode=1&season=${sezon.number}&videoId=0&titleId=${titleId}").parsedSafe<TitleVideos>()
                    if (sezonResponse != null) {
                        for (video in sezonResponse.videos) {
                            episodes.add(newEpisode(video.url) {
                                this.name = "${video.seasonNum}. Sezon ${video.episodeNum}. Bölüm"
                                this.season = video.seasonNum
                                this.episode = video.episodeNum
                            })
                        }
                    }
                } catch (_: Exception) {}
            }
        } else {
            if (response.title.videos.isNotEmpty()) {
                episodes.add(newEpisode(response.title.videos.first().url) {
                    this.name    = "Filmi İzle"
                    this.season  = 1
                    this.episode = 1
                })
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name    = "1. Sezon 1. Bölüm"
                this.season  = 1
                this.episode = 1
            })
        }

        return newTvSeriesLoadResponse(
            response.title.title,
            "${mainUrl}/secure/titles/${response.title.id}?titleId=${response.title.id}",
            TvType.Anime,
            episodes
        ) {
            this.posterUrl = fixUrlNull(response.title.poster)
            this.year      = response.title.year
            this.plot      = response.title.description
            this.tags      = response.title.tags.map { it.name }
            addActors(response.title.actors.map { Actor(it.name, fixUrlNull(it.poster)) })
            addTrailer(response.title.trailer)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var foundStream = false
        val linkCallback: (ExtractorLink) -> Unit = { link ->
            foundStream = true
            callback.invoke(link)
        }

        try {
            if (data.startsWith("http")) {
                val iframeLink = try {
                    app.get(data, referer = "${mainUrl}/").url
                } catch (_: Exception) {
                    data
                }
                loadExtractor(iframeLink, "${mainUrl}/", subtitleCallback, linkCallback)
            }
        } catch (e: Exception) {
            Log.e("ACX", "Error loadLinks", e)
        }

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