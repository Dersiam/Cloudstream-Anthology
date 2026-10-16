@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.cloudstream.anthologycloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DiziBak : MainAPI() {
    override var mainUrl = "https://dizibak.net"
    override var name = "DiziBak"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        val items = document.select("a[href*='/diziler/']").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (href == mainUrl || href == "$mainUrl/" || href.contains("/kategori/")) return null

        val img = this.selectFirst("img")
        val title = this.attr("title").replace(" izle", "").trim().ifEmpty { null }
            ?: img?.attr("alt")?.trim()?.ifEmpty { null }
            ?: this.text().trim().replace(" izle", "").ifEmpty { null }
            ?: return null

        val poster = fixUrlNull(img?.let { it.attr("data-src").ifEmpty { it.attr("src") } })

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val searchUrl = "$mainUrl/?s=$query"
            val document = app.get(searchUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
            val results = document.select("a[href*='/diziler/']").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
            if (results.isNotEmpty()) results else getMainPage(1, MainPageRequest(name, mainUrl, false)).items
        } catch (e: Exception) {
            getMainPage(1, MainPageRequest(name, mainUrl, false)).items
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        val title = document.selectFirst("h1")?.text()?.replace(" izle", "")?.trim() ?: name
        val poster = fixUrlNull(document.selectFirst("div.poster img, img")?.attr("src"))
        val description = document.selectFirst("div.description, p.overview, div.summary, p")?.text()?.trim()

        val episodeElements = document.select("a[href*='-sezon-'], a[href*='-bolum-'], a[href*='/bolum/']")
        val eps = mutableListOf<Episode>()
        val epRegex = Regex("""(\d+)[.-]sezon[.-](\d+)[.-]bolum""")
        val added = mutableSetOf<String>()

        for (a in episodeElements) {
            val epHref = fixUrlNull(a.attr("href")) ?: continue
            if (!added.add(epHref)) continue
            val match = epRegex.find(epHref)
            val sNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val eNum = match?.groupValues?.get(2)?.toIntOrNull() ?: (eps.size + 1)

            eps.add(newEpisode(epHref) {
                this.name = a.text().trim().ifEmpty { "$sNum. Sezon $eNum. Bölüm" }
                this.season = sNum
                this.episode = eNum
            })
        }

        if (eps.isEmpty()) {
            eps.add(newEpisode(url) {
                this.name = "1. Sezon 1. Bölüm"
                this.season = 1
                this.episode = 1
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
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
        try {
            val document = app.get(data, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
            val iframes = document.select("iframe").mapNotNull { fixUrlNull(it.attr("src").ifEmpty { it.attr("data-src") }) }
            for (iframe in iframes) {
                try {
                    loadExtractor(iframe, subtitleCallback, callback)
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
        return true
    }
}
