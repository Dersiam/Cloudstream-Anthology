@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun.anthologybelgeselocuk

import java.util.Locale
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AnthologyBelgeselocuk : MainAPI() {
    override var mainUrl              = "https://belgeselx.com"
    override var name                 = "Anthology Belgesel & Çocuk"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Documentary)

    override val mainPage = mainPageOf(
        "${mainUrl}/son-eklenenler"               to "Son Eklenenler",
        "${mainUrl}/en-cok-izlenen-belgeseller"   to "En Çok İzlenenler",
        "${mainUrl}/haftanin-trendleri"           to "Haftanın Trendleri",
        "${mainUrl}/konu/turk-tarihi-belgeselleri" to "Türk Tarihi",
        "${mainUrl}/konu/tarih-belgeselleri"       to "Tarih",
        "${mainUrl}/konu/bilim-belgeselleri"       to "Bilim",
        "${mainUrl}/konu/doga-belgeselleri"        to "Doğa",
        "${mainUrl}/konu/savas-belgeselleri"       to "Savaş",
        "${mainUrl}/konu/kozmik-belgeseller"       to "Kozmik",
        "${mainUrl}/konu/seri-belgeseller"         to "Seri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page > 1) "${request.data}&page=$page" else request.data
        val document = app.get(pageUrl).document
        val home = document.select("a.px-today-card, a.px-ep-card, div.px-notable-item a, a[href*='/belgeseldizi/'], a[href*='/belgesel/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun String.toTitleCase(): String {
        val locale = Locale("tr", "TR")
        return this.split(" ").joinToString(" ") { word ->
            word.lowercase(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (!href.contains("/belgesel/") && !href.contains("/belgeseldizi/")) return null

        val titleRaw = this.selectFirst(".px-today-card-title, .px-ep-series, .px-ep-title, h3, h2")?.text()
            ?: this.selectFirst("img")?.attr("alt")
            ?: this.text()
        val title = titleRaw.trim().toTitleCase()
        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src")
                ?: this.selectFirst("img")?.attr("data-src")
        )

        return newTvSeriesSearchResponse(title, href, TvType.Documentary) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponses = mutableListOf<SearchResponse>()
        try {
            val document = app.get("${mainUrl}/?s=${query.trim()}").document
            val results = document.select("a.px-today-card, a.px-ep-card, div.px-notable-item a, a[href*='/belgeseldizi/'], a[href*='/belgesel/']")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            searchResponses.addAll(results)
        } catch (e: Exception) {
            Log.e("BLX", "Search error: ${e.message}")
        }

        if (searchResponses.isEmpty()) {
            try {
                val cx = "016376594590146270301:iwmy65ijgrm"
                val tokenResponse = app.get("https://cse.google.com/cse.js?cx=${cx}")
                val cseLibVersion = Regex("""cselibVersion": "(.*)"""").find(tokenResponse.text)?.groupValues?.get(1)
                val cseToken      = Regex("""cse_token": "(.*)"""").find(tokenResponse.text)?.groupValues?.get(1)

                if (cseLibVersion != null && cseToken != null) {
                    val response = app.get(
                        "https://cse.google.com/cse/element/v1?rsz=filtered_cse&num=20&hl=tr&source=gcsc&cselibv=${cseLibVersion}&cx=${cx}&q=${query}&safe=off&cse_tok=${cseToken}&oq=${query}&rurl=https%3A%2F%2Fbelgeselx.com%2F",
                        referer = "https://belgeselx.com/"
                    )
                    val titles = Regex(""""titleNoFormatting": "(.*)"""").findAll(response.text).map { it.groupValues[1] }.toList()
                    val urls = Regex(""""url": "(.*)"""").findAll(response.text).map { it.groupValues[1] }.toList()
                    val posterUrls = Regex(""""ogImage": "(.*)"""").findAll(response.text).map { it.groupValues[1] }.toList()

                    for (i in titles.indices) {
                        val title = titles[i].split("İzle")[0].trim().toTitleCase()
                        val url = urls.getOrNull(i) ?: continue
                        val posterUrl = posterUrls.getOrNull(i)
                        if (!url.contains("belgesel")) continue
                        searchResponses.add(newTvSeriesSearchResponse(title, url, TvType.Documentary) {
                            this.posterUrl = posterUrl
                        })
                    }
                }
            } catch (e: Exception) {
                Log.e("BLX", "CSE search fallback error: ${e.message}")
            }
        }

        if (searchResponses.isEmpty()) {
            try {
                val doc = app.get("${mainUrl}/son-eklenenler").document
                val fallback = doc.select("a.px-today-card, a.px-ep-card, div.px-notable-item a, a[href*='/belgeseldizi/'], a[href*='/belgesel/']")
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
                searchResponses.addAll(fallback)
            } catch (e: Exception) {}
        }
        return searchResponses.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private val srcMap = mapOf(
        "0" to "new5",
        "1" to "new5",
        "2" to "new1",
        "3" to "new2",
        "4" to "new3",
        "5" to "new4"
    )

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.px-hero-title, h2.px-info-title, h1, h2")?.text()?.trim()?.toTitleCase() ?: "Belgesel"
        val poster = fixUrlNull(document.selectFirst("div.px-dizi-card-poster img, div.px-info-poster img, .px-ep-thumb, img")?.attr("src"))
        val description = document.selectFirst("div.px-dizi-card-body p, div.px-info-body p, p")?.text()?.trim()

        val episodes = mutableListOf<Episode>()
        val pageText = document.html()

        // Match onclick="diziGetir('id', 'ic1', 'ic2', 'ic3', 'baslik', ...)"
        val diziRegex = Regex("""diziGetir\('(\d+)',\s*'([^']*)',\s*'([^']*)',\s*'([^']*)',\s*'([^']*)',\s*'([^']*)',\s*'([^']*)',\s*'(\d+)',\s*'(\d+)'""")
        val diziMatches = diziRegex.findAll(pageText).toList()

        if (diziMatches.isNotEmpty()) {
            diziMatches.forEach { match ->
                val epId = match.groupValues[1]
                val ic1 = match.groupValues[2]
                val epTitle = match.groupValues[5].trim().toTitleCase()
                val seasonNum = match.groupValues[8].toIntOrNull() ?: 1
                val episodeNum = match.groupValues[9].toIntOrNull() ?: 1

                val playerFile = srcMap[ic1] ?: "new5"
                val epData = "${mainUrl}/video/data/${playerFile}.php?id=${epId}&sira=1"

                episodes.add(newEpisode(epData) {
                    this.name = epTitle
                    this.season = seasonNum
                    this.episode = episodeNum
                })
            }
        } else {
            // Check HTML episode cards
            var count = 1
            document.select("a.px-ep-card, div.px-season-pane a").forEach { el ->
                val epHref = fixUrlNull(el.attr("href")) ?: return@forEach
                val epTitle = el.selectFirst(".px-ep-title")?.text()?.trim() ?: "Bölüm $count"
                val epData = epHref

                episodes.add(newEpisode(epData) {
                    this.name = epTitle
                    this.season = 1
                    this.episode = count++
                })
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = title
                this.season = 1
                this.episode = 1
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.Documentary, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("BLX", "loadLinks data » $data")
        val response = app.get(data, referer = "${mainUrl}/")
        val text = response.text

        // 1. Direct file regex (e.g. from new4.php)
        Regex("""file:\s*"([^"]+)",\s*label:\s*"([^"]+)"""").findAll(text).forEach {
            val videoUrl = it.groupValues[1]
            var quality = it.groupValues[2]
            var thisName = this.name
            if (quality == "FULL") {
                quality = "1080p"
                thisName = "Google"
            }
            callback.invoke(newExtractorLink(source = thisName, name = thisName, url = videoUrl, type = INFER_TYPE) {
                this.referer = data
                this.quality = getQualityFromName(quality)
            })
        }

        // 2. Direct iframes in response document
        response.document.select("iframe").forEach { iframeEl ->
            val src = fixUrlNull(iframeEl.attr("src")) ?: return@forEach
            Log.d("BLX", "Found iframe src: $src")
            loadExtractor(src, "${mainUrl}/", subtitleCallback, callback)
        }

        // 3. Fallback: if data is an episode page with diziGetir
        if (text.contains("diziGetir(")) {
            val diziRegex = Regex("""diziGetir\('(\d+)',\s*'([^']*)'""")
            diziRegex.findAll(text).firstOrNull()?.let { match ->
                val epId = match.groupValues[1]
                val ic1 = match.groupValues[2]
                val playerFile = srcMap[ic1] ?: "new5"
                val playerUrl = "${mainUrl}/video/data/${playerFile}.php?id=${epId}&sira=1"
                loadLinks(playerUrl, isCasting, subtitleCallback, callback)
            }
        }

        return true
    }
}