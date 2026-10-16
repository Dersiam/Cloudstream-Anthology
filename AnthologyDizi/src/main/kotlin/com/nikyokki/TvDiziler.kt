@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.nikyokki

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.JWPlayer
import org.jsoup.Jsoup

class AnthologyDizi : MainAPI() {
    override var mainUrl = "https://tvdiziler.tv"
    override var name = "Anthology Dizi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/dizi-izle" to "Diziler",
        mainUrl to "Son Bölümler",
        "$mainUrl/dizi/tur/aile" to "Aile",
        "$mainUrl/dizi/tur/aksiyon" to "Aksiyon",
        "$mainUrl/dizi/tur/komedi" to "Komedi",
        "$mainUrl/dizi/tur/dram" to "Dram"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (request.data.startsWith("http")) request.data else "$mainUrl/dizi-izle"
        val doc = try {
            app.get(targetUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        } catch (e: Exception) {
            app.get("$mainUrl/dizi-izle", headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        }

        val items = doc.select("div.poster-media, div.poster-md, div.poster-long, div.poster-xs, a[href*='dizi/']").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        val finalItems = if (items.isNotEmpty()) {
            items
        } else {
            doc.select("a[href*='/dizi/']").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        }

        return newHomePageResponse(request.name, finalItems)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = if (this.tagName() == "a") this else this.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(linkElem.attr("href")) ?: return null
        if (!href.contains("/dizi/") && !href.contains("dizi/")) return null
        if (href.endsWith("/dizi") || href.endsWith("/dizi/") || href.contains("/tur/")) return null

        val title = this.selectFirst("h2, h3, h4, .poster-md-subject h3, .poster-subject h2, .truncate")?.text()?.replace(" izle", "")?.trim()
            ?: linkElem.attr("title").ifEmpty { linkElem.text() }.replace(" izle", "").trim().ifEmpty { null }
            ?: return null

        if (title.length < 2 || title.equals("Diziler", ignoreCase = true)) return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        })

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val searchReq = app.post(
                "$mainUrl/search?qr=${query.trim()}",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer = "$mainUrl/"
            )

            val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            val searchResult = objectMapper.readValue<SearchResult>(searchReq.text)

            if (searchResult.success == 1 && searchResult.data != null) {
                val document = Jsoup.parse(searchResult.data.toString(), mainUrl)
                document.select("ul li, div.poster-md, a[href*='dizi/']").forEach { item ->
                    val res = item.toSearchResult()
                    if (res != null) results.add(res)
                }
            }
        } catch (e: Exception) {}

        if (results.isEmpty()) {
            try {
                val home = getMainPage(1, MainPageRequest(name, "$mainUrl/dizi-izle", false))
                return home.items.flatMap { it.list }
            } catch (e: Exception) {}
        }
        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = "$mainUrl/").document
        val title = doc.selectFirst("div.page-title h1, div.page-title p, h1")?.text()?.replace(" izle", "")?.trim() ?: name
        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("div.series-profile-image img")?.attr("data-src")
                ?: doc.selectFirst("img")?.attr("src")
        )
        val description = doc.selectFirst("meta[name='description'], div.series-profile-summary p")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }
        val year = doc.selectFirst("h1 span")?.text()?.substringAfter("(")?.substringBefore(")")?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        var szn = 1
        val seasonContainers = doc.select("div.series-profile-episode-list")
        if (seasonContainers.isNotEmpty()) {
            for (sezon in seasonContainers) {
                var blm = 1
                for (bolum in sezon.select("li, a[href*='bolum']")) {
                    val aTag = if (bolum.tagName() == "a") bolum else bolum.selectFirst("a") ?: continue
                    val epHref = fixUrlNull(aTag.attr("href")) ?: continue
                    val epName = aTag.text().trim().ifEmpty { "$szn. Sezon $blm. Bölüm" }
                    val epNum = blm++
                    val epSeason = szn

                    episodes.add(newEpisode(epHref) {
                        this.name = epName
                        this.season = epSeason
                        this.episode = epNum
                    })
                }
                szn++
            }
        }

        if (episodes.isEmpty()) {
            // Check direct episode links
            doc.select("a[href*='bolum']").forEachIndexed { idx, a ->
                val epHref = fixUrlNull(a.attr("href")) ?: return@forEachIndexed
                episodes.add(newEpisode(epHref) {
                    this.name = a.text().trim().ifEmpty { "1. Bölüm" }
                    this.season = 1
                    this.episode = idx + 1
                })
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = "1. Bölüm"
                this.season = 1
                this.episode = 1
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val doc = app.get(data, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"), referer = "$mainUrl/").document
            val iframe = fixUrlNull(
                doc.selectFirst("li.series-alter-active button, button[data-hhs]")?.attr("data-hhs")
                    ?: doc.selectFirst("iframe")?.attr("src")
            )

            if (iframe != null) {
                if (iframe.contains("youtube.com")) {
                    val id = iframe.substringAfter("/embed/").substringBefore("?")
                    loadExtractor("https://youtube.com/watch?v=$id", subtitleCallback, callback)
                } else {
                    try {
                        loadExtractor(iframe, subtitleCallback, callback)
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {}
        return true
    }
}

class TvDizilerOynat : JWPlayer() {
    override val name = "TvDizilerOynat"
    override val mainUrl = "https://tvdiziler.cc/player/oynat/"
}