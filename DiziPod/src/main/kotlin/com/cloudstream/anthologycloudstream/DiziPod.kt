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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val items = document.select("div.item, article, li > a").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(name, items)
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
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("div.taxonomy-term-item, div.item, li:has(div.title)").mapNotNull { it.toSearchResult() }
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
                        extractDiziPodEmbed(iframeSrc, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {}
        }

        val iframes = document.select("iframe").mapNotNull { fixUrlNull(it.attr("src")) }
        for (iframe in iframes) {
            if (iframe.contains("player.dizipod.com")) {
                extractDiziPodEmbed(iframe, subtitleCallback, callback)
            } else {
                loadExtractor(iframe, subtitleCallback, callback)
            }
        }
        return true
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
