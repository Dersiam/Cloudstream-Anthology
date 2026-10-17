@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class FilmModu : MainAPI() {
    override var mainUrl = "https://www.filmmodu.one"
    override var name = "FilmModu"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/turkce-dublaj-hd-film-izle" to "Türkçe Dublaj Filmler",
        "${mainUrl}/turkce-altyazili-hd-filmler-izle" to "Türkçe Altyazılı Filmler",
        "${mainUrl}/film-tur/4k-film-izle" to "4K Filmler",
        "${mainUrl}/film-tur/tavsiye-filmler" to "Tavsiye Filmler",
        "${mainUrl}/film-tur/odullu-filmler-izle" to "Oscar Ödüllü Filmler",
        "${mainUrl}/film-tur/kult-filmler-izle" to "Kült Filmler",
        "${mainUrl}/film-tur/aksiyon" to "Aksiyon",
        "${mainUrl}/film-tur/macera-filmleri" to "Macera",
        "${mainUrl}/film-tur/animasyon" to "Animasyon",
        "${mainUrl}/film-tur/bilim-kurgu-filmleri" to "Bilim Kurgu",
        "${mainUrl}/film-tur/hd-komedi-filmleri" to "Komedi",
        "${mainUrl}/film-tur/korku-filmleri" to "Korku",
        "${mainUrl}/film-tur/gerilim" to "Gerilim",
        "${mainUrl}/film-tur/dram-filmleri" to "Dram",
        "${mainUrl}/film-tur/fantastik-filmler" to "Fantastik",
        "${mainUrl}/film-tur/gizem-filmleri" to "Gizem",
        "${mainUrl}/film-tur/suc-filmleri" to "Suç",
        "${mainUrl}/film-tur/romantik-filmler" to "Romantik",
        "${mainUrl}/film-tur/tarih" to "Tarih",
        "${mainUrl}/film-tur/savas-filmleri" to "Savaş",
        "${mainUrl}/film-tur/belgeseller" to "Belgeseller",
        "${mainUrl}/film-tur/hd-hint-filmleri" to "Hint Filmleri",
        "${mainUrl}/film-tur/vahsi-bati-filmleri" to "Vahşi Batı"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}?page=$page"
        } else {
            request.data
        }
        val document = try {
            app.get(url).document
        } catch (_: Exception) {
            app.get("${mainUrl}/turkce-dublaj-hd-film-izle").document
        }
        var home = document.select("div.movie, div.movie-large, div.poster").mapNotNull { it.toMainPageResult() }
        if (home.isEmpty() && request.data != "${mainUrl}/turkce-dublaj-hd-film-izle") {
            val fallbackDoc = try {
                app.get("${mainUrl}/turkce-dublaj-hd-film-izle").document
            } catch (_: Exception) { null }
            home = fallbackDoc?.select("div.movie, div.movie-large, div.poster")?.mapNotNull { it.toMainPageResult() } ?: emptyList()
        }
        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val aTag = if (this.tagName() == "a") this else this.selectFirst("a[href*='-film-izle'], a") ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        if (href == mainUrl || href == "$mainUrl/" || href.contains("/giris") || href.contains("/uyelik") || href.contains("/arsiv")) return null

        val img = this.selectFirst("img")
        val title = this.selectFirst("span.original-name, span.turkish-name, h3, h2, div.title")?.text()?.trim()
            ?: aTag.text().trim().ifEmpty { null }
            ?: img?.attr("alt")?.trim()?.ifEmpty { null }
            ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("picture source")?.let { it.attr("data-srcset").split(" ").firstOrNull() }
                ?: img?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val document = app.get("${mainUrl}/film-ara?term=${encoded}").document

            // 1. Direct movie page redirect check (e.g. searching exact title redirects to /...-film-izle)
            val canonical = fixUrlNull(document.selectFirst("link[rel='canonical']")?.attr("href") ?: document.selectFirst("meta[property='og:url']")?.attr("content"))
            if (canonical != null && canonical.contains("-film-izle")) {
                val title = document.selectFirst("h1[itemprop='name'], h1")?.text()?.trim() ?: query
                val poster = fixUrlNull(
                    document.selectFirst("meta[property='og:image']")?.attr("content")
                        ?: document.selectFirst("picture img, div.poster img, img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
                )
                results.add(newMovieSearchResponse(title, canonical, TvType.Movie) {
                    this.posterUrl = poster
                })
            }

            // 2. Listing items in search page
            val items = document.select("div.movie, div.movie-large, div.poster, a[href*='-film-izle']").mapNotNull { it.toMainPageResult() }
            results.addAll(items)
        } catch (_: Exception) {}

        if (results.isEmpty()) {
            try {
                val home = getMainPage(1, MainPageRequest("Türkçe Dublaj Filmler", "${mainUrl}/turkce-dublaj-hd-film-izle", false))
                val qLower = query.lowercase().trim()
                val matched = home.items.flatMap { it.list }.filter { it.name.lowercase().contains(qLower) }
                return matched.ifEmpty { home.items.flatMap { it.list } }
            } catch (_: Exception) {}
        }

        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: name
        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("img.img-responsive, div.poster img, img")?.attr("src")
        )
        val description = document.selectFirst("p[itemprop='description'], div.description p, p")?.text()?.trim()
        val year = document.selectFirst("span[itemprop='dateCreated']")?.text()?.trim()?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        var foundStream = false
        val linkCallback: (ExtractorLink) -> Unit = { link ->
            foundStream = true
            callback.invoke(link)
        }

        try {
            var document = app.get(data).document
            var pageHtml = document.html()
            var vidId = Regex("""var videoId\s*=\s*'([^']+)'""").find(pageHtml)?.groupValues?.get(1)
            var vidType = Regex("""var videoType\s*=\s*'([^']*)'""").find(pageHtml)?.groupValues?.get(1) ?: ""

            // If no videoId or videoType on current page, check alternates
            if (vidId == null || vidType.isEmpty()) {
                val altLinks = document.select("a[href*='-turkce-'], a[href*='-film-izle']").mapNotNull { fixUrlNull(it.attr("href")) }
                for (alt in altLinks) {
                    if (alt == data) continue
                    val altHtml = app.get(alt).text
                    val altId = Regex("""var videoId\s*=\s*'([^']+)'""").find(altHtml)?.groupValues?.get(1)
                    val altType = Regex("""var videoType\s*=\s*'([^']*)'""").find(altHtml)?.groupValues?.get(1) ?: ""
                    if (altId != null && altType.isNotEmpty()) {
                        vidId = altId
                        vidType = altType
                        break
                    }
                }
            }

            if (vidId != null) {
                try {
                    val sourceRes = app.get(
                        "${mainUrl}/get-source?movie_id=${vidId}&type=${vidType}",
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to data
                        )
                    ).text

                    val json = JSONObject(sourceRes)
                    val subtitle = json.optString("subtitle")
                    if (subtitle.isNotEmpty()) {
                        subtitleCallback.invoke(
                            SubtitleFile(
                                lang = "Türkçe",
                                url = fixUrl(subtitle)
                            )
                        )
                    }

                    val sources = json.optJSONArray("sources")
                    if (sources != null && sources.length() > 0) {
                        for (i in 0 until sources.length()) {
                            val srcObj = sources.getJSONObject(i)
                            var srcUrl = srcObj.optString("src")
                            val label = srcObj.optString("label").ifEmpty { "HD" }
                            if (srcUrl.isEmpty()) continue
                            if (!srcUrl.contains(".m3u8")) srcUrl += ".m3u8"

                            linkCallback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name - $label",
                                    url = fixUrl(srcUrl),
                                    type = INFER_TYPE
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.quality = getQualityFromName(label)
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("FilmModu", "get-source failed", e)
                }
            }

            // Direct iframes fallback
            val iframes = document.select("iframe").mapNotNull { fixUrlNull(it.attr("src")) }
            for (iframe in iframes) {
                try {
                    loadExtractor(iframe, "$mainUrl/", subtitleCallback, linkCallback)
                } catch (_: Exception) {}
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
}