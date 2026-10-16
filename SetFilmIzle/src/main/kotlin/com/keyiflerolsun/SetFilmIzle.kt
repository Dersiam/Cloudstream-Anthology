@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.json.JSONObject
import okhttp3.*

class SetFilmIzle : MainAPI() {
    override var mainUrl              = "https://www.setfilmizle.ltd"
    override var name                 = "SetFilmIzle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aile/"        to "Aile",
        "${mainUrl}/tur/aksiyon/"     to "Aksiyon",
        "${mainUrl}/tur/animasyon/"   to "Animasyon",
        "${mainUrl}/tur/belgesel/"    to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim-Kurgu",
        "${mainUrl}/tur/biyografi/"   to "Biyografi",
        "${mainUrl}/tur/dini/"        to "Dini",
        "${mainUrl}/tur/dram/"        to "Dram",
        "${mainUrl}/tur/fantastik/"   to "Fantastik",
        "${mainUrl}/tur/genclik/"     to "Gençlik",
        "${mainUrl}/tur/gerilim/"     to "Gerilim",
        "${mainUrl}/tur/gizem/"       to "Gizem",
        "${mainUrl}/tur/komedi/"      to "Komedi",
        "${mainUrl}/tur/korku/"       to "Korku",
        "${mainUrl}/tur/macera/"      to "Macera",
        "${mainUrl}/tur/mini-dizi/"   to "Mini Dizi",
        "${mainUrl}/tur/muzik/"       to "Müzik",
        "${mainUrl}/tur/program/"     to "Program",
        "${mainUrl}/tur/romantik/"    to "Romantik",
        "${mainUrl}/tur/savas/"       to "Savaş",
        "${mainUrl}/tur/spor/"        to "Spor",
        "${mainUrl}/tur/suc/"         to "Suç",
        "${mainUrl}/tur/tarih/"       to "Tarih",
        "${mainUrl}/tur/western/"     to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(pageUrl).document
        val home     = document.select("a.card-link, div.items article, article.card").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href").ifEmpty { this.selectFirst("a")?.attr("href") }) ?: return null
        val title = this.selectFirst(".card-ad, h2")?.text()?.trim() ?: this.attr("title").ifEmpty { null } ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.let { it.attr("src").ifEmpty { it.attr("data-src") } })

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchDoc = app.get("${mainUrl}/?s=${query.trim()}").document
        return searchDoc.select("a.card-link, div.items article, article.card").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? = toMainPageResult()

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title           = document.selectFirst("span.fbox-title-tx, h1")?.text()?.substringBefore(" izle")?.trim() ?: "Film"
        val poster          = fixUrlNull(document.selectFirst(".fbox-art img, .poster-art img, div.poster img")?.attr("src"))
        val description     = document.selectFirst("div.fbox-story p, div.wp-content p")?.text()?.trim()
        var year            = document.selectFirst("span.fbox-date, div.extra span.C a")?.text()?.replace(Regex("""[()]"""), "")?.trim()?.toIntOrNull()
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

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("STF", "loadLinks data » $data")
        val response = app.get(data, referer = "$mainUrl/")
        val html = response.text
        val document = response.document

        val videoNonce = Regex("""video:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""nonce:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            ?: ""

        val postId = document.selectFirst("#stfPlayer, div.fplayer")?.attr("data-post-id")
            ?: document.selectFirst("[data-post-id]")?.attr("data-post-id")
            ?: ""

        val buttons = document.select("button.fsrc, nav.player a")
        for (btn in buttons) {
            val playerName = btn.attr("data-player-name").ifEmpty { btn.text().trim() }
            val partKey = btn.attr("data-part-key").ifEmpty { "turkcedublaj" }
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
                        Log.d("STF", "Resolved streamUrl » $sourceUrl")
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