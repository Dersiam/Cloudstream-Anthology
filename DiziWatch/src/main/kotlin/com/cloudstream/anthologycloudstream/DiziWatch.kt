@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.cloudstream.anthologycloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup

data class DiziWatchSearchItem(
    @JsonProperty("object_name") val objectName: String? = null,
    @JsonProperty("object_alternative_name") val objectAlternativeName: String? = null,
    @JsonProperty("used_slug") val usedSlug: String? = null,
    @JsonProperty("object_type") val objectType: String? = null,
    @JsonProperty("object_image") val objectImage: String? = null
)

data class DiziWatchSearchData(
    @JsonProperty("state") val state: Boolean? = null,
    @JsonProperty("result") val result: List<DiziWatchSearchItem>? = null
)

data class DiziWatchSearchResponse(
    @JsonProperty("data") val data: DiziWatchSearchData? = null
)

class DiziWatch : MainAPI() {
    override var mainUrl = "https://diziwatch.ac"
    override var name = "DiziWatch"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Tüm Diziler",
        "$mainUrl/animeler" to "Animeler",
        "$mainUrl/episodes" to "Son Bölümler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(request.data).document
        val items = document.select("div.post-card, article.post, div.show-card").mapNotNull { el ->
            val title = el.selectFirst("h2, h3, .title, a.title")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrlNull(el.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val poster = fixUrlNull(el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } })
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.post(
            "$mainUrl/bg/searchcontent",
            data = mapOf("searchterm" to query),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$mainUrl/"
            )
        ).parsedSafe<DiziWatchSearchResponse>()

        return res?.data?.result?.mapNotNull { item ->
            val name = item.objectName ?: return@mapNotNull null
            val slug = item.usedSlug?.removePrefix("/") ?: return@mapNotNull null
            val href = "$mainUrl/$slug"
            val poster = item.objectImage
            val type = if (item.objectType?.contains("anime", ignoreCase = true) == true) TvType.Anime else TvType.TvSeries
            newTvSeriesSearchResponse(name, href, type) {
                this.posterUrl = poster
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: name
        val poster = fixUrlNull(document.selectFirst("div.show-poster img, img.poster")?.attr("src"))
        val description = document.selectFirst("div.show-desc, div.description")?.text()?.trim()

        val episodes = mutableListOf<Episode>()
        val epRegex = Regex("""sezon-(\d+)/bolum-(\d+)""")

        document.select("a[href*='sezon-']").forEach { link ->
            val href = fixUrlNull(link.attr("href")) ?: return@forEach
            val match = epRegex.find(href)
            val season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episode = match?.groupValues?.get(2)?.toIntOrNull() ?: 1
            episodes.add(
                newEpisode(href) {
                    this.name = "$season. Sezon $episode. Bölüm"
                    this.season = season
                    this.episode = episode
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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
        val document = app.get(data).document
        val iframes = mutableListOf<String>()

        document.select("iframe").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src"))
            if (src != null) iframes.add(src)
        }

        val cstkIframe = document.selectFirst("div#cstk iframe")?.attr("src")
        if (cstkIframe != null) {
            fixUrlNull(cstkIframe)?.let { iframes.add(it) }
        }

        for (iframeUrl in iframes) {
            if (iframeUrl.contains("pichive.online")) {
                val pichiveDoc = app.get(iframeUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                val fileMatch = Regex("""file["']?\s*:\s*["']([^"']+)["']""").find(pichiveDoc)
                val m3u8Url = fileMatch?.groupValues?.get(1)
                if (m3u8Url != null) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - Pichive",
                            url = fixUrl(m3u8Url),
                            type = ExtractorLinkType.M3U8
                        ) {
                            headers = mapOf("Referer" to "$mainUrl/")
                        }
                    )
                }
            } else {
                loadExtractor(iframeUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
