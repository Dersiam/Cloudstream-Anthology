package com.cloudstream.anthologycloudstream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnthologyCanlSporPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnthologyCanlSpor())
    }
}
