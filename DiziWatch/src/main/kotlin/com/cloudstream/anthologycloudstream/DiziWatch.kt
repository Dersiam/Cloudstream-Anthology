@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.cloudstream.anthologycloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import org.json.JSONArray

class DiziWatch : MainAPI() {
    override var mainUrl = "https://diziwatch.org"
    override var name = "DiziWatch"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/yeni-bolumler" to "Yeni Bölümler",
        "$mainUrl/anime-arsivi" to "Anime Arşivi",
        "$mainUrl/" to "Öne Çıkan Anime ve Diziler",
        "$mainUrl/dizi-arsivi" to "Yabancı Dizi Arşivi",
        "$mainUrl/tur/aksiyon" to "Aksiyon",
        "$mainUrl/tur/bilim-kurgu" to "Bilim Kurgu",
        "$mainUrl/tur/komedi" to "Komedi",
        "$mainUrl/tur/dram" to "Dram",
        "$mainUrl/tur/fantastik" to "Fantastik",
        "$mainUrl/tur/romantik" to "Romantik"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val document = try {
            app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        } catch (_: Exception) {
            app.get("$mainUrl/yeni-bolumler", headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        }
        var items = document.select("a[href*='/dizi/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        if (items.isEmpty() && request.data != "$mainUrl/yeni-bolumler") {
            items = app.get("$mainUrl/yeni-bolumler", headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"))
                .document.select("a[href*='/dizi/']")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val rawHref = fixUrlNull(this.attr("href")) ?: return null
        if (rawHref == mainUrl || rawHref == "$mainUrl/") return null
        val href = if (rawHref.contains("/sezon-")) {
            rawHref.substringBefore("/sezon-")
        } else if (rawHref.contains("/bolum-")) {
            rawHref.substringBefore("/bolum-")
        } else {
            rawHref
        }
        if (href == mainUrl || href == "$mainUrl/") return null

        val img = this.selectFirst("img")
        val title = this.selectFirst("h2, h3, span, p")?.text()?.trim()
            ?: img?.attr("alt")?.trim()?.ifEmpty { null }
            ?: this.attr("title").trim().ifEmpty { null }
            ?: this.text().trim().ifEmpty { null }
            ?: return null

        val poster = fixUrlNull(img?.let { it.attr("data-src").ifEmpty { it.attr("src") } })

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val searchUrl = "$mainUrl/?s=$query"
            val document = app.get(searchUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
            val results = document.select("a[href*='/dizi/']")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            if (results.isNotEmpty()) results else getMainPage(1, MainPageRequest(name, "$mainUrl/yeni-bolumler", false)).items.flatMap { it.list }
        } catch (e: Exception) {
            getMainPage(1, MainPageRequest(name, "$mainUrl/yeni-bolumler", false)).items.flatMap { it.list }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.replace(" izle", "")?.trim()
            ?: name

        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("img")?.attr("src"))

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("p")?.text()?.trim()

        val episodeElements = document.select("a[href*='/sezon-'][href*='/bolum-'], a[href*='/bolum-']")
        val eps = mutableListOf<Episode>()
        val epRegex = Regex("""sezon-(\d+)/bolum-(\d+)""")
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

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, eps)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val res = app.get(data, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).text

            val iframes = mutableListOf<String>()

            // 1. Direct regex for players in HTML and React Server Components (RSC)
            val playerRegex = Regex("""(https?://[^\s"'\\]*(?:anizmplayer\.com|ok\.ru|my\.mail\.ru|sibnet\.ru|abyssplayer\.com|video|player|embed)[^\s"'\\]*)""")
            playerRegex.findAll(res).forEach {
                val found = it.groupValues[1].replace("\\/", "/").trimEnd('\\')
                if (!found.contains("schema.org") && !found.contains("w3.org") && !found.contains("facebook") && !found.contains("twitter") && !found.contains(".js") && !found.contains(".css") && !found.contains(".png") && !found.contains(".jpg")) {
                    iframes.add(found)
                }
            }

            // 2. Direct iframes
            Regex("""<iframe[^>]+src=["\']([^"\']+)["\']""").findAll(res).forEach {
                iframes.add(it.groupValues[1].replace("\\/", "/"))
            }

            // 3. Direct m3u8
            Regex("""(https?://[^\s"'\\]+\.m3u8[^\s"'\\]*)""").findAll(res).forEach {
                val m3u8 = it.groupValues[1].replace("\\/", "/")
                callback(
                    newExtractorLink(
                        name,
                        name,
                        m3u8,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }

            for (iframe in iframes.distinct()) {
                if (iframe.contains("vidmoly")) {
                    try {
                        val vHtml = app.get(iframe, referer = "$mainUrl/").text
                        val m3u8Match = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""").find(vHtml)?.groupValues?.get(1)
                        if (!m3u8Match.isNullOrEmpty()) {
                            callback.invoke(
                                newExtractorLink(
                                    name = "$name - Vidmoly",
                                    source = name,
                                    url = m3u8Match,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = iframe
                                    this.quality = Qualities.P1080.value
                                }
                            )
                        }
                    } catch (e: Exception) {}
                }
                try {
                    loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
        return true
    }
}
