@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.cloudstream.anthologycloudstream

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element

class PuhuTV : MainAPI() {
    override var mainUrl = "https://puhutv.com"
    override var name = "PuhuTV"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl to "Öne Çıkanlar",
        "$mainUrl/dizi" to "Diziler",
        "$mainUrl/film" to "Filmler",
        "$mainUrl/cocuk" to "Çocuk",
        "$mainUrl/canli-yayin" to "Canlı Yayınlar"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(request.data, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        val items = document.select("a[href*='-detay'], a[href*='-izle']").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (!href.contains("-detay") && !href.contains("-izle")) return null

        val title = this.selectFirst(".content-name, span.content-name, h2, h3, .title")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: this.selectFirst("img")?.attr("title")?.trim()
            ?: this.text().trim().ifEmpty { null }
            ?: return null

        val img = this.selectFirst("img")
        var poster = fixUrlNull(img?.attr("src"))
        if (poster?.startsWith("data:") == true) {
            poster = fixUrlNull(this.selectFirst("noscript img")?.attr("src"))
        }

        return if (href.contains("-detay")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val searchUrl = "$mainUrl/arama?q=$query"
            val document = app.get(searchUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
            val results = document.select("a[href*='-detay'], a[href*='-izle']").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
            if (results.isNotEmpty()) results else {
                getMainPage(1, MainPageRequest(name, mainUrl, false))?.items?.flatMap { it.list } ?: emptyList()
            }
        } catch (e: Exception) {
            getMainPage(1, MainPageRequest(name, mainUrl, false))?.items?.flatMap { it.list } ?: emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: name
        val poster = fixUrlNull(document.selectFirst("div.poster img, img.poster, [property='og:image']")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("div.poster img, img")?.attr("src"))
        val description = document.selectFirst("div.description, p.overview, div.summary, p")?.text()?.trim()

        if (url.contains("-detay")) {
            val episodeElements = document.select("a[href*='-bolum-izle'], a[href*='-izle']")
            val eps = mutableListOf<Episode>()
            val epRegex = Regex("""(\d+)[.-]bolum""")
            val added = mutableSetOf<String>()

            for (a in episodeElements) {
                val epHref = fixUrlNull(a.attr("href")) ?: continue
                if (!epHref.contains("-izle")) continue
                if (!added.add(epHref)) continue

                val epMatch = epRegex.find(epHref)
                val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: (eps.size + 1)

                eps.add(newEpisode(epHref) {
                    this.name = a.text().trim().ifEmpty { "$epNum. Bölüm" }
                    this.season = 1
                    this.episode = epNum
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
        var found = false
        try {
            val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", "Referer" to "$mainUrl/")
            val html = app.get(data, headers = headers).text

            val videoId = Regex(""""video_id":\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                ?: Regex("""(PUHU_[A-Z0-9_]+)""").find(html)?.groupValues?.get(1)

            if (videoId != null) {
                try {
                    val apiUrl = "https://dygvideo.dygdigital.com/api/video_info?akamai=true&PublisherId=29&ReferenceId=$videoId&SecretKey=NtvApiSecret2014*"
                    val res = app.get(apiUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)", "Referer" to "$mainUrl/")).text
                    val json = JSONObject(res)
                    val hls = json.optJSONObject("data")?.optJSONObject("flavors")?.optString("hls")
                    if (!hls.isNullOrEmpty()) {
                        callback(
                            newExtractorLink(
                                name,
                                "$name 1080p",
                                hls,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                        found = true
                    }
                } catch (e: Exception) {
                    Log.e("PuhuTV", "DYG API error", e)
                }
            }

            val document = org.jsoup.Jsoup.parse(html)
            val iframes = document.select("iframe").mapNotNull { fixUrlNull(it.attr("src")) }
            for (iframe in iframes) {
                loadExtractor(iframe, subtitleCallback, callback)
                found = true
            }
        } catch (e: Exception) {
            Log.e("PuhuTV", "loadLinks error", e)
        }
        return found
    }
}
