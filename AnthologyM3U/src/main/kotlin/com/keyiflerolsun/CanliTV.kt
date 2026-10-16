@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.keyiflerolsun.anthologym3u

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.io.InputStream

class AnthologyM3U : MainAPI() {
    override var mainUrl = "https://raw.githubusercontent.com/falsisdev/anthology/main/providers/M3U/Liste/canli.m3u"
    override var name = "Anthology M3U"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val kanallar = IptvPlaylistParser().parseM3U(app.get(mainUrl).text)

        return newHomePageResponse(
            kanallar.items.groupBy { it.attributes["group-title"] }.map { group ->
                val title = group.key ?: ""
                val show = group.value.map { kanal ->
                    val streamurl = kanal.url.toString()
                    val channelname = kanal.title.toString()
                    val posterurl = kanal.attributes["tvg-logo"].toString()
                    val chGroup = kanal.attributes["group-title"].toString()
                    val nation = kanal.attributes["tvg-country"].toString()

                    newLiveSearchResponse(
                        channelname,
                        LoadData(streamurl, channelname, posterurl, chGroup, nation).toJson(),
                        type = TvType.Live
                    ) {
                        this.posterUrl = posterurl
                        this.lang = nation
                    }
                }

                HomePageList(title, show, isHorizontalImages = true)
            },
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val kanallar = IptvPlaylistParser().parseM3U(app.get(mainUrl).text)
        val filtered = kanallar.items.filter { it.title.toString().lowercase().contains(query.lowercase()) }
        val targetList = if (filtered.isNotEmpty()) filtered else kanallar.items.take(15)

        return targetList.map { kanal ->
            val streamurl = kanal.url.toString()
            val channelname = kanal.title.toString()
            val posterurl = kanal.attributes["tvg-logo"].toString()
            val chGroup = kanal.attributes["group-title"].toString()
            val nation = kanal.attributes["tvg-country"].toString()

            newLiveSearchResponse(
                channelname,
                LoadData(streamurl, channelname, posterurl, chGroup, nation).toJson(),
                type = TvType.Live
            ) {
                this.posterUrl = posterurl
                this.lang = nation
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val loadData = fetchDataFromUrlOrJson(url)
        val nation: String = if (loadData.group == "NSFW") {
            "⚠️🔞🔞🔞 » ${loadData.group} | ${loadData.nation} « 🔞🔞🔞⚠️"
        } else {
            "» ${loadData.group} | ${loadData.nation} «"
        }

        val kanallar = IptvPlaylistParser().parseM3U(app.get(mainUrl).text)
        val recommendations = mutableListOf<LiveSearchResponse>()

        for (kanal in kanallar.items) {
            if (kanal.attributes["group-title"].toString() == loadData.group) {
                val rcStreamUrl = kanal.url.toString()
                val rcChannelName = kanal.title.toString()
                if (rcChannelName == loadData.title) continue

                val rcPosterUrl = kanal.attributes["tvg-logo"].toString()
                val rcChGroup = kanal.attributes["group-title"].toString()
                val rcNation = kanal.attributes["tvg-country"].toString()

                recommendations.add(newLiveSearchResponse(
                    rcChannelName,
                    LoadData(rcStreamUrl, rcChannelName, rcPosterUrl, rcChGroup, rcNation).toJson(),
                    type = TvType.Live
                ) {
                    this.posterUrl = rcPosterUrl
                    this.lang = rcNation
                })
            }
        }

        return newLiveStreamLoadResponse(loadData.title, loadData.url, url) {
            this.posterUrl = loadData.poster
            this.plot = nation
            this.tags = listOf(loadData.group, loadData.nation)
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val loadData = fetchDataFromUrlOrJson(data)
        val kanallar = IptvPlaylistParser().parseM3U(app.get(mainUrl).text)
        val kanal = kanallar.items.firstOrNull { it.url == loadData.url }
        val headers = kanal?.headers ?: emptyMap()
        val referer = headers["referrer"] ?: ""

        callback.invoke(newExtractorLink(source = this.name, name = this.name, url = loadData.url, ExtractorLinkType.M3U8) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.headers = headers
        })

        return true
    }

    data class LoadData(val url: String, val title: String, val poster: String, val group: String, val nation: String)

    private suspend fun fetchDataFromUrlOrJson(data: String): LoadData {
        if (data.startsWith("{")) {
            return try {
                parseJson<LoadData>(data)
            } catch (e: Exception) {
                LoadData(data, this.name, "", "Genel", "TR")
            }
        } else {
            val kanallar = IptvPlaylistParser().parseM3U(app.get(mainUrl).text)
            val kanal = kanallar.items.firstOrNull { it.url == data }

            val streamurl = kanal?.url?.toString() ?: data
            val channelname = kanal?.title?.toString() ?: this.name
            val posterurl = kanal?.attributes?.get("tvg-logo")?.toString() ?: ""
            val chGroup = kanal?.attributes?.get("group-title")?.toString() ?: "Genel"
            val nation = kanal?.attributes?.get("tvg-country")?.toString() ?: "TR"

            return LoadData(streamurl, channelname, posterurl, chGroup, nation)
        }
    }
}

data class Playlist(
    val items: List<PlaylistItem> = emptyList()
)

data class PlaylistItem(
    val title: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val url: String? = null,
    val userAgent: String? = null
)

class IptvPlaylistParser {
    fun parseM3U(content: String): Playlist {
        return parseM3U(content.byteInputStream())
    }

    @Throws(PlaylistParserException::class)
    fun parseM3U(input: InputStream): Playlist {
        val reader = input.bufferedReader()
        val firstLine = reader.readLine()
        if (firstLine == null || !firstLine.startsWith("#EXTM3U")) {
            throw PlaylistParserException.InvalidHeader()
        }

        val playlistItems: MutableList<PlaylistItem> = mutableListOf()
        var currentIndex = 0
        var line: String? = reader.readLine()

        while (line != null) {
            if (line.isNotEmpty()) {
                if (line.startsWith("#EXTINF")) {
                    val title = line.split(",").lastOrNull()?.replace(""", "")?.trim()
                    val extInfRegex = Regex("(#EXTINF:.?[0-9]+)", RegexOption.IGNORE_CASE)
                    val attributesString = line.replace(extInfRegex, "").replace(""", "").trim().split(",").first()
                    val attributes = attributesString.split(Regex("\\s")).mapNotNull {
                        val pair = it.split("=")
                        if (pair.size == 2) pair.first() to pair.last().replace(""", "").trim() else null
                    }.toMap()

                    playlistItems.add(PlaylistItem(title, attributes))
                } else if (line.startsWith("#EXTVLCOPT")) {
                    if (currentIndex < playlistItems.size) {
                        val item = playlistItems[currentIndex]
                        val headers = item.headers.toMutableMap()
                        if (line.contains("http-user-agent=")) {
                            headers["user-agent"] = line.substringAfter("http-user-agent=").replace(""", "").trim()
                        }
                        if (line.contains("http-referrer=")) {
                            headers["referrer"] = line.substringAfter("http-referrer=").replace(""", "").trim()
                        }
                        playlistItems[currentIndex] = item.copy(headers = headers)
                    }
                } else {
                    if (!line.startsWith("#")) {
                        if (currentIndex < playlistItems.size) {
                            val item = playlistItems[currentIndex]
                            val cleanUrl = line.split("|").firstOrNull()?.replace(""", "")?.trim()
                            playlistItems[currentIndex] = item.copy(url = cleanUrl)
                            currentIndex++
                        }
                    }
                }
            }
            line = reader.readLine()
        }
        return Playlist(playlistItems)
    }
}

sealed class PlaylistParserException(message: String) : Exception(message) {
    class InvalidHeader : PlaylistParserException("Invalid file header. Header doesn't start with #EXTM3U")
}
