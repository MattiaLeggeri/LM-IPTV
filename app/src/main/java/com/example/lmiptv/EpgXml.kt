package com.example.lmiptv

import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

fun loadBestCurrentPrograms(x: Xtream, channels: List<IptvItem>): Map<String, String> {
    val sources = listOfNotNull(discoverEpgUrl(x), standardEpgUrl(x)).distinct()
    var xml = emptyMap<String, String>()
    for (source in sources) {
        xml = runCatching { loadXmlTvCurrent(x, channels, source) }.getOrDefault(emptyMap())
        if (xml.isNotEmpty()) break
    }
    return if (xml.isNotEmpty()) {
        xml + loadCurrentPrograms(x, channels.filterNot { xml.containsKey(it.id) }.take(20))
    } else loadCurrentPrograms(x, channels)
}

private fun standardEpgUrl(x: Xtream) = "${x.server}/xmltv.php?username=${Uri.encode(x.username)}&password=${Uri.encode(x.password)}"

private fun discoverEpgUrl(x: Xtream): String? {
    if (x.playlistUrl.isBlank()) return null
    return runCatching {
        val connection = URL(x.playlistUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "LMIPTV-FireTV/2.4")
        val firstLine = connection.inputStream.bufferedReader().use { it.readLine().orEmpty() }
        connection.disconnect()
        val match = Regex("(?:url-tvg|x-tvg-url|tvg-url)=[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE).find(firstLine)
        val raw = match?.groupValues?.getOrNull(1)?.replace("&amp;", "&")?.substringBefore('|')?.trim()
        raw?.takeIf { it.isNotBlank() }?.let { URL(URL(x.server), it).toString() }
    }.getOrNull()
}

private fun loadXmlTvCurrent(x: Xtream, channels: List<IptvItem>, address: String): Map<String, String> {
    val wanted = buildMap {
        channels.forEach { channel ->
            put(channel.streamId, channel)
            if (channel.epgId.isNotBlank()) put(channel.epgId, channel)
        }
    }
    val result = linkedMapOf<String, String>()
    val byName = channels.associateBy { normalizeChannelName(it.title) }
    val xmlChannels = linkedMapOf<String, IptvItem>()
    val connection = URL(address).openConnection() as HttpURLConnection
    connection.connectTimeout = 20_000
    connection.readTimeout = 120_000
    connection.setRequestProperty("User-Agent", "LMIPTV-FireTV/2.4")
    connection.setRequestProperty("Accept-Encoding", "gzip")
    val buffered = BufferedInputStream(connection.inputStream).apply { mark(4) }
    val first = buffered.read()
    val second = buffered.read()
    buffered.reset()
    val source = if (connection.contentEncoding?.contains("gzip", true) == true || (first == 0x1f && second == 0x8b)) GZIPInputStream(buffered) else buffered
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    parser.setInput(source, "UTF-8")
    val now = System.currentTimeMillis()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT && result.size < wanted.size) {
        if (event == XmlPullParser.START_TAG && parser.name == "channel") {
            val xmlId = parser.getAttributeValue(null, "id").orEmpty()
            var displayName = ""
            var inner = parser.next()
            while (!(inner == XmlPullParser.END_TAG && parser.name == "channel")) {
                if (inner == XmlPullParser.START_TAG && parser.name == "display-name" && displayName.isBlank()) displayName = parser.nextText()
                inner = parser.next()
            }
            val matched = wanted[xmlId] ?: byName[normalizeChannelName(displayName)]
            if (matched != null) xmlChannels[xmlId] = matched
        } else if (event == XmlPullParser.START_TAG && parser.name == "programme") {
            val xmlId = parser.getAttributeValue(null, "channel")
            val item = wanted[xmlId] ?: xmlChannels[xmlId]
            val start = parseXmlTvTime(parser.getAttributeValue(null, "start"))
            val stop = parseXmlTvTime(parser.getAttributeValue(null, "stop"))
            if (item != null && now in start..stop) {
                var title = ""
                var inner = parser.next()
                while (!(inner == XmlPullParser.END_TAG && parser.name == "programme")) {
                    if (inner == XmlPullParser.START_TAG && parser.name == "title") title = parser.nextText()
                    inner = parser.next()
                }
                if (title.isNotBlank()) result[item.id] = packEpg(title, start, stop)
            }
        }
        event = parser.next()
    }
    source.close()
    connection.disconnect()
    return result
}

private fun normalizeChannelName(value: String): String = value.lowercase(Locale.ROOT)
    .replace(Regex("\\b(full\\s*hd|fhd|uhd|4k|hd|sd|hevc|h265|raw)\\b"), "")
    .replace(Regex("[^a-z0-9]"), "")

private fun parseXmlTvTime(value: String?): Long = try {
    val clean = value?.trim().orEmpty()
    SimpleDateFormat(if (clean.contains(' ')) "yyyyMMddHHmmss Z" else "yyyyMMddHHmmss", Locale.US).parse(clean)?.time ?: 0
} catch (_: Exception) { 0 }

fun packEpg(title: String, startMillis: Long = 0, stopMillis: Long = 0): String {
    if (startMillis <= 0 || stopMillis <= 0) return title
    val formatter = SimpleDateFormat("HH:mm", Locale.ITALY)
    return "$title\u001F${formatter.format(startMillis)} - ${formatter.format(stopMillis)}"
}

fun epgTitle(value: String?): String? = value?.substringBefore('\u001F')?.takeIf { it.isNotBlank() }
fun epgTime(value: String?): String? = value?.substringAfter('\u001F', "")?.takeIf { it.isNotBlank() }
