package app.brootlite.tv.data

import android.content.Context
import app.brootlite.tv.BrootLiteApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLEncoder

object NativeTorrentSearch {
    private val json = AppPrefs.json

    suspend fun searchAll(
        context: Context,
        query: String,
        category: String = "all"
    ): List<StreamCandidate> = withContext(Dispatchers.IO) {
        coroutineScope {
            val trimmed = query.trim()
            val cfg = AppPrefs.getTorrentConfig(context)

            if (trimmed.isBlank()) {
                return@coroutineScope when (category) {
                    "public_domain" -> withTimeoutOrNull(12_000L) {
                        fetchInternetArchive("", category = "feature_films")
                    }.orEmpty()
                    "rss" -> {
                        val customSites = AppPrefs.readCustomSites(context)
                        val tasks = customSites.map { (title, url, _) ->
                            async { withTimeoutOrNull(8_000L) { fetchCustomRssFeed(url, title, "") }.orEmpty() }
                        }
                        dedupAndSort(tasks.awaitAll())
                    }
                    "torznab" -> {
                        if (cfg.torznabUrl.isNotBlank()) {
                            withTimeoutOrNull(10_000L) { fetchTorznab(cfg.torznabUrl, cfg.torznabApiKey, "") }.orEmpty()
                        } else emptyList()
                    }
                    else -> { // "all"
                        val archive = async { withTimeoutOrNull(10_000L) { fetchInternetArchive("", category = "feature_films") }.orEmpty() }
                        val torznab = async {
                            if (cfg.torznabUrl.isNotBlank()) {
                                withTimeoutOrNull(10_000L) { fetchTorznab(cfg.torznabUrl, cfg.torznabApiKey, "") }.orEmpty()
                            } else emptyList()
                        }
                        val rssTasks = AppPrefs.readCustomSites(context).map { (title, url, _) ->
                            async { withTimeoutOrNull(8_000L) { fetchCustomRssFeed(url, title, "") }.orEmpty() }
                        }
                        val allBatches = mutableListOf<List<StreamCandidate>>()
                        allBatches.add(archive.await())
                        allBatches.add(torznab.await())
                        allBatches.addAll(rssTasks.awaitAll())
                        dedupAndSort(allBatches)
                    }
                }
            }

            val tasks = buildList {
                when (category) {
                    "public_domain" -> {
                        add(async { withTimeoutOrNull(12_000L) { fetchInternetArchive(trimmed, category = "feature_films") }.orEmpty() })
                    }
                    "rss" -> {
                        for ((title, url, _) in AppPrefs.readCustomSites(context)) {
                            add(async { withTimeoutOrNull(8_000L) { fetchCustomRssFeed(url, title, trimmed) }.orEmpty() })
                        }
                    }
                    "torznab" -> {
                        if (cfg.torznabUrl.isNotBlank()) {
                            add(async { withTimeoutOrNull(10_000L) { fetchTorznab(cfg.torznabUrl, cfg.torznabApiKey, trimmed) }.orEmpty() })
                        }
                    }
                    else -> { // "all"
                        add(async { withTimeoutOrNull(12_000L) { fetchInternetArchive(trimmed, category = "feature_films") }.orEmpty() })
                        if (cfg.torznabUrl.isNotBlank()) {
                            add(async { withTimeoutOrNull(10_000L) { fetchTorznab(cfg.torznabUrl, cfg.torznabApiKey, trimmed) }.orEmpty() })
                        }
                        for ((title, url, _) in AppPrefs.readCustomSites(context)) {
                            add(async { withTimeoutOrNull(8_000L) { fetchCustomRssFeed(url, title, trimmed) }.orEmpty() })
                        }
                    }
                }
            }

            dedupAndSort(tasks.awaitAll())
        }
    }

    private fun dedupAndSort(batches: List<List<StreamCandidate>>): List<StreamCandidate> {
        val dedup = LinkedHashMap<String, StreamCandidate>()
        for (batch in batches) {
            for (item in batch) {
                val key = item.infoHash?.lowercase() ?: item.url ?: item.externalUrl ?: continue
                val prev = dedup[key]
                if (prev == null || item.seeders > prev.seeders) {
                    dedup[key] = item
                }
            }
        }
        return dedup.values.sortedByDescending { it.seeders }
    }

    suspend fun searchImdbSwarm(
        imdbId: String,
        season: Int? = null,
        episode: Int? = null
    ): List<StreamCandidate> = withContext(Dispatchers.IO) {
        val cleanImdb = imdbId.substringBefore(':').trim()
        if (!cleanImdb.startsWith("tt", ignoreCase = true)) return@withContext emptyList()
        coroutineScope {
            val archiveJob = async {
                withTimeoutOrNull(8_500L) {
                    fetchInternetArchive(cleanImdb, category = "movies")
                }.orEmpty()
            }
            dedupAndSort(listOf(archiveJob.await()))
        }
    }

