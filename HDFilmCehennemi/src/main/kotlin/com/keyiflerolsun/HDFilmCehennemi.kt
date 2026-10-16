@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.json.JSONArray

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi2.biz"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl" to "Son Eklenenler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = app.get(
            "$mainUrl/api/search?q=a",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "application/json"
            )
        )
        val items = parseJsonResults(res.text)
        return newHomePageResponse(request.name, items)
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
                val href = "$mainUrl/$slug"

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
        } catch (e: Exception) {}
        return items
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val res = app.get(
                "$mainUrl/api/search?q=$query",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept" to "application/json"
                )
            )
            val results = parseJsonResults(res.text)
            if (results.isNotEmpty()) results else getMainPage(1, MainPageRequest(name, mainUrl)).items
        } catch (e: Exception) {
            getMainPage(1, MainPageRequest(name, mainUrl)).items
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        ).document

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = app.get(
                data,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            ).document

            val iframes = document.select("iframe").mapNotNull { fixUrlNull(it.attr("data-src").ifEmpty { it.attr("src") }) }
            for (iframe in iframes) {
                try {
                    loadExtractor(iframe, subtitleCallback, callback)
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
        return true
    }
}