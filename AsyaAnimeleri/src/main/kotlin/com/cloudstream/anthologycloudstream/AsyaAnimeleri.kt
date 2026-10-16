@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.cloudstream.anthologycloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Base64

class AsyaAnimeleri : MainAPI() {
    override var mainUrl = "https://asyaanimeleri.top"
    override var name = "AsyaAnimeleri"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val pageUrl = if (page > 1) "$mainUrl/page/$page/" else mainUrl
        val document = app.get(pageUrl).document
        val items = document.select("article.bs, div.bsx, div.movie, article, div.film, div.item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".tt, h2, h3, a.title, .title, a")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val poster = fixUrlNull(this.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } })
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("article.bs, div.bsx, div.movie, article, div.film, div.item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: name
        val poster = fixUrlNull(document.selectFirst("div.thumb img, div.poster img, img.poster")?.let { it.attr("src").ifEmpty { it.attr("data-src") } })
        val description = document.selectFirst("div.entry-content, div.description, p.overview, div.summary")?.text()?.trim()

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
        val document = app.get(data).document
        
        // Direct iframes
        val iframes = document.select("iframe").mapNotNull { fixUrlNull(it.attr("src")) }.toMutableList()
        
        // Base64 mirror options
        document.select("select.mirror option").forEach { opt ->
            val b64 = opt.attr("value")
            if (b64.isNotBlank() && b64.length > 10) {
                try {
                    val decoded = String(Base64.decode(b64, Base64.DEFAULT))
                    Regex("""src=["']([^"']+)["']""").find(decoded)?.groupValues?.get(1)?.let {
                        iframes.add(it)
                    }
                } catch (_: Exception) {}
            }
        }

        for (iframe in iframes.distinct()) {
            loadExtractor(iframe, data, subtitleCallback, callback)
        }
        return true
    }
}
