@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.nikyokki

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.json.JSONArray
import org.jsoup.nodes.Element
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class YabancDizi : MainAPI() {
    override var mainUrl = "https://yabanci-dizi.now"
    override var name = "YabancıDizi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler" to "Popüler Diziler",
        "${mainUrl}/filmler" to "Popüler Filmler",
        "${mainUrl}/trendler" to "Trendler",
        "${mainUrl}/arsiv" to "Arşiv",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page > 1) "${request.data}?page=${page}" else request.data
        val document = app.get(pageUrl).document
        val home = document.select("a[href*='/dizi/'], a[href*='/film/']")
            .filter { a ->
                val href = a.attr("href")
                !href.contains("/tur/") && !href.contains("/yil/") && !href.contains("/kategori/") && !href.contains("/sezon-")
            }
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.selectFirst("img")?.attr("alt")?.trim()
            ?: this.selectFirst("h2, h3, h4, span.text-white")?.text()?.trim()
            ?: this.attr("title").trim()
        if (title.isEmpty()) return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        })

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    private fun decryptAes(cipherText: String): String? {
        return try {
            val sha = MessageDigest.getInstance("SHA-256")
            val keyHash = sha.digest("!!22xx!!90!!".toByteArray(Charsets.UTF_8))
            val keyBase64 = Base64.encodeToString(keyHash, Base64.NO_WRAP).substring(0, 32)
            val secretKey = SecretKeySpec(keyBase64.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(ByteArray(16))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val decoded = Base64.decode(cipherText, Base64.DEFAULT)
            String(cipher.doFinal(decoded), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("YabancDizi", "decrypt error", e)
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val res = app.post(
                "$mainUrl/api/bg/searchContent?searchterm=$query",
                data = mapOf(),
                headers = mapOf("Content-Type" to "application/json"),
                referer = "$mainUrl/"
            ).text

            val json = JSONObject(res)
            val cipher = json.optString("response")
            if (cipher.isNotEmpty()) {
                val decrypted = decryptAes(cipher)
                if (decrypted != null) {
                    val decObj = JSONObject(decrypted)
                    val arr = decObj.optJSONArray("result")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            val title = item.optString("object_name").ifEmpty { item.optString("object_alternative_name") }
                            val slug = item.optString("used_slug").ifEmpty { item.optString("slug") }
                            val poster = fixUrlNull(item.optString("object_poster_url").ifEmpty { item.optString("poster_url") })
                            val href = fixUrlNull("$mainUrl/$slug") ?: continue
                            val isSeries = item.optString("used_type") == "Series" || slug.startsWith("dizi/")

                            if (isSeries) {
                                results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                                    this.posterUrl = poster
                                })
                            } else {
                                results.add(newMovieSearchResponse(title, href, TvType.Movie) {
                                    this.posterUrl = poster
                                })
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        if (results.isEmpty()) {
            try {
                val document = app.get("$mainUrl/diziler").document
                val fallback = document.select("a[href*='/dizi/'], a[href*='/film/']")
                    .filter { a ->
                        val href = a.attr("href")
                        !href.contains("/tur/") && !href.contains("/yil/") && !href.contains("/kategori/") && !href.contains("/sezon-")
                    }
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
                    .take(10)
                results.addAll(fallback)
            } catch (e: Exception) {}
        }

        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val script = document.selectFirst("script#__NEXT_DATA__")?.html()
        val isTv = url.contains("/dizi/")

        if (script != null) {
            try {
                val json = JSONObject(script)
                val props = json.optJSONObject("props")?.optJSONObject("pageProps")
                val content = props?.optJSONObject("content")
                    ?: props?.optJSONObject("series")
                    ?: props?.optJSONObject("movie")

                if (content != null) {
                    val title = content.optString("title").ifEmpty { content.optString("name") }
                    val poster = fixUrlNull(content.optString("poster_url"))
                    val plot = content.optString("overview").ifEmpty { content.optString("description") }
                    val year = content.optString("release_year").take(4).toIntOrNull()

                    if (isTv) {
                        val seasonsArr = content.optJSONArray("seasons")
                        val episodes = mutableListOf<Episode>()
                        if (seasonsArr != null) {
                            for (s in 0 until seasonsArr.length()) {
                                val sObj = seasonsArr.getJSONObject(s)
                                val sNum = sObj.optInt("season_number", 1)
                                val epArr = sObj.optJSONArray("episodes")
                                if (epArr != null) {
                                    for (e in 0 until epArr.length()) {
                                        val epObj = epArr.getJSONObject(e)
                                        val eNum = epObj.optInt("episode_number", e + 1)
                                        val epSlug = epObj.optString("slug")
                                        val epUrl = fixUrlNull("$mainUrl/$epSlug") ?: "$url/sezon-$sNum/bolum-$eNum"
                                        val epName = epObj.optString("name").ifEmpty { "$sNum. Sezon $eNum. Bölüm" }
                                        episodes.add(newEpisode(epUrl) {
                                            this.name = epName
                                            this.season = sNum
                                            this.episode = eNum
                                        })
                                    }
                                }
                            }
                        }
                        if (episodes.isEmpty()) {
                            episodes.add(newEpisode("$url/sezon-1/bolum-1") {
                                this.name = "1. Sezon 1. Bölüm"
                                this.season = 1
                                this.episode = 1
                            })
                        }
                        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                            this.posterUrl = poster
                            this.plot = plot
                            this.year = year
                        }
                    } else {
                        return newMovieLoadResponse(title, url, TvType.Movie, url) {
                            this.posterUrl = poster
                            this.plot = plot
                            this.year = year
                        }
                    }
                }
            } catch (e: Exception) {}
        }

        val title = document.selectFirst("h1")?.text()?.trim() ?: name
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("div.poster img, img")?.attr("src"))
        val description = document.selectFirst("p.overview, p.description, div.summary")?.text()?.trim()

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            val epElements = document.select("a[href*='/sezon-'][href*='/bolum-']")
            val epRegex = Regex("""sezon-(\\d+)/bolum-(\\d+)""")
            for (a in epElements) {
                val epHref = fixUrlNull(a.attr("href")) ?: continue
                val match = epRegex.find(epHref)
                val sNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val eNum = match?.groupValues?.get(2)?.toIntOrNull() ?: 1
                episodes.add(newEpisode(epHref) {
                    this.name = a.text().trim().ifEmpty { "$sNum. Sezon $eNum. Bölüm" }
                    this.season = sNum
                    this.episode = eNum
                })
            }
            if (episodes.isEmpty()) {
                episodes.add(newEpisode("$url/sezon-1/bolum-1") {
                    this.name = "1. Sezon 1. Bölüm"
                    this.season = 1
                    this.episode = 1
                })
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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
        val document = app.get(data).document
        val iframes = document.select("iframe").mapNotNull { fixUrlNull(it.attr("src")) }
        for (iframe in iframes) {
            loadExtractor(iframe, subtitleCallback, callback)
        }
        return true
    }
}
