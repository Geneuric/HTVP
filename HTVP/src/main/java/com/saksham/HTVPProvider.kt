package com.saksham.htvp

import android.util.Base64
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HTVP : MainAPI() {
    override var lang = "hi"
    override var mainUrl: String = base64Decode("aHR0cHM6Ly9ob3N0LmNsb3VkcGxheS5tZQ==")
    override var name = "HTVP"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    companion object {
        private val LOGO_REGEX = Regex("""tvg-logo="([^"]+)"""")
        private val GROUP_REGEX = Regex("""group-title="([^"]+)"""")
        private val KEY_REGEX = Regex("""(?i)key="?([^"&]+)""")
        private val KEYID_REGEX = Regex("""(?i)keyid="?([^"&]+)""")
        private val UA_REGEX = Regex("""(?i)User-Agent=([^&]+)""")
        private val REF_REGEX = Regex("""(?i)Referer=([^&]+)""")
        private val CENC_REGEX = Regex("""cenc:default_KID=["']([0-9a-fA-F\-]{36})["']""")
        private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")
        
        private val STRICT_KEYWORDS = setOf(
            "hindi", "india", "star", "zee", "sony", "colors", "ndtv", "aaj tak", 
            "abp", "republic", "pogo", "dangal", "b4u", "shemaroo", "&pictures", 
            "&tv", "and pictures", "cineplex", "filamchi", "bhojpuri", "punjabi"
        )
        
        private val API_HEADERS = mapOf(
            "Connection" to "Keep-Alive",
            "User-Agent" to "okhttp/4.12.0",
            "X-Package" to base64Decode("Y29tLmNsb3VkcGxheS5hcHA=")
        )
    }

    private fun isTargetChannel(name: String?, group: String?): Boolean {
        val combined = "${name.orEmpty()} ${group.orEmpty()}".lowercase()
        return STRICT_KEYWORDS.any { combined.contains(it) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val req = app.get("$mainUrl/app.php", headers = API_HEADERS)
        val res = req.parsedSafe<HTVPResponse>() ?: throw Error("Connection Failed")
        val decryptedJson = decryptPayload(res.payload, res.iv)
        val streams = parseJson<HTVPStreams>(decryptedJson).streams

        val homePageLists = streams.amap { stream ->
            val shows = fetchChannels(stream.url, stream.logo)
            HomePageList(stream.name ?: "Unknown", shows, isHorizontalImages = true)
        }.filter { it.list.isNotEmpty() }

        return newHomePageResponse(homePageLists)
    }

    private suspend fun fetchChannels(url: String, fallbackLogo: String?): List<SearchResponse> {
        val shows = mutableListOf<SearchResponse>()
        val headers = if (url.contains(base64Decode("aG9zdC5jbG91ZHBsYXkubWU="))) API_HEADERS else emptyMap()
        val resText = app.get(url, headers = headers).text
        if (resText.isBlank()) return shows

        if (resText.startsWith("#EXTM3U")) {
            return parseM3u(resText).map { channel ->
                newLiveSearchResponse(channel.name ?: "Unknown", channel.toJson(), TvType.Live) {
                    this.posterUrl = channel.logo?.takeIf { it.isNotEmpty() } ?: fallbackLogo.orEmpty()
                }
            }
        }

        try {
            val channels = parseJson<List<HTVPChannel>>(resText)
            if (channels.isNotEmpty() && (channels[0].m3u8_url != null || channels[0].mpd_url != null)) {
                return channels.filter { isTargetChannel(it.name, it.group) }.map { channel ->
                    newLiveSearchResponse(channel.name ?: "Unknown", channel.toJson(), TvType.Live) {
                        this.posterUrl = channel.logo ?: fallbackLogo.orEmpty()
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            val subStreams = parseJson<List<HTVPStream>>(resText)
            if (subStreams.isNotEmpty()) {
                shows.addAll(subStreams.amap { subStream ->
                    fetchChannels(subStream.url, subStream.logo ?: fallbackLogo)
                }.flatten())
            }
        } catch (_: Exception) {}

        return shows
    }

    private fun parseM3u(m3uText: String): List<HTVPChannel> {
        val channels = mutableListOf<HTVPChannel>()
        var cName = ""; var cLogo = ""; var cGroup = ""; var cKey = ""; var cKeyId = ""
        var cUserAgent = ""; var cReferer = ""

        m3uText.lineSequence().map { it.trim() }.forEach { l ->
            when {
                l.startsWith("#EXTINF:") -> {
                    cName = l.substringAfterLast(",").trim()
                    cLogo = LOGO_REGEX.find(l)?.groupValues?.get(1).orEmpty()
                    cGroup = GROUP_REGEX.find(l)?.groupValues?.get(1).orEmpty()
                    cKey = KEY_REGEX.find(l)?.groupValues?.get(1).orEmpty()
                    cKeyId = KEYID_REGEX.find(l)?.groupValues?.get(1).orEmpty()
                }
                l.startsWith("#EXTVLCOPT:") -> {
                    val opt = l.substringAfter(":")
                    if (opt.startsWith("http-user-agent=")) cUserAgent = opt.substringAfter("=")
                    else if (opt.startsWith("http-referrer=")) cReferer = opt.substringAfter("=")
                }
                !l.startsWith("#") && l.isNotEmpty() -> {
                    if (isTargetChannel(cName, cGroup)) {
                        val urlParts = l.split("|")
                        val rawUrl = urlParts[0]
                        val params = urlParts.getOrNull(1).orEmpty()

                        val urlUserAgent = UA_REGEX.find(params)?.groupValues?.get(1) ?: cUserAgent
                        val urlReferer = REF_REGEX.find(params)?.groupValues?.get(1) ?: cReferer
                        val urlKey = KEY_REGEX.find(params)?.groupValues?.get(1) ?: cKey
                        val urlKeyId = KEYID_REGEX.find(params)?.groupValues?.get(1) ?: cKeyId

                        val type = if (rawUrl.contains(".mpd")) "dash" else "hls"
                        val licenseUrl = if (urlKey.isNotEmpty() && urlKeyId.isNotEmpty()) "https://dummy.com/?keyid=$urlKeyId&key=$urlKey" else ""

                        val headersMap = mutableMapOf<String, String>()
                        if (urlUserAgent.isNotEmpty()) headersMap["User-Agent"] = urlUserAgent
                        if (urlReferer.isNotEmpty()) headersMap["Referer"] = urlReferer

                        channels.add(HTVPChannel(
                            type = type, id = null, name = cName, group = cGroup, logo = cLogo,
                            user_agent = urlUserAgent, m3u8_url = if (type == "hls") rawUrl else null,
                            mpd_url = if (type == "dash") rawUrl else null, license_url = licenseUrl, headers = headersMap
                        ))
                    }
                    cName = ""; cLogo = ""; cGroup = ""; cKey = ""; cKeyId = ""; cUserAgent = ""; cReferer = ""
                }
            }
        }
        return channels
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get("$mainUrl/app.php", headers = API_HEADERS).parsedSafe<HTVPResponse>() ?: return emptyList()
        val streams = parseJson<HTVPStreams>(decryptPayload(res.payload, res.iv)).streams
        return streams.amap { fetchChannels(it.url, it.logo) }.flatten().filter { it.name.contains(query, true) }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<HTVPChannel>(url)
        return newLiveStreamLoadResponse(data.name ?: "Unknown", url, url) {
            this.posterUrl = data.logo.orEmpty()
            this.plot = data.group
        }
    }

    private fun String.hexToBase64Url(): String {
        val normalizedHex = trim().replace("-", "")
        if (normalizedHex.isEmpty() || normalizedHex.length % 2 != 0 || !HEX_REGEX.matches(normalizedHex)) return this
        return try {
            val bytes = normalizedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (_: Exception) { this }
    }

    private suspend fun getDRMKeysFromLicenseServer(url: String, kid: String): String {
        return try {
            val responseString = app.post(
                url,
                headers = mapOf("User-Agent" to "Dalvik/2.1.0 (Linux; U; Android)", "Content-Type" to "application/json;charset=UTF-8"),
                json = mapOf("kids" to listOf(kid), "type" to "temporary")
            ).text
            val jsonResponse = parseJson<Map<String, Any>>(responseString)
            @Suppress("UNCHECKED_CAST")
            val keys = jsonResponse["keys"] as? List<Map<String, String>> ?: return ""
            keys.firstOrNull()?.get("k").orEmpty()
        } catch (_: Exception) { "" }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val channel = parseJson<HTVPChannel>(data)

        if (channel.mpd_url != null) {
            val licenseUrl = channel.license_url.orEmpty()
            var keyStr = ""; var kidStr = ""

            if (licenseUrl.contains("keyid=") && licenseUrl.contains("key=")) {
                kidStr = KEYID_REGEX.find(licenseUrl)?.groupValues?.get(1)?.hexToBase64Url().orEmpty()
                keyStr = KEY_REGEX.find(licenseUrl)?.groupValues?.get(1)?.hexToBase64Url().orEmpty()
            } else if (licenseUrl.isNotEmpty()) {
                val mpdStr = app.get(channel.mpd_url, headers = channel.headers ?: emptyMap()).text
                val drmKid = CENC_REGEX.find(mpdStr)?.groupValues?.get(1) ?: UUID.randomUUID().toString()
                kidStr = drmKid.hexToBase64Url()
                keyStr = getDRMKeysFromLicenseServer(licenseUrl, kidStr)
            }

            callback.invoke(
                newDrmExtractorLink(
                    this.name, channel.name ?: "DASH", channel.mpd_url, INFER_TYPE,
                    if (kidStr.isNotEmpty() && keyStr.isNotEmpty()) CLEARKEY_UUID else UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
                ) {
                    channel.headers?.let { this.headers = it }
                    if (kidStr.isNotEmpty() && keyStr.isNotEmpty()) {
                        this.kid = kidStr; this.key = keyStr
                    } else if (licenseUrl.isNotEmpty()) {
                        this.licenseUrl = licenseUrl
                    }
                }
            )
        } else if (channel.m3u8_url != null) {
            callback.invoke(
                newExtractorLink(
                    this.name, channel.name ?: "HLS", channel.m3u8_url,
                    if (channel.m3u8_url.contains(".ts", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                ) {
                    channel.headers?.let { 
                        this.headers = it 
                        it.forEach { (k, v) -> if (k.equals("referer", true)) this.referer = v }
                    }
                }
            )
        }
        return true
    }

    private fun decryptPayload(payloadBase64: String, ivBase64: String): String {
        val keyHash = MessageDigest.getInstance("SHA-256").digest((base64Decode("YmFja3VwLXVwZGF0ZS0zLjM=") + base64Decode("Y29tLmNsb3VkcGxheS5hcHA=")).toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyHash, "AES"), IvParameterSpec(base64DecodeArray(ivBase64)))
        return String(cipher.doFinal(base64DecodeArray(payloadBase64)), Charsets.UTF_8)
    }

    data class HTVPResponse(val payload: String, val iv: String, val expires: Long?)
    data class HTVPStreams(val streams: List<HTVPStream>)
    data class HTVPStream(val name: String?, val url: String, val logo: String?)
    data class HTVPChannel(
        val type: String?, val id: String?, val name: String?, val group: String?, val logo: String?,
        val user_agent: String?, val m3u8_url: String?, val mpd_url: String?, val license_url: String?, val headers: Map<String, String>?
    )
}