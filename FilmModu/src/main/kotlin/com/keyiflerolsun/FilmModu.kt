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
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/turkce-dublaj-hd-film-izle?page=" to "Türkçe Dublaj Filmler",
        "${mainUrl}/turkce-altyazili-hd-filmler-izle?page=" to "Türkçe Altyazılı Filmler",
        "${mainUrl}/film-tur/4k-film-izle?page=" to "4K Filmler",
        "${mainUrl}/film-tur/aksiyon?page=" to "Aksiyon",
        "${mainUrl}/film-tur/animasyon?page=" to "Animasyon",
        "${mainUrl}/film-tur/bilim-kurgu-filmleri?page=" to "Bilim Kurgu",
        "${mainUrl}/film-tur/komedi?page=" to "Komedi",
        "${mainUrl}/film-tur/korku-filmleri?page=" to "Korku",
        "${mainUrl}/film-tur/kult-filmler-izle?page=" to "Kült Filmler",
        "${mainUrl}/film-tur/odullu-filmler-izle?page=" to "Oscar Ödüllü Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}").document
        val home = document.select("div.movie").mapNotNull { it.toMainPageResult() }
        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        val title = aTag.text().trim().ifEmpty {
            this.selectFirst("img")?.attr("alt")?.trim() ?: return null
        }
        val posterUrl = fixUrlNull(
            this.selectFirst("picture source")?.attr("data-srcset")
                ?: this.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/film-ara?term=${query}").document
        return document.select("div.movie").mapNotNull { it.toMainPageResult() }.distinctBy { it.url }
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

                        callback.invoke(
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
                    return true
                }
            } catch (e: Exception) {
                Log.e("FilmModu", "get-source failed", e)
            }
        }

        // Direct iframes fallback
        val iframes = document.select("iframe").mapNotNull { fixUrlNull(it.attr("src")) }
        for (iframe in iframes) {
            loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }
}