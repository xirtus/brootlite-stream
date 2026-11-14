package app.brootlite.tv.data

import android.content.Context
import app.brootlite.tv.BrootLiteApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import java.net.URLEncoder
import java.util.Locale
import java.util.zip.ZipInputStream

@Serializable
data class PackStremioAddon(
    val id: String,
    val name: String,
    val transportUrl: String,
    val version: String = "1.0.0",
    val description: String = "",
    val types: List<String> = listOf("movie", "series", "anime"),
    val resources: List<String> = listOf("stream"),
    val idPrefixes: List<String> = listOf("tt", "kitsu")
)

@Serializable
data class PackSwarmIndexer(
    val id: String,
    val name: String,
    val kind: String, // "rss" | "json_array_btih" | "json_movies_torrents" | "torznab"
    val searchUrlTemplate: String = "",
    val imdbUrlTemplate: String = "",
    val baseUrls: List<String> = emptyList(),
    val pathTemplate: String = "",
    val defaultCategory: String = "",
    val apiKey: String = "",
    val tabGroup: String = "Swarm",
    val adult: Boolean = false,
    val imdbFallback: Boolean = false
)

@Serializable
data class PackFeaturedFeed(
    val key: String,
    val label: String,
    val kind: String, // "rss" | "json_array_btih" | "json_movies_torrents"
    val url: String = "",
    val baseUrls: List<String> = emptyList(),
    val path: String = "",
    val adult: Boolean = false
)

@Serializable
data class PackKodiRepo(
    val id: String,
    val name: String,
    val zipUrl: String,
    val installAddons: List<String> = emptyList()
)

@Serializable
data class ProviderPack(
    val schemaVersion: Int = 1,
    val name: String = "External Provider Pack",
    val trackers: List<String> = emptyList(),
    val stremioAddons: List<PackStremioAddon> = emptyList(),
    val swarmIndexers: List<PackSwarmIndexer> = emptyList(),
    val featuredFeeds: List<PackFeaturedFeed> = emptyList(),
    val kodiRepositories: List<PackKodiRepo> = emptyList()
)

object ProviderPackManager {
    private const val PREFS_NAME = "brootlite_prefs"
    private const val KEY_ACTIVE_PACK = "active_provider_pack_json_v1"
    private const val SEED_ASSET_NAME = "seed_pack.json"
    private const val EXTERNAL_FILE_NAME = "provider_pack.json"

    @Volatile
    private var cachedPack: ProviderPack? = null

