package app.brootlite.tv.data

import android.content.Context
import app.brootlite.tv.BrootLiteApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

object StremioApiClient {
    private val json = AppPrefs.json
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    data class LinkSession(val code: String, val linkUrl: String, val qrcodeUrl: String)

    suspend fun createQrLinkSession(): LinkSession? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://link.stremio.com/api/v2/create?type=Create")
                .get()
                .build()
            BrootLiteApp.sharedHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val root = json.parseToJsonElement(resp.body?.string() ?: "").jsonObject
                val res = root["result"]?.jsonObject ?: return@withContext null
                val code = res["code"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
                val link = res["link"]?.jsonPrimitive?.contentOrNull ?: "https://link.stremio.com/?code=$code"
                val qr = res["qrcode"]?.jsonPrimitive?.contentOrNull ?: link
                LinkSession(code = code, linkUrl = link, qrcodeUrl = qr)
            }
        }.getOrNull()
    }

    suspend fun pollQrLinkAuthKey(code: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://link.stremio.com/api/v2/read?type=Read&code=${URLEncoder.encode(code, "UTF-8")}")
                .get()
                .build()
            BrootLiteApp.sharedHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val root = json.parseToJsonElement(resp.body?.string() ?: "").jsonObject
                val res = root["result"] as? JsonObject ?: return@withContext null
                val authKey = res["authKey"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@withContext null
                val email = fetchUserEmail(authKey) ?: "Stremio Cloud Linked"
                authKey to email
            }
        }.getOrNull()
    }

    suspend fun fetchUserEmail(authKey: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildJsonObject {
                put("type", "GetUser")
                put("authKey", authKey)
            }.toString()
            val req = Request.Builder()
                .url("https://api.strem.io/api/getUser")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            BrootLiteApp.sharedHttpClient.newCall(req).execute().use { resp ->
                val root = json.parseToJsonElement(resp.body?.string() ?: "").jsonObject
                root["result"]?.jsonObject?.get("email")?.jsonPrimitive?.contentOrNull
            }
        }.getOrNull()
    }

    private const val DESKTOP_BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    suspend fun syncAddonsFromAccount(context: Context, authKey: String): Int = withContext(Dispatchers.IO) {
        var syncedAddonsCount = 0
        runCatching {
            val payload = buildJsonObject {
                put("type", "AddonCollectionGet")
                put("authKey", authKey)
                put("update", true)
            }.toString()
            val req = Request.Builder()
                .url("https://api.strem.io/api/addonCollectionGet")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            BrootLiteApp.sharedHttpClient.newCall(req).execute().use { resp ->
                val root = json.parseToJsonElement(resp.body?.string() ?: "").jsonObject
                val addonsArr = root["result"]?.jsonObject?.get("addons") as? JsonArray
                if (addonsArr != null) {
                    val parsed = coroutineScope {
                        addonsArr.map { elem ->
                            async(Dispatchers.IO) {
                                val itemObj = elem as? JsonObject ?: return@async null
                                val rawUrl = itemObj["transportUrl"]?.jsonPrimitive?.contentOrNull ?: return@async null
                                val tUrl = normalizeManifestUrl(rawUrl)
                                val manifestObj = itemObj["manifest"] as? JsonObject
                                val fromCached = manifestObj?.let { parseManifestObject(tUrl, it) }
                                val isLocalhost = tUrl.contains("127.0.0.1") || tUrl.contains("localhost")
                                if (isLocalhost) {
                                    fromCached
                                } else {
                                    val live = withTimeoutOrNull(6_000L) { fetchLiveManifest(tUrl) }
                                    when {
                                        live == null -> fromCached
                                        fromCached != null && live.resources.isEmpty() && fromCached.resources.isNotEmpty() -> fromCached
                                        else -> live
                                    }
                                }
                            }
                        }.awaitAll().filterNotNull()
                    }
                    if (parsed.isNotEmpty()) {
                        AppPrefs.saveInstalledAddons(context, parsed)
                        syncedAddonsCount = parsed.size
                    }
                }
            }
        }
        syncedAddonsCount
    }

    suspend fun syncAccountEverything(context: Context, authKey: String): Int = withContext(Dispatchers.IO) {
        // 1. Sync Full Addon Collection (Catalogs, Streams, Subtitles & Mirrors)
        val syncedAddonsCount = syncAddonsFromAccount(context, authKey)

        // 2. Sync Library & Continue Watching across ALL Stremio content types
        runCatching {
            val payload = buildJsonObject {
                put("authKey", authKey)
                put("collection", "libraryItem")
                put("all", true)
            }.toString()
            val req = Request.Builder()
                .url("https://api.strem.io/api/datastoreGet")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            BrootLiteApp.sharedHttpClient.newCall(req).execute().use { resp ->
                val root = json.parseToJsonElement(resp.body?.string() ?: "").jsonObject
                val arr = root["result"] as? JsonArray ?: return@use
                val watchList = mutableListOf<WatchHistoryEntry>()
                val favList = mutableListOf<FavoriteEntry>()

                for (elem in arr) {
                    val obj = elem as? JsonObject ?: continue
                    val id = obj["_id"]?.jsonPrimitive?.contentOrNull ?: continue
                    val removed = obj["removed"]?.jsonPrimitive?.booleanOrNull ?: false
                    val temp = obj["temp"]?.jsonPrimitive?.booleanOrNull ?: false
                    val type = obj["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "movie"
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                    val poster = obj["poster"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: if (id.startsWith("tt")) "https://images.metahub.space/poster/medium/$id/img" else ""
                    val mtimeStr = obj["_mtime"]?.jsonPrimitive?.contentOrNull ?: ""
                    val updatedMs = parseIsoTimeMs(mtimeStr)

                    if (!removed && !temp) {
                        favList.add(
                            FavoriteEntry(
                                id = id,
                                type = type,
                                name = name,
                                poster = poster,
                                subtitle = "Stremio Library · ${type.replaceFirstChar { it.uppercase() }}",
                                addedAtMs = updatedMs
                            )
                        )
                    }

                    val state = obj["state"] as? JsonObject
                    if (state != null && !removed) {
                        val timeOffset = state["timeOffset"]?.jsonPrimitive?.longOrNull ?: 0L
                        val duration = state["duration"]?.jsonPrimitive?.longOrNull ?: 0L
                        val videoId = state["video_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: id
                        val finished = duration > 30_000L && timeOffset >= (duration * 0.94).toLong()
                        if (timeOffset > 15_000L && !finished) {
                            watchList.add(
                                WatchHistoryEntry(
                                    itemId = id,
                                    videoId = videoId,
                                    type = type,
                                    name = name,
                                    episodeTitle = if (videoId != id && videoId.contains(':')) {
                                        val parts = videoId.split(':')
                                        if (parts.size >= 3) "S${parts[1]}:E${parts[2]}" else videoId.substringAfter(':')
                                    } else "",
                                    poster = poster,
                                    background = if (id.startsWith("tt")) "https://images.metahub.space/background/medium/$id/img" else poster,
                                    timeOffsetMs = timeOffset,
                                    durationMs = duration,
                                    updatedAtMs = updatedMs
                                )
                            )
                        }
                    }
                }
                AppPrefs.mergeRemoteWatchHistory(context, watchList)
                AppPrefs.mergeFavorites(context, favList)
            }
        }
        syncedAddonsCount
    }

    suspend fun pushWatchProgress(context: Context, entry: WatchHistoryEntry) = withContext(Dispatchers.IO) {
        val authKey = AppPrefs.getStremioAuthKey(context) ?: return@withContext
        if (entry.itemId.startsWith("torrent:") || entry.itemId.startsWith("kodi:")) return@withContext
        runCatching {
            val isoNow = formatIsoTime(System.currentTimeMillis())
            val change = buildJsonObject {
                put("_id", entry.itemId)
                put("name", entry.name)
                put("type", entry.type)
                put("poster", entry.poster)
                put("removed", false)
                put("temp", !AppPrefs.isFavorite(context, entry.itemId))
                put("_ctime", isoNow)
                put("_mtime", isoNow)
                put(
                    "state",
                    buildJsonObject {
                        put("lastWatched", isoNow)
                        put("timeWatched", entry.timeOffsetMs)
                        put("timeOffset", entry.timeOffsetMs)
                        put("overallTimeWatched", entry.timeOffsetMs)
                        put("timesWatched", if (entry.progressFraction >= 0.95f) 1 else 0)
                        put("flaggedWatched", if (entry.progressFraction >= 0.95f) 1 else 0)
                        put("duration", entry.durationMs)
                        put("video_id", entry.videoId)
                        put("noNotif", true)
                    }
                )
            }
            val payload = buildJsonObject {
                put("authKey", authKey)
                put("collection", "libraryItem")
                put("changes", buildJsonArray { add(change) })
            }.toString()
            val req = Request.Builder()
                .url("https://api.strem.io/api/datastorePut")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            BrootLiteApp.sharedHttpClient.newCall(req).execute().close()
        }
    }

    private fun normalizeManifestUrl(rawUrl: String): String {
        var url = rawUrl.trim()
        if (url.startsWith("stremio://", ignoreCase = true)) {
            url = "https://" + url.substringAfter("://")
        }
        if (!url.contains("/manifest.json")) {
            val qIdx = url.indexOf('?')
            url = if (qIdx >= 0) {
                url.substring(0, qIdx).trimEnd('/') + "/manifest.json" + url.substring(qIdx)
            } else {
                url.trimEnd('/') + "/manifest.json"
            }
        }
        return url
    }

    private fun executeGetBodyWithSslFallback(url: String): String? {
        val primaryReq = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        var httpFailureCode = 0
        val primary = runCatching {
            BrootLiteApp.sharedHttpClient.newCall(primaryReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.string()
                } else {
                    httpFailureCode = resp.code
                    null
                }
            }
        }.getOrNull()
        if (primary != null) return primary

        // Retry over Conscrypt TLS 1.3 + HTTP/2 with standard desktop Firefox UA when Cloudflare challenges Android TV UA
        if (httpFailureCode in listOf(401, 403, 429, 503)) {
            val desktopReq = primaryReq.newBuilder()
                .header("User-Agent", DESKTOP_BROWSER_UA)
                .build()
            val desktopBody = runCatching {
                BrootLiteApp.sharedHttpClient.newCall(desktopReq).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }.getOrNull()
            if (desktopBody != null) return desktopBody
        }

        // Only fall back to permissive custom SSLContext if standard TLS threw a certificate/handshake error
        if (httpFailureCode == 0) {
            return runCatching {
                BrootLiteApp.sharedPermissiveHttpClient.newCall(
                    primaryReq.newBuilder().header("User-Agent", DESKTOP_BROWSER_UA).build()
                ).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }.getOrNull()
        }
        return null
    }

    private fun fetchLiveManifest(manifestUrl: String): InstalledAddon? {
        return runCatching {
            val body = executeGetBodyWithSslFallback(manifestUrl) ?: return null
            val manifestObj = json.parseToJsonElement(body).jsonObject
            parseManifestObject(manifestUrl, manifestObj)
        }.getOrNull()
    }

    suspend fun installAddonFromUrl(context: Context, rawUrl: String): InstalledAddon? = withContext(Dispatchers.IO) {
        runCatching {
            val url = normalizeManifestUrl(rawUrl)
            val addon = fetchLiveManifest(url) ?: return@withContext null
            val updated = AppPrefs.getInstalledAddons(context).toMutableList()
            updated.removeAll { it.id == addon.id || it.transportUrl == addon.transportUrl }
            updated.add(addon)
            AppPrefs.saveInstalledAddons(context, updated)
            addon
        }.getOrNull()
    }

    private fun parseManifestObject(transportUrl: String, m: JsonObject): InstalledAddon? {
        val id = m["id"]?.jsonPrimitive?.contentOrNull ?: return null
        if (id == "com.linvo.cinemeta") {
            return AppPrefs.defaultCinemetaAddon().copy(transportUrl = transportUrl)
        }

        val name = m["name"]?.jsonPrimitive?.contentOrNull ?: id
        val version = m["version"]?.jsonPrimitive?.contentOrNull ?: "1.0.0"
        val description = m["description"]?.jsonPrimitive?.contentOrNull ?: ""
        val logo = m["logo"]?.jsonPrimitive?.contentOrNull ?: ""
        val types = (m["types"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

        val idPrefixes = buildList {
            (m["idPrefixes"] as? JsonArray)?.forEach { el ->
                (el as? JsonPrimitive)?.contentOrNull?.let { add(it) }
            }
            (m["idPrefix"] as? JsonPrimitive)?.contentOrNull?.let { single ->
                if (single !in this) add(single)
            }
        }

        val catalogs = mutableListOf<AddonCatalogDef>()
        (m["catalogs"] as? JsonArray)?.forEach { catEl ->
            val cObj = catEl as? JsonObject ?: return@forEach
            val cType = cObj["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: types.firstOrNull()
                ?: "movie"
            val cId = cObj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val cName = cObj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: cId

            var searchSup = false
            var searchReq = false
            var skipSup = false
            var genreSup = false
            var genreReq = false
            var extraParamName = "genre"
            val genreOpts = mutableListOf<String>()
            val defaultExtras = LinkedHashMap<String, String>()

            (cObj["extra"] as? JsonArray)?.forEach { ex ->
                when (ex) {
                    is JsonPrimitive -> {
                        when (ex.contentOrNull) {
                            "search" -> searchSup = true
                            "skip" -> skipSup = true
                            "genre" -> genreSup = true
                        }
                    }
                    is JsonObject -> {
                        val exName = ex["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@forEach
                        val req = ex["isRequired"]?.jsonPrimitive?.booleanOrNull == true
                        val opts = (ex["options"] as? JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
                            .orEmpty()
                        val defVal = (ex["default"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

                        when (exName) {
                            "search" -> {
                                searchSup = true
                                if (req) searchReq = true
                            }
                            "skip" -> {
                                skipSup = true
                            }
                            "genre" -> {
                                genreSup = true
                                extraParamName = "genre"
                                if (req) genreReq = true
                                opts.forEach { if (it !in genreOpts) genreOpts.add(it) }
                            }
                            else -> {
                                // Custom filterable extras used by JAV / Hentai / Anime / Network addons
                                // (e.g. category, tag, sort, filter, studio, language, page)
                                if (!genreSup && opts.isNotEmpty()) {
                                    genreSup = true
                                    extraParamName = exName
                                    if (req) genreReq = true
                                    opts.forEach { if (it !in genreOpts) genreOpts.add(it) }
                                } else if (req) {
                                    val fallback = defVal ?: opts.firstOrNull() ?: if (exName == "page") "1" else ""
                                    if (fallback.isNotEmpty()) {
                                        defaultExtras[exName] = fallback
                                    }
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }

            (cObj["extraSupported"] as? JsonArray)?.forEach { exEl ->
                when ((exEl as? JsonPrimitive)?.contentOrNull) {
                    "search" -> searchSup = true
                    "skip" -> skipSup = true
                    "genre" -> genreSup = true
                }
            }
            (cObj["extraRequired"] as? JsonArray)?.forEach { reqEl ->
                when (val reqName = (reqEl as? JsonPrimitive)?.contentOrNull) {
                    "search" -> searchReq = true
                    "genre" -> genreReq = true
                    null, "" -> Unit
                    else -> {
                        if (reqName == extraParamName && genreOpts.isNotEmpty()) {
                            genreReq = true
                        } else if (!defaultExtras.containsKey(reqName)) {
                            defaultExtras[reqName] = if (reqName == "page") "1" else ""
                        }
                    }
                }
            }
            if (genreOpts.isEmpty() && (cObj["genres"] as? JsonArray) != null) {
                (cObj["genres"] as? JsonArray)?.forEach { gEl ->
                    (gEl as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { genreOpts.add(it) }
                }
                if (genreOpts.isNotEmpty()) genreSup = true
            }

            val browsable = !searchReq
            catalogs.add(
                AddonCatalogDef(
                    type = cType,
                    id = cId,
                    name = cName,
                    isSearchSupported = searchSup,
                    isSearchRequired = searchReq,
                    isBrowseSupported = browsable,
                    isSkipSupported = skipSup,
                    isGenreSupported = genreSup,
                    isGenreRequired = genreReq,
                    genreOptions = genreOpts.take(500),
                    extraParamName = extraParamName,
                    defaultExtraMap = defaultExtras
                )
            )
        }

        val resources = mutableListOf<AddonResourceSpec>()
        (m["resources"] as? JsonArray)?.forEach { resEl ->
            when (resEl) {
                is JsonPrimitive -> {
                    val rName = resEl.contentOrNull ?: return@forEach
                    resources.add(AddonResourceSpec(rName, types, idPrefixes))
                }
                is JsonObject -> {
                    val rName = resEl["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val rTypes = (resEl["types"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: types
                    val rPref = buildList {
                        (resEl["idPrefixes"] as? JsonArray)?.forEach { el ->
                            (el as? JsonPrimitive)?.contentOrNull?.let { add(it) }
                        }
                        (resEl["idPrefix"] as? JsonPrimitive)?.contentOrNull?.let { single ->
                            if (single !in this) add(single)
                        }
                    }.ifEmpty { idPrefixes }
                    resources.add(AddonResourceSpec(rName, rTypes, rPref))
                }
                else -> Unit
            }
        }
        if (resources.none { it.name == "catalog" } && catalogs.isNotEmpty()) {
            val catTypes = (types + catalogs.map { it.type }).distinct()
            resources.add(AddonResourceSpec("catalog", catTypes, idPrefixes))
        }

        return InstalledAddon(
            transportUrl = transportUrl,
            id = id,
            name = name,
            version = version,
            description = description,
            logo = logo,
            types = (types + catalogs.map { it.type }).distinct(),
            resources = resources,
            catalogs = catalogs,
            idPrefixes = idPrefixes
        )
    }

    suspend fun fetchCatalog(
        addon: InstalledAddon,
        catalog: AddonCatalogDef,
        genre: String? = null,
        skip: Int = 0,
        searchQuery: String? = null
    ): List<MetaItem> = withContext(Dispatchers.IO) {
        val extraParts = mutableListOf<String>()
        if (!searchQuery.isNullOrBlank()) {
            extraParts.add("search=${encodeStremioPathComponent(searchQuery.trim())}")
        }
        val paramName = catalog.extraParamName.ifBlank { "genre" }
        val effectiveGenre = genre?.takeIf { it.isNotBlank() }
            ?: if (catalog.isGenreRequired) catalog.genreOptions.firstOrNull() else null
        if (!effectiveGenre.isNullOrBlank()) {
            extraParts.add("$paramName=${encodeStremioPathComponent(effectiveGenre)}")
        }
        for ((k, v) in catalog.defaultExtraMap) {
            if (k != paramName && v.isNotBlank()) {
                extraParts.add("$k=${encodeStremioPathComponent(v)}")
            }
        }
        if (skip > 0) {
            extraParts.add("skip=$skip")
        }

        val encType = encodeStremioPathComponent(catalog.type)
        val encId = encodeStremioPathComponent(catalog.id)
        val base = addon.baseUrl
        val qIdx = addon.transportUrl.indexOf('?')
        val querySuffix = if (qIdx >= 0) addon.transportUrl.substring(qIdx) else ""

        val extraSegment = if (extraParts.isNotEmpty()) "/${extraParts.joinToString("&")}" else ""
        val primaryUrl = "$base/catalog/$encType/$encId$extraSegment.json$querySuffix"
        val primary = executeCatalogGet(primaryUrl, catalog.type)
        if (primary.isNotEmpty() || extraParts.isEmpty() || !searchQuery.isNullOrBlank() || skip > 0) {
            return@withContext primary
        }
        // Fallback without default extras if an addon's manifest misdeclared isRequired
        val bareUrl = "$base/catalog/$encType/$encId.json$querySuffix"
        executeCatalogGet(bareUrl, catalog.type)
    }

    private fun executeCatalogGet(url: String, defaultType: String): List<MetaItem> {
        return runCatching {
            val body = executeGetBodyWithSslFallback(url) ?: return emptyList()
            val root = json.parseToJsonElement(body).jsonObject
            val metas = (root["metas"] ?: root["metasDetailed"]) as? JsonArray ?: return emptyList()
            metas.mapNotNull { parseMetaObject(it as? JsonObject, defaultType) }
        }.getOrDefault(emptyList())
    }

    private fun encodeStremioPathComponent(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%3A", ":")

    private fun encodeStremioPathStrict(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    suspend fun searchStremioCatalogs(addons: List<InstalledAddon>, query: String): List<MetaItem> = coroutineScope {
        val jobs = addons.flatMap { addon ->
            addon.catalogs
                .filter { it.isSearchSupported }
                .take(10)
                .map { cat ->
                    async(Dispatchers.IO) {
                        withTimeoutOrNull(10_000L) {
                            fetchCatalog(addon, cat, searchQuery = query)
                        }.orEmpty()
                    }
                }
        }
        val map = LinkedHashMap<String, MetaItem>()
        for (list in jobs.awaitAll()) {
            for (item in list) {
                val key = "${item.type}:${item.id}"
                if (!map.containsKey(key)) {
                    map[key] = item
                }
            }
        }
        map.values.toList()
    }

    suspend fun fetchMetaDetail(addons: List<InstalledAddon>, type: String, itemId: String): MetaItem? =
        withContext(Dispatchers.IO) {
            val candidates = addons
                .mapNotNull { a -> a.resolveResourceType("meta", type, itemId)?.let { resolvedType -> a to resolvedType } }
                .toMutableList()
            if (candidates.none { it.first.id == "com.linvo.cinemeta" } && itemId.startsWith("tt")) {
                val cinemetaType = if (type == "series" || itemId.contains(':')) "series" else "movie"
                candidates.add(AppPrefs.defaultCinemetaAddon() to cinemetaType)
            }
            for ((addon, reqType) in candidates) {
                val qIdx = addon.transportUrl.indexOf('?')
                val querySuffix = if (qIdx >= 0) addon.transportUrl.substring(qIdx) else ""
                val idVariants = listOf(
                    encodeStremioPathComponent(itemId),
                    encodeStremioPathStrict(itemId)
                ).distinct()
                for (encId in idVariants) {
                    val res = runCatching {
                        val url = "${addon.baseUrl}/meta/${encodeStremioPathComponent(reqType)}/$encId.json$querySuffix"
                        val body = executeGetBodyWithSslFallback(url) ?: return@runCatching null
                        val root = json.parseToJsonElement(body).jsonObject
                        parseMetaObject(root["meta"] as? JsonObject, type)
                    }.getOrNull()
                    if (res != null) return@withContext res
                }
            }
            null
        }

    /**
     * Progressive stream resolver: queries ALL compatible Stremio stream & HTTP mirror addons
     * concurrently (with cross-type IMDb/Kitsu/Custom resolution and a 25s timeout for mirror scrapers),
     * streaming deduplicated, smart-sorted results back to the UI as each addon responds.
     */
    suspend fun fetchStreamsProgressive(
        addons: List<InstalledAddon>,
        type: String,
        videoId: String,
        onProgress: ((streams: List<StreamCandidate>, completedAddons: Int, totalAddons: Int) -> Unit)? = null
    ): List<StreamCandidate> = coroutineScope {
        val targets = addons.mapNotNull { addon ->
            addon.resolveResourceType("stream", type, videoId)?.let { resolvedType -> addon to resolvedType }
        }
        if (targets.isEmpty()) return@coroutineScope emptyList()

        val mutex = Mutex()
        val combined = ArrayList<StreamCandidate>()
        val completed = AtomicInteger(0)
        val total = targets.size

        val jobs = targets.map { (addon, resolvedType) ->
            launch(Dispatchers.IO) {
                val single = withTimeoutOrNull(25_000L) {
                    fetchStreamsFromSingleAddon(addon, resolvedType, videoId)
                }.orEmpty()
                val doneNow = completed.incrementAndGet()
                val snapshot = mutex.withLock {
                    if (single.isNotEmpty()) {
                        combined.addAll(single)
                    }
                    dedupeAndSortStreams(combined)
                }
                if (onProgress != null && (single.isNotEmpty() || doneNow == total)) {
                    withContext(Dispatchers.Main) {
                        onProgress(snapshot, doneNow, total)
                    }
                }
            }
        }
        jobs.forEach { it.join() }
        mutex.withLock { dedupeAndSortStreams(combined) }
    }

    suspend fun fetchStreams(addons: List<InstalledAddon>, type: String, videoId: String): List<StreamCandidate> =
        fetchStreamsProgressive(addons, type, videoId, onProgress = null)

    fun dedupeAndSortStreams(raw: List<StreamCandidate>): List<StreamCandidate> {
        val seen = LinkedHashMap<String, StreamCandidate>(raw.size)
        for (s in raw) {
            val key = when {
                !s.infoHash.isNullOrBlank() -> "btih:${s.infoHash.lowercase()}:${s.fileIdx ?: -1}"
                !s.url.isNullOrBlank() -> "url:${s.url}"
                !s.externalUrl.isNullOrBlank() -> "ext:${s.externalUrl}"
                else -> "${s.addonName}:${s.name}:${s.title}"
            }
            if (!seen.containsKey(key)) {
                seen[key] = s
            }
        }
        return sortStreamsSmart(seen.values.toList())
    }

    /**
     * Smart-sorts streams so instant HTTP / Debrid / Mirror streams and healthy P2P swarms
     * surface immediately above dead 0-seed torrents.
     */
    fun sortStreamsSmart(streams: List<StreamCandidate>): List<StreamCandidate> {
        return streams.withIndex().sortedWith(
            compareBy<IndexedValue<StreamCandidate>>(
                { (_, s) ->
                    when {
                        s.isDirectHttp -> 0                       // Instant HTTP / Debrid / Resolved Mirror
                        s.isTorrent && s.seeders >= 3 -> 1        // Healthy P2P Swarm (3+ seeds)
                        s.isExternalMirror -> 2                   // Web / Host Mirror (vital when 0 seeds!)
                        s.isTorrent && s.seeders in 1..2 -> 3     // Low-seed P2P
                        else -> 4                                 // 0-seed / unseeded P2P fallback
                    }
                },
                { (idx, s) ->
                    if (s.isTorrent) -s.seeders else idx
                },
                { (idx, _) -> idx }
            )
        ).map { it.value }
    }

    private fun fetchStreamsFromSingleAddon(addon: InstalledAddon, type: String, videoId: String): List<StreamCandidate> {
        val qIdx = addon.transportUrl.indexOf('?')
        val querySuffix = if (qIdx >= 0) addon.transportUrl.substring(qIdx) else ""
        val idVariants = listOf(
            encodeStremioPathComponent(videoId),
            encodeStremioPathStrict(videoId)
        ).distinct()

        for (encId in idVariants) {
            val parsed = runCatching {
                val url = "${addon.baseUrl}/stream/${encodeStremioPathComponent(type)}/$encId.json$querySuffix"
                val body = executeGetBodyWithSslFallback(url) ?: return@runCatching emptyList()
                val root = json.parseToJsonElement(body).jsonObject
                val streams = root["streams"] as? JsonArray ?: return@runCatching emptyList()
                streams.mapNotNull { el ->
                    parseStreamObject(addon.name, el as? JsonObject)
                }
            }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private fun parseStreamObject(addonName: String, s: JsonObject?): StreamCandidate? {
        s ?: return null
        val rawStreamUrl = s["url"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val explicitHash = s["infoHash"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val magnetExtractedHash = if (explicitHash == null && rawStreamUrl?.startsWith("magnet:", true) == true) {
            Regex("""xt=urn:btih:([A-Za-z0-9]{32,40})""", RegexOption.IGNORE_CASE)
                .find(rawStreamUrl)?.groupValues?.getOrNull(1)?.lowercase()
        } else null
        val infoHash = explicitHash ?: magnetExtractedHash
        val ytId = s["ytId"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val extUrl = s["externalUrl"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            ?: ytId?.let { "https://www.youtube.com/watch?v=$it" }

        // Keep Direct HTTP, P2P Torrents, AND External Mirrors!
        if (rawStreamUrl == null && infoHash == null && extUrl == null) return null

        val headers = LinkedHashMap<String, String>()
        var cleanStreamUrl = rawStreamUrl
        var ignoreSsl = false
        if (rawStreamUrl != null && rawStreamUrl.contains('|') && !rawStreamUrl.startsWith("magnet:", true)) {
            val pipeIdx = rawStreamUrl.lastIndexOf('|')
            val pipeParams = rawStreamUrl.substring(pipeIdx + 1)
            val eqFirst = pipeParams.indexOf('=')
            val slashFirst = pipeParams.indexOf('/')
            // Only split on '|' when suffix is genuine Kodi header key=value syntax (preserving '|' in proxy URL paths!)
            if (eqFirst > 0 && (slashFirst < 0 || slashFirst > eqFirst)) {
                cleanStreamUrl = rawStreamUrl.substring(0, pipeIdx).trim()
                for (pair in pipeParams.split('&')) {
                    val eq = pair.indexOf('=')
                    if (eq > 0) {
                        val hk = pair.substring(0, eq).trim()
                        val hv = runCatching { URLDecoder.decode(pair.substring(eq + 1).trim(), "UTF-8") }
                            .getOrDefault(pair.substring(eq + 1).trim())
                        if (hk.equals("verifypeer", true)) {
                            if (hv.equals("false", true) || hv == "0") ignoreSsl = true
                        } else {
                            headers[hk] = hv
                        }
                    }
                }
            }
        }

        val hints = s["behaviorHints"] as? JsonObject
        val proxyReqHeaders = (hints?.get("proxyHeaders") as? JsonObject)?.get("request") as? JsonObject
        val hintHeaders = hints?.get("headers") as? JsonObject
        val directHeaders = s["headers"] as? JsonObject
        listOfNotNull(directHeaders, hintHeaders, proxyReqHeaders).forEach { hObj ->
            hObj.forEach { (k, v) ->
                (v as? JsonPrimitive)?.contentOrNull?.let { headers[k] = it }
            }
        }

        val sources = (s["sources"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        val title = s["title"]?.jsonPrimitive?.contentOrNull ?: ""
        val desc = s["description"]?.jsonPrimitive?.contentOrNull ?: ""
        val combinedText = "$title\n$desc"
        val explicitSeeders = s["seeders"]?.jsonPrimitive?.intOrNull
            ?: s["seeds"]?.jsonPrimitive?.intOrNull
        val seedMatch = explicitSeeders
            ?: Regex("""(?:👤|🌱|⬆️?|🟢|Seeds?:?|Seeders?:?|S:)\s*(\d[\d,]*)""", RegexOption.IGNORE_CASE)
                .find(combinedText)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
            ?: 0

        return StreamCandidate(
            addonName = addonName,
            name = s["name"]?.jsonPrimitive?.contentOrNull ?: addonName,
            title = title,
            description = desc,
            url = cleanStreamUrl,
            infoHash = infoHash,
            fileIdx = s["fileIdx"]?.jsonPrimitive?.intOrNull,
            externalUrl = extUrl,
            sources = sources,
            headers = headers,
            ignoreSsl = ignoreSsl,
            bingeGroup = hints?.get("bingeGroup")?.jsonPrimitive?.contentOrNull ?: "",
            filename = hints?.get("filename")?.jsonPrimitive?.contentOrNull ?: "",
            seeders = seedMatch
        )
    }

    suspend fun fetchSubtitles(addons: List<InstalledAddon>, type: String, videoId: String): List<ExternalSubtitle> =
        coroutineScope {
            val targets = addons.mapNotNull { addon ->
                addon.resolveResourceType("subtitles", type, videoId)?.let { resolvedType -> addon to resolvedType }
            }.toMutableList()
            val defSub = AppPrefs.defaultOpenSubtitlesAddon()
            if (targets.none { it.first.id == defSub.id } && videoId.startsWith("tt")) {
                val subType = if (type == "series" || videoId.contains(':')) "series" else "movie"
                targets.add(defSub to subType)
            }
            val jobs = targets.map { (addon, subType) ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(4_000L) {
                        runCatching {
                            val encId = encodeStremioPathComponent(videoId)
                            val url = "${addon.baseUrl}/subtitles/${encodeStremioPathComponent(subType)}/$encId.json"
                            val body = executeGetBodyWithSslFallback(url) ?: return@runCatching emptyList()
                            val root = json.parseToJsonElement(body).jsonObject
                            val subs = root["subtitles"] as? JsonArray ?: return@runCatching emptyList()
                            subs.mapNotNull { el ->
                                val o = el as? JsonObject ?: return@mapNotNull null
                                val sUrl = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                                if (sUrl.endsWith(".srt", true) || sUrl.endsWith(".vtt", true) || sUrl.contains("strem.io") || sUrl.startsWith("http")) {
                                    ExternalSubtitle(
                                        id = o["id"]?.jsonPrimitive?.contentOrNull ?: sUrl.hashCode().toString(),
                                        url = sUrl,
                                        lang = o["lang"]?.jsonPrimitive?.contentOrNull ?: "eng",
                                        addonName = addon.name
                                    )
                                } else null
                            }.take(25)
                        }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }
            jobs.awaitAll().flatten()
        }

    private fun parseMetaObject(obj: JsonObject?, fallbackType: String): MetaItem? {
        obj ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: obj["_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull
            ?: obj["title"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val type = obj["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: fallbackType
        val addonHintLabel = type.replaceFirstChar { it.uppercase() }
        val poster = obj["poster"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: obj["thumbnail"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: obj["background"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: if (id.startsWith("tt")) "https://images.metahub.space/poster/medium/$id/img" else ""
        val background = obj["background"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: if (id.startsWith("tt")) "https://images.metahub.space/background/medium/$id/img" else poster
        val genres = ((obj["genres"] ?: obj["genre"]) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

        val metaHints = obj["behaviorHints"] as? JsonObject
        val defaultVideoId = metaHints?.get("defaultVideoId")?.jsonPrimitive?.contentOrNull ?: ""
        val metaEmbeddedStreams = (obj["streams"] as? JsonArray)
            ?.mapNotNull { parseStreamObject(addonHintLabel, it as? JsonObject) }
            .orEmpty()

        val videos = ((obj["videos"] as? JsonArray)?.mapNotNull { vEl ->
            val v = vEl as? JsonObject ?: return@mapNotNull null
            val vid = v["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val epStreams = buildList {
                (v["streams"] as? JsonArray)?.forEach { sEl ->
                    parseStreamObject(addonHintLabel, sEl as? JsonObject)?.let { add(it) }
                }
                val vExt = v["externalUrl"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                if (vExt != null && none { it.externalUrl == vExt }) {
                    add(
                        StreamCandidate(
                            addonName = addonHintLabel,
                            name = "$addonHintLabel · Mirror",
                            title = v["title"]?.jsonPrimitive?.contentOrNull ?: name,
                            externalUrl = vExt
                        )
                    )
                }
            }
            VideoEpisode(
                id = vid,
                title = v["title"]?.jsonPrimitive?.contentOrNull
                    ?: v["name"]?.jsonPrimitive?.contentOrNull
                    ?: "Episode ${v["episode"]?.jsonPrimitive?.intOrNull ?: 1}",
                season = v["season"]?.jsonPrimitive?.intOrNull ?: 0,
                episode = v["episode"]?.jsonPrimitive?.intOrNull ?: v["number"]?.jsonPrimitive?.intOrNull ?: 0,
                overview = v["overview"]?.jsonPrimitive?.contentOrNull
                    ?: v["description"]?.jsonPrimitive?.contentOrNull ?: "",
                thumbnail = v["thumbnail"]?.jsonPrimitive?.contentOrNull ?: "",
                released = v["released"]?.jsonPrimitive?.contentOrNull ?: "",
                streams = epStreams
            )
        } ?: emptyList()).sortedWith(compareBy({ if (it.season == 0) 999 else it.season }, { it.episode }))

        return MetaItem(
            id = id,
            type = type,
            name = name,
            poster = poster,
            background = background,
            logo = obj["logo"]?.jsonPrimitive?.contentOrNull ?: "",
            description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
            releaseInfo = obj["releaseInfo"]?.jsonPrimitive?.contentOrNull
                ?: obj["year"]?.jsonPrimitive?.contentOrNull ?: "",
            imdbRating = obj["imdbRating"]?.jsonPrimitive?.contentOrNull ?: "",
            runtime = obj["runtime"]?.jsonPrimitive?.contentOrNull ?: "",
            genres = genres,
            videos = videos,
            defaultVideoId = defaultVideoId,
            embeddedStreams = metaEmbeddedStreams
        )
    }

    private fun parseIsoTimeMs(iso: String): Long {
        if (iso.isBlank()) return System.currentTimeMillis()
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sdf.parse(iso.take(19))?.time ?: System.currentTimeMillis()
        }.getOrDefault(System.currentTimeMillis())
    }

    private fun formatIsoTime(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.format(Date(ms))
    }
}
