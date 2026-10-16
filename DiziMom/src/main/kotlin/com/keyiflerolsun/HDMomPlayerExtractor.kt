@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.helper.AesHelper

open class HDMomPlayer : ExtractorApi() {
    override val name            = "HDMomPlayer"
    override val mainUrl         = "https://hdmomplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val m3uLink: String?
        val extRef  = referer ?: ""
        val iSource = app.get(url, referer=extRef).text

        val bePlayer = Regex("""bePlayer\('([^']+)',\s*'(\{[^}]+\})'\);""").find(iSource)?.groupValues
        if (bePlayer != null) {
            val bePlayerPass = bePlayer[1]
            val bePlayerData = bePlayer[2]
            val encrypted    = AesHelper.cryptoAESHandler(bePlayerData, bePlayerPass.toByteArray(), false)?.replace("\\", "") ?: throw ErrorLoadingException("failed to decrypt")
            Log.d("Kekik_${this.name}", "encrypted » $encrypted")

            m3uLink = Regex("""video_location":"([^"]+)""").find(encrypted)?.groupValues?.get(1)
        } else {
            m3uLink = Regex("""file:"([^"]+)""").find(iSource)?.groupValues?.get(1)

            val trackRegex = Regex("""["']file["']\s*:\s*["']([^"']+)["'].*?["']label["']\s*:\s*["']([^"']+)["']""")
            trackRegex.findAll(iSource).forEach { match ->
                val trackFile = match.groupValues[1]
                val trackLabel = match.groupValues[2]
                if (!trackLabel.contains("Forced")) {
                    subtitleCallback.invoke(
                        SubtitleFile(
                            lang = trackLabel,
                            url  = fixUrl(mainUrl + trackFile)
                        )
                    )
                }
            }
        }

        if (m3uLink != null) {
            callback.invoke(
                newExtractorLink(
                    source  = this.name,
                    name    = this.name,
                    url     = m3uLink,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.P1080.value
                }
            )
        }
    }
}