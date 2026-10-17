@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import android.util.Base64
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject

class KultFilmler : MainAPI() {
    override var mainUrl              = "https://kultfilmler.net"
    override var name = "KultFilmler"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Son Filmler",
        "${mainUrl}/category/aksiyon-filmleri-izle/" to "Aksiyon",
        "${mainUrl}/category/bilim-kurgu-filmleri-izle/" to "Bilim Kurgu",
        "${mainUrl}/category/dram-filmleri-izle/" to "Dram",
        "${mainUrl}/category/gerilim-filmleri-izle/" to "Gerilim",
        "${mainUrl}/category/korku-filmleri-izle/" to "Korku",
        "${mainUrl}/category/komedi-filmleri-izle/" to "Komedi",
        "${mainUrl}/category/macera-filmleri-izle/" to "Macera",
        "${mainUrl}/dizi-arsivi/" to "Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page > 1) {
            if (request.data.endsWith("/")) "${request.data}page/$page/" else "${request.data}/page/$page/"
        } else {
            request.data
        }
        val document = app.get(pageUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        val home = document.select("a.mcard, a.dcard, div.movie-box, a:has(img.pimg), a:has(div.poster)")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (href == mainUrl || href == "$mainUrl/" || href.contains("/category/") || href.contains("/liste/") || href.contains("/feed/")) return null

        val img = link.selectFirst("img.pimg, img")
        val title = img?.attr("alt")?.replace(" izle", "")?.trim()?.ifEmpty { null }
            ?: link.selectFirst("div.name a, .title, strong, h2, h3")?.text()?.replace(" izle", "")?.trim()?.ifEmpty { null }
            ?: link.attr("title").replace(" izle", "").trim().ifEmpty { null }
            ?: return null

        val posterUrl = fixUrlNull(img?.let { it.attr("data-src").ifEmpty { it.attr("src") } })

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val document = app.get("${mainUrl}/?s=${query}", headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
            val results = document.select("a.mcard, a.dcard, div.movie-box, a:has(img.pimg)")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            if (results.isNotEmpty()) results else {
                getMainPage(1, MainPageRequest(name, "${mainUrl}/", false)).items.flatMap { it.list }
            }
        } catch (e: Exception) {
            getMainPage(1, MainPageRequest(name, "${mainUrl}/", false)).items.flatMap { it.list }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document

        val title = document.selectFirst("h1, div.film h1, h1.film")?.text()?.replace(" izle", "")?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.replace(" izle", "")?.trim()
            ?: return null

        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("img.pimg, div.poster img, img")?.attr("src"))

        val description = document.selectFirst("div.description, meta[property='og:description'], p")?.text()?.trim()
        val year = Regex("""(\d{4})""").find(document.selectFirst("li.release, div.meta, span.year")?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull()

        if (url.contains("/dizi/")) {
            val episodes = document.select("div.episode-box, a[href*='/sezon-'], a[href*='/bolum-'], a[href*='/bolum/'], a[href*='-bolum-']").mapNotNull {
                val a = if (it.tagName() == "a") it else it.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val epName = a.text().trim().ifEmpty { "Bölüm" }
                val epSeason = Regex("""(\d+)[.-]sezon""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epEpisode = Regex("""(\d+)[.-]bolum""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }.toMutableList()

            if (episodes.isEmpty()) {
                episodes.add(newEpisode(url) {
                    this.name = "1. Sezon 1. Bölüm"
                    this.season = 1
                    this.episode = 1
                })
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val ua = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            val document = app.get(data, headers = ua).document
            val iframes = mutableSetOf<String>()

            // 1. Direct iframes in HTML
            document.select("iframe").forEach {
                val src = fixUrlNull(it.attr("src").ifEmpty { it.attr("data-src") })
                if (src != null) iframes.add(src)
            }

            // 2. Script kf-srcdata JSON
            val srcDataElem = document.selectFirst("script#kf-srcdata")
            if (srcDataElem != null) {
                val jsonText = srcDataElem.data().ifEmpty { srcDataElem.html() }
                Regex("""src=\\?["']([^"'\\]+)\\?["']""").findAll(jsonText).forEach {
                    val s = it.groupValues[1].replace("\\/", "/")
                    if (s.startsWith("http")) iframes.add(s)
                }
            }

            // 3. Base64 encoded PHA+
            val atob = Regex("""PHA\+[0-9a-zA-Z+/=]*""").find(document.html())?.value
            if (atob != null) {
                try {
                    val padding = 4 - atob.length % 4
                    val atobPadded = if (padding < 4) atob.padEnd(atob.length + padding, '=') else atob
                    val decoded = String(Base64.decode(atobPadded, Base64.DEFAULT), Charsets.UTF_8)
                    val iframe = Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")
                    if (!iframe.isNullOrEmpty()) iframes.add(iframe)
                } catch (e: Exception) {}
            }

            for (iframe in iframes) {
                if (iframe.contains("youtube.com")) continue

                if (iframe.contains("vidpapi.xyz")) {
                    val hash = Regex("""(?:video/|data=|embed/)([a-zA-Z0-9_-]+)""").find(iframe)?.groupValues?.get(1)
                    if (hash != null) {
                        try {
                            val vRes = app.post(
                                "https://vidpapi.xyz/player/index.php?data=$hash&do=getVideo",
                                headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                                    "X-Requested-With" to "XMLHttpRequest",
                                    "Referer" to "https://vidpapi.xyz/video/$hash"
                                ),
                                data = mapOf(
                                    "hash" to hash,
                                    "r" to "$mainUrl/"
                                )
                            ).text
                            val json = JSONObject(vRes)
                            val streamUrl = json.optString("securedLink").ifEmpty { json.optString("videoSource") }
                            if (streamUrl.isNotEmpty()) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "$name - VidPapi",
                                        url = streamUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = "https://vidpapi.xyz/"
                                        this.quality = Qualities.P1080.value
                                    }
                                )
                            }
                        } catch (e: Exception) {}
                    }
                }

                if (iframe.contains("vidmoly")) {
                    try {
                        val playerRes = app.get(iframe, headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).text
                        val unpacked = getAndUnpack(playerRes)

                        // Check subtitles in player
                        val subMatches = Regex("""["']file["']\s*:\s*["']([^"']+\.(?:srt|vtt)[^"']*)["'].*?["']label["']\s*:\s*["']([^"']+)["']""").findAll(unpacked)
                        subMatches.forEach {
                            val subUrl = it.groupValues[1].replace("\\/", "/")
                            val subLabel = it.groupValues[2]
                            subtitleCallback(SubtitleFile(subLabel, subUrl))
                        }

                        // Check m3u8 in player
                        val m3u8Match = Regex("""(https?://[^\s"'\\]+\.m3u8[^\s"'\\]*)""").find(unpacked)
                            ?: Regex("""["']file["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(unpacked)
                        if (m3u8Match != null) {
                            val m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = name,
                                    url = m3u8Url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "https://vidmoly.to/"
                                    this.quality = Qualities.P1080.value
                                }
                            )
                        }
                    } catch (e: Exception) {}
                }

                try {
                    loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.e("KultFilmler", "Error loadLinks", e)
        }
        return true
    }
}
