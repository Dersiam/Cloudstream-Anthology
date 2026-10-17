@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.json.JSONObject

class SetFilmIzle : MainAPI() {
    override var mainUrl              = "https://www.setfilmizle.ltd"
    override var name                 = "SetFilmIzle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/trend/"           to "Trendler",
        "${mainUrl}/imdb-en-iyiler/"   to "IMDb Top 250",
        "${mainUrl}/turkce-dublaj-filmler/" to "Türkçe Dublaj Filmler",
        "${mainUrl}/yerli-filmler/"   to "Yerli Filmler",
        "${mainUrl}/ag/netflix/"      to "Netflix",
        "${mainUrl}/ag/amazon/"       to "Amazon Prime",
        "${mainUrl}/ag/disney/"       to "Disney+",
        "${mainUrl}/ag/hbo/"          to "HBO",
        "${mainUrl}/tur/aksiyon/"     to "Aksiyon",
        "${mainUrl}/tur/macera/"      to "Macera",
        "${mainUrl}/tur/animasyon/"   to "Animasyon",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim-Kurgu",
        "${mainUrl}/tur/komedi/"      to "Komedi",
        "${mainUrl}/tur/korku/"       to "Korku",
        "${mainUrl}/tur/gerilim/"     to "Gerilim",
        "${mainUrl}/tur/dram/"        to "Dram",
        "${mainUrl}/tur/fantastik/"   to "Fantastik",
        "${mainUrl}/tur/gizem/"       to "Gizem",
        "${mainUrl}/tur/suc/"         to "Suç",
        "${mainUrl}/tur/romantik/"    to "Romantik",
        "${mainUrl}/tur/savas/"       to "Savaş",
        "${mainUrl}/tur/tarih/"       to "Tarih",
        "${mainUrl}/tur/aile/"        to "Aile",
        "${mainUrl}/tur/belgesel/"    to "Belgesel",
        "${mainUrl}/tur/biyografi/"   to "Biyografi",
        "${mainUrl}/tur/genclik/"     to "Gençlik",
        "${mainUrl}/tur/mini-dizi/"   to "Mini Dizi",
        "${mainUrl}/tur/muzik/"       to "Müzik",
        "${mainUrl}/tur/spor/"        to "Spor",
        "${mainUrl}/tur/western/"     to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page > 1) "${request.data}page/$page/" else request.data
        val document = try {
            app.get(pageUrl).document
        } catch (_: Exception) {
            app.get("${mainUrl}/tur/aksiyon/").document
        }
        var home = document.select("a.card-link, div.items article, article.card").mapNotNull { it.toMainPageResult() }.distinctBy { it.url }

        if (home.isEmpty() && request.data != "${mainUrl}/tur/aksiyon/") {
            val fallbackDoc = try { app.get("${mainUrl}/tur/aksiyon/").document } catch (_: Exception) { null }
            home = fallbackDoc?.select("a.card-link, div.items article, article.card")?.mapNotNull { it.toMainPageResult() }?.distinctBy { it.url } ?: emptyList()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val a = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        if (href == mainUrl || href == "$mainUrl/" || href.contains("/tur/") || href.contains("/yil/")) return null

        val title = this.selectFirst(".card-ad, h2, h3")?.text()?.trim()
            ?: a.attr("title").ifEmpty { null }
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.let { it.attr("src").ifEmpty { it.attr("data-src") } })

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchDoc = app.get("${mainUrl}/?s=${query.trim()}").document
        return searchDoc.select("a.card-link, div.items article, article.card").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? = toMainPageResult()

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title           = document.selectFirst("span.fbox-title-tx, h1")?.text()?.substringBefore(" izle")?.trim() ?: "Film"
        val poster          = fixUrlNull(document.selectFirst(".fbox-art img, .poster-art img, div.poster img")?.attr("src"))
        val description     = document.selectFirst("div.fbox-story p, div.wp-content p")?.text()?.trim()
        val year            = document.selectFirst("span.fbox-date, div.extra span.C a")?.text()?.replace(Regex("""[()]"""), "")?.trim()?.toIntOrNull()
        val tags            = document.select("div.sgeneros a, div.fbox-tags a").map { it.text() }
        val actors          = document.select("span.valor a, div.fbox-actors a").map { Actor(it.text()) }
        val trailer         = Regex("""embed/(.*)\?rel""").find(document.html())?.groupValues?.get(1)?.let { "https://www.youtube.com/embed/$it" }

        if (url.contains("/dizi/")) {
            val episodes = mutableListOf<Episode>()
            val epElements = document.select("a[href*='/bolum/'], div#episodes ul.episodios li, div.season-panel a")
            
            var counter = 1
            epElements.forEach { it ->
                val epHref = fixUrlNull(it.attr("href").ifEmpty { it.selectFirst("a")?.attr("href") }) ?: return@forEach
                val epText = it.text().trim()
                val epSeason = Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epEpisode = Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: counter++

                episodes.add(newEpisode(epHref) {
                    this.name = epText.ifEmpty { "Bölüm $epEpisode" }
                    this.season = epSeason
                    this.episode = epEpisode
                })
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = tags
                addActors(actors)
                addTrailer(trailer)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun xorDecrypt(t: String, n: String): String {
        return try {
            val r = Base64.decode(t, Base64.DEFAULT)
            val o = Base64.decode(n, Base64.DEFAULT)
            val sb = java.lang.StringBuilder()
            for (i in r.indices) {
                sb.append((r[i].toInt() xor o[i % o.size].toInt()).toChar())
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("STF", "loadLinks data » $data")
        val response = app.get(data, referer = "$mainUrl/")
        val html = response.text
        val document = response.document

        val videoNonce = Regex("""video:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""nonce:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?: ""

        val postId = document.selectFirst("#stfPlayer, div.fplayer, [data-post-id]")?.attr("data-post-id") ?: ""

        val buttons = document.select("button.fsrc, [data-player-name], nav.player a")
        for (btn in buttons) {
            val playerName = btn.attr("data-player-name").ifEmpty { btn.text().trim() }
            val partKey = btn.attr("data-part-key")
            val sourceId = btn.attr("data-post-id").ifEmpty { postId }

            if (sourceId.isBlank() || playerName.isBlank()) continue

            try {
                val ajaxRes = app.post(
                    "${mainUrl}/wp-admin/admin-ajax.php",
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to data
                    ),
                    data = mapOf(
                        "action" to "get_video_url",
                        "nonce" to videoNonce,
                        "post_id" to sourceId,
                        "player_name" to playerName,
                        "part_key" to partKey
                    )
                )

                val json = JSONObject(ajaxRes.text)
                if (json.optBoolean("success", false)) {
                    val streamObj = json.optJSONObject("data")?.optJSONObject("stream")
                    val sourceUrl = streamObj?.optString("url")
                        ?: json.optJSONObject("data")?.optString("url")
                        ?: ""

                    if (sourceUrl.isNotBlank()) {
                        Log.d("STF", "Resolved sourceUrl » $sourceUrl")
                        // If it is setplay.shop or fastplay, resolve XOR obfuscation
                        if (sourceUrl.contains("setplay.shop") || sourceUrl.contains("stfplay.php")) {
                            try {
                                val stfHtml = app.get(sourceUrl, referer = data).text
                                val spgMatch = Regex("""SPG\.cerceve\("b2","([^"]+)","([^"]+)"\)""").find(stfHtml)
                                if (spgMatch != null) {
                                    val decUrl = xorDecrypt(spgMatch.groupValues[1], spgMatch.groupValues[2])
                                    if (decUrl.startsWith("http")) {
                                        val fpHtml = app.get(decUrl, referer = sourceUrl).text
                                        val kopruMatch = Regex("""window\.STF_KOPRU\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(fpHtml)
                                        if (kopruMatch != null) {
                                            val kopruJsonStr = kopruMatch.groupValues[1].replace("\t", "").replace("\n", "")
                                            val src = Regex("""src:\s*"([^"]+)"""").find(kopruJsonStr)?.groupValues?.get(1)
                                            if (src != null) {
                                                val streamUrl = if (src.startsWith("http")) src else "https://fastplay.mom$src"
                                                callback(
                                                    newExtractorLink(
                                                        name,
                                                        "$name - FastPlay",
                                                        streamUrl,
                                                        ExtractorLinkType.M3U8
                                                    ) {
                                                        this.referer = "https://fastplay.mom/"
                                                        this.quality = Qualities.P1080.value
                                                    }
                                                )
                                            }
                                            // Subtitles
                                            Regex("""\{"file":"([^"]+)","label":"([^"]+)"""").findAll(kopruJsonStr).forEach { subMatch ->
                                                val file = subMatch.groupValues[1].replace("\\/", "/")
                                                val label = subMatch.groupValues[2]
                                                subtitleCallback(SubtitleFile(label, file))
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("STF", "Error resolving STF bridge: ${e.message}")
                            }
                        }

                        loadExtractor(sourceUrl, "${mainUrl}/", subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {
                Log.e("STF", "Error resolving player $playerName: ${e.message}")
            }
        }

        // Generic iframe extraction fallback
        document.select("iframe").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            loadExtractor(src, data, subtitleCallback, callback)
        }

        return true
    }
}