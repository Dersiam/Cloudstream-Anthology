@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class SetPlay : ExtractorApi() {
    override val name            = "SetPlay"
    override val mainUrl         = "https://setplay.shop"
    override val requiresReferer = true

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

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef = referer ?: ""
        val iSource = app.get(url, referer = extRef).text

        val spgMatch = Regex("""SPG\.cerceve\("b2","([^"]+)","([^"]+)"\)""").find(iSource)
        if (spgMatch != null) {
            val decUrl = xorDecrypt(spgMatch.groupValues[1], spgMatch.groupValues[2])
            if (decUrl.startsWith("http")) {
                val fpHtml = app.get(decUrl, referer = url).text
                val kopruMatch = Regex("""window\.STF_KOPRU\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(fpHtml)
                if (kopruMatch != null) {
                    val kopruJsonStr = kopruMatch.groupValues[1].replace("\t", "").replace("\n", "")
                    val src = Regex("""src:\s*"([^"]+)"""").find(kopruJsonStr)?.groupValues?.get(1)
                    if (src != null) {
                        val streamUrl = if (src.startsWith("http")) src else "https://fastplay.mom$src"
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} - 1080p",
                                url = streamUrl,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://fastplay.mom/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                    Regex("""\{"file":"([^"]+)","label":"([^"]+)"""").findAll(kopruJsonStr).forEach { subMatch ->
                        val file = subMatch.groupValues[1].replace("\\/", "/")
                        val label = subMatch.groupValues[2]
                        subtitleCallback(SubtitleFile(label, file))
                    }
                    return
                }
            }
        }

        // Fallback for legacy format
        val videoUrl = Regex("""videoUrl":"([^",]+)""").find(iSource)?.groupValues?.get(1)
        val videoServer = Regex("""videoServer":"([^",]+)""").find(iSource)?.groupValues?.get(1)
        if (videoUrl != null && videoServer != null) {
            val m3uLink = "${mainUrl}${videoUrl.replace("\\", "")}?s=${videoServer}"
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = m3uLink,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }
}