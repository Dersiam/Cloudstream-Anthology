@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

open class HDPlayerSystem : ExtractorApi() {
    override val name            = "HDPlayerSystem"
    override val mainUrl         = "https://hdplayersystem.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef  = referer ?: ""
        val vidId   = if (url.contains("video/")) {
            url.substringAfter("video/").substringBefore("?")
        } else {
            url.substringAfter("?data=").substringBefore("&")
        }
        val baseUrl = if (url.contains("hdplayersystem.")) {
            url.substringBefore("/video").substringBefore("/player")
        } else {
            mainUrl
        }
        val postUrl = "${baseUrl}/player/index.php?data=${vidId}&do=getVideo"
        Log.d("Kekik_${this.name}", "postUrl » $postUrl")

        try {
            val response = app.post(
                postUrl,
                data = mapOf(
                    "hash" to vidId,
                    "r"    to extRef
                ),
                referer = extRef,
                headers = mapOf(
                    "Content-Type"     to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer"          to extRef
                )
            ).text

            var m3uLink: String? = null
            try {
                val json = JSONObject(response)
                m3uLink = json.optString("securedLink").ifEmpty { json.optString("videoSource") }
            } catch (e: Exception) {
                m3uLink = Regex("""(?:securedLink|videoSource)"\s*:\s*"([^"]+)""").find(response)?.groupValues?.get(1)?.replace("\\/", "/")
            }

            if (!m3uLink.isNullOrEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        source  = this.name,
                        name    = this.name,
                        url     = m3uLink,
                        type    = ExtractorLinkType.M3U8
                    ) {
                        this.referer = extRef
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("HDPlayerSystem", "Error extracting", e)
        }
    }
}