@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class SinemaCX : MainAPI() {
    override var mainUrl              = "https://sinemacc.com"
    override var name                 = "SinemaCX"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 250L
    override var sequentialMainPageScrollDelay = 250L

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aksiyon-filmleri/page/"    to "Aksiyon Filmleri",
        "${mainUrl}/tur/macera-filmleri/page/"     to "Macera Filmleri",
        "${mainUrl}/tur/bilim-kurgu-filmleri/page/" to "Bilim Kurgu Filmleri",
        "${mainUrl}/tur/animasyon-filmleri/page/"  to "Animasyon Filmleri",
        "${mainUrl}/tur/komedi-filmleri/page/"     to "Komedi Filmleri",
        "${mainUrl}/tur/korku-filmleri/page/"      to "Korku Filmleri",
        "${mainUrl}/tur/gerilim-filmleri/page/"    to "Gerilim Filmleri",
        "${mainUrl}/tur/dram-filmleri/page/"       to "Dram Filmleri",
        "${mainUrl}/tur/suc-filmleri/page/"        to "Suç Filmleri",
        "${mainUrl}/tur/fantastik-filmler/page/"   to "Fantastik Filmler",
        "${mainUrl}/tur/gizem-filmleri/page/"      to "Gizem Filmleri",
        "${mainUrl}/tur/romantik-filmler/page/"    to "Romantik Filmler",
        "${mainUrl}/tur/biyografi-filmleri/page/"  to "Biyografi Filmleri",
        "${mainUrl}/tur/tarihi-filmler/page/"      to "Tarihi Filmler",
        "${mainUrl}/tur/savas-filmleri/page/"      to "Savaş Filmleri",
        "${mainUrl}/tur/aile-filmleri/page/"       to "Aile Filmleri",
        "${mainUrl}/tur/belgesel-filmleri/page/"   to "Belgesel Filmleri",
        "${mainUrl}/tur/western-filmleri/page/"    to "Western Filmleri",
        "${mainUrl}/tur/spor-filmleri/page/"       to "Spor Filmleri",
        "${mainUrl}/tur/muzikal-filmler/page/"     to "Müzikal Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (request.data.endsWith("/page/")) "${request.data}${page}/" else request.data
        val document = try {
            app.get(targetUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        } catch (_: Exception) {
            app.get("${mainUrl}/tur/aksiyon-filmleri/page/1/", headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        }
        var home = document.select("a[href*='/film/'], div.frag-k, div.film_kutu").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        if (home.isEmpty() && targetUrl != "${mainUrl}/tur/aksiyon-filmleri/page/1/") {
            val fallbackDoc = try {
                app.get("${mainUrl}/tur/aksiyon-filmleri/page/1/", headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
            } catch (_: Exception) { null }
            home = fallbackDoc?.select("a[href*='/film/'], div.frag-k, div.film_kutu")?.mapNotNull { it.toSearchResult() }?.distinctBy { it.url } ?: emptyList()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href").ifEmpty { this.selectFirst("a")?.attr("href") }) ?: return null
        if (!href.contains("/film/")) return null
        if (href.endsWith("/film/") || href.endsWith("/film") || href == "$mainUrl/film/") return null

        val title = this.attr("title").ifEmpty {
            this.selectFirst(".title, h2, h3, div.yanac span")?.text() ?: this.selectFirst("img")?.attr("alt")
        }?.substringBefore(" İzle")?.replace(" Film Posteri", "")?.trim() ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }
        )

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query.trim()}").document
        return document.select("a[href*='/film/'], div.frag-k, div.film_kutu").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1")?.text()?.substringBefore(" İzle")?.trim() ?: "Film"
        val poster      = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content") ?: document.selectFirst("link[rel='image_src']")?.attr("href") ?: document.selectFirst("img")?.attr("src"))
        val year        = document.selectFirst("a[href*='/yil/']")?.text()?.toIntOrNull()
        val description = document.selectFirst("meta[property='og:description']")?.attr("content") ?: document.selectFirst("div.f-bilgi div.ackl, div.aciklama")?.text()?.trim()
        val tags        = document.select("a[href*='/tur/']").map { it.text().trim() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("SCX", "data » $data")
        val response = app.get(data, referer = "${mainUrl}/")
        val html = response.text
        val document = response.document

        val candidateUrls = mutableListOf<String>()

        // 1. Direct iframes and data-vsrc/data-url
        document.select("iframe, [data-vsrc], [data-url], [data-link]").forEach { el ->
            listOf(el.attr("src"), el.attr("data-vsrc"), el.attr("data-url"), el.attr("data-link")).forEach { raw ->
                if (raw.isNotBlank()) {
                    candidateUrls.add(raw)
                    // Try Base64 decode
                    if (raw.length > 20 && !raw.startsWith("http")) {
                        try {
                            val decoded = String(Base64.decode(raw, Base64.DEFAULT))
                            if (decoded.startsWith("http")) candidateUrls.add(decoded)
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        // 2. Also check page regex for base64 player links
        Regex("""aHR0c[a-zA-Z0-9+/=]{20,}""").findAll(html).forEach { match ->
            try {
                val decoded = String(Base64.decode(match.value, Base64.DEFAULT))
                if (decoded.startsWith("http")) candidateUrls.add(decoded)
            } catch (_: Exception) {}
        }

        var foundStream = false

        for (candidate in candidateUrls.distinct()) {
            val cleanUrl = candidate.substringBefore("?img=")
            if (cleanUrl.startsWith("http")) {
                Log.d("SCX", "Processing candidate: $cleanUrl")

                // Handle player.filmizle.in
                if (cleanUrl.contains("filmizle.in") || cleanUrl.contains("/video/")) {
                    val videoId = cleanUrl.substringAfter("/video/").substringBefore("?").substringBefore("/")
                    if (videoId.isNotBlank()) {
                        try {
                            val getVideoUrl = "https://player.filmizle.in/player/index.php?data=$videoId&do=getVideo"
                            val getRes = app.post(
                                getVideoUrl,
                                headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                    "Referer" to cleanUrl,
                                    "X-Requested-With" to "XMLHttpRequest",
                                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                                ),
                                data = mapOf(
                                    "hash" to videoId,
                                    "r" to "$mainUrl/"
                                )
                            ).text

                            val json = JSONObject(getRes)
                            val securedLink = json.optString("securedLink")
                            val videoSource = json.optString("videoSource")
                            val streamUrl = if (securedLink.isNotBlank()) securedLink else videoSource

                            if (streamUrl.isNotBlank()) {
                                callback(
                                    newExtractorLink(
                                        name,
                                        "$name - 1080p HLS",
                                        streamUrl,
                                        ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = cleanUrl
                                        this.quality = Qualities.P1080.value
                                    }
                                )
                                foundStream = true
                            }
                        } catch (e: Exception) {
                            Log.e("SCX", "Error fetching filmizle.in getVideo", e)
                        }
                    }
                }

                try {
                    loadExtractor(cleanUrl, "${mainUrl}/", subtitleCallback, callback)
                } catch (_: Exception) {}
            }
        }

        return foundStream || candidateUrls.isNotEmpty()
    }
}