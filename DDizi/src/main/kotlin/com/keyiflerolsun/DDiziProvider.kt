@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DDizi : MainAPI() {
    override var mainUrl = "https://www.ddizi.tel"
    override var name = "DDizi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl" to "Son Eklenen Bölümler",
        "$mainUrl/yabanci-dizi-izle" to "Yabancı Diziler",
        "$mainUrl/eski.diziler" to "Eski Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        val items = document.select("div.dizi-boxpost-cat, div.dizi-boxpost, a[href*='/diziler/'], a[href*='/izle/']").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = fixUrlNull(linkElem.attr("href")) ?: return null
        if (href == mainUrl || href == "$mainUrl/" || href.contains("/iletisim") || href.contains("/kategori/")) return null

        val img = this.selectFirst("img")
        val title = this.selectFirst("div.title, h2, h3, h4, .title")?.text()?.trim()
            ?: img?.attr("alt")?.trim()?.ifEmpty { null }
            ?: linkElem.attr("title").ifEmpty { null }
            ?: linkElem.text().trim().ifEmpty { null }
            ?: return null

        val poster = fixUrlNull(img?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        })

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val searchUrl = "$mainUrl/?s=$query"
            val document = app.get(searchUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
            val results = document.select("div.dizi-boxpost-cat, div.dizi-boxpost, a[href*='/diziler/'], a[href*='/izle/']").mapNotNull {
                it.toSearchResult()
            }.distinctBy { it.url }
            if (results.isNotEmpty()) results else getMainPage(1, MainPageRequest(name, mainUrl, false)).items.flatMap { it.list }
        } catch (e: Exception) {
            getMainPage(1, MainPageRequest(name, mainUrl, false)).items.flatMap { it.list }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: name
        val poster = fixUrlNull(document.selectFirst("div.dizi-detay-resim img, div.poster img, img")?.attr("src"))
        val description = document.selectFirst("div.dizi-ozet, div.description, p")?.text()?.trim()

        val episodeElements = document.select("a[href*='/izle/']")
        val eps = mutableListOf<Episode>()
        val epRegex = Regex("""(\d+)[.-]bolum""")
        val added = mutableSetOf<String>()

        for (a in episodeElements) {
            val epHref = fixUrlNull(a.attr("href")) ?: continue
            if (!added.add(epHref)) continue
            val match = epRegex.find(epHref)
            val eNum = match?.groupValues?.get(1)?.toIntOrNull() ?: (eps.size + 1)

            eps.add(newEpisode(epHref) {
                this.name = a.text().trim().ifEmpty { "$eNum. Bölüm" }
                this.season = 1
                this.episode = eNum
            })
        }

        if (eps.isEmpty()) {
            eps.add(newEpisode(url) {
                this.name = "1. Bölüm"
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