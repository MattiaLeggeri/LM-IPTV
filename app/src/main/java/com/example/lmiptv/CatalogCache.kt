package com.example.lmiptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class CatalogCache(
    val items: List<IptvItem>,
    val epg: Map<String, String>,
    val categories: Map<String, List<Category>>
)

private fun cacheName(url: String) = "catalog_${url.hashCode().toUInt()}.json.gz"

fun saveCatalogCache(context: Context, url: String, items: List<IptvItem>, epg: Map<String, String>, categories: Map<String, List<Category>>) {
    val root = JSONObject()
    root.put("savedAt", System.currentTimeMillis())
    root.put("items", JSONArray().also { array -> items.forEach { item -> array.put(JSONObject().apply {
        put("id", item.id); put("title", item.title); put("url", item.url); put("group", item.group); put("kind", item.kind)
        put("seriesId", item.seriesId); put("image", item.image); put("streamId", item.streamId); put("epgId", item.epgId)
    }) } })
    root.put("epg", JSONObject().also { value -> epg.forEach { (key, text) -> value.put(key, text) } })
    root.put("categories", JSONObject().also { value -> categories.forEach { (kind, list) -> value.put(kind, JSONArray().also { array -> list.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)) } }) } })
    GZIPOutputStream(context.openFileOutput(cacheName(url), Context.MODE_PRIVATE)).bufferedWriter().use { it.write(root.toString()) }
}

fun readCatalogCache(context: Context, url: String): CatalogCache? = runCatching {
    val root = GZIPInputStream(context.openFileInput(cacheName(url))).bufferedReader().use { JSONObject(it.readText()) }
    val itemArray = root.getJSONArray("items")
    val items = List(itemArray.length()) { index -> itemArray.getJSONObject(index).let { row -> IptvItem(
        row.optString("id"), row.optString("title"), row.optString("url"), row.optString("group"), row.optString("kind"),
        row.optString("seriesId").takeIf { it.isNotBlank() && it != "null" }, row.optString("image"), row.optString("streamId"), row.optString("epgId")
    ) } }
    val epgObject = root.optJSONObject("epg") ?: JSONObject()
    val epg = epgObject.keys().asSequence().associateWith { epgObject.optString(it) }
    val categoryObject = root.optJSONObject("categories") ?: JSONObject()
    val categories = categoryObject.keys().asSequence().associateWith { kind ->
        val array = categoryObject.getJSONArray(kind)
        List(array.length()) { index -> array.getJSONObject(index).let { Category(it.optString("id"), it.optString("name")) } }
    }
    CatalogCache(items, epg, categories)
}.getOrNull()

fun deleteCatalogCache(context: Context, url: String) {
    runCatching { context.deleteFile(cacheName(url)) }
}
