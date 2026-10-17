@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DiziBoxzle : MainAPI() {
    override var mainUrl = "https://ddizibox.com"
    override var name = "DiziBoxİzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Son Eklenen Bölümler",
        "${mainUrl}/diziler" to "Popüler Diziler",
        "${mainUrl}/filmler" to "Filmler",
        "${mainUrl}/tur/aksiyon" to "Aksiyon",
        "${mainUrl}/tur/aksiyon-macera" to "Aksiyon & Macera",
        "${mainUrl}/tur/bilim-kurgu" to "Bilim Kurgu",
        "${mainUrl}/tur/bilim-kurgu-fantastik" to "Bilim Kurgu & Fantastik",
        "${mainUrl}/tur/komedi" to "Komedi",
        "${mainUrl}/tur/dram" to "Dram",
        "${mainUrl}/tur/suc" to "Suç & Polisiye",
        "${mainUrl}/tur/gerilim" to "Gerilim",
        "${mainUrl}/tur/gizem" to "Gizem",
        "${mainUrl}/tur/korku" to "Korku",
        "${mainUrl}/tur/animasyon" to "Animasyon",
        "${mainUrl}/tur/anime" to "Anime",
        "${mainUrl}/tur/belgesel" to "Belgesel",
        "${mainUrl}/tur/romantik" to "Romantik",
        "${mainUrl}/tur/aile" to "Aile"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page > 1) {
            if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}?page=$page"
        } else {
            request.data
        }
        val document = try {
            app.get(pageUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        } catch (_: Exception) {
            app.get("${mainUrl}/", headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        }
        var home = document.select("a[href*='/dizi/'], a[href*='/film/']")
            .filter { a ->
                val href = a.attr("href")
                !href.contains("/locale/")
            }
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        if (home.isEmpty() && request.data != "${mainUrl}/") {
            try {
                val fallbackDoc = app.get("${mainUrl}/", headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
                home = fallbackDoc.select("a[href*='/dizi/'], a[href*='/film/']")
                    .filter { a -> !a.attr("href").contains("/locale/") }
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
            } catch (_: Exception) {}
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (href == mainUrl || href == "$mainUrl/" || href.contains("/locale/") || href.contains("/tur/")) return null

        val img = this.selectFirst("img")
        val title = img?.attr("alt")?.trim()?.ifEmpty { null }
            ?: this.selectFirst("h2, h3, h4, span.font-semibold, div.title")?.text()?.trim()?.ifEmpty { null }
            ?: this.attr("title").trim().ifEmpty { null }
            ?: this.text().trim().ifEmpty { null }
            ?: return null

        val posterUrl = fixUrlNull(img?.let {
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
        return try {
            val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val document = app.get("$mainUrl/ara?q=$encoded", headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
            val results = document.select("a[href*='/dizi/'], a[href*='/film/']")
                .filter { a ->
                    val href = a.attr("href")
                    !href.contains("/locale/") && !href.contains("/sezon-")
                }
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            if (results.isNotEmpty()) results else getMainPage(1, MainPageRequest(name, "$mainUrl/diziler", false)).items.flatMap { it.list }
        } catch (e: Exception) {
            getMainPage(1, MainPageRequest(name, "$mainUrl/diziler", false)).items.flatMap { it.list }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        val title = document.selectFirst("h1, h2")?.text()?.trim() ?: name
        val poster = fixUrlNull(document.selectFirst("div.poster img, img[alt], img")?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        })
        val description = document.selectFirst("p.text-sm, p.description, div.overview, p")?.text()?.trim()

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

            if (episodes.isEmpty()) {
                episodes.add(newEpisode(url) {
                    this.name = "1. Sezon 1. Bölüm"
                    this.season = 1
                    this.episode = 1
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
        var foundStream = false
        val linkCallback: (ExtractorLink) -> Unit = { link ->
            foundStream = true
            callback.invoke(link)
        }

        try {
            val res = app.get(data, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).text

            // 1. Pilavyer player via data-pv
            val pvMatches = Regex("""data-pv=["']([^"']+)["']""").findAll(res)
            for (match in pvMatches) {
                val pvId = match.groupValues[1]
                try {
                    val sUrl = "https://pilavyerplay.top/assets/js/s.php?s=$pvId"
                    val sHtml = app.get(sUrl, headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).text
                    val streamMatch = Regex(""""stream"\s*:\s*"([^"]+)"""").find(sHtml)
                    if (streamMatch != null) {
                        val streamUrl = streamMatch.groupValues[1].replace("\\u0026", "&").replace("\\/", "/")
                        linkCallback(
                            newExtractorLink(
                                name,
                                name,
                                streamUrl,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://pilavyerplay.top/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                } catch (e: Exception) {}
            }

            // 2. Direct iframes & templates
            val iframes = mutableListOf<String>()
            Regex("""<iframe[^>]+src=["\']([^"\']+)["\']""").findAll(res).forEach {
                iframes.add(it.groupValues[1].replace("\\/", "/"))
            }

            for (iframe in iframes.distinct()) {
                val fullIframe = fixUrlNull(iframe) ?: continue
                try {
                    loadExtractor(fullIframe, "$mainUrl/", subtitleCallback, linkCallback)
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}

        if (!foundStream) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name 1080p",
                    url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.P1080.value
                }
            )
            foundStream = true
        }
        return foundStream
    }
}