    private fun fetchInternetArchive(query: String, category: String = "movies"): List<StreamCandidate> {
        val trimmed = query.trim()
        val qParam = if (trimmed.isBlank()) "mediatype:movies+AND+collection:$category" else "${URLEncoder.encode(trimmed, "UTF-8")}+AND+mediatype:movies"
        val url = "https://archive.org/advancedsearch.php?q=$qParam&fl[]=identifier,title,item_size&output=json&rows=15"
        val clients = listOf(BrootLiteApp.sharedHttpClient, BrootLiteApp.sharedPermissiveHttpClient)
        for (client in clients) {
            val items = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching emptyList()
                    val root = json.parseToJsonElement(resp.body?.string() ?: "").jsonObject
                    val docs = root["response"]?.jsonObject?.get("docs") as? JsonArray ?: return@runCatching emptyList()
                    val out = mutableListOf<StreamCandidate>()
                    for (dEl in docs) {
                        val d = dEl as? JsonObject ?: continue
                        val id = d["identifier"]?.jsonPrimitive?.contentOrNull ?: continue
                        val title = d["title"]?.jsonPrimitive?.contentOrNull ?: id
                        val sizeBytes = d["item_size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                        val sizeFmt = formatBytes(sizeBytes)
                        val torrentUrl = "https://archive.org/download/$id/${id}_archive.torrent"
                        out.add(
                            StreamCandidate(
                                addonName = "InternetArchive",
                                name = "InternetArchive · Public Domain",
                                title = "$title\n👤 25  💾 $sizeFmt  ⚙️ InternetArchive",
                                url = torrentUrl,
                                sources = StreamCandidate.DEFAULT_PUBLIC_TRACKERS,
                                bingeGroup = "archive:$id",
                                filename = "$title.torrent",
                                seeders = 25,
                                sizeLabel = sizeFmt
                            )
                        )
                    }
                    out
                }
            }.getOrDefault(emptyList())
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    fun fetchCustomRssFeed(url: String, label: String, query: String): List<StreamCandidate> {
        val clients = listOf(BrootLiteApp.sharedHttpClient, BrootLiteApp.sharedPermissiveHttpClient)
        for (client in clients) {
            val parsed = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching emptyList()
                    val xml = resp.body?.string() ?: return@runCatching emptyList()
                    parseRssXml(xml, label, query)
                }
            }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    internal fun parseRssXml(xml: String, sourceLabel: String, query: String): List<StreamCandidate> {
        val results = mutableListOf<StreamCandidate>()
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var inItem = false
        var currentTag = ""
        val textBuf = StringBuilder()
        var title = ""
        var linkUrl = ""
        var infoHash = ""
        var seeders = 10
        var sizeStr = ""

        val qLower = query.lowercase()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name ?: ""
                    if (tag.equals("item", ignoreCase = true)) {
                        inItem = true
                        title = ""
                        linkUrl = ""
                        infoHash = ""
                        seeders = 10
                        sizeStr = ""
                        currentTag = ""
                        textBuf.setLength(0)
                    } else if (inItem) {
                        currentTag = tag.lowercase()
                        textBuf.setLength(0)
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    if (inItem && currentTag.isNotEmpty()) {
                        parser.text?.let { textBuf.append(it) }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = (parser.name ?: "").lowercase()
                    if (tag == "item") {
                        inItem = false
                        if (qLower.isBlank() || title.lowercase().contains(qLower)) {
                            val resolvedHash = infoHash.takeIf { it.length == 40 }
                                ?: Regex("""btih:([0-9a-fA-F]{40})""").find(linkUrl)?.groupValues?.getOrNull(1)?.lowercase()
                                ?: ""
                            val directUrl = linkUrl.takeIf { it.startsWith("http") && !it.contains("magnet:") }
                            val magnetUrl = linkUrl.takeIf { it.startsWith("magnet:") }
                            if (resolvedHash.length == 40 || magnetUrl != null || directUrl != null) {
                                results.add(
                                    StreamCandidate(
                                        addonName = sourceLabel,
                                        name = "$sourceLabel · RSS",
                                        title = "$title\n👤 $seeders  💾 $sizeStr  ⚙️ $sourceLabel",
                                        url = magnetUrl ?: directUrl,
                                        infoHash = resolvedHash.takeIf { it.length == 40 },
                                        sources = StreamCandidate.DEFAULT_PUBLIC_TRACKERS,
                                        filename = title,
                                        seeders = seeders,
                                        sizeLabel = sizeStr
                                    )
                                )
                            }
                        }
                    } else if (inItem && currentTag.isNotEmpty()) {
                        val txt = textBuf.toString().trim()
                        if (txt.isNotEmpty()) {
                            when {
                                currentTag == "title" -> title = txt
                                currentTag == "link" -> linkUrl = txt
                                currentTag.endsWith("infohash") -> infoHash = txt.lowercase()
                                currentTag.endsWith("seeders") -> seeders = txt.toIntOrNull() ?: seeders
                                currentTag.endsWith("size") -> sizeStr = txt
                            }
                        }
                        currentTag = ""
                        textBuf.setLength(0)
                    }
                }
            }
            event = parser.next()
        }
        return results.sortedByDescending { it.seeders }.take(40)
    }

