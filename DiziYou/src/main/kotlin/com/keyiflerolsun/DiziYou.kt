@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class DiziYou : MainAPI() {
    override var mainUrl              = "https://www.diziyou.one"
    override var name                 = "DiziYou"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl                                                       to "Son Eklenen Bölümler",
        "${mainUrl}/dizi-arsivi/page/SAYFA/"                          to "Tüm Diziler",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Aksiyon"              to "Aksiyon",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Macera"               to "Macera",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Bilim+Kurgu"          to "Bilim Kurgu",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Komedi"               to "Komedi",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Dram"                 to "Dram",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Gerilim"              to "Gerilim",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Korku"                to "Korku",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Fantazi"              to "Fantazi",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Gizem"                to "Gizem",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Su%C3%A7"             to "Suç",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Romantik"             to "Romantik",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Animasyon"            to "Animasyon",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Tarih"                to "Tarih",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Sava%C5%9F"           to "Savaş",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Biyografi"            to "Biyografi",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Belgesel"             to "Belgesel",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Aile"                 to "Aile",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=M%C3%BCzik"           to "Müzik",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Vah%C5%9Fi+Bat%C4%B1" to "Vahşi Batı"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("SAYFA")) request.data.replace("SAYFA", "$page") else request.data
        val document = try {
            app.get(url).document
        } catch (_: Exception) {
            app.get(mainUrl).document
        }
        var home = document.select("div.single-item, div.listepisodes, div.post").mapNotNull { it.toMainPageResult() }
        if (home.isEmpty() && url != mainUrl) {
            val fallbackDoc = try { app.get(mainUrl).document } catch (_: Exception) { null }
            home = fallbackDoc?.select("div.single-item, div.listepisodes, div.post")?.mapNotNull { it.toMainPageResult() } ?: emptyList()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val aTag = this.selectFirst("a[title]") ?: this.selectFirst("div#categorytitle a") ?: this.selectFirst("a") ?: return null
        val title = aTag.attr("title").ifEmpty { aTag.text() }.substringBefore(" izle").trim()
        if (title.isBlank()) return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.let { it.attr("src").ifEmpty { it.attr("data-src") } })

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val res = app.post(
                "${mainUrl}/wp-admin/admin-ajax.php",
                data = mapOf("action" to "data_fetch", "keyword" to query),
                referer = "${mainUrl}/"
            )
            val doc = res.document
            doc.select("div#searchelement, div.single-item").mapNotNull {
                val a = it.selectFirst("a:has(img)") ?: it.selectFirst("a") ?: return@mapNotNull null
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val title = it.select("a").lastOrNull()?.text()?.trim() ?: a.text().trim()
                val poster = fixUrlNull(it.selectFirst("img")?.attr("src"))
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title           = document.selectFirst("h1")?.text()?.trim()?.removePrefix("Slow Horses")?.ifEmpty { document.selectFirst("h1")?.text()?.trim() } ?: return null
        val cleanTitle      = document.selectFirst("h1")?.text()?.replace(Regex("""<[^>]+>"""), "")?.trim() ?: "Dizi"
        val poster          = fixUrlNull(document.selectFirst("div.category_image img, div.dil-afis img")?.attr("src"))
        val description     = document.selectFirst("div.diziyou_desc, div.entry-content p")?.text()?.trim()
        val year            = document.selectFirst("span.dizimeta:contains(Yapım Yılı)")?.nextSibling()?.toString()?.trim()?.toIntOrNull()
        val tags            = document.select("div.genres a").map { it.text() }
        val actors          = document.selectFirst("span.dizimeta:contains(Oyuncular)")?.nextSibling()?.toString()?.trim()?.split(", ")?.map { Actor(it) }
        val trailer         = document.selectFirst("iframe.trailer-video")?.attr("src")

        val episodes = document.select("div.bolumust, div.listepisodes").mapNotNull {
            val epLink = it.selectFirst("a") ?: it.closest("a") ?: return@mapNotNull null
            val epHref = fixUrlNull(epLink.attr("href")) ?: return@mapNotNull null
            val epName = it.selectFirst("div.baslik, .bolumismi")?.text()?.trim()
                ?: epLink.attr("title").ifEmpty { epLink.text() }.trim()
            val epEpisode = Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull()
            val epSeason  = Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            newEpisode(epHref) {
                this.name = epName
                this.season = epSeason
                this.episode = epEpisode ?: 1
            }
        }.distinctBy { it.data }

        return newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZY", "data » $data")
        val document = app.get(data).document

        val playerIframe = document.selectFirst("iframe#diziyouPlayer, iframe")?.attr("src")
        if (playerIframe != null) {
            val playerDoc = app.get(playerIframe, referer = "$mainUrl/").text
            Regex("""(?:file|source|src):\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""").findAll(playerDoc).forEach {
                val mediaUrl = it.groupValues[1]
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = mediaUrl,
                        type = if (mediaUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = playerIframe
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        val itemId = playerIframe?.split("/")?.lastOrNull()?.substringBefore(".html")
        if (itemId != null) {
            val subTitles  = mutableListOf<DiziyouSubtitle>()
            val streamUrls = mutableListOf<DiziyouStream>()
            val storage    = mainUrl.replace("www", "storage")

            document.select("span.diziyouOption").forEach {
                val optId   = it.attr("id")

                if (optId == "turkceAltyazili") {
                    subTitles.add(DiziyouSubtitle("Turkish", "${storage}/subtitles/${itemId}/tr.vtt"))
                    streamUrls.add(DiziyouStream("Orjinal Dil", "${storage}/episodes/${itemId}/play.m3u8"))
                }

                if (optId == "ingilizceAltyazili") {
                    subTitles.add(DiziyouSubtitle("English", "${storage}/subtitles/${itemId}/en.vtt"))
                    streamUrls.add(DiziyouStream("Orjinal Dil", "${storage}/episodes/${itemId}/play.m3u8"))
                }

                if (optId == "turkceDublaj") {
                    streamUrls.add(DiziyouStream("Türkçe Dublaj", "${storage}/episodes/${itemId}_tr/play.m3u8"))
                }
            }

            for (sub in subTitles) {
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = sub.name,
                        url  = fixUrl(sub.url)
                    )
                )
            }

            for (stream in streamUrls) {
                callback.invoke(
                    newExtractorLink(
                        source  = stream.name,
                        name    = stream.name,
                        url     = stream.url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "${mainUrl}/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return true
    }

    data class DiziyouSubtitle(val name: String, val url: String)
    data class DiziyouStream(val name: String, val url: String)
}