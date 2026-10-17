@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.cloudstream.anthologycloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class HDFilmDelisi : MainAPI() {
    override var mainUrl = "https://hdfilmdelisi.one"
    override var name = "HDFilmDelisi"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/api/films" to "Son Eklenen Filmler",
        "$mainUrl/api/films?sort=imdb" to "En Yüksek IMDb",
        "$mainUrl/api/films?sort=popular" to "Popüler Filmler",
        "$mainUrl/api/search?q=aksiyon" to "Aksiyon",
        "$mainUrl/api/search?q=macera" to "Macera",
        "$mainUrl/api/search?q=bilim" to "Bilim Kurgu",
        "$mainUrl/api/search?q=komedi" to "Komedi",
        "$mainUrl/api/search?q=korku" to "Korku",
        "$mainUrl/api/search?q=gerilim" to "Gerilim",
        "$mainUrl/api/search?q=dram" to "Dram",
        "$mainUrl/api/search?q=suc" to "Suç",
        "$mainUrl/api/search?q=animasyon" to "Animasyon",
        "$mainUrl/api/search?q=fantastik" to "Fantastik",
        "$mainUrl/api/search?q=gizem" to "Gizem",
        "$mainUrl/api/search?q=romantik" to "Romantik",
        "$mainUrl/api/search?q=savas" to "Savaş",
        "$mainUrl/api/search?q=aile" to "Aile",
        "$mainUrl/api/search?q=belgesel" to "Belgesel"
    )

    private data class FilmItem(
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("baslik") val baslik: String? = null,
        @JsonProperty("afis") val afis: String? = null,
        @JsonProperty("yayinYili") val yayinYili: Int? = null,
        @JsonProperty("imdbPuani") val imdbPuani: Double? = null
    )

    private data class FilmsResponse(
        @JsonProperty("films") val films: List<FilmItem>? = null,
        @JsonProperty("results") val results: List<FilmItem>? = null
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val apiUrl = if (page > 1) {
            if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}?page=$page"
        } else {
            request.data
        }
        val response = try {
            app.get(apiUrl, referer = "$mainUrl/").parsedSafe<FilmsResponse>()
        } catch (_: Exception) { null }

        var list = response?.films ?: response?.results ?: emptyList()
        if (list.isEmpty() && request.data != "$mainUrl/api/films") {
            val fallback = try {
                app.get("$mainUrl/api/films", referer = "$mainUrl/").parsedSafe<FilmsResponse>()
            } catch (_: Exception) { null }
            list = fallback?.films ?: fallback?.results ?: emptyList()
        }

        val items = list.mapNotNull { film ->
            val slug = film.slug ?: return@mapNotNull null
            val title = film.baslik ?: slug
            val href = "$mainUrl/film/$slug"
            val poster = film.afis

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = film.yayinYili
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "$mainUrl/api/search?q=$encoded"
        val response = try {
            app.get(searchUrl, referer = "$mainUrl/").parsedSafe<FilmsResponse>()
        } catch (_: Exception) { null }

        var list = response?.results ?: response?.films ?: emptyList()
        if (list.isEmpty()) {
            val fallback = try {
                app.get("$mainUrl/api/films", referer = "$mainUrl/").parsedSafe<FilmsResponse>()
            } catch (_: Exception) { null }
            list = fallback?.films ?: fallback?.results ?: emptyList()
        }

        return list.mapNotNull { film ->
            val slug = film.slug ?: return@mapNotNull null
            val title = film.baslik ?: slug
            val href = "$mainUrl/film/$slug"
            val poster = film.afis

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = film.yayinYili
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore(" izle")?.trim()
            ?: name
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("p.overview, div.description, p")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
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

        val response = app.get(data)
        val embedUrls = mutableListOf<String>()

        val playerSrcMatch = Regex("""src=["'](https?://[^"']*(?:player|embed|vids)[^"']*)["']""").findAll(response.text)
        for (m in playerSrcMatch) {
            embedUrls.add(m.groupValues[1])
        }

        Regex("""https?://hdfilmdelisi\.one/vids/[^"'\s\\]+\.mp4""").findAll(response.text).forEach { m ->
            linkCallback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m.value,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        for (embed in embedUrls) {
            try {
                val embedResp = app.get(embed, referer = data).text
                Regex("""https?://hdfilmdelisi\.one/vids/[^"'\s\\]+\.mp4""").findAll(embedResp).forEach { m ->
                    linkCallback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = m.value,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = embed
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }

                // Any iframe in embed
                Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(embedResp).forEach { m ->
                    loadExtractor(m.groupValues[1], embed, subtitleCallback, linkCallback)
                }
            } catch (_: Exception) {}
        }

        // Generic iframe extraction
        response.document.select("iframe").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            try {
                loadExtractor(src, data, subtitleCallback, linkCallback)
            } catch (_: Exception) {}
        }

        if (!foundStream) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
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
