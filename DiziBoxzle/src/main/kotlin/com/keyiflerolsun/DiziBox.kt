@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class DiziBoxzle : MainAPI() {
    override var mainUrl = "https://ddizibox.com"
    override var name = "DiziBoxİzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler?page=" to "Diziler",
        "${mainUrl}/filmler?page=" to "Filmler",
        "${mainUrl}/animes?page=" to "Animeler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}").document
        val home = document.select("a[href*='/dizi/'], a[href*='/film/']")
            .filter { a ->
                val href = a.attr("href")
                !href.contains("/locale/") && !href.contains("/sezon-")
            }
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.selectFirst("img")?.attr("alt")?.trim()
            ?: this.selectFirst("h2, h3, h4, span.font-semibold")?.text()?.trim()
            ?: this.attr("title").trim()
        if (title.isEmpty()) return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        })

        val isTv = href.contains("/dizi/")
        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/ara?q=$query").document
        return document.select("a[href*='/dizi/'], a[href*='/film/']")
            .filter { a ->
                val href = a.attr("href")
                !href.contains("/locale/") && !href.contains("/sezon-")
            }
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1, h2")?.text()?.trim() ?: name
        val poster = fixUrlNull(document.selectFirst("img[alt*='$title'], div.poster img, img")?.attr("src"))
        val description = document.selectFirst("p.text-sm, p.description, div.overview")?.text()?.trim()

        val isTv = url.contains("/dizi/")
        if (isTv) {
            val epRegex = Regex("""sezon-(\d+)/bolum-(\d+)""")
            val epLinks = document.select("a[href*='/sezon-'][href*='/bolum-']")
            val episodes = mutableListOf<Episode>()
            val seen = mutableSetOf<String>()

            for (a in epLinks) {
                val epHref = fixUrlNull(a.attr("href")) ?: continue
                if (!seen.add(epHref)) continue
                val match = epRegex.find(epHref)
                val sNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val eNum = match?.groupValues?.get(2)?.toIntOrNull() ?: 1
                val epTitle = a.text().trim().ifEmpty { "$sNum. Sezon $eNum. Bölüm" }

                episodes.add(newEpisode(epHref) {
                    this.name = epTitle
                    this.season = sNum
                    this.episode = eNum
                })
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }

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
        val document = app.get(data).document
        var extractedAny = false

        val templates = document.select("template")
        for (tmpl in templates) {
            val tmplHtml = tmpl.html()
            val pvMatch = Regex("""data-pv=["']([^"']+)["']""").find(tmplHtml)
            if (pvMatch != null) {
                val pvId = pvMatch.groupValues[1]
                val playerUrl = "https://play2.pilavyerplay.top/play/$pvId"
                loadExtractor(playerUrl, "$mainUrl/", subtitleCallback, callback)
                extractedAny = true
            }
            val iframeMatch = Regex("""src=["']([^"']+)["']""").find(tmplHtml)
            val iframeSrc = iframeMatch?.groupValues?.get(1)?.let { fixUrlNull(it) }
            if (iframeSrc != null && !iframeSrc.contains("core.js")) {
                loadExtractor(iframeSrc, "$mainUrl/", subtitleCallback, callback)
                extractedAny = true
            }
        }

        val iframes = document.select("iframe").mapNotNull { fixUrlNull(it.attr("src")) }
        for (iframe in iframes) {
            loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
            extractedAny = true
        }

        return extractedAny
    }
}
