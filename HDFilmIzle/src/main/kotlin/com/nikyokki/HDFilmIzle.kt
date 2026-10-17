@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.nikyokki

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element

class HDFilmIzle : MainAPI() {
    override var mainUrl = "https://www.hdfilmizle.best"
    override var name = "HDFilmIzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Son Filmler",
        "${mainUrl}/dizi/" to "Yabancı Diziler",
        "${mainUrl}/imdb-top-250/" to "IMDb Top 250",
        "${mainUrl}/yerli-filmler/" to "Yerli Filmler",
        "${mainUrl}/olmeden-izle/" to "Ölmeden Önce İzle",
        "${mainUrl}/kanal/netflix/" to "Netflix İçerikleri",
        "${mainUrl}/kanal/prime-video/" to "Prime Video",
        "${mainUrl}/kanal/apple-tv/" to "Apple TV+",
        "${mainUrl}/kanal/disney/" to "Disney+",
        "${mainUrl}/tur/aksiyon/" to "Aksiyon Filmleri",
        "${mainUrl}/tur/macera/" to "Macera Filmleri",
        "${mainUrl}/tur/animasyon/" to "Animasyon Filmleri",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim Kurgu",
        "${mainUrl}/tur/komedi/" to "Komedi Filmleri",
        "${mainUrl}/tur/korku/" to "Korku Filmleri",
        "${mainUrl}/tur/gerilim/" to "Gerilim Filmleri",
        "${mainUrl}/tur/dram/" to "Dram Filmleri",
        "${mainUrl}/tur/fantastik/" to "Fantastik Filmler",
        "${mainUrl}/tur/gizem/" to "Gizem Filmleri",
        "${mainUrl}/tur/suc/" to "Suç Filmleri",
        "${mainUrl}/tur/romantik/" to "Romantik Filmler",
        "${mainUrl}/tur/biyografi/" to "Biyografi",
        "${mainUrl}/tur/tarih/" to "Tarih",
        "${mainUrl}/tur/savas/" to "Savaş",
        "${mainUrl}/tur/belgesel/" to "Belgesel",
        "${mainUrl}/tur/aile/" to "Aile",
        "${mainUrl}/tur/western/" to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page > 1) "${request.data}page/$page/" else request.data
        val document = try {
            app.get(pageUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        } catch (_: Exception) {
            app.get("${mainUrl}/tur/aksiyon/", headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        }
        var home = document.select("div#moviesListResult a.poster, div.film-list a.poster, a[href*='/film/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        if (home.isEmpty() && request.data != "${mainUrl}/tur/aksiyon/") {
            home = app.get("${mainUrl}/tur/aksiyon/", headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
                .select("div#moviesListResult a.poster, div.film-list a.poster, a[href*='/film/']")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (!href.contains("/film/") || href.endsWith("/film/") || href.endsWith("/film") || href == "$mainUrl/film/") return null

        val title = this.selectFirst("h2.title, .title, span")?.text()?.trim()
            ?: link.attr("title").ifEmpty { null }
            ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        })

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val document = app.get("$mainUrl/?s=$query", headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
            val results = document.select("div#moviesListResult a.poster, div.film-list a.poster, a[href*='/film/']")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            if (results.isNotEmpty()) results else getMainPage(1, MainPageRequest(name, "${mainUrl}/tur/aksiyon/", false)).items.flatMap { it.list }
        } catch (e: Exception) {
            getMainPage(1, MainPageRequest(name, "${mainUrl}/tur/aksiyon/", false)).items.flatMap { it.list }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        val title = document.selectFirst("div.page-title h1, h1")?.text()?.trim() ?: name
        val poster = fixUrlNull(document.selectFirst("picture.poster-auto img, div.poster img, img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        })
        val description = document.selectFirst("article.text-white > p, div.description, p")?.text()?.trim()
        val year = document.selectFirst("div.page-title small.text-muted")?.text()
            ?.replace("(", "")?.replace(")", "")?.trim()?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        var extracted = false

        val nonce = Regex("""_hdfNonce_\s*=\s*["']([^"']+)["']""").find(document.html())?.groupValues?.get(1) ?: ""
        val postId = document.select("[data-id]").map { it.attr("data-id") }.firstOrNull { it.toIntOrNull() != null && it.toInt() > 0 }
            ?: Regex("""data-id=["'](\d+)["']""").find(document.html())?.groupValues?.get(1)
            ?: ""

        if (postId.isNotEmpty()) {
            try {
                val res = app.post(
                    "$mainUrl/ajax/videosrc/?id=$postId&lang=tr&mr=0",
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                        "X-HDF-Nonce" to nonce,
                        "Referer" to data
                    )
                ).text

                val json = JSONObject(res)
                if (json.optString("sonuc") == "ok") {
                    val src = json.optString("src")
                    if (src.isNotEmpty()) {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                name,
                                src,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$mainUrl/"
                                this.headers = mapOf(
                                    "Referer" to "$mainUrl/",
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                )
                                this.quality = Qualities.P1080.value
                            }
                        )
                        extracted = true
                    }

                    val tracks = json.optJSONArray("tracks")
                    if (tracks != null) {
                        for (i in 0 until tracks.length()) {
                            val track = tracks.getJSONObject(i)
                            val subSrc = track.optString("src")
                            val label = track.optString("label")
                            if (subSrc.isNotEmpty()) {
                                subtitleCallback.invoke(SubtitleFile(label, subSrc))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HDFilmIzle", "videosrc fetch error", e)
            }
        }

        // Fallback to iframes
        val iframes = document.select("iframe").mapNotNull { fixUrlNull(it.attr("src").ifEmpty { it.attr("data-src") }) }
        for (iframe in iframes) {
            if (!iframe.contains("youtube.com")) {
                try {
                    loadExtractor(iframe, mainUrl, subtitleCallback, callback)
                    extracted = true
                } catch (e: Exception) {}
            }
        }

        return extracted
    }
}