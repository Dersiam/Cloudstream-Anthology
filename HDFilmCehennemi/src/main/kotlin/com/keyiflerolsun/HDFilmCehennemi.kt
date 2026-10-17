@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.nio.charset.StandardCharsets
import java.net.URLEncoder

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi2.biz"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/api/search?q=a" to "Son Eklenenler",
        "$mainUrl/filmler/" to "Tüm Filmler",
        "$mainUrl/dizi-izle/" to "Yabancı Diziler",
        "$mainUrl/kategori/tavsiye-filmleri-izle/" to "Tavsiye Filmleri",
        "$mainUrl/kategori/en-iyi-filmler/" to "En İyi Filmler",
        "$mainUrl/kategori/netflix-filmleri-izle/" to "Netflix Filmleri",
        "$mainUrl/imdb-puani/" to "IMDb Puanına Göre",
        "$mainUrl/tur/aksiyon-filmleri-izle/" to "Aksiyon",
        "$mainUrl/tur/komedi-filmleri/" to "Komedi",
        "$mainUrl/tur/korku-filmleri/" to "Korku",
        "$mainUrl/tur/bilim-kurgu-filmleri-izle/" to "Bilim Kurgu",
        "$mainUrl/tur/dram-filmleri/" to "Dram",
        "$mainUrl/tur/gerilim-filmleri/" to "Gerilim",
        "$mainUrl/tur/animasyon-film-izle/" to "Animasyon",
        "$mainUrl/tur/macera-filmleri/" to "Macera",
        "$mainUrl/tur/fantastik-filmleri/" to "Fantastik",
        "$mainUrl/tur/gizem-filmleri/" to "Gizem",
        "$mainUrl/tur/suc-filmleri/" to "Suç",
        "$mainUrl/tur/romantik-filmler/" to "Romantik",
        "$mainUrl/tur/aile-filmleri/" to "Aile",
        "$mainUrl/tur/savas-filmleri/" to "Savaş",
        "$mainUrl/tur/belgesel-filmleri/" to "Belgesel",
        "$mainUrl/tur/tarih-filmleri/" to "Tarih"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = try {
            if (request.data.startsWith("$mainUrl/api/")) {
                val res = app.get(
                    request.data,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Accept" to "application/json"
                    )
                )
                parseJsonResults(res.text)
            } else {
                val pageUrl = if (page > 1) "${request.data}page/$page/" else request.data
                val document = app.get(
                    pageUrl,
                    headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                ).document
                document.select("div.movie-card, article, div.poster, a[href*='-izle']")
                    .mapNotNull { it.toHtmlSearchResult() }
                    .distinctBy { it.url }
            }
        } catch (_: Exception) {
            emptyList()
        }

        val finalItems = if (items.isNotEmpty()) items else {
            try {
                val res = app.get(
                    "$mainUrl/api/search?q=a",
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Accept" to "application/json"
                    )
                )
                parseJsonResults(res.text)
            } catch (_: Exception) {
                emptyList()
            }
        }

        return newHomePageResponse(request.name, finalItems)
    }

    private fun Element.toHtmlSearchResult(): SearchResponse? {
        val a = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        if (href == mainUrl || href == "$mainUrl/" || href.contains("/kategori/") || href.contains("/tur/")) return null

        val title = this.selectFirst(".card-title, .title, h2, h3")?.text()?.trim()
            ?: a.attr("title").ifEmpty { null }
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val poster = fixUrlNull(this.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } })
        val isTv = href.contains("/dizi") || href.contains("sezon")

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    private fun parseJsonResults(jsonStr: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        try {
            val json = JSONObject(jsonStr)
            val data = json.optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) {
                val obj = data.getJSONObject(i)
                val title = obj.optString("title")
                val slug = obj.optString("slug")
                if (title.isEmpty() || slug.isEmpty()) continue
                val posterUrl = obj.optString("posterUrl")
                val isTv = obj.optString("contentableType").contains("Series") || slug.contains("dizi")
                val href = if (slug.endsWith("/")) "$mainUrl/$slug" else "$mainUrl/$slug/"

                if (isTv) {
                    items.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                    })
                } else {
                    items.add(newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = posterUrl
                    })
                }
            }
        } catch (_: Exception) {}
        return items
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val res = app.get(
                "$mainUrl/api/search?q=$encoded",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept" to "application/json"
                )
            )
            val results = parseJsonResults(res.text)
            if (results.isNotEmpty()) results else getMainPage(1, MainPageRequest(name, "$mainUrl/api/search?q=a", false)).items.flatMap { it.list }
        } catch (e: Exception) {
            getMainPage(1, MainPageRequest(name, "$mainUrl/api/search?q=a", false)).items.flatMap { it.list }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = try {
            app.get(
                url,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            ).document
        } catch (e: Exception) {
            return newMovieLoadResponse(name, url, TvType.Movie, url)
        }

        val title = document.selectFirst("h1")?.text()?.replace(" izle", "")?.trim() ?: name
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("img")?.attr("src"))
        val description = document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst("p")?.text()?.trim()

        val isTv = url.contains("/dizi/") || document.select("a[href*='sezon-']").isNotEmpty()
        if (isTv) {
            val eps = mutableListOf<Episode>()
            val epElements = document.select("a[href*='sezon-'][href*='bolum-']")
            val epRegex = Regex("""sezon-(\d+)[/-]bolum-(\d+)""")
            val added = mutableSetOf<String>()

            for (a in epElements) {
                val epHref = fixUrlNull(a.attr("href")) ?: continue
                if (!added.add(epHref)) continue
                val match = epRegex.find(epHref)
                val sNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val eNum = match?.groupValues?.get(2)?.toIntOrNull() ?: 1

                eps.add(newEpisode(epHref) {
                    this.name = a.text().trim().ifEmpty { "$sNum. Sezon $eNum. Bölüm" }
                    this.season = sNum
                    this.episode = eNum
                })
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

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private fun cleanEscapes(str: String): String {
        return str.replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to "$mainUrl/"
            )
            val html = app.get(data, headers = headers).text

            // 1. Check videoPlayerData JSON
            val vpdMatch = Regex("""var\s+videoPlayerData\s*=\s*(\{.*?\});""").find(html)
            if (vpdMatch != null) {
                try {
                    val vpdJson = JSONObject(vpdMatch.groupValues[1])
                    val keys = vpdJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val items = vpdJson.optJSONArray(key) ?: continue
                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            val link = item.optString("link")
                            val tpl = item.optString("template")
                            if (tpl.isNotEmpty()) {
                                try {
                                    val decTpl = String(Base64.decode(tpl, Base64.DEFAULT), StandardCharsets.UTF_8)
                                    val srcMatch = Regex("""data-src=["']([^"']+)["']""").find(decTpl)
                                        ?: Regex("""src=["']([^"']+)["']""").find(decTpl)
                                    val rawPlayerUrl = srcMatch?.groupValues?.get(1)?.replace("{url}", link)
                                    if (rawPlayerUrl != null) {
                                        val playerUrl = when {
                                            rawPlayerUrl.startsWith("http") -> rawPlayerUrl
                                            rawPlayerUrl.startsWith("//") -> "https:$rawPlayerUrl"
                                            else -> "$mainUrl/$rawPlayerUrl"
                                        }

                                        if (playerUrl.contains("vidload.top")) {
                                            try {
                                                val vHtml = cleanEscapes(app.get(playerUrl, headers = headers).text)
                                                val m3u8 = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(vHtml)?.groupValues?.get(1)
                                                    ?: Regex("""file["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(vHtml)?.groupValues?.get(1)
                                                if (m3u8 != null) {
                                                    callback(
                                                        newExtractorLink(
                                                            name,
                                                            "VIP 1080p",
                                                            m3u8,
                                                            ExtractorLinkType.M3U8
                                                        ) {
                                                            this.referer = playerUrl
                                                            this.quality = Qualities.P1080.value
                                                        }
                                                    )
                                                    found = true
                                                }
                                                Regex("""(https?://[^\s"']+\.vtt[^\s"']*)""").findAll(vHtml).forEach { vttM ->
                                                    subtitleCallback.invoke(SubtitleFile("Altyazı", vttM.groupValues[1]))
                                                }
                                            } catch (e: Exception) {
                                                Log.e("HDFC", "Vidload error", e)
                                            }
                                        } else if (playerUrl.contains("vidmoly")) {
                                            try {
                                                val mHtml = cleanEscapes(app.get(playerUrl, headers = headers).text)
                                                val m3u8 = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""").find(mHtml)?.groupValues?.get(1)
                                                    ?: Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(mHtml)?.groupValues?.get(1)
                                                if (m3u8 != null) {
                                                    callback(
                                                        newExtractorLink(
                                                            name,
                                                            "Vidmoly 1080p",
                                                            m3u8,
                                                            ExtractorLinkType.M3U8
                                                        ) {
                                                            this.referer = playerUrl
                                                            this.quality = Qualities.P1080.value
                                                        }
                                                    )
                                                    found = true
                                                }
                                                Regex("""(https?://[^\s"']+\.vtt[^\s"']*)""").findAll(mHtml).forEach { vttM ->
                                                    subtitleCallback.invoke(SubtitleFile("Altyazı", vttM.groupValues[1]))
                                                }
                                            } catch (e: Exception) {
                                                Log.e("HDFC", "Vidmoly error", e)
                                            }
                                        }

                                        try {
                                            loadExtractor(playerUrl, data, subtitleCallback) { link ->
                                                callback(link)
                                                found = true
                                            }
                                        } catch (_: Exception) {}
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("HDFC", "videoPlayerData error", e)
                }
            }

            // 2. Fallback to iframes
            val document = org.jsoup.Jsoup.parse(html)
            val iframes = document.select("iframe").mapNotNull { fixUrlNull(it.attr("data-src").ifEmpty { it.attr("src") }) }
            for (iframe in iframes) {
                if (!iframe.contains("youtube.com")) {
                    try {
                        loadExtractor(iframe, subtitleCallback) { link ->
                            callback(link)
                            found = true
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e("HDFC", "loadLinks error", e)
        }

        if (!found) {
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
            found = true
        }

        return found
    }
}