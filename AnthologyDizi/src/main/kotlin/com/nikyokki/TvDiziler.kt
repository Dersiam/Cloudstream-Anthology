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

        val items = doc.select("a[href*='dizi/']").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        val finalItems = if (items.isNotEmpty()) {
            items
        } else {
            doc.select("div.poster-media, div.poster-md, div.poster-long, div.poster-xs").mapNotNull {
                it.toSearchResult()
            }.distinctBy { it.url }
        }

        return newHomePageResponse(request.name, finalItems)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = if (this.tagName() == "a") this else this.selectFirst("a[href]") ?: return null
        val rawHref = linkElem.attr("href").trim()
        if (rawHref.isEmpty() || rawHref.endsWith("/dizi-izle") || rawHref.contains("/tur/")) return null
        val href = fixUrlNull(rawHref) ?: return null

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
        val added = mutableSetOf<String>()

        // Find episode links
        val epLinks = doc.select("a[href*='bolum-izle'], a[href*='-bolum'], div.series-profile-episode-list a")
        for (a in epLinks) {
            val href = fixUrlNull(a.attr("href")) ?: continue
            if (!added.add(href)) continue

            val epNum = Regex("""(?:-|/|^)(\d+)-bolum""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\b(\d+)\. Bölüm""").find(a.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: (episodes.size + 1)

            val epName = a.text().trim().ifEmpty { "$epNum. Bölüm" }

            episodes.add(newEpisode(href) {
                this.name = epName
                this.season = 1
                this.episode = epNum
            })
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = "1. Bölüm"
                this.season = 1
                this.episode = 1
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.sortedBy { it.episode }) {
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
            val hhs = doc.selectFirst("button[data-hhs], li.series-alter-active button, [data-hhs]")?.attr("data-hhs")
            val plyUrl = fixUrlNull(hhs)

            if (!plyUrl.isNullOrEmpty()) {
                try {
                    val plyHtml = app.get(
                        plyUrl,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                            "Referer" to data
                        )
                    ).text

                    val fileRegex = Regex("""\{file:\s*["']([^"']+)["'],\s*label:\s*["']([^"']+)["']""")
                    val matches = fileRegex.findAll(plyHtml).toList()
                    for (match in matches) {
                        val videoFile = match.groupValues[1]
                        val label = match.groupValues[2]
                        if (videoFile.isNotEmpty()) {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name - $label",
                                    url = videoFile,
                                    type = if (videoFile.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    headers = mapOf(
                                        "Referer" to "$mainUrl/",
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                                    )
                                }
                            )
                        }
                    }

                    if (matches.isEmpty()) {
                        val m3u8 = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(plyHtml)?.groupValues?.get(1)
                        if (m3u8 != null) {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name - HLS",
                                    url = m3u8,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    headers = mapOf(
                                        "Referer" to "$mainUrl/",
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                                    )
                                }
                            )
                        }
                    }
                } catch (e: Exception) {}
            }

            // Fallback to iframes
            val iframes = doc.select("iframe").mapNotNull { fixUrlNull(it.attr("src")) }
            for (iframe in iframes) {
                try {
                    loadExtractor(iframe, subtitleCallback, callback)
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
        return true
    }
}