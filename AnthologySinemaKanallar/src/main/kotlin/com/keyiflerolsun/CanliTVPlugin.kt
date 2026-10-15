@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.keyiflerolsun.anthologysinemakanallar

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnthologySinemaKanallarPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnthologySinemaKanallar())
    }
}