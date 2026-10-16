@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.nikyokki

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
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
        mainUrl to "Son Bölümler",
        "${mainUrl}/dizi-izle" to "Tüm Diziler",
        "${mainUrl}/dizi/tur/aile" to "Aile",
        "${mainUrl}/dizi/tur/aksiyon" to "Aksiyon",
        "${mainUrl}/dizi/tur/gerilim" to "Gerilim",
        "${mainUrl}/dizi/tur/komedi" to "Komedi",
        "${mainUrl}/dizi/tur/romantik" to "Romantik",
        "${mainUrl}/dizi/tur/suc" to "Suç"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (request.data == mainUrl) mainUrl else "${request.data}/$page"
        val doc = app.get(
            targetUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
        ).document

        val items = mutableListOf<SearchResponse>()
        doc.select("div.poster-xs, div.poster-long, article, div.poster").forEach { el ->
            val title = el.selectFirst("div.poster-xs-subject p, div.poster-long-subject h2, h2, h3, a")?.text()
                ?.replace(" izle", "")?.trim() ?: return@forEach
            val rawHref = el.selectFirst("a")?.attr("href") ?: return@forEach
            val href = fixUrl(rawHref)
            val posterUrl = fixUrlNull(el.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } })

            items.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            })
        }

        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val searchReq = app.post(
                "${mainUrl}/search?qr=${query.trim()}",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "${mainUrl}/"
                )
            )

            val json = JSONObject(searchReq.text)
            if (json.optInt("success", 0) == 1) {
                val dataHtml = json.optString("data", "")
                val doc = Jsoup.parse(dataHtml)
                doc.select("ul li, li").forEach { li ->
                    val title = li.selectFirst("h3, h2, a")?.text()?.replace(" izle", "")?.trim() ?: return@forEach
                    val href = fixUrlNull(li.selectFirst("a")?.attr("href")) ?: return@forEach
                    val poster = fixUrlNull(li.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } })
                    results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = poster
                    })
                }
            }
        } catch (_: Exception) {}

        if (results.isEmpty()) {
            return getMainPage(1, MainPageRequest("Son Bölümler", mainUrl, false)).items.flatMap { it.list }
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        ).document

        val title = doc.selectFirst("div.page-title h1, div.page-title p, h1")?.text()
            ?.replace(" izle", "")?.trim() ?: name
        val poster = fixUrlNull(doc.selectFirst("div.series-profile-image img, div.poster img, meta[property=og:image]")
            ?.let { it.attr("data-src").ifEmpty { it.attr("src") }.ifEmpty { it.attr("content") } })
        val description = doc.selectFirst("div.series-profile-summary p, meta[name=og:description]")
            ?.let { it.text().ifEmpty { it.attr("content") } }?.trim()

        val episodes = mutableListOf<Episode>()
        var seasonNum = 1
        val seasonLists = doc.select("div.series-profile-episode-list")
        if (seasonLists.isNotEmpty()) {
            seasonLists.forEach { seasonEl ->
                var epNum = 1
                seasonEl.select("li").forEach { li ->
                    val epHref = fixUrlNull(li.selectFirst("a[href*='bolum-izle'], a")?.attr("href")) ?: return@forEach
                    val epName = li.selectFirst("h6 a, a")?.text()?.trim() ?: "$seasonNum. Sezon $epNum. Bölüm"
                    episodes.add(newEpisode(epHref) {
                        this.name = epName
                        this.season = seasonNum
                        this.episode = epNum++
                    })
                }
                seasonNum++
            }
        } else {
            // If the current page itself has a player, add it as episode 1
            if (doc.select("button[data-hhs], #player_area, [data-hhs]").isNotEmpty() || url.contains("bolum-izle")) {
                episodes.add(newEpisode(url) {
                    this.name = title
                    this.season = 1
                    this.episode = 1
                })
            }
            // Check direct links on episode page (excluding series overview links)
            doc.select("a[href*='bolum-izle']").forEach { a ->
                val rawHref = a.attr("href").trim()
                if (rawHref.contains("/dizi/")) return@forEach
                val epHref = when {
                    rawHref.startsWith("http") -> rawHref
                    rawHref.startsWith("//") -> "https:$rawHref"
                    rawHref.startsWith("/") -> "$mainUrl$rawHref"
                    else -> "$mainUrl/$rawHref"
                }
                val epName = a.text().trim().ifEmpty { "${episodes.size + 1}. Bölüm" }
                episodes.add(newEpisode(epHref) {
                    this.name = epName
                    this.season = 1
                    this.episode = episodes.size + 1
                })
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = title
                this.season = 1
                this.episode = 1
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
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
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to "$mainUrl/"
        )

        val plyUrls = mutableListOf<String>()

        if (data.contains("/vid/ply/")) {
            plyUrls.add(data)
        } else {
            val doc = app.get(data, headers = headers).document
            // Find button data-hhs
            doc.select("button[data-hhs], li.series-alter-active button, [data-hhs]").forEach { btn ->
                val hhs = btn.attr("data-hhs").trim()
                if (hhs.isNotEmpty()) {
                    val fixed = when {
                        hhs.startsWith("http") -> hhs
                        hhs.startsWith("//") -> "https:$hhs"
                        hhs.startsWith("/") -> "$mainUrl$hhs"
                        else -> "$mainUrl/$hhs"
                    }
                    plyUrls.add(fixed)
                }
            }
            // Also check direct iframes
            doc.select("iframe[src]").forEach { iframe ->
                val src = fixUrl(iframe.attr("src"))
                if (src.contains("/vid/ply/")) {
                    plyUrls.add(src)
                } else {
                    loadExtractor(src, data, subtitleCallback, callback)
                }
            }
        }

        var foundStream = false
        for (plyUrl in plyUrls.distinct()) {
            try {
                val plyDoc = app.get(plyUrl, headers = headers, referer = data).text
                val sourceRegex = Regex("""file\s*:\s*["']([^"']+)["'](?:\s*,\s*label\s*:\s*["']([^"']+)["'])?(?:\s*,\s*type\s*:\s*["']([^"']+)["'])?""")
                val matches = sourceRegex.findAll(plyDoc).toList()
                for (match in matches) {
                    val fileUrl = match.groupValues[1].replace("\\/", "/").trim()
                    val label = match.groupValues.getOrNull(2) ?: ""
                    val isM3u8 = fileUrl.contains(".m3u8")

                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = if (label.isNotBlank()) "${this.name} $label" else this.name,
                            url = fileUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = getQualityFromName(label)
                        }
                    )
                    foundStream = true
                }

                // If sources: [...] array regex
                if (!foundStream) {
                    val directMp4Regex = Regex("""["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""")
                    directMp4Regex.findAll(plyDoc).forEach { m ->
                        val url = m.groupValues[1].replace("\\/", "/").trim()
                        val isM3u8 = url.contains(".m3u8")
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = url,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.P720.value
                            }
                        )
                        foundStream = true
                    }
                }
            } catch (e: Exception) {
                Log.e("AnthologyDizi", "Error loading ply: ${e.message}")
            }
        }

        return foundStream
    }
}