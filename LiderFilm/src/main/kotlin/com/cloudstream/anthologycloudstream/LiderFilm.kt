@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.cloudstream.anthologycloudstream

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.jsoup.nodes.Element
import java.nio.charset.StandardCharsets

class LiderFilm : MainAPI() {
    override var mainUrl = "https://liderfilmizle.vip"
    override var name = "LiderFilm"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Son Eklenenler",
        "${mainUrl}/filmler" to "Filmler",
        "${mainUrl}/diziler" to "Diziler",
        "${mainUrl}/?sort=imdb" to "IMDb Puanına Göre",
        "${mainUrl}/?sort=newest" to "En Yeniler",
        "${mainUrl}/yerli-filmler" to "Yerli Filmler",
        "${mainUrl}/yerli-diziler" to "Yerli Diziler",
        "${mainUrl}/kore-dizileri" to "Kore Dizileri",
        "${mainUrl}/asya-dizileri" to "Asya Dizileri",
        "${mainUrl}/hint-filmleri" to "Hint Filmleri",
        "${mainUrl}/tur/aksiyon" to "Aksiyon",
        "${mainUrl}/tur/animasyon" to "Animasyon",
        "${mainUrl}/tur/bilim-kurgu" to "Bilim Kurgu",
        "${mainUrl}/tur/komedi" to "Komedi",
        "${mainUrl}/tur/korku" to "Korku",
        "${mainUrl}/tur/dram" to "Dram",
        "${mainUrl}/tur/gerilim" to "Gerilim",
        "${mainUrl}/tur/gizem" to "Gizem",
        "${mainUrl}/tur/macera" to "Macera",
        "${mainUrl}/tur/romantik" to "Romantik",
        "${mainUrl}/tur/suc" to "Suç & Polisiye",
        "${mainUrl}/tur/belgesel" to "Belgesel"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val pageUrl = if (page > 1) "${request.data}?page=$page" else request.data
        val document = try {
            app.get(pageUrl).document
        } catch (_: Exception) {
            app.get("${mainUrl}/").document
        }
        var items = document.select("a.movie-card, div.movie-card, a.cw-card, div.cw-card, a[href]:has(img)")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        if (items.isEmpty() && request.data != "${mainUrl}/") {
            try {
                val fallbackDoc = app.get("${mainUrl}/").document
                items = fallbackDoc.select("a.movie-card, div.movie-card, a.cw-card, div.cw-card, a[href]:has(img)")
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
            } catch (_: Exception) {}
        }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = fixUrlNull(linkElem.attr("href")) ?: return null
        if (href == mainUrl || href == "$mainUrl/" || href.contains("/tur/") || href.contains("/yil/")) return null

        val title = this.selectFirst("div.movie-title, h2, h3, h4, .title, a.title")?.text()?.trim()
            ?: linkElem.attr("title").ifEmpty { null }
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.ifEmpty { null }
            ?: return null

        val poster = fixUrlNull(this.selectFirst("img")?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        })

        val isTv = href.contains("/dizi") || href.contains("/series") || href.contains("/sezon")
        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?search=${query.trim()}"
        val document = app.get(searchUrl).document
        return document.select("a.movie-card, div.movie-card, a.cw-card, div.cw-card, a[href]:has(img)")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: name
        val poster = fixUrlNull(document.selectFirst("div.movie-poster img, img[src*='image.tmdb.org'], div.poster img, img")?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        })
        val description = document.selectFirst("p.movie-overview, p.description, div.overview, p")?.text()?.trim()
        val year = document.selectFirst("span.movie-year, span.year")?.text()?.trim()?.toIntOrNull()

        val episodeElements = document.select("a[href*='-sezon-'], a[href*='-bolum-'], a[href*='/bolum/'], a.episode-link")
        if (episodeElements.isNotEmpty()) {
            val eps = mutableListOf<Episode>()
            val epRegex = Regex("""(\d+)[.-]sezon[.-](\d+)[.-]bolum""")
            for (elem in episodeElements) {
                val epHref = fixUrlNull(elem.attr("href")) ?: continue
                val epMatch = epRegex.find(epHref)
                val sNum = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val eNum = epMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1
                val epName = elem.text().trim().ifEmpty { "$sNum. Sezon $eNum. Bölüm" }
                eps.add(newEpisode(epHref) {
                    this.name = epName
                    this.season = sNum
                    this.episode = eNum
                })
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        }

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
        val document = app.get(data).document
        var extracted = false

        val vsMatch = Regex("""window\._vs\s*=\s*['"]([^'"]+)['"]""").find(document.html())
        if (vsMatch != null) {
            try {
                val b64 = vsMatch.groupValues[1]
                val decoded = String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8)
                val arr = JSONArray(decoded)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val rawUrl = obj.optString("url")
                    val embedUrl = fixUrlNull(rawUrl.replace("\\/", "/")) ?: continue
                    if (!embedUrl.contains("youtube.com")) {
                        if (embedUrl.contains("play.liderfilm.cc") || embedUrl.contains("/vod/")) {
                            try {
                                val pDoc = app.get(
                                    embedUrl,
                                    headers = mapOf(
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                        "Referer" to "$mainUrl/"
                                    )
                                ).text
                                val contentUrl = Regex("""contentUrl["']?\s*:\s*["']([^"']+)["']""").find(pDoc)?.groupValues?.get(1)
                                if (contentUrl != null) {
                                    val streamUrl = if (contentUrl.startsWith("http")) contentUrl else "https://play.liderfilm.cc$contentUrl"
                                    callback(
                                        newExtractorLink(
                                            name,
                                            "$name 1080p",
                                            streamUrl,
                                            ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = "https://play.liderfilm.cc/"
                                            this.quality = Qualities.P1080.value
                                        }
                                    )
                                    extracted = true
                                }
                            } catch (e: Exception) {
                                Log.e("LiderFilm", "play.liderfilm.cc error", e)
                            }
                        }
                        loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
                        extracted = true
                    }
                }
            } catch (e: Exception) {
                Log.e("LiderFilm", "Error decoding _vs", e)
            }
        }

        val iframes = document.select("iframe").mapNotNull { fixUrlNull(it.attr("src").ifEmpty { it.attr("data-src") }) }
        for (iframe in iframes) {
            if (!iframe.contains("youtube.com")) {
                loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
                extracted = true
            }
        }

        return extracted
    }
}