    fun getActivePack(context: Context): ProviderPack? {
        cachedPack?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ACTIVE_PACK, null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            AppPrefs.json.decodeFromString<ProviderPack>(raw).also { cachedPack = it }
        }.getOrNull()
    }

    fun mergedTrackers(context: Context): List<String> {
        val extra = getActivePack(context)?.trackers.orEmpty()
        return if (extra.isEmpty()) {
            StreamCandidate.DEFAULT_PUBLIC_TRACKERS
        } else {
            (StreamCandidate.DEFAULT_PUBLIC_TRACKERS + extra).distinct()
        }
    }

    /**
     * Automatically loads either `/sdcard/Android/data/<pkg>/files/provider_pack.json` (runtime unlock)
     * or optional build-time `assets/seed_pack.json` (preloaded build).
     */
    fun tryHydrateSeedOrExternalPack(context: Context): Boolean {
        val appCtx = context.applicationContext
        val extDirs = listOfNotNull(appCtx.getExternalFilesDir(null), appCtx.filesDir)
        for (dir in extDirs) {
            val extFile = File(dir, EXTERNAL_FILE_NAME)
            if (extFile.isFile && extFile.length() in 10..2_000_000) {
                val raw = runCatching { extFile.readText(Charsets.UTF_8) }.getOrNull()
                if (!raw.isNullOrBlank() && importPackJsonSync(appCtx, raw, persistExternalCopy = false)) {
                    return true
                }
            }
        }

        if (getActivePack(appCtx) != null) {
            // Still ensure any Kodi plugins declared in active pack are extracted
            getActivePack(appCtx)?.let { installPackKodiRepositories(appCtx, it) }
            return true
        }

        val seedRaw = runCatching {
            appCtx.assets.open(SEED_ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
        if (!seedRaw.isNullOrBlank()) {
            return importPackJsonSync(appCtx, seedRaw, persistExternalCopy = false)
        }
        return false
    }

    suspend fun importPackFromUrlOrPath(context: Context, urlOrPath: String): ProviderPack? =
        withContext(Dispatchers.IO) {
            val trimmed = urlOrPath.trim()
            if (trimmed.isBlank()) return@withContext null
            val rawJson = when {
                trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> {
                    val req = Request.Builder().url(trimmed).get().build()
                    runCatching {
                        BrootLiteApp.sharedPermissiveHttpClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) null else resp.body?.string()
                        }
                    }.getOrNull()
                }
                trimmed.startsWith("file://", true) -> {
                    runCatching { File(java.net.URI(trimmed)).readText(Charsets.UTF_8) }.getOrNull()
                }
                else -> {
                    runCatching { File(trimmed).readText(Charsets.UTF_8) }.getOrNull()
                }
            } ?: return@withContext null

            if (importPackJsonSync(context, rawJson, persistExternalCopy = true)) {
                getActivePack(context)
            } else {
                null
            }
        }

    fun clearPack(context: Context) {
        cachedPack = null
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ACTIVE_PACK)
            .apply()
        listOfNotNull(context.getExternalFilesDir(null), context.filesDir).forEach { dir ->
            runCatching { File(dir, EXTERNAL_FILE_NAME).delete() }
        }
    }

    fun importPackJsonSync(
        context: Context,
        rawJson: String,
        persistExternalCopy: Boolean = true
    ): Boolean {
        val pack = runCatching { AppPrefs.json.decodeFromString<ProviderPack>(rawJson) }.getOrNull()
            ?: return false
        if (pack.swarmIndexers.isEmpty() && pack.stremioAddons.isEmpty() && pack.kodiRepositories.isEmpty()) {
            return false
        }

        val appCtx = context.applicationContext
        cachedPack = pack
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_PACK, AppPrefs.json.encodeToString(pack))
            .apply()

        if (persistExternalCopy) {
            runCatching {
                val dir = appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
                File(dir, EXTERNAL_FILE_NAME).writeText(rawJson, Charsets.UTF_8)
            }
        }

        // Merge Stremio addons into installed addons list
        if (pack.stremioAddons.isNotEmpty()) {
            val current = AppPrefs.getInstalledAddons(appCtx).toMutableList()
            var changed = false
            for (pa in pack.stremioAddons) {
                val exists = current.any {
                    it.id.equals(pa.id, ignoreCase = true) ||
                        it.transportUrl.equals(pa.transportUrl, ignoreCase = true)
                }
                if (!exists) {
                    current.add(
                        InstalledAddon(
                            id = pa.id,
                            name = pa.name,
                            version = pa.version,
                            description = pa.description,
                            transportUrl = pa.transportUrl,
                            types = pa.types,
                            resources = pa.resources.map { rName ->
                                AddonResourceSpec(
                                    name = rName,
                                    types = pa.types,
                                    idPrefixes = pa.idPrefixes
                                )
                            },
                            idPrefixes = pa.idPrefixes,
                            catalogs = emptyList()
                        )
                    )
                    changed = true
                }
            }
            if (changed) {
                AppPrefs.saveInstalledAddons(appCtx, current)
            }
        }

        // Install Kodi repositories & requested video addons
        installPackKodiRepositories(appCtx, pack)
        return true
    }

    // =========================================================================
    // GENERIC DECLARATIVE SWARM SEARCH & FEATURED FEEDS
    // =========================================================================

    suspend fun searchPackIndexers(
        context: Context,
        query: String,
        includeAdult: Boolean = true
    ): List<StreamCandidate> = coroutineScope {
        val pack = getActivePack(context) ?: return@coroutineScope emptyList()
        val trackers = mergedTrackers(context)
        val trimmed = query.trim()

        if (trimmed.isBlank()) {
            val feeds = pack.featuredFeeds.filter { includeAdult || !it.adult }
            val jobs = feeds.map { feed ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(9_500L) {
                        fetchFeaturedFeed(feed, trackers)
                    }.orEmpty()
                }
            }
            return@coroutineScope jobs.awaitAll().flatten()
        }

        val indexers = pack.swarmIndexers.filter { includeAdult || !it.adult }
        val jobs = indexers.map { idx ->
            async(Dispatchers.IO) {
                withTimeoutOrNull(9_500L) {
                    runSingleIndexerSearch(idx, trimmed, trackers)
                }.orEmpty()
            }
        }
        jobs.awaitAll().flatten()
    }

    suspend fun searchPackImdbFallbacks(
        context: Context,
        imdbId: String,
        season: Int?,
        episode: Int?
    ): List<StreamCandidate> = coroutineScope {
        val pack = getActivePack(context) ?: return@coroutineScope emptyList()
        val trackers = mergedTrackers(context)
        val cleanTt = imdbId.substringBefore(':').trim()
        if (!cleanTt.startsWith("tt", ignoreCase = true)) return@coroutineScope emptyList()

        val indexers = pack.swarmIndexers.filter { it.imdbFallback }
        val jobs = indexers.map { idx ->
            async(Dispatchers.IO) {
                withTimeoutOrNull(9_000L) {
                    when (idx.kind.lowercase(Locale.US)) {
                        "json_array_btih" -> {
                            val tpl = idx.imdbUrlTemplate.ifBlank { idx.searchUrlTemplate }
                            if (tpl.isBlank()) emptyList()
                            else {
                                val url = tpl
                                    .replace("{imdb}", URLEncoder.encode(cleanTt, "UTF-8"))
                                    .replace("{query}", URLEncoder.encode(cleanTt, "UTF-8"))
                                    .replace("{cat}", idx.defaultCategory)
                                fetchJsonArrayBtih(url, idx.name, trackers, season, episode, filterEpisode = true)
                            }
                        }
                        "json_movies_torrents" -> {
                            if (season != null || episode != null) emptyList()
                            else fetchJsonMoviesTorrents(idx.baseUrls, idx.pathTemplate, cleanTt, idx.name, trackers)
                        }
                        else -> emptyList()
                    }
                }.orEmpty()
            }
        }
        jobs.awaitAll().flatten()
    }

    private fun fetchFeaturedFeed(
        feed: PackFeaturedFeed,
        trackers: List<String>
    ): List<StreamCandidate> {
        return when (feed.kind.lowercase(Locale.US)) {
            "rss" -> NativeTorrentSearch.fetchCustomRssFeed(feed.url, feed.label, "", trackers)
            "json_array_btih" -> fetchJsonArrayBtih(feed.url, feed.label, trackers, null, null, filterEpisode = false)
            "json_movies_torrents" -> fetchJsonMoviesTorrents(feed.baseUrls, feed.path, "", feed.label, trackers)
            else -> emptyList()
        }
    }

    private fun runSingleIndexerSearch(
        idx: PackSwarmIndexer,
        query: String,
        trackers: List<String>
    ): List<StreamCandidate> {
        val encQ = URLEncoder.encode(query, "UTF-8")
        return when (idx.kind.lowercase(Locale.US)) {
            "rss" -> {
                val url = idx.searchUrlTemplate
                    .replace("{query}", encQ)
                    .replace("{cat}", idx.defaultCategory)
                NativeTorrentSearch.fetchCustomRssFeed(url, idx.name, "", trackers)
            }
            "json_array_btih" -> {
                val url = idx.searchUrlTemplate
                    .replace("{query}", encQ)
                    .replace("{cat}", idx.defaultCategory)
                fetchJsonArrayBtih(url, idx.name, trackers, null, null, filterEpisode = false)
            }
            "json_movies_torrents" -> {
                fetchJsonMoviesTorrents(idx.baseUrls, idx.pathTemplate, query, idx.name, trackers)
            }
            else -> emptyList()
        }
    }

    private fun fetchJsonArrayBtih(
        url: String,
        sourceName: String,
        trackers: List<String>,
        season: Int?,
        episode: Int?,
        filterEpisode: Boolean
    ): List<StreamCandidate> {
        if (url.isBlank()) return emptyList()
        val clients = listOf(BrootLiteApp.sharedHttpClient, BrootLiteApp.sharedPermissiveHttpClient)
        for (client in clients) {
            val out = runCatching {
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching emptyList()
                    val body = resp.body?.string()?.trim().orEmpty()
                    if (!body.startsWith("[")) return@runCatching emptyList()
                    val arr = AppPrefs.json.parseToJsonElement(body) as? JsonArray ?: return@runCatching emptyList()
                    val list = ArrayList<StreamCandidate>(arr.size)
                    for (el in arr) {
                        val obj = el as? JsonObject ?: continue
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        val hash = obj["info_hash"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase(Locale.US).orEmpty()
                        if (name.isBlank() || hash.length != 40 || hash.all { it == '0' }) continue
                        val seeds = obj["seeders"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                            ?: obj["seeders"]?.jsonPrimitive?.intOrNull ?: 0
                        val sizeBytes = obj["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                            ?: obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
                        val numFiles = obj["num_files"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                            ?: obj["num_files"]?.jsonPrimitive?.intOrNull ?: 1
                        if (filterEpisode && season != null && !matchesSeasonEpisode(name, season, episode, numFiles)) {
                            continue
                        }
                        val sizeFmt = formatSize(sizeBytes)
                        list.add(
                            StreamCandidate(
                                addonName = sourceName,
                                name = sourceName,
                                title = "$name\n👤 $seeds  💾 $sizeFmt  ⚙️ $sourceName",
                                infoHash = hash,
                                sources = trackers,
                                bingeGroup = "pack-${sourceName.lowercase(Locale.US)}",
                                filename = name,
                                seeders = seeds,
                                sizeLabel = sizeFmt
                            )
                        )
                    }
                    list
                }
            }.getOrDefault(emptyList())
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    private fun fetchJsonMoviesTorrents(
        baseUrls: List<String>,
        pathTemplate: String,
        query: String,
        sourceName: String,
        trackers: List<String>
    ): List<StreamCandidate> {
        val encQ = URLEncoder.encode(query, "UTF-8")
        val path = pathTemplate.replace("{query}", encQ)
        for (base in baseUrls) {
            val fullUrl = base.trimEnd('/') + "/" + path.trimStart('/')
            val items = runCatching {
                val req = Request.Builder().url(fullUrl).get().build()
                BrootLiteApp.sharedPermissiveHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching emptyList()
                    val root = AppPrefs.json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                    val movies = root["data"]?.jsonObject?.get("movies") as? JsonArray ?: return@runCatching emptyList()
                    val out = mutableListOf<StreamCandidate>()
                    for (mEl in movies) {
                        val m = mEl as? JsonObject ?: continue
                        val mvTitle = m["title_long"]?.jsonPrimitive?.contentOrNull
                            ?: m["title"]?.jsonPrimitive?.contentOrNull ?: continue
                        val torrents = m["torrents"] as? JsonArray ?: continue
                        for (tEl in torrents) {
                            val t = tEl as? JsonObject ?: continue
                            val hash = t["hash"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase(Locale.US) ?: continue
                            if (hash.length != 40) continue
                            val quality = t["quality"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val codec = t["video_codec"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val seeds = t["seeds"]?.jsonPrimitive?.intOrNull ?: 0
                            val sizeStr = t["size"]?.jsonPrimitive?.contentOrNull
                                ?: formatSize(t["size_bytes"]?.jsonPrimitive?.longOrNull ?: 0L)
                            val labelTitle = "$mvTitle [$quality] [$codec]"
                            out.add(
                                StreamCandidate(
                                    addonName = sourceName,
                                    name = "$sourceName · $quality",
                                    title = "$labelTitle\n👤 $seeds  💾 $sizeStr  ⚙️ $sourceName",
                                    infoHash = hash,
                                    sources = trackers,
                                    bingeGroup = "${sourceName.lowercase(Locale.US)}-$quality-$codec",
                                    filename = labelTitle,
                                    seeders = seeds,
                                    sizeLabel = sizeStr
                                )
                            )
                        }
                    }
                    out
                }
            }.getOrDefault(emptyList())
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    internal fun matchesSeasonEpisode(
        name: String,
        season: Int,
        episode: Int?,
        numFiles: Int
    ): Boolean {
        val lower = name.lowercase(Locale.US)
        if (episode != null) {
            val s2 = "%02d".format(Locale.US, season)
            val e2 = "%02d".format(Locale.US, episode)
            if (lower.contains("s${s2}e${e2}") || lower.contains("${season}x${e2}")) return true
        }
        if (numFiles > 1) {
            val s2 = "%02d".format(Locale.US, season)
            if (Regex("""\bs$s2\b|\bseason\s*0?$season\b|\bcomplete\b""").containsMatchIn(lower)) {
                return true
            }
        }
        return false
    }

    // =========================================================================
    // STANDARD KODI `xbmc.addon.repository` ZIP + DEPENDENCY INSTALLER
    // =========================================================================

    private fun installPackKodiRepositories(context: Context, pack: ProviderPack) {
        if (pack.kodiRepositories.isEmpty()) return
        val addonsRoot = File(AppPrefs.getKodiEnvDir(context), "addons").apply { mkdirs() }
        for (repo in pack.kodiRepositories) {
            runCatching {
                if (!File(addonsRoot, repo.id).isDirectory && repo.zipUrl.isNotBlank()) {
                    downloadAndExtractZip(repo.zipUrl, addonsRoot)
                }
                val repoXmlFile = File(File(addonsRoot, repo.id), "addon.xml")
                if (repoXmlFile.isFile && repo.installAddons.isNotEmpty()) {
                    val dirs = parseRepositoryDirs(repoXmlFile.readText(Charsets.UTF_8))
                    if (dirs.isNotEmpty()) {
                        val catalog = fetchAddonCatalog(dirs)
                        val visited = HashSet<String>()
                        for (addonId in repo.installAddons) {
                            installAddonRecursive(addonId, catalog, addonsRoot, visited)
                        }
                    }
                }
            }
        }
    }

    private data class RepoDirSpec(val infoUrl: String, val datadirUrl: String)
    private data class RemoteAddonEntry(
        val id: String,
        val version: String,
        val datadirUrl: String,
        val requires: List<String>
    )

    private fun installAddonRecursive(
        addonId: String,
        catalog: Map<String, RemoteAddonEntry>,
        addonsRoot: File,
        visited: MutableSet<String>
    ) {
        if (!visited.add(addonId)) return
        if (addonId.startsWith("xbmc.") || addonId == "script.module.python.twitch") return
        val entry = catalog[addonId]
        if (!File(addonsRoot, addonId).isDirectory && entry != null) {
            val base = entry.datadirUrl.trimEnd('/')
            val zipUrl = "$base/${entry.id}/${entry.id}-${entry.version}.zip"
            downloadAndExtractZip(zipUrl, addonsRoot)
        }
        val localXml = File(File(addonsRoot, addonId), "addon.xml")
        val deps = if (localXml.isFile) {
            parseAddonImports(localXml.readText(Charsets.UTF_8))
        } else {
            entry?.requires.orEmpty()
        }
        for (dep in deps) {
            installAddonRecursive(dep, catalog, addonsRoot, visited)
        }
    }

    private fun parseRepositoryDirs(xml: String): List<RepoDirSpec> {
        val dirs = mutableListOf<RepoDirSpec>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        var inDir = false
        var tag = ""
        var info = ""
        var datadir = ""
        val buf = StringBuilder()
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name.orEmpty().lowercase(Locale.US)
                    if (tag == "dir") {
                        inDir = true
                        info = ""
                        datadir = ""
                    }
                    buf.setLength(0)
                }
                XmlPullParser.TEXT -> if (inDir) parser.text?.let { buf.append(it) }
                XmlPullParser.END_TAG -> {
                    val end = parser.name.orEmpty().lowercase(Locale.US)
                    if (inDir) {
                        val txt = buf.toString().trim()
                        if (end == "info" && txt.startsWith("http")) info = txt
                        else if (end == "datadir" && txt.startsWith("http")) datadir = txt
                        else if (end == "dir") {
                            inDir = false
                            if (info.isNotBlank() && datadir.isNotBlank()) {
                                dirs.add(RepoDirSpec(info, datadir))
                            }
                        }
                    }
                    buf.setLength(0)
                }
            }
            ev = parser.next()
        }
        return dirs
    }

    private fun fetchAddonCatalog(dirs: List<RepoDirSpec>): Map<String, RemoteAddonEntry> {
        val map = LinkedHashMap<String, RemoteAddonEntry>()
        val http = BrootLiteApp.buildPermissiveHttpClient(25L)
        for (dir in dirs) {
            runCatching {
                val req = Request.Builder().url(dir.infoUrl).get().build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val xml = resp.body?.string().orEmpty()
                    val parser = XmlPullParserFactory.newInstance().newPullParser()
                    parser.setInput(StringReader(xml))
                    var curId = ""
                    var curVer = ""
                    val reqs = mutableListOf<String>()
                    var ev = parser.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        when (ev) {
                            XmlPullParser.START_TAG -> {
                                val t = parser.name.orEmpty().lowercase(Locale.US)
                                if (t == "addon") {
                                    curId = parser.getAttributeValue(null, "id").orEmpty()
                                    curVer = parser.getAttributeValue(null, "version").orEmpty()
                                    reqs.clear()
                                } else if (t == "import" && curId.isNotEmpty()) {
                                    val imp = parser.getAttributeValue(null, "addon").orEmpty()
                                    val opt = parser.getAttributeValue(null, "optional").orEmpty()
                                    if (imp.isNotBlank() && !opt.equals("true", true)) reqs.add(imp)
                                }
                            }
                            XmlPullParser.END_TAG -> {
                                if (parser.name.equals("addon", true) && curId.isNotEmpty() && curVer.isNotEmpty()) {
                                    map.putIfAbsent(
                                        curId,
                                        RemoteAddonEntry(curId, curVer, dir.datadirUrl, reqs.toList())
                                    )
                                    curId = ""
                                }
                            }
                        }
                        ev = parser.next()
                    }
                }
            }
        }
        return map
    }

    private fun parseAddonImports(xml: String): List<String> {
        val reqs = mutableListOf<String>()
        runCatching {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && parser.name.equals("import", true)) {
                    val imp = parser.getAttributeValue(null, "addon").orEmpty()
                    val opt = parser.getAttributeValue(null, "optional").orEmpty()
                    if (imp.isNotBlank() && !opt.equals("true", true)) reqs.add(imp)
                }
                ev = parser.next()
            }
        }
        return reqs
    }

    private fun downloadAndExtractZip(zipUrl: String, addonsRoot: File): Boolean {
        return runCatching {
            val req = Request.Builder().url(zipUrl).get().build()
            BrootLiteApp.buildPermissiveHttpClient(30L).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val bytes = resp.body?.bytes() ?: return false
                if (bytes.isEmpty() || bytes.size > 60 * 1024 * 1024) return false
                val canonicalRoot = addonsRoot.canonicalPath + File.separator
                ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(addonsRoot, entry.name)
                        if (!outFile.canonicalPath.startsWith(canonicalRoot)) {
                            throw SecurityException("Blocked zip-slip entry")
                        }
                        if (entry.isDirectory) outFile.mkdirs()
                        else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { zis.copyTo(it) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                true
            }
        }.getOrDefault(false)
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        if (gb >= 1.0) return String.format(Locale.US, "%.2f GB", gb)
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }
}
