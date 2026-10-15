package com.cloudstream.anthologycloudstream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SonAnimePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SonAnime())
    }
}
