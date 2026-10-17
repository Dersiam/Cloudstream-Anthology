@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.cloudstream.anthologycloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.nio.charset.StandardCharsets

class AsyaAnimeleri : MainAPI() {
    override var mainUrl = "https://asyaanimeleri.top"
    override var name = "AsyaAnimeleri"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime, TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/series/?order=popular" to "Popüler Seriler",
        "${mainUrl}/series/?order=update" to "Son Güncellenenler",
        "${mainUrl}/series/?order=latest" to "Yeni Eklenenler",
        mainUrl to "Son Bölümler",
        "${mainUrl}/genres/aksiyon/" to "Aksiyon Animeleri",
        "${mainUrl}/genres/fantastik/" to "Fantastik Animeleri",
        "${mainUrl}/genres/komedi/" to "Komedi Animeleri",
        "${mainUrl}/genres/macera/" to "Macera Animeleri",
        "${mainUrl}/genres/romantik/" to "Romantik Animeleri",
        "${mainUrl}/genres/dram/" to "Dram Animeleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val pageUrl = if (page > 1) {
            if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}page/$page/"
        } else {
            request.data
        }

        val document = try {
            app.get(
                pageUrl,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            ).document
        } catch (_: Exception) {
            app.get(
                mainUrl,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            ).document
        }

        var items = document.select("article.bs, div.bsx, div.movie, article, div.film, div.item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        if (items.isEmpty() && request.data != mainUrl) {
            items = app.get(mainUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"))
                .document.select("article.bs, div.bsx, div.movie, article, div.film, div.item")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val rawHref = a.attr("href").trim()
        if (rawHref.isEmpty()) return null
        val href = fixUrl(rawHref)

        val title = a.attr("title").ifEmpty {
            this.selectFirst("h2, h3, .tt, a.title, .title")?.text()
        }?.trim() ?: a.text().trim().ifEmpty { return null }

        val poster = fixUrlNull(this.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } })

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.trim()}"
        val document = app.get(
            searchUrl,
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        ).document

        val results = document.select("article.bs, div.bsx, div.movie, article, div.film, div.item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        if (results.isEmpty()) {
            return getMainPage(1, MainPageRequest("Popüler Seriler", "$mainUrl/series/?order=popular", false))?.items?.flatMap { it.list } ?: emptyList()
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        ).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: name
        val poster = fixUrlNull(document.selectFirst("div.thumb img, div.poster img, img.poster, meta[property=og:image]")
            ?.let { it.attr("src").ifEmpty { it.attr("data-src") }.ifEmpty { it.attr("content") } })
        val description = document.selectFirst("div.entry-content, div.description, p.overview, div.summary")?.text()?.trim()

        val episodes = mutableListOf<Episode>()
        val epElements = document.select("div.eplister ul li a, ul.clstyle li a")
        if (epElements.isNotEmpty()) {
            epElements.forEach { a ->
                val epHref = fixUrlNull(a.attr("href")) ?: return@forEach
                val epNum = a.selectFirst(".epl-num")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                val epTitle = a.selectFirst(".epl-title")?.text()?.trim() ?: a.text().trim()
                episodes.add(newEpisode(epHref) {
                    this.name = epTitle
                    this.season = 1
                    this.episode = epNum
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
        val document = app.get(
            data,
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        ).document

        val iframes = mutableListOf<String>()

        // Direct iframes
        document.select("iframe").forEach { iframe ->
            fixUrlNull(iframe.attr("src"))?.let { iframes.add(it) }
        }

        // Base64 mirror options
        document.select("select.mirror option, select option").forEach { opt ->
            val b64 = opt.attr("value").trim()
            if (b64.length > 10) {
                try {
                    val decoded = decodeBase64Safe(b64)
                    val srcMatch = Regex("""src=["']([^"']+)["']""").find(decoded)?.groupValues?.get(1)
                    if (srcMatch != null) {
                        iframes.add(fixUrl(srcMatch))
                    }
                } catch (_: Exception) {}
            }
        }

        var streamFound = false

        for (iframe in iframes.distinct()) {
            try {
                if (iframe.contains("vidmoly")) {
                    val vidmolyDoc = app.get(
                        iframe,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Referer" to "$mainUrl/"
                        )
                    ).text

                    val cleanDoc = vidmolyDoc.replace("\\/", "/")
                    val m3u8Match = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""").find(cleanDoc)?.groupValues?.get(1)
                        ?: Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(cleanDoc)?.groupValues?.get(1)
                    if (m3u8Match != null) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "Vidmoly 1080p",
                                url = m3u8Match,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                        streamFound = true
                    }
                } else if (iframe.contains("rumble.com")) {
                    val rumbleDoc = app.get(
                        iframe,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Referer" to "$mainUrl/"
                        )
                    ).text

                    val cleanDoc = rumbleDoc.replace("\\/", "/").replace("\\\"", "\"")
                    val hlsMatch = Regex(""""hls"\s*:\s*\{\s*"url"\s*:\s*"([^"]+)"""").find(cleanDoc)?.groupValues?.get(1)
                        ?: Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(cleanDoc)?.groupValues?.get(1)
                    val mp4Match = Regex("""(https?://[^\s"']+\.mp4[^\s"']*)""").find(cleanDoc)?.groupValues?.get(1)
                    val vidUrl = hlsMatch ?: mp4Match
                    if (vidUrl != null) {
                        val isM3u8 = vidUrl.contains(".m3u8")
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = if (isM3u8) "Rumble HLS 1080p" else "Rumble 720p",
                                url = vidUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://rumble.com/"
                                this.quality = if (isM3u8) Qualities.P1080.value else Qualities.P720.value
                            }
                        )
                        streamFound = true
                    }
                }

                loadExtractor(iframe, data, subtitleCallback, callback)
            } catch (_: Exception) {}
        }

        return streamFound || iframes.isNotEmpty()
    }

    private fun decodeBase64Safe(input: String): String {
        return try {
            val bytes = android.util.Base64.decode(input, android.util.Base64.DEFAULT)
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            try {
                val clean = input.replace("\n", "").replace("\r", "").trim()
                val bytes = java.util.Base64.getDecoder().decode(clean)
                String(bytes, StandardCharsets.UTF_8)
            } catch (_: Throwable) {
                ""
            }
        }
    }
}
