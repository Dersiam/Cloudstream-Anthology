@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

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
        "${mainUrl}/tur/aile-filmleri/page/"       to "Aile Filmleri",
        "${mainUrl}/tur/aksiyon-filmleri/page/"    to "Aksiyon Filmleri",
        "${mainUrl}/tur/animasyon-filmleri/page/"  to "Animasyon Filmleri",
        "${mainUrl}/tur/belgesel-filmleri/page/"   to "Belgesel Filmleri",
        "${mainUrl}/tur/bilim-kurgu-filmleri/page/" to "Bilim Kurgu Filmleri",
        "${mainUrl}/tur/biyografi-filmleri/page/"  to "Biyografi Filmleri",
        "${mainUrl}/tur/fantastik-filmler/page/"   to "Fantastik Filmler",
        "${mainUrl}/tur/gizem-filmleri/page/"      to "Gizem Filmleri",
        "${mainUrl}/tur/komedi-filmleri/page/"     to "Komedi Filmleri",
        "${mainUrl}/tur/korku-filmleri/page/"      to "Korku Filmleri",
        "${mainUrl}/tur/macera-filmleri/page/"     to "Macera Filmleri",
        "${mainUrl}/tur/romantik-filmler/page/"    to "Romantik Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}/").document
        val home     = document.select("a[href*='/film/'], div.frag-k, div.film_kutu").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href").ifEmpty { this.selectFirst("a")?.attr("href") }) ?: return null
        if (!href.contains("/film/")) return null

        val title = this.attr("title").ifEmpty { this.selectFirst(".title, h2, h3, div.yanac span")?.text() }?.substringBefore(" İzle")?.trim() ?: return null
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

        for (candidate in candidateUrls.distinct()) {
            val cleanUrl = candidate.substringBefore("?img=")
            if (cleanUrl.startsWith("http")) {
                Log.d("SCX", "Processing candidate: $cleanUrl")
                loadExtractor(cleanUrl, "${mainUrl}/", subtitleCallback, callback)
            }
        }

        return true
    }
}