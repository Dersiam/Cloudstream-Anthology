@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
// ! https://github.com/recloudstream/extensions/blob/master/InvidiousProvider/src/main/kotlin/recloudstream/InvidiousProvider.kt

package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri

class YouTubeDiziFilm : MainAPI() {
    override var mainUrl = "https://invidious.f5.si"
    override var name = "YouTube Dizi & Film"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Others)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val trending = try {
            tryParseJson<List<SearchEntry>>(
                app.get("${mainUrl}/api/v1/trending?region=${lang.uppercase()}&type=news&fields=videoId,title").text
            )
        } catch (e: Exception) { null }

        val music = try {
            tryParseJson<List<SearchEntry>>(
                app.get("${mainUrl}/api/v1/trending?region=${lang.uppercase()}&type=music&fields=videoId,title").text
            )
        } catch (e: Exception) { null }

        val movies = try {
            tryParseJson<List<SearchEntry>>(
                app.get("${mainUrl}/api/v1/trending?region=${lang.uppercase()}&type=movies&fields=videoId,title").text
            )
        } catch (e: Exception) { null }

        val gaming = try {
            tryParseJson<List<SearchEntry>>(
                app.get("${mainUrl}/api/v1/trending?region=${lang.uppercase()}&type=gaming&fields=videoId,title").text
            )
        } catch (e: Exception) { null }

        val list = mutableListOf<HomePageList>()
        if (!trending.isNullOrEmpty()) {
            list.add(HomePageList("Trend", trending.map { it.toSearchResponse(this) }, true))
        }
        if (!music.isNullOrEmpty()) {
            list.add(HomePageList("Müzik", music.map { it.toSearchResponse(this) }, true))
        }
        if (!movies.isNullOrEmpty()) {
            list.add(HomePageList("Film", movies.map { it.toSearchResponse(this) }, true))
        }
        if (!gaming.isNullOrEmpty()) {
            list.add(HomePageList("Oyun", gaming.map { it.toSearchResponse(this) }, true))
        }

        if (list.isEmpty()) {
            list.add(HomePageList("Trend", listOf(
                newMovieSearchResponse("YouTube Trend", "${mainUrl}/watch?v=dQw4w9WgXcQ", TvType.Others) {
                    this.posterUrl = "${mainUrl}/vi/dQw4w9WgXcQ/mqdefault.jpg"
                }
            ), true))
        }

        return newHomePageResponse(list, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val res = tryParseJson<List<SearchEntry>>(
                app.get("${mainUrl}/api/v1/search?q=${query.encodeUri()}&region=${lang.uppercase()}&page=1&type=video&fields=videoId,title").text
            )
            val results = res?.map { it.toSearchResponse(this) } ?: emptyList()
            if (results.isNotEmpty()) results else {
                getMainPage(1, MainPageRequest(name, mainUrl, false)).items.flatMap { it.list }
            }
        } catch (e: Exception) {
            try {
                getMainPage(1, MainPageRequest(name, mainUrl, false)).items.flatMap { it.list }
            } catch (ex: Exception) {
                listOf(
                    newMovieSearchResponse(query, "${mainUrl}/watch?v=dQw4w9WgXcQ", TvType.Others) {
                        this.posterUrl = "${mainUrl}/vi/dQw4w9WgXcQ/mqdefault.jpg"
                    }
                )
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val videoId = Regex("watch\\?v=([a-zA-Z0-9_-]+)").find(url)?.groupValues?.get(1) ?: "dQw4w9WgXcQ"
        return try {
            val res = tryParseJson<VideoEntry>(
                app.get("${mainUrl}/api/v1/videos/${videoId}?region=${lang.uppercase()}&fields=videoId,title,description,recommendedVideos,author,authorThumbnails,formatStreams").text
            )
            res?.toLoadResponse(this) ?: newMovieLoadResponse(name, url, TvType.Others, videoId) {
                this.posterUrl = "${mainUrl}/vi/${videoId}/hqdefault.jpg"
            }
        } catch (e: Exception) {
            newMovieLoadResponse(name, url, TvType.Others, videoId) {
                this.posterUrl = "${mainUrl}/vi/${videoId}/hqdefault.jpg"
            }
        }
    }

    private data class SearchEntry(val title: String, val videoId: String) {
        fun toSearchResponse(provider: YouTubeDiziFilm): SearchResponse {
            return provider.newMovieSearchResponse(
                title,
                "${provider.mainUrl}/watch?v=${videoId}",
                TvType.Others
            ) {
                this.posterUrl = "${provider.mainUrl}/vi/${videoId}/mqdefault.jpg"
            }
        }
    }

    private data class VideoEntry(
        val title: String,
        val description: String?,
        val videoId: String,
        val recommendedVideos: List<SearchEntry>?,
        val author: String?,
        val authorThumbnails: List<Thumbnail>?
    ) {
        suspend fun toLoadResponse(provider: YouTubeDiziFilm): LoadResponse {
            return provider.newMovieLoadResponse(
                title,
                "${provider.mainUrl}/watch?v=${videoId}",
                TvType.Others,
                videoId
            ) {
                plot            = description
                posterUrl       = "${provider.mainUrl}/vi/${videoId}/hqdefault.jpg"
                recommendations = recommendedVideos?.map { it.toSearchResponse(provider) } ?: emptyList()
                if (author != null) {
                    actors = listOf(ActorData(Actor(
                        author,
                        if (!authorThumbnails.isNullOrEmpty()) authorThumbnails.last().url else ""
                    )))
                }
            }
        }
    }

    private data class Thumbnail(val url: String)

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            loadExtractor("https://youtube.com/watch?v=${data}", subtitleCallback, callback)
            callback(
                newExtractorLink(
                    "YouTube",
                    "YouTube",
                    "${mainUrl}/api/manifest/dash/id/${data}",
                    ExtractorLinkType.DASH
                ) {
                    this.referer = ""
                    this.extractorData = null
                    this.headers = mapOf()
                }
            )
        } catch (e: Exception) {
            // ignore
        }
        return true
    }
}
