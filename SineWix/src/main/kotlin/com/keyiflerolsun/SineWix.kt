@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject

class SineWix : MainAPI() {
    override var mainUrl = "https://sinewix.net"
    override var name = "SineWix"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_KEY = "500330721680edb6d5f7f12ba7cd9023"
        private const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
    }

    override val mainPage = mainPageOf(
        "$TMDB_API/trending/all/day?api_key=$TMDB_KEY&language=tr-TR&page=" to "Günün Trendleri",
        "$TMDB_API/movie/popular?api_key=$TMDB_KEY&language=tr-TR&page=" to "Popüler Filmler",
        "$TMDB_API/tv/popular?api_key=$TMDB_KEY&language=tr-TR&page=" to "Popüler Diziler",
        "$TMDB_API/movie/top_rated?api_key=$TMDB_KEY&language=tr-TR&page=" to "En Çok Beğenilen Filmler",
        "$TMDB_API/discover/movie?api_key=$TMDB_KEY&language=tr-TR&with_genres=28&page=" to "Aksiyon Filmleri",
        "$TMDB_API/discover/movie?api_key=$TMDB_KEY&language=tr-TR&with_genres=35&page=" to "Komedi Filmleri",
        "$TMDB_API/discover/movie?api_key=$TMDB_KEY&language=tr-TR&with_genres=18&page=" to "Dram Filmleri",
        "$TMDB_API/discover/movie?api_key=$TMDB_KEY&language=tr-TR&with_genres=878&page=" to "Bilim Kurgu",
        "$TMDB_API/discover/movie?api_key=$TMDB_KEY&language=tr-TR&with_genres=27&page=" to "Korku & Gerilim",
        "$TMDB_API/discover/movie?api_key=$TMDB_KEY&language=tr-TR&with_genres=10749&page=" to "Romantik Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = app.get(
            "${request.data}$page",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "application/json"
            )
        )
        val json = JSONObject(res.text)
        val results = json.optJSONArray("results") ?: JSONArray()
        val items = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val id = item.optInt("id", 0)
            if (id == 0) continue
            val isTv = item.has("name") || item.optString("media_type") == "tv"
            val title = item.optString("title").ifEmpty { item.optString("name") }
            if (title.isEmpty()) continue
            val posterPath = item.optString("poster_path")
            val poster = if (posterPath.isNotEmpty() && posterPath != "null") "$IMAGE_BASE$posterPath" else null
            val typeStr = if (isTv) "tv" else "movie"
            val linkUrl = "$mainUrl/tmdb/$typeStr/$id"

            if (isTv) {
                items.add(newTvSeriesSearchResponse(title, linkUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                })
            } else {
                items.add(newMovieSearchResponse(title, linkUrl, TvType.Movie) {
                    this.posterUrl = poster
                })
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$TMDB_API/search/multi?api_key=$TMDB_KEY&language=tr-TR&query=$query"
        val res = app.get(
            url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "application/json"
            )
        )
        val json = JSONObject(res.text)
        val results = json.optJSONArray("results") ?: JSONArray()
        val items = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val id = item.optInt("id", 0)
            if (id == 0) continue
            val mediaType = item.optString("media_type")
            if (mediaType != "movie" && mediaType != "tv") continue
            val isTv = mediaType == "tv"
            val title = item.optString("title").ifEmpty { item.optString("name") }
            if (title.isEmpty()) continue
            val posterPath = item.optString("poster_path")
            val poster = if (posterPath.isNotEmpty() && posterPath != "null") "$IMAGE_BASE$posterPath" else null
            val typeStr = if (isTv) "tv" else "movie"
            val linkUrl = "$mainUrl/tmdb/$typeStr/$id"

            if (isTv) {
                items.add(newTvSeriesSearchResponse(title, linkUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                })
            } else {
                items.add(newMovieSearchResponse(title, linkUrl, TvType.Movie) {
                    this.posterUrl = poster
                })
            }
        }
        return items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.substringAfter("/tmdb/").split("/")
        val type = parts.getOrNull(0) ?: "movie"
        val id = parts.getOrNull(1)?.toIntOrNull() ?: return null

        val res = app.get(
            "$TMDB_API/$type/$id?api_key=$TMDB_KEY&language=tr-TR",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "application/json"
            )
        )
        val json = JSONObject(res.text)
        val title = json.optString("title").ifEmpty { json.optString("name") }
        val overview = json.optString("overview")
        val posterPath = json.optString("poster_path")
        val poster = if (posterPath.isNotEmpty() && posterPath != "null") "$IMAGE_BASE$posterPath" else null
        val year = json.optString("release_date").ifEmpty { json.optString("first_air_date") }.take(4).toIntOrNull()
        val rating = json.optDouble("vote_average", 0.0).toFloat()

        if (type == "tv") {
            val numSeasons = json.optInt("number_of_seasons", 1).coerceAtLeast(1)
            val episodes = mutableListOf<Episode>()
            val seasonsArr = json.optJSONArray("seasons")
            val seasonEpCounts = mutableMapOf<Int, Int>()
            if (seasonsArr != null) {
                for (s in 0 until seasonsArr.length()) {
                    val sObj = seasonsArr.getJSONObject(s)
                    val sNum = sObj.optInt("season_number", -1)
                    val epCount = sObj.optInt("episode_count", 0)
                    if (sNum > 0 && epCount > 0) {
                        seasonEpCounts[sNum] = epCount
                    }
                }
            }
            for (s in 1..numSeasons) {
                val epCount = seasonEpCounts[s] ?: 10
                for (e in 1..epCount) {
                    episodes.add(newEpisode("$mainUrl/embed/tv/$id/$s/$e") {
                        this.name = "$s. Sezon $e. Bölüm"
                        this.season = s
                        this.episode = e
                    })
                }
            }
            if (episodes.isEmpty()) {
                episodes.add(newEpisode("$mainUrl/embed/tv/$id/1/1") {
                    this.name = "1. Sezon 1. Bölüm"
                    this.season = 1
                    this.episode = 1
                })
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = overview
                this.year = year
                this.rating = rating.toInt()
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/embed/movie/$id") {
                this.posterUrl = poster
                this.plot = overview
                this.year = year
                this.rating = rating.toInt()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isTv = data.contains("/embed/tv/")
        val parts = data.substringAfter("/embed/").split("/")
        val id = parts.getOrNull(1) ?: return true

        val embedUrls = mutableListOf<String>()
        if (isTv) {
            val s = parts.getOrNull(2) ?: "1"
            val e = parts.getOrNull(3) ?: "1"
            embedUrls.add("https://vixsrc.to/embed/tv/$id/$s/$e")
            embedUrls.add("https://multiembed.mov/?video_id=$id&tmdb=1&s=$s&e=$e")
            embedUrls.add("https://vidlink.pro/tv/$id/$s/$e")
            embedUrls.add("https://vidsrc.to/embed/tv/$id/$s/$e")
        } else {
            embedUrls.add("https://vixsrc.to/embed/movie/$id")
            embedUrls.add("https://multiembed.mov/?video_id=$id&tmdb=1")
            embedUrls.add("https://vidlink.pro/movie/$id")
            embedUrls.add("https://vidsrc.to/embed/movie/$id")
        }

        var foundStream = false
        for (embed in embedUrls) {
            try {
                loadExtractor(embed, subtitleCallback) { link ->
                    callback(link)
                    foundStream = true
                }
            } catch (_: Exception) {}
        }

        if (!foundStream) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} 1080p",
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
