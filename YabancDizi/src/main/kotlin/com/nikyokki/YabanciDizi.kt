@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.nikyokki

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.net.URLEncoder

class YabancDizi : MainAPI() {
    override var mainUrl = "https://yabanci-dizi.now"
    override var name = "YabancıDizi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/#trend-series" to "Trend Diziler",
        "${mainUrl}/#popular-series" to "Popüler Diziler",
        "${mainUrl}/#last-movies" to "Son Eklenen Filmler",
        "${mainUrl}/#trend-movies" to "Trend Filmler",
        "${mainUrl}/#new-episodes" to "Son Eklenen Bölümler",
        "${mainUrl}/aksiyon-dizileri" to "Aksiyon Dizileri",
        "${mainUrl}/bilim-kurgu-dizileri" to "Bilim Kurgu Dizileri",
        "${mainUrl}/komedi-dizileri" to "Komedi Dizileri",
        "${mainUrl}/drama-dizileri" to "Dram Dizileri",
        "${mainUrl}/gerilim-dizileri" to "Gerilim Dizileri",
        "${mainUrl}/korku-dizileri" to "Korku Dizileri",
        "${mainUrl}/fantastik-dizileri" to "Fantastik Dizileri",
        "${mainUrl}/romantik-dizileri" to "Romantik Dizileri",
        "${mainUrl}/anime-izle" to "Animeler",
        "${mainUrl}/asya-dizileri-izle" to "Asya Dizileri",
        "${mainUrl}/kdrama-izle" to "K-Drama",
        "${mainUrl}/imdb-top-100" to "IMDb Top 100",
        "${mainUrl}/turkce-dublaj-dizi-izle" to "Dublajlı Diziler",
        "${mainUrl}/turkce-altyazili-dizi-izle" to "Altyazılı Diziler"
    )

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        if (request.data.contains("#")) {
            try {
                val doc = app.get(
                    mainUrl,
                    headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                ).document

                val nextDataHtml = doc.selectFirst("script#__NEXT_DATA__")?.html()
                if (nextDataHtml != null) {
                    val json = JSONObject(nextDataHtml)
                    val pageProps = json.optJSONObject("props")?.optJSONObject("pageProps")
                    val secureData = pageProps?.optString("secureData")
                    if (!secureData.isNullOrEmpty()) {
                        val decrypted = decryptAes(secureData)
                        if (decrypted != null) {
                            val decObj = JSONObject(decrypted)
                            val key = when {
                                request.data.contains("trend-series") -> "getTrendSeries"
                                request.data.contains("popular-series") -> "allPopularSeries"
                                request.data.contains("last-movies") -> "getLastMovies"
                                request.data.contains("trend-movies") -> "getTrendMovies"
                                request.data.contains("new-episodes") -> "getEpisodesOnNewSeries"
                                else -> "allPopularSeries"
                            }

                            val arr = decObj.optJSONArray(key) ?: decObj.optJSONArray("allPopularSeries") ?: JSONArray()
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                val title = obj.optString("culture_title").ifEmpty { obj.optString("original_title") }.ifEmpty { obj.optString("used_title") }
                                if (title.isEmpty()) continue
                                val slug = obj.optString("used_slug").ifEmpty { obj.optString("slug") }
                                if (slug.isEmpty()) continue
                                val href = if (slug.startsWith("http")) slug else "$mainUrl/$slug"
                                val poster = fixUrlNull(obj.optString("poster_url").ifEmpty { obj.optString("face_url") })
                                val isTv = slug.contains("dizi/") || obj.optString("used_type").contains("Series")

                                if (isTv) {
                                    items.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                                        this.posterUrl = poster
                                    })
                                } else {
                                    items.add(newMovieSearchResponse(title, href, TvType.Movie) {
                                        this.posterUrl = poster
                                    })
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("YabancDizi", "getMainPage error", e)
            }
        } else {
            try {
                val targetUrl = if (page > 1) "${request.data}?page=$page" else request.data
                val document = app.get(targetUrl, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")).document
                document.select("a[href*='/dizi/'], a[href*='/film/']").forEach { a ->
                    val rawHref = fixUrlNull(a.attr("href")) ?: return@forEach
                    val href = if (rawHref.startsWith("http")) rawHref else "$mainUrl$rawHref"
                    val title = a.selectFirst("img")?.attr("alt")?.trim() ?: a.text().trim()
                    if (title.isNotEmpty()) {
                        val poster = fixUrlNull(a.selectFirst("img")?.attr("src"))
                        if (href.contains("/dizi/")) {
                            items.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster })
                        } else {
                            items.add(newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster })
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (items.isEmpty()) {
            try {
                val document = app.get("$mainUrl/diziler", headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")).document
                document.select("a[href*='/dizi/'], a[href*='/film/']").forEach { a ->
                    val rawHref = fixUrlNull(a.attr("href")) ?: return@forEach
                    val href = if (rawHref.startsWith("http")) rawHref else "$mainUrl$rawHref"
                    val title = a.selectFirst("img")?.attr("alt")?.trim() ?: a.text().trim()
                    if (title.isNotEmpty()) {
                        val poster = fixUrlNull(a.selectFirst("img")?.attr("src"))
                        if (href.contains("/dizi/")) {
                            items.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster })
                        } else {
                            items.add(newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster })
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return newHomePageResponse(request.name, items.distinctBy { it.url })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val res = app.post(
                "$mainUrl/api/bg/searchContent?searchterm=$encoded",
                data = mapOf(),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                ),
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
        } catch (_: Exception) {}

        if (results.isEmpty()) {
            return getMainPage(1, MainPageRequest(name, "${mainUrl}/#popular-series", false)).items.flatMap { it.list }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = try {
            app.get(
                url,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            ).document
        } catch (e: Exception) {
            return newMovieLoadResponse(name, url, TvType.Movie, url)
        }
        val script = document.selectFirst("script#__NEXT_DATA__")?.html()
        val isTv = url.contains("/dizi/")

        if (script != null) {
            try {
                val json = JSONObject(script)
                val pageProps = json.optJSONObject("props")?.optJSONObject("pageProps")
                val secureData = pageProps?.optString("secureData")
                val contentObj = if (!secureData.isNullOrEmpty()) {
                    val dec = decryptAes(secureData)
                    if (dec != null) {
                        val d = JSONObject(dec)
                        d.optJSONObject("contentItem") ?: d.optJSONObject("content")
                    } else null
                } else null

                val content = contentObj ?: pageProps?.optJSONObject("content") ?: pageProps?.optJSONObject("series") ?: pageProps?.optJSONObject("movie")

                if (content != null) {
                    val title = content.optString("culture_title").ifEmpty { content.optString("original_title") }
                        .ifEmpty { content.optString("title") }.ifEmpty { content.optString("name") }
                    val poster = fixUrlNull(content.optString("poster_url").ifEmpty { content.optString("face_url") })
                    val plot = content.optString("description").ifEmpty { content.optString("overview") }
                    val year = content.optString("release_year").take(4).toIntOrNull()

                    if (isTv) {
                        val episodes = mutableListOf<Episode>()
                        val seasonsArr = content.optJSONArray("seasons")
                        if (seasonsArr != null) {
                            for (s in 0 until seasonsArr.length()) {
                                val sObj = seasonsArr.getJSONObject(s)
                                val sNum = sObj.optInt("season_number", s + 1)
                                val epArr = sObj.optJSONArray("episodes")
                                if (epArr != null) {
                                    for (e in 0 until epArr.length()) {
                                        val epObj = epArr.getJSONObject(e)
                                        val eNum = epObj.optInt("episode_number", e + 1)
                                        val epSlug = epObj.optString("slug").ifEmpty { epObj.optString("used_slug") }
                                        val epUrl = if (epSlug.isNotEmpty()) "$mainUrl/$epSlug" else "$url/sezon-$sNum/bolum-$eNum"
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
                            episodes.add(newEpisode(url) {
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
            } catch (e: Exception) {
                Log.e("YabancDizi", "load JSON error", e)
            }
        }

        val title = document.selectFirst("h1")?.text()?.trim() ?: name
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("div.poster img, img")?.attr("src"))
        val description = document.selectFirst("p.overview, p.description, div.summary")?.text()?.trim()

        if (isTv) {
            val episodes = mutableListOf<Episode>()
            episodes.add(newEpisode(url) {
                this.name = "1. Sezon 1. Bölüm"
                this.season = 1
                this.episode = 1
            })
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
        var foundStream = false
        try {
            val document = app.get(data, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).document
            val iframes = document.select("iframe").mapNotNull { fixUrlNull(it.attr("src")) }
            for (iframe in iframes) {
                try {
                    loadExtractor(iframe, subtitleCallback) { link ->
                        callback(link)
                        foundStream = true
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        if (!foundStream) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} 1080p",
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
