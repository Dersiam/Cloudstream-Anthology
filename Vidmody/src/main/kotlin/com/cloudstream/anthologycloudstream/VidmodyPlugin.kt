package com.cloudstream.anthologycloudstream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class VidmodyPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Vidmody())
    }
}
