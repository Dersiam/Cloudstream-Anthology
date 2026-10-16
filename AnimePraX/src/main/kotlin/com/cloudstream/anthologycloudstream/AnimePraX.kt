@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.cloudstream.anthologycloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject

class AnimePraX : MainAPI() {
    override var mainUrl = "https://animeprax.com"
    override var name = "AnimePraX"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val fallbackSource = "https://animom.org"

    override val mainPage = mainPageOf(
        "$fallbackSource/trendler" to "Günün Trendleri",
        "$fallbackSource/kesfet" to "Popüler & Keşfet",
        "$fallbackSource/anime-filmleri" to "Anime Filmleri",
        "$fallbackSource/anime-izle" to "Tüm Animeler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (request.data.startsWith("http")) request.data else "$fallbackSource/trendler"
        val doc = try {
            app.get(targetUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")).document
        } catch (e: Exception) {
            app.get("$fallbackSource/trendler", headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")).document
        }

        val items = doc.select("a[href*='/anime/']").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (!href.contains("/anime/") || href.contains("/tur/") || href.contains("/kategori/")) return null
        if (href.endsWith("/anime") || href.endsWith("/anime/")) return null

        val title = this.selectFirst("h2, h3, h4, .title, .truncate")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: this.text().trim().ifEmpty { null }
            ?: return null

        if (title.length < 2 || title.equals("Anime", ignoreCase = true)) return null

        val poster = fixUrlNull(this.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        })

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val res = app.post(
                "$fallbackSource/search",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Referer" to "$fallbackSource/",
                    "Origin" to fallbackSource
                ),
                data = mapOf("query" to query.trim())
            )
            val json = JSONObject(res.text)
            if (json.optBoolean("success", false)) {
                val themeHtml = json.optString("theme")
                if (themeHtml.isNotEmpty()) {
                    val doc = Jsoup.parse(themeHtml, fallbackSource)
                    val items = doc.select("a[href*='/anime/']").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
                    results.addAll(items)
                }
            }
        } catch (_: Exception) {}

        if (results.isEmpty()) {
            try {
                val home = getMainPage(1, MainPageRequest("Günün Trendleri", "$fallbackSource/trendler", false))
                return home.items.flatMap { it.list }
            } catch (_: Exception) {}
        }
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")).document
        val title = doc.selectFirst("meta[property='og:title']")?.attr("content")?.replace(Regex("""(?i)\s*-\s*animom.*"""), "")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: name

        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst("div.anime-cover img, div.poster img, img.lazy")?.attr("data-src")
            ?: doc.selectFirst("img")?.attr("src"))

        val plot = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?: doc.selectFirst("div.description, p.overview, div.content p")?.text()?.trim()

        val episodes = mutableListOf<Episode>()
        val added = mutableSetOf<String>()

        val candidateLinks = doc.select(
            ".profile-episode-list li a[href*='bolum'], .series-profile-episode-list a[href*='bolum']"
        ).ifEmpty {
            doc.select("a:has(data), a[href*='bolum']")
        }
        for (a in candidateLinks) {
            if (a.parents().any { it.hasClass("series-profile-episode-buttons") } ||
                a.attr("class").contains("series-profile-episode-buttons") ||
                a.select("[class*='series-profile-episode-buttons']").isNotEmpty() ||
                a.text().contains("İlk Bölüm") ||
                a.text().contains("Son Bölüm")) continue

            val href = fixUrlNull(a.attr("href")) ?: continue
            if (!added.add(href)) continue

            val epNum = a.selectFirst("data")?.text()?.trim()?.toIntOrNull()
                ?: Regex("""-(\d+)-bolum""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\b(\d+)\. Bölüm""").find(a.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: (episodes.size + 1)

            val epName = a.text().trim().ifEmpty { "$epNum. Bölüm" }

            episodes.add(newEpisode(href) {
                this.name = epName
                this.season = 1
                this.episode = epNum
            })
        }

        if (episodes.isEmpty()) {
            doc.select("a[href*='bolum']").forEach { a ->
                if (a.parents().any { it.hasClass("series-profile-episode-buttons") } ||
                    a.attr("class").contains("series-profile-episode-buttons") ||
                    a.select("[class*='series-profile-episode-buttons']").isNotEmpty() ||
                    a.text().contains("İlk Bölüm") ||
                    a.text().contains("Son Bölüm")) return@forEach

                val href = fixUrlNull(a.attr("href")) ?: return@forEach
                if (added.add(href)) {
                    episodes.add(newEpisode(href) {
                        this.name = a.text().trim().ifEmpty { "1. Bölüm" }
                        this.season = 1
                        this.episode = episodes.size + 1
                    })
                }
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = "1. Bölüm"
                this.season = 1
                this.episode = 1
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.sortedBy { it.episode }) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val doc = app.get(data, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")).document

            val frameUrls = mutableListOf<String>()
            doc.select("[data-frame]").forEach { btn ->
                val f = btn.attr("data-frame")
                if (f.isNotEmpty()) frameUrls.add(f)
            }
            doc.select("iframe").forEach { ifr ->
                val src = fixUrlNull(ifr.attr("src"))
                if (src != null) frameUrls.add(src)
            }

            for (url in frameUrls) {
                if (url.contains("hdplayersystem.com")) {
                    val hash = Regex("""(?:embed/|data=)([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1)
                    if (hash != null) {
                        try {
                            val vRes = app.post(
                                "https://hdplayersystem.com/player/index.php?data=$hash&do=getVideo",
                                headers = mapOf(
                                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                                    "Referer" to "https://hdplayersystem.com/embed/$hash",
                                    "Origin" to "https://hdplayersystem.com",
                                    "X-Requested-With" to "XMLHttpRequest",
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                ),
                                data = mapOf(
                                    "hash" to hash,
                                    "r" to "$fallbackSource/"
                                )
                            )
                            val vJson = JSONObject(vRes.text)
                            val securedLink = vJson.optString("securedLink")
                            val videoSource = vJson.optString("videoSource")
                            val finalStreamUrl = securedLink.ifEmpty { videoSource }
                            if (finalStreamUrl.isNotEmpty()) {
                                callback.invoke(
                                    newExtractorLink(
                                        name = "$name - HDPlayer",
                                        source = name,
                                        url = finalStreamUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.headers = mapOf(
                                            "Referer" to "https://hdplayersystem.com/",
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                        )
                                        this.quality = Qualities.P1080.value
                                    }
                                )
                            }
                        } catch (_: Exception) {}
                    }
                } else {
                    try {
                        loadExtractor(url, subtitleCallback, callback)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        return true
    }
}
