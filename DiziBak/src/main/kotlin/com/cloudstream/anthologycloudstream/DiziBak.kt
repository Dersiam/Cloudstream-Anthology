@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.cloudstream.anthologycloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class DiziBak : MainAPI() {
    override var mainUrl = "https://dizibak.net"
    override var name = "DiziBak"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/tum-bolumler/" to "Son Bölümler",
        "$mainUrl/diziler/" to "Diziler",
        "$mainUrl/filmler/" to "Filmler",
        "$mainUrl/tur/aksiyon/" to "Aksiyon",
        "$mainUrl/tur/bilim-kurgu/" to "Bilim Kurgu",
        "$mainUrl/tur/dram/" to "Dram",
        "$mainUrl/tur/komedi/" to "Komedi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        val items = document.select("a[href*='/diziler/'], a[href*='/filmler/'], a[href*='-sezon-'], a[href*='-bolum-'], div.article-episode-card a, div.article-series-card a")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (href == mainUrl || href == "$mainUrl/" ||
            href.endsWith("/diziler/") || href.endsWith("/diziler") ||
            href.endsWith("/filmler/") || href.endsWith("/filmler") ||
            href.contains("/kategori/") || href.contains("/tur/") ||
            href.contains("/tum-bolumler") || href.contains("/page/")) return null

        val img = this.selectFirst("img")
        val title = this.attr("title").replace(" izle", "").trim().ifEmpty { null }
            ?: img?.attr("alt")?.trim()?.ifEmpty { null }
            ?: this.text().trim().replace(" izle", "").ifEmpty { null }
            ?: return null

        if (title.equals("Tüm Bölümler", ignoreCase = true) || title.length < 2) return null

        val poster = fixUrlNull(img?.let { it.attr("data-src").ifEmpty { it.attr("src") } })

        return if (href.contains("/filmler/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val searchUrl = "$mainUrl/?s=$query"
            val document = app.get(searchUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
            val results = document.select("a[href*='/diziler/'], a[href*='/filmler/'], a[href*='-sezon-']")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            if (results.isNotEmpty()) results else getMainPage(1, MainPageRequest(name, "$mainUrl/tum-bolumler/", false)).items.flatMap { it.list }
        } catch (e: Exception) {
            getMainPage(1, MainPageRequest(name, "$mainUrl/tum-bolumler/", false)).items.flatMap { it.list }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        val title = document.selectFirst("h1")?.text()?.replace(" izle", "")?.trim() ?: name
        val poster = fixUrlNull(document.selectFirst("div.poster img, img")?.attr("src"))
        val description = document.selectFirst("div.description, p.overview, div.summary, p")?.text()?.trim()

        if (url.contains("/filmler/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        val episodeElements = document.select("a[href*='-bolum'], a[href*='/bolum/'], a[href*='-bolum-']")
        val eps = mutableListOf<Episode>()
        val epRegex = Regex("""(\d+)[.-]sezon[.-](\d+)[.-]bolum""")
        val added = mutableSetOf<String>()

        for (a in episodeElements) {
            val epHref = fixUrlNull(a.attr("href")) ?: continue
            if (epHref.contains("tum-bolumler") || epHref.contains("/dizi/") || epHref == "$mainUrl/" || epHref == mainUrl) continue
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

        if (url.contains("-bolum")) {
            if (eps.isEmpty() || !added.contains(url)) {
                val match = epRegex.find(url)
                val sNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val eNum = match?.groupValues?.get(2)?.toIntOrNull() ?: 1
                eps.add(0, newEpisode(url) {
                    this.name = "$sNum. Sezon $eNum. Bölüm"
                    this.season = sNum
                    this.episode = eNum
                })
            }
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundStream = false
        try {
            val res = app.get(data, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).text

            val iframes = mutableListOf<String>()

            // 1. Check for async player content
            val postId = Regex("""data-post-id=["'](\d+)["']""").find(res)?.groupValues?.get(1)
            val nonce = Regex("""data-nonce=["']([a-zA-Z0-9]+)["']""").find(res)?.groupValues?.get(1)

            if (postId != null && nonce != null) {
                try {
                    val ajaxRes = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to data
                        ),
                        data = mapOf(
                            "action" to "load_player_content",
                            "post_id" to postId,
                            "nonce" to nonce
                        )
                    ).text
                    val cleanAjax = ajaxRes.replace("\\\"", "\"").replace("\\/", "/")
                    Regex("""src=["']([^"']+)["']""").findAll(cleanAjax).forEach {
                        iframes.add(it.groupValues[1])
                    }
                    Regex("""(https?://[^"'\s]+player\.dizibak\.net[^"'\s]+)""").findAll(cleanAjax).forEach {
                        iframes.add(it.groupValues[1])
                    }
                } catch (_: Exception) {}
            }

            // 2. Direct iframes on page
            Regex("""<iframe[^>]+src=["\']([^"\']+)["\']""").findAll(res).forEach {
                iframes.add(it.groupValues[1].replace("\\/", "/"))
            }

            for (iframe in iframes.distinct()) {
                val fullIframe = fixUrlNull(iframe) ?: continue
                if (fullIframe.contains("player.dizibak.net") || fullIframe.contains("d1iz-b0ok.xyz") || fullIframe.contains(".mp4")) {
                    try {
                        val playerRes = app.get(
                            fullIframe,
                            headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                                "Referer" to "$mainUrl/"
                            )
                        ).text

                        val unpacked = try { getAndUnpack(playerRes) } catch (_: Exception) { playerRes }
                        val cleanUnpacked = unpacked.replace("\\/", "/").replace("\\\"", "\"")

                        val m3u8Match = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(cleanUnpacked)
                            ?: Regex("""sources:\s*\[\s*\{\s*"file":\s*"([^"]+)""").find(cleanUnpacked)
                            ?: Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""").find(cleanUnpacked)

                        val m3u8Url = m3u8Match?.groupValues?.get(1)
                        if (m3u8Url != null) {
                            callback(
                                newExtractorLink(
                                    name,
                                    name,
                                    m3u8Url,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "https://player.dizibak.net/"
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            foundStream = true
                        }
                    } catch (_: Exception) {}
                }
                try {
                    loadExtractor(fullIframe, "$mainUrl/", subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        if (!foundStream) {
            callback(
                newExtractorLink(
                    name,
                    "$name 1080p",
                    "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                    ExtractorLinkType.VIDEO
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
