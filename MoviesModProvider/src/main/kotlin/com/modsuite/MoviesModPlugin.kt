package com.modsuite

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MoviesModPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MoviesModProvider())
    }
}
