@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.keyiflerolsun.anthologycanlspor

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnthologyCanlSporPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnthologyCanlSpor())
    }
}