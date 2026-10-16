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
        val apiUrl = "$mainUrl/api/films?page=$page"
        val response = app.get(apiUrl, referer = "$mainUrl/").parsedSafe<FilmsResponse>()
        val items = (response?.films ?: response?.results ?: emptyList()).mapNotNull { film ->
            val slug = film.slug ?: return@mapNotNull null
            val title = film.baslik ?: slug
            val href = "$mainUrl/film/$slug"
            val poster = film.afis

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = film.yayinYili
            }
        }
        return newHomePageResponse(name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/api/search?q=${query.trim()}"
        val response = app.get(searchUrl, referer = "$mainUrl/").parsedSafe<FilmsResponse>()
        val items = (response?.results ?: response?.films ?: emptyList()).mapNotNull { film ->
            val slug = film.slug ?: return@mapNotNull null
            val title = film.baslik ?: slug
            val href = "$mainUrl/film/$slug"
            val poster = film.afis

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = film.yayinYili
            }
        }
        return items
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
        val response = app.get(data, referer = "$mainUrl/")
        val html = response.text

        // Check for direct embed URLs or video files in page HTML / Next data
        val embedUrls = Regex("""https?://hdfilmdelisi\.one/embed/[^"'\s\\]+""").findAll(html).map { it.value }.toSet()
        val directVids = Regex("""https?://hdfilmdelisi\.one/vids/[^"'\s\\]+\.mp4""").findAll(html).map { it.value }.toSet()

        for (vid in directVids) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = vid,
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
                    callback.invoke(
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
                    loadExtractor(m.groupValues[1], embed, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
        }

        // Generic iframe extraction
        response.document.select("iframe").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            loadExtractor(src, data, subtitleCallback, callback)
        }

        return true
    }
}
