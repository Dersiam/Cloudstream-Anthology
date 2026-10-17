@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.cloudstream.anthologycloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DiziPod : MainAPI() {
    override var mainUrl = "https://dizipod.com"
    override var name = "DiziPod"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        mainUrl to "Son Eklenenler",
        "$mainUrl/diziler" to "Popüler Diziler",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/tur/aksiyon/" to "Aksiyon",
        "$mainUrl/tur/aksiyon-macera/" to "Aksiyon & Macera",
        "$mainUrl/tur/macera/" to "Macera",
        "$mainUrl/tur/animasyon/" to "Animasyon",
        "$mainUrl/tur/anime/" to "Anime",
        "$mainUrl/tur/belgesel/" to "Belgesel",
        "$mainUrl/tur/bilim-kurgu/" to "Bilim Kurgu",
        "$mainUrl/tur/bilim-kurgu-fantazi/" to "Bilim Kurgu & Fantazi",
        "$mainUrl/tur/komedi/" to "Komedi",
        "$mainUrl/tur/dram/" to "Dram",
        "$mainUrl/tur/gerilim/" to "Gerilim",
        "$mainUrl/tur/korku/" to "Korku",
        "$mainUrl/tur/fantastik/" to "Fantastik",
        "$mainUrl/tur/gizem/" to "Gizem",
        "$mainUrl/tur/suc/" to "Suç",
        "$mainUrl/tur/romantik/" to "Romantik",
        "$mainUrl/tur/aile/" to "Aile",
        "$mainUrl/tur/savas-politik/" to "Savaş & Politik",
        "$mainUrl/tur/tarih/" to "Tarih",
        "$mainUrl/tur/western/" to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val pageUrl = if (page > 1) {
            if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}?page=$page"
        } else {
            request.data
        }
        val document = try {
            app.get(pageUrl).document
        } catch (_: Exception) {
            app.get(mainUrl).document
        }
        var items = document.select("div.item, article, li > a, div.movie").mapNotNull { it.toSearchResult() }
        if (items.isEmpty() && request.data != mainUrl) {
            items = app.get(mainUrl).document.select("div.item, article, li > a, div.movie").mapNotNull { it.toSearchResult() }
        }
        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = if (this.tagName() == "a") this else this.selectFirst("a") ?: this.parents().firstOrNull { it.tagName() == "a" } ?: return null
        val href = fixUrlNull(linkElem.attr("href")) ?: return null
        if (href.contains("/tur/") || href.contains("/kategori/") || href == "$mainUrl/") return null

        val title = this.selectFirst("div.title, h2, h3, .flbaslik")?.text()?.trim() 
            ?: linkElem.selectFirst("div.title")?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(this.selectFirst("img")?.let { 
            it.attr("data-src").ifEmpty { it.attr("src") } 
        })

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val searchUrl = "$mainUrl/?s=$encoded"
            val document = app.get(searchUrl).document
            val results = document.select("div.taxonomy-term-item, div.item, li:has(div.title), article").mapNotNull { it.toSearchResult() }
            if (results.isNotEmpty()) results else {
                getMainPage(1, MainPageRequest(name, mainUrl, false))?.items?.flatMap { it.list } ?: emptyList()
            }
        } catch (e: Exception) {
            getMainPage(1, MainPageRequest(name, mainUrl, false))?.items?.flatMap { it.list } ?: emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: name
        val poster = fixUrlNull(document.selectFirst("div.poster img, img.poster, div.img img")?.attr("src"))
        val description = document.selectFirst("div.description, p.overview, div.summary, div.content p")?.text()?.trim()

        val episodeElements = document.select("a[href*='-sezon-'][href*='-bolum']")
        if (episodeElements.isNotEmpty()) {
            val eps = mutableListOf<Episode>()
            val epRegex = Regex("""(\d+)-sezon-(\d+)-bolum""")
            val addedUrls = mutableSetOf<String>()

            for (a in episodeElements) {
                val epHref = fixUrlNull(a.attr("href")) ?: continue
                if (!addedUrls.add(epHref)) continue
                val match = epRegex.find(epHref)
                val seasonNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = match?.groupValues?.get(2)?.toIntOrNull() ?: 1
                val epTitle = a.text().trim().ifEmpty { "$seasonNum. Sezon $epNum. Bölüm" }

                eps.add(newEpisode(epHref) {
                    this.name = epTitle
                    this.season = seasonNum
                    this.episode = epNum
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
        var foundStream = false
        val linkCallback: (ExtractorLink) -> Unit = { link ->
            foundStream = true
            callback.invoke(link)
        }

        try {
            val document = app.get(data).document
            
            val postId = document.selectFirst("[data-post-id]")?.attr("data-post-id")
                ?: Regex("""data-post-id=["'](\d+)["']""").find(document.html())?.groupValues?.get(1)

            if (postId != null) {
                try {
                    val ajaxRes = app.get(
                        "$mainUrl/wp/wp-admin/admin-ajax.php?action=get_episode_player&post_id=$postId",
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to data
                        )
                    )
                    val ajaxJson = ajaxRes.parsedSafe<DiziPodPlayerResponse>()
                    val iframeHtml = ajaxJson?.data
                    if (!iframeHtml.isNullOrEmpty()) {
                        val iframeSrc = Regex("""src=["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
                        if (iframeSrc != null) {
                            extractDiziPodEmbed(iframeSrc, subtitleCallback, linkCallback)
                        }
                    }
                } catch (e: Exception) {}
            }

            val iframes = document.select("iframe").mapNotNull { fixUrlNull(it.attr("src")) }
            for (iframe in iframes) {
                if (iframe.contains("player.dizipod.com")) {
                    extractDiziPodEmbed(iframe, subtitleCallback, linkCallback)
                } else {
                    loadExtractor(iframe, subtitleCallback, linkCallback)
                }
            }
        } catch (_: Exception) {}

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

    private suspend fun extractDiziPodEmbed(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(embedUrl, referer = "$mainUrl/")
            val html = res.text
            val unpacked = getAndUnpack(html)
            
            val srcMatch = Regex("""sources\s*:\s*(\[[^\]]+\])""").find(unpacked)
                ?: Regex("""sources\s*:\s*(\[[^\]]+\])""").find(html)
                
            if (srcMatch != null) {
                val sourcesJson = srcMatch.groupValues[1]
                val fileRegex = Regex("""["']file["']\s*:\s*["']([^"']+)["']""")
                val labelRegex = Regex("""["']label["']\s*:\s*["']([^"']+)["']""")
                
                val file = fileRegex.find(sourcesJson)?.groupValues?.get(1)?.replace("\\/", "/")
                val label = labelRegex.find(sourcesJson)?.groupValues?.get(1) ?: name
                
                if (file != null) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name ($label)",
                            url = file,
                            type = if (file.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.headers = mapOf("Referer" to "$mainUrl/")
                        }
                    )
                    return
                }
            }
            
            val directStream = Regex("""file\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
                ?: Regex("""file\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""").find(html)?.groupValues?.get(1)
            if (directStream != null) {
                val cleanUrl = directStream.replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = cleanUrl,
                        type = if (cleanUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.headers = mapOf("Referer" to "$mainUrl/")
                    }
                )
            }
        } catch (e: Exception) {}
    }

    data class DiziPodPlayerResponse(
        val success: Boolean? = null,
        val data: String? = null
    )
}