    private fun fetchTorznab(endpointUrl: String, apiKey: String, query: String): List<StreamCandidate> {
        return runCatching {
            val sep = if (endpointUrl.contains('?')) "&" else "?"
            val encQ = URLEncoder.encode(query, "UTF-8")
            val keyParam = if (apiKey.isNotBlank()) "&apikey=${URLEncoder.encode(apiKey, "UTF-8")}" else ""
            val fullUrl = "${endpointUrl.trimEnd('/')}${sep}t=search&q=$encQ$keyParam"
            val req = Request.Builder().url(fullUrl).get().build()
            BrootLiteApp.sharedPermissiveHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyList()
                val xml = resp.body?.string() ?: return@runCatching emptyList()
                parseTorznabXml(xml)
            }
        }.getOrDefault(emptyList())
    }

    private fun parseTorznabXml(xml: String): List<StreamCandidate> {
        val out = mutableListOf<StreamCandidate>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        var inItem = false
        var tag = ""
        val textBuf = StringBuilder()
        var title = ""
        var link = ""
        var sizeBytes = 0L
        var seeders = 0
        var peers = 0
        var infoHash = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name ?: ""
                    if (name.equals("item", true)) {
                        inItem = true
                        title = ""
                        link = ""
                        sizeBytes = 0L
                        seeders = 0
                        peers = 0
                        infoHash = ""
                        tag = ""
                        textBuf.setLength(0)
                    } else if (inItem) {
                        tag = name.lowercase()
                        textBuf.setLength(0)
                        if (tag.endsWith("attr")) {
                            val attrName = parser.getAttributeValue(null, "name")?.lowercase() ?: ""
                            val attrVal = parser.getAttributeValue(null, "value") ?: ""
                            when (attrName) {
                                "seeders" -> seeders = attrVal.toIntOrNull() ?: seeders
                                "peers" -> peers = attrVal.toIntOrNull() ?: peers
                                "infohash" -> infoHash = attrVal.lowercase()
                                "magneturl" -> if (attrVal.startsWith("magnet:")) link = attrVal
                                "size" -> sizeBytes = attrVal.toLongOrNull() ?: sizeBytes
                            }
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    if (inItem && tag.isNotEmpty()) {
                        parser.text?.let { textBuf.append(it) }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("item", true)) {
                        inItem = false
                        val hasMagnet = link.startsWith("magnet:", true)
                        if (infoHash.isNotBlank() || hasMagnet) {
                            val sizeFmt = formatBytes(sizeBytes)
                            out.add(
                                StreamCandidate(
                                    addonName = "Torznab",
                                    name = "Torznab · P2P",
                                    title = "$title\n👤 $seeders  📥 $peers  💾 $sizeFmt",
                                    url = if (hasMagnet) link else null,
                                    infoHash = infoHash.takeIf { it.isNotBlank() },
                                    sources = StreamCandidate.DEFAULT_PUBLIC_TRACKERS,
                                    filename = title,
                                    seeders = seeders,
                                    leechers = peers,
                                    sizeLabel = sizeFmt
                                )
                            )
                        }
                    } else if (inItem && tag.isNotEmpty()) {
                        val txt = textBuf.toString().trim()
                        if (txt.isNotEmpty()) {
                            when (tag) {
                                "title" -> title = txt
                                "link" -> if (link.isBlank()) link = txt
                                "size" -> sizeBytes = txt.toLongOrNull() ?: sizeBytes
                            }
                        }
                        tag = ""
                        textBuf.setLength(0)
                    }
                }
            }
            event = parser.next()
        }
        return out.sortedByDescending { it.seeders }.take(40)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return ""
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format("%.2f GB", mb / 1024.0)
        } else {
            String.format("%.0f MB", mb)
        }
    }
}
