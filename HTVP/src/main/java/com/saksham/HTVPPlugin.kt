package com.saksham.htvp

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class HTVPPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(HTVP())
    }
}