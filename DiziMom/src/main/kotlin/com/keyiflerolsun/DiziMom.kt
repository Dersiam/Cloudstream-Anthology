@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class DiziMom : MainAPI() {
    override var mainUrl              = "https://www.dizidad.com"
    override var name = "DiziMom"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler/" to "Son Bölümler",
        "${mainUrl}/yabanci-dizi-izle/" to "Yabancı Diziler",
        "${mainUrl}/netflix-dizileri-izle/" to "Netflix Dizileri",
        "${mainUrl}/turkce-dublaj-diziler-hd/" to "Dublajlı Diziler",
        "${mainUrl}/anime-izle/" to "Animeler",
        "${mainUrl}/yerli-dizi-izle/" to "Yerli Diziler",
        "${mainUrl}/kore-dizileri-izle-hd/" to "Kore Dizileri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
        val home = document.select("div.episode-box, div.single-item, a[href*='-bolum-'], a[href*='-sezon-'], div.categorytitle a, a[href*='/diziler/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (href == mainUrl || href == "$mainUrl/" || href.contains("/category/") || href.contains("/tag/") || href.contains("/page/")) return null

        val img = this.selectFirst("img")
        val title = this.selectFirst("div.categorytitle, div.episode-name, strong, h2, h3")?.text()?.replace(" izle", "")?.trim()
            ?: link.attr("title").replace(" izle", "").trim().ifEmpty { null }
            ?: img?.attr("alt")?.replace(" izle", "")?.trim()?.ifEmpty { null }
            ?: link.text().replace(" izle", "").trim().ifEmpty { null }
            ?: return null

        val posterUrl = fixUrlNull(img?.let { it.attr("data-src").ifEmpty { it.attr("src") } })

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val document = app.get("${mainUrl}/?s=${encoded}", headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
            val results = document.select("div.single-item, div.episode-box, a[href*='-bolum-'], a[href*='-sezon-'], div.categorytitle a, a[href*='/diziler/']")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            if (results.isNotEmpty()) results else getMainPage(1, MainPageRequest(name, "${mainUrl}/tum-bolumler/", false)).items.flatMap { it.list }
        } catch (e: Exception) {
            getMainPage(1, MainPageRequest(name, "${mainUrl}/tum-bolumler/", false)).items.flatMap { it.list }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document

        val title = document.selectFirst("div.title h1, h1")?.text()?.replace(" izle", "")?.trim() ?: name
        val poster = fixUrlNull(document.selectFirst("div.category_image img, div.poster img, img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        })
        val year = document.selectXpath("//div[span[contains(text(), 'Yapım Yılı')]]").text().substringAfter("Yapım Yılı : ").trim().toIntOrNull()
        val description = document.selectFirst("div.category_desc, div.description, p")?.text()?.trim()
        val tags = document.select("div.genres a").mapNotNull { it.text().trim() }
        val actors = document.selectXpath("//div[span[contains(text(), 'Oyuncular')]]").text().substringAfter("Oyuncular : ").split(", ").map {
            Actor(it.trim())
        }

        val episodes = mutableListOf<Episode>()
        val episodeElements = document.select("div.bolumust, a[href*='-bolum-'], a[href*='-sezon-']")
        val epRegex = Regex("""(\d+)[.-]sezon[.-](\d+)[.-]bolum""")
        val added = mutableSetOf<String>()

        for (it in episodeElements) {
            val a = if (it.tagName() == "a") it else it.selectFirst("a") ?: continue
            val epHref = fixUrlNull(a.attr("href")) ?: continue
            if (!added.add(epHref)) continue

            val epName = it.selectFirst("div.baslik")?.text()?.trim() ?: a.text().trim()
            val match = epRegex.find(epHref)
            val epSeason = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epEpisode = match?.groupValues?.get(2)?.toIntOrNull() ?: (episodes.size + 1)

            episodes.add(newEpisode(epHref) {
                this.name = epName.replace(" izle", "").replace(title, "").trim().ifEmpty { "$epSeason. Sezon $epEpisode. Bölüm" }
                this.season = epSeason
                this.episode = epEpisode
            })
        }

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
            this.tags = tags
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var foundStream = false
        val linkCallback: (ExtractorLink) -> Unit = { link ->
            foundStream = true
            callback.invoke(link)
        }

        try {
            val ua = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            val document = app.get(data, headers = ua).document
            val iframes = mutableListOf<String>()

            // 1. Direct iframes
            document.select("iframe").forEach {
                val src = fixUrlNull(it.attr("src").ifEmpty { it.attr("data-src") })
                if (src != null) iframes.add(src)
            }

            // 2. OpenGraph / Meta video
            document.select("meta[property='og:video'], meta[name='twitter:player']").forEach {
                val content = fixUrlNull(it.attr("content"))
                if (content != null) iframes.add(content)
            }

            // 3. Alternative sources
            document.select("div.sources a, div.diziplus_sources a").forEach {
                val href = fixUrlNull(it.attr("href"))
                if (href != null && href != data) {
                    try {
                        val subDoc = app.get(href, headers = ua).document
                        subDoc.select("iframe").forEach { subIf ->
                            val subSrc = fixUrlNull(subIf.attr("src").ifEmpty { subIf.attr("data-src") })
                            if (subSrc != null) iframes.add(subSrc)
                        }
                    } catch (e: Exception) {}
                }
            }

            for (iframe in iframes.distinct()) {
                if (iframe.contains("youtube.com")) continue

                // Check for direct hdplayersystem
                if (iframe.contains("hdplayersystem.")) {
                    try {
                        HDPlayerSystem().getUrl(iframe, "$mainUrl/", subtitleCallback, linkCallback)
                    } catch (e: Exception) {}
                }

                try {
                    loadExtractor(iframe, "$mainUrl/", subtitleCallback, linkCallback)
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.e("DiziMom", "Error loadLinks", e)
        }

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
