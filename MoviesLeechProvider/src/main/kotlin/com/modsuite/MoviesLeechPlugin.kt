package com.modsuite

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MoviesLeechPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MoviesLeechProvider())
    }
}
