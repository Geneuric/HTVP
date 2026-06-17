@file:Suppress("UnstableApiUsage")

version = 1

android {
    defaultConfig {
        android.buildFeatures.buildConfig = true
    }
}

cloudstream {
    language = "hi"
    requiresResources = false
    description = "HTVP Low-End Optimized DRM Hindi Extension"
    authors = listOf("Saksham")
    
    status = 1
    tvTypes = listOf(
        "Live",
    )

    isCrossPlatform = false
}