package app.brootlite.tv.youtube

import android.content.Context
import android.util.LruCache
import app.brootlite.tv.BrootLiteApp
import app.cash.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.regex.Pattern

/**
 * Ultra-low-RAM YouTube `sig` & `nsig` (`n` throttle parameter) solver backed by `QuickJs`.
 *
 * Memory invariants for Android TV:
 * - Caches `player_ias` / `player_embed` `base.js` on disk under `context.cacheDir/yt_player/` (max 3 files).
 * - Caches solved `n` parameters in a 256-entry [LruCache] and solved `sig` values in a 128-entry [LruCache].
 * - Instantiates [QuickJs] ONLY on cache misses and calls [QuickJs.close] immediately inside `use { ... }`
 *   so **0.0 MB** native QuickJS heap remains allocated during playback!
 */
object QuickJsSignatureSolver {

    data class PlayerScriptMetadata(
        val jsUrl: String,
        val playerHash: String,
        val signatureTimestamp: Int?,
        val visitorData: String?
    )

    data class SolvedChallenges(
        val signatures: Map<String, String> = emptyMap(),
        val nParams: Map<String, String> = emptyMap()
    )

    @Serializable
    private data class JsBatchSolveOutput(
        val sig: Map<String, String> = emptyMap(),
        val n: Map<String, String> = emptyMap(),
        val error: String? = null
    )

    private const val CACHE_SUBDIR = "yt_player"
    private const val MAX_DISK_PLAYERS = 3
    private const val METADATA_TTL_MS = 30L * 60L * 1000L

    private const val FALLBACK_PLAYER_URL =
        "https://www.youtube.com/s/player/06ab6907/tv-player-es6.vflset/tv-player-es6.js"

    private const val SAFARI_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"

    private val JS_URL_REGEX = Regex(""""jsUrl"\s*:\s*"([^"]+)"""")
    private val VISITOR_DATA_REGEX = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""")
    private val STS_REGEX = Regex("""signatureTimestamp[:=](\d+)(?!\d)""")
    private val PLAYER_HASH_REGEX = Regex("""/s/player/([a-zA-Z0-9_-]+)/""")

    // Live-verified 2025/2026 base.js top-level URL decipher function containing `.set("alr","yes")`
    private val ALR_SOLVER_FUNC_REGEX = Regex(
        """(?:var\s+|let\s+|const\s+|;\s*|\n\s*)([$\w]+(?:\.[$\w]+)?)\s*=\s*function\s*\(([$\w,\s]*)\)\s*\{([^{}]{0,700}?[$\w]+\.set\(\s*["']alr["']\s*,\s*["']yes["']\s*\)[^{}]{0,700}?)\}"""
    )
    private val IIFE_TAIL_REGEX = Regex("""\}\)\(_yt_player\)\s*;?\s*$""")

    // Classic fallback patterns (SigExtractor / NSigExtractor)
    private val GLOBAL_VAR_PATTERN = Pattern.compile(
        """(["'])use\s+strict\1;\s*(var\s+([a-zA-Z0-9_$]+)\s*=\s*((["'])(?:(?!\5).|\\.)+\5\.split\((["'])(?:(?!\6).)+\6\)|\[\s*(?:(["'])(?:(?!\7).|\\.)*\7\s*,?\s*)+\]))[;,]"""
    )
    private val SIG_FUNC_NAME_PATTERN = Pattern.compile(
        """\b([a-zA-Z0-9_$]+)&&\(\1=([a-zA-Z0-9_$]{2,})\(decodeURIComponent\(\1\)\)"""
    )
    private val NFUNC_FALLBACK_PATTERN = Pattern.compile(
        """;\s*([a-zA-Z0-9_$]+)\s*=\s*function\([a-zA-Z0-9_$]+\)\s*\{(?:(?!\};).)+?return\s*(["'])[\w-]+_w8_\2\s*\+\s*[a-zA-Z0-9_$]+""",
        Pattern.DOTALL
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val solveMutex = Mutex()

    // 256-entry LRU cache for solved `n` transforms ("playerHash:rawN" -> "solvedN")
    private val nParamLruCache = LruCache<String, String>(256)
    // 128-entry LRU cache for solved `sig` transforms ("playerHash:rawSig" -> "solvedSig")
    private val sigLruCache = LruCache<String, String>(128)

    @Volatile
    private var cachedMetadata: Pair<Long, PlayerScriptMetadata>? = null

    /**
     * Resolves current YouTube player `base.js` URL, `signatureTimestamp`, and `VISITOR_DATA`
     * from `/embed/{videoId}`, caching metadata in RAM for 30 minutes and caching `base.js` on disk.
     */
    suspend fun ensurePlayerMetadata(
        context: Context,
        videoId: String = "aqz-KE-bpKQ",
        forceRefresh: Boolean = false
    ): PlayerScriptMetadata? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedMetadata?.let { (ts, meta) ->
                if (now - ts < METADATA_TTL_MS) return@withContext meta
            }
        }

        val embedUrl = "https://www.youtube.com/embed/$videoId?hl=en"
        val embedHtml = runCatching {
            val req = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", SAFARI_UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()
            BrootLiteApp.sharedHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()

        val rawJsPath = embedHtml?.let { JS_URL_REGEX.find(it)?.groupValues?.getOrNull(1)?.replace("\\/", "/") }
        val visitorData = embedHtml?.let { VISITOR_DATA_REGEX.find(it)?.groupValues?.getOrNull(1) }

        val jsUrl = when {
            rawJsPath == null -> FALLBACK_PLAYER_URL
            rawJsPath.startsWith("https://") -> rawJsPath
            rawJsPath.startsWith("//") -> "https:$rawJsPath"
            rawJsPath.startsWith("/") -> "https://www.youtube.com$rawJsPath"
            else -> FALLBACK_PLAYER_URL
        }

        val playerHash = PLAYER_HASH_REGEX.find(jsUrl)?.groupValues?.getOrNull(1)
            ?: sha256Short(jsUrl)

        val jsCode = loadOrDownloadPlayerJs(context, jsUrl, playerHash, forceRefresh)
        val sts = jsCode?.let { STS_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        val meta = PlayerScriptMetadata(
            jsUrl = jsUrl,
            playerHash = playerHash,
            signatureTimestamp = sts,
            visitorData = visitorData ?: cachedMetadata?.second?.visitorData
        )
        cachedMetadata = now to meta
        meta
    }

    /**
     * Batch-solves any [signatures] and [nParams] using cached `base.js` + disposable [QuickJs] runtime.
     * Immediately closes the QuickJS context after evaluation so 0 MB native heap stays allocated!
     */
    suspend fun solveChallenges(
        context: Context,
        signatures: Set<String>,
        nParams: Set<String>,
        videoIdForBootstrap: String = "aqz-KE-bpKQ"
    ): SolvedChallenges {
        if (signatures.isEmpty() && nParams.isEmpty()) return SolvedChallenges()

        val meta = ensurePlayerMetadata(context, videoIdForBootstrap) ?: return SolvedChallenges()
        val pHash = meta.playerHash

        val solvedSigs = LinkedHashMap<String, String>()
        val missingSigs = ArrayList<String>()
        synchronized(sigLruCache) {
            for (s in signatures) {
                if (s.isBlank()) continue
                val hit = sigLruCache.get("$pHash:$s")
                if (hit != null) solvedSigs[s] = hit else missingSigs.add(s)
            }
        }

        val solvedNs = LinkedHashMap<String, String>()
        val missingNs = ArrayList<String>()
        synchronized(nParamLruCache) {
            for (n in nParams) {
                if (n.isBlank()) continue
                val hit = nParamLruCache.get("$pHash:$n")
                if (hit != null) solvedNs[n] = hit else missingNs.add(n)
            }
        }

        if (missingSigs.isEmpty() && missingNs.isEmpty()) {
            return SolvedChallenges(solvedSigs, solvedNs)
        }

        return solveMutex.withLock {
            // Re-check caches under lock in case a concurrent coroutine just solved them
            val stillMissingSigs = synchronized(sigLruCache) {
                missingSigs.filter { s ->
                    val hit = sigLruCache.get("$pHash:$s")
                    if (hit != null) {
                        solvedSigs[s] = hit
                        false
                    } else true
                }
            }
            val stillMissingNs = synchronized(nParamLruCache) {
                missingNs.filter { n ->
                    val hit = nParamLruCache.get("$pHash:$n")
                    if (hit != null) {
                        solvedNs[n] = hit
                        false
                    } else true
                }
            }

            if (stillMissingSigs.isEmpty() && stillMissingNs.isEmpty()) {
                return@withLock SolvedChallenges(solvedSigs, solvedNs)
            }

            withContext(Dispatchers.IO) {
                val jsCode = loadOrDownloadPlayerJs(context, meta.jsUrl, pHash, forceRefresh = false)
                    ?: return@withContext SolvedChallenges(solvedSigs, solvedNs)

                val batchOut = evaluateInDisposableQuickJs(jsCode, stillMissingSigs, stillMissingNs)
                if (batchOut != null) {
                    synchronized(sigLruCache) {
                        for ((raw, dec) in batchOut.sig) {
                            if (dec.isNotBlank()) {
                                sigLruCache.put("$pHash:$raw", dec)
                                solvedSigs[raw] = dec
                            }
                        }
                    }
                    synchronized(nParamLruCache) {
                        for ((raw, dec) in batchOut.n) {
                            if (dec.isNotBlank() && dec != raw) {
                                nParamLruCache.put("$pHash:$raw", dec)
                                solvedNs[raw] = dec
                            } else if (dec.isNotBlank()) {
                                solvedNs[raw] = dec
                            }
                        }
                    }
                }
                SolvedChallenges(solvedSigs, solvedNs)
            }
        }
    }

    /**
     * Deciphers a format's `url` or `signatureCipher` and replaces its `&n=` throttle token.
     */
    fun decipherFormatUrl(
        directUrl: String?,
        signatureCipher: String?,
        solved: SolvedChallenges
    ): String? {
        var baseUrl = directUrl?.takeIf { it.isNotBlank() }
        var rawSig: String? = null
        var sigParamName = "sig"

        if (baseUrl == null && !signatureCipher.isNullOrBlank()) {
            val params = parseFormEncoded(signatureCipher)
            baseUrl = params["url"]?.takeIf { it.isNotBlank() } ?: return null
            rawSig = params["s"]?.takeIf { it.isNotBlank() }
            sigParamName = params["sp"]?.takeIf { it.isNotBlank() } ?: "sig"
        }

        var workingUrl = baseUrl ?: return null
        if (rawSig != null) {
            val decipheredSig = solved.signatures[rawSig] ?: return null
            workingUrl = appendOrReplaceQueryParam(workingUrl, sigParamName, decipheredSig)
        }

        val rawN = extractQueryParam(workingUrl, "n")
        if (!rawN.isNullOrBlank()) {
            val decipheredN = solved.nParams[rawN]
            if (!decipheredN.isNullOrBlank()) {
                workingUrl = appendOrReplaceQueryParam(workingUrl, "n", decipheredN)
            }
        }
        return workingUrl
    }

    fun extractFormatPendingChallenges(
        directUrl: String?,
        signatureCipher: String?,
        outSignatures: MutableSet<String>,
        outNParams: MutableSet<String>
    ) {
        var baseUrl = directUrl?.takeIf { it.isNotBlank() }
        if (baseUrl == null && !signatureCipher.isNullOrBlank()) {
            val params = parseFormEncoded(signatureCipher)
            baseUrl = params["url"]
            params["s"]?.takeIf { it.isNotBlank() }?.let { outSignatures.add(it) }
        }
        if (!baseUrl.isNullOrBlank()) {
            extractQueryParam(baseUrl, "n")?.takeIf { it.isNotBlank() }?.let { outNParams.add(it) }
        }
    }

    private fun evaluateInDisposableQuickJs(
        jsCode: String,
        missingSigs: List<String>,
        missingNs: List<String>
    ): JsBatchSolveOutput? {
        val sigJsonArray = json.encodeToString(missingSigs)
        val nJsonArray = json.encodeToString(missingNs)

        // Strategy 1: Hook modern base.js URL-builder decipher function (contains `.set("alr","yes")`)
        val alrMatch = ALR_SOLVER_FUNC_REGEX.find(jsCode)
        if (alrMatch != null && IIFE_TAIL_REGEX.containsMatchIn(jsCode)) {
            val fnName = alrMatch.groupValues[1]
            val hookedPlayer = jsCode.replace(
                IIFE_TAIL_REGEX,
                ";globalThis.__ytSolve=$fnName;})(_yt_player);"
            )
            val script = buildString(hookedPlayer.length + 2048) {
                append(BROWSER_SHIM_JS)
                append('\n')
                append(hookedPlayer)
                append('\n')
                append(
                    """
                    (function() {
                      try {
                        var fn = globalThis.__ytSolve;
                        if (typeof fn !== "function") return JSON.stringify({error: "missing_fn"});
                        var sigIn = $sigJsonArray;
                        var nIn = $nJsonArray;
                        var sigOut = {};
                        var nOut = {};
                        function runUrl(u) {
                          var proto = Object.getPrototypeOf(u);
                          var keys = Object.keys(proto).concat(Object.getOwnPropertyNames(proto));
                          for (var i = 0; i < keys.length; i++) {
                            var k = keys[i];
                            if (k !== "constructor" && k !== "set" && k !== "get" && k !== "clone" && typeof u[k] === "function") {
                              try { u[k](); break; } catch (e) {}
                            }
                          }
                        }
                        for (var i = 0; i < sigIn.length; i++) {
                          var rawS = sigIn[i];
                          var u1 = fn("https://youtube.com/watch?v=yt-dlp-wins", "s", encodeURIComponent(rawS));
                          runUrl(u1);
                          var decS = u1.get("s");
                          if (decS) sigOut[rawS] = decodeURIComponent(decS);
                        }
                        for (var j = 0; j < nIn.length; j++) {
                          var rawN = nIn[j];
                          var u2 = fn("https://youtube.com/watch?v=yt-dlp-wins", "s", undefined);
                          u2.set("n", rawN);
                          runUrl(u2);
                          var decN = u2.get("n");
                          if (decN) nOut[rawN] = decN;
                        }
                        return JSON.stringify({sig: sigOut, n: nOut});
                      } catch (err) {
                        return JSON.stringify({error: String(err)});
                      }
                    })();
                    """.trimIndent()
                )
            }

            // CRITICAL: QuickJs.create().use guarantees qjs.close() immediately frees 100% of native heap!
            val evalOut = runCatching {
                QuickJs.create().use { qjs ->
                    qjs.evaluate(script, "yt_solver.js") as? String
                }
            }.getOrNull()

            if (!evalOut.isNullOrBlank()) {
                val parsed = runCatching { json.decodeFromString<JsBatchSolveOutput>(evalOut) }.getOrNull()
                if (parsed != null && parsed.error == null &&
                    (parsed.sig.isNotEmpty() || parsed.n.isNotEmpty())
                ) {
                    return parsed
                }
            }
        }

        // Strategy 2: Classic standalone function extraction fallback
        return runCatching {
            val standaloneJs = buildStandaloneFallbackScript(jsCode, sigJsonArray, nJsonArray)
                ?: return@runCatching null
            QuickJs.create().use { qjs ->
                val raw = qjs.evaluate(standaloneJs, "yt_fallback_solver.js") as? String
                raw?.let { json.decodeFromString<JsBatchSolveOutput>(it) }
            }
        }.getOrNull()
    }

    private fun buildStandaloneFallbackScript(
        jsCode: String,
        sigJsonArray: String,
        nJsonArray: String
    ): String? {
        val globalMatcher = GLOBAL_VAR_PATTERN.matcher(jsCode)
        val globalDecl = if (globalMatcher.find()) globalMatcher.group(2)?.let { "$it;" }.orEmpty() else ""

        val sigMatcher = SIG_FUNC_NAME_PATTERN.matcher(jsCode)
        val sigFnName = if (sigMatcher.find()) sigMatcher.group(2) else null
        val sigExtracted = sigFnName?.let { extractBalancedFunction(jsCode, it) }

        val nMatcher = NFUNC_FALLBACK_PATTERN.matcher(jsCode)
        val nFnName = if (nMatcher.find()) nMatcher.group(1) else null
        val nExtracted = nFnName?.let { extractBalancedFunction(jsCode, it) }

        if (sigExtracted == null && nExtracted == null) return null

        // If sigExtracted references a helper object like ` XY.ab(a, 3) `, extract that object literal too
        val helperObjCode = sigExtracted?.second?.let { body ->
            val objName = Regex("""([a-zA-Z0-9_$]{2,})\.[a-zA-Z0-9_$]{2,}\(""").find(body)?.groupValues?.getOrNull(1)
            if (objName != null && objName != "Math" && objName != "Object" && objName != "String") {
                extractHelperObject(jsCode, objName).orEmpty()
            } else ""
        }.orEmpty()

        return buildString {
            append(BROWSER_SHIM_JS).append('\n')
            if (globalDecl.isNotEmpty()) append(globalDecl).append('\n')
            if (helperObjCode.isNotEmpty()) append("var ").append(helperObjCode).append('\n')
            if (sigExtracted != null) {
                append("var __sigFn = function(").append(sigExtracted.first).append("){")
                    .append(sigExtracted.second).append("};\n")
            }
            if (nExtracted != null) {
                val cleanBody = nExtracted.second.replace(
                    Regex("""if\s*\(\s*typeof\s+[a-zA-Z0-9_$]+\s*===?\s*["']undefined["']\s*\)\s*return\s+[a-zA-Z0-9_$]+;"""),
                    ""
                )
                append("var __nFn = function(").append(nExtracted.first).append("){")
                    .append(cleanBody).append("};\n")
            }
            append(
                """
                (function() {
                  var sigIn = $sigJsonArray;
                  var nIn = $nJsonArray;
                  var sigOut = {};
                  var nOut = {};
                  if (typeof __sigFn === "function") {
                    for (var i = 0; i < sigIn.length; i++) {
                      try { sigOut[sigIn[i]] = String(__sigFn(sigIn[i])); } catch (e) {}
                    }
                  }
                  if (typeof __nFn === "function") {
                    for (var j = 0; j < nIn.length; j++) {
                      try { nOut[nIn[j]] = String(__nFn(nIn[j])); } catch (e) {}
                    }
                  }
                  return JSON.stringify({sig: sigOut, n: nOut});
                })();
                """.trimIndent()
            )
        }
    }

    private fun extractBalancedFunction(jsCode: String, funcName: String): Pair<String, String>? {
        val q = Pattern.quote(funcName)
        val startRegex = Regex("""(?:function\s+$q|[{;,]\s*$q\s*=\s*function|(?:var|let|const)\s+$q\s*=\s*function)\s*\(([^)]*)\)\s*\{""")
        val match = startRegex.find(jsCode) ?: return null
        val args = match.groupValues[1]
        val openBraceIdx = match.range.last
        var depth = 1
        var i = openBraceIdx + 1
        var quote: Char? = null
        var escaped = false
        while (i < jsCode.length && depth > 0) {
            val ch = jsCode[i]
            if (quote != null) {
                if (!escaped && ch == quote) quote = null
                escaped = !escaped && ch == '\\'
            } else {
                when (ch) {
                    '"', '\'' -> {
                        quote = ch
                        escaped = false
                    }
                    '{' -> depth++
                    '}' -> depth--
                }
            }
            i++
        }
        if (depth != 0) return null
        return args to jsCode.substring(openBraceIdx + 1, i - 1)
    }

    private fun extractHelperObject(jsCode: String, objName: String): String? {
        val q = Pattern.quote(objName)
        val m = Pattern.compile("""(?<![a-zA-Z0-9_$.])$q\s*=\s*\{[\s\S]{10,1200}?\};""").matcher(jsCode)
        return if (m.find()) m.group() else null
    }

    private fun loadOrDownloadPlayerJs(
        context: Context,
        jsUrl: String,
        playerHash: String,
        forceRefresh: Boolean
    ): String? {
        val dir = File(context.cacheDir, CACHE_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        val safeHash = playerHash.replace(Regex("[^a-zA-Z0-9_-]"), "").ifBlank { "default" }
        val cachedFile = File(dir, "base_$safeHash.js")

        if (!forceRefresh && cachedFile.exists() && cachedFile.length() > 10_000L) {
            return runCatching { cachedFile.readText(Charsets.UTF_8) }.getOrNull()
        }

        val downloaded = runCatching {
            val req = Request.Builder()
                .url(jsUrl)
                .header("User-Agent", SAFARI_UA)
                .get()
                .build()
            BrootLiteApp.sharedHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()?.takeIf { it.length > 10_000 } ?: return null

        runCatching {
            val tmp = File(dir, "base_${safeHash}.tmp")
            tmp.writeText(downloaded, Charsets.UTF_8)
            tmp.renameTo(cachedFile)
            pruneOldPlayerFiles(dir, keepMax = MAX_DISK_PLAYERS)
        }
        return downloaded
    }

    private fun pruneOldPlayerFiles(dir: File, keepMax: Int) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("base_") && f.name.endsWith(".js") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (files.size > keepMax) {
            files.drop(keepMax).forEach { runCatching { it.delete() } }
        }
    }

    private fun sha256Short(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    internal fun extractQueryParam(url: String, paramName: String): String? {
        val qIdx = url.indexOf('?')
        if (qIdx < 0) return null
        val hashIdx = url.indexOf('#', qIdx)
        val query = if (hashIdx >= 0) url.substring(qIdx + 1, hashIdx) else url.substring(qIdx + 1)
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            val key = if (eq >= 0) part.substring(0, eq) else part
            if (key == paramName) {
                val rawVal = if (eq >= 0) part.substring(eq + 1) else ""
                return runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
            }
        }
        return null
    }

    internal fun appendOrReplaceQueryParam(url: String, paramName: String, value: String): String {
        val hashIdx = url.indexOf('#')
        val main = if (hashIdx >= 0) url.substring(0, hashIdx) else url
        val fragment = if (hashIdx >= 0) url.substring(hashIdx) else ""
        val qIdx = main.indexOf('?')
        val encodedVal = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        val encodedKey = URLEncoder.encode(paramName, "UTF-8")

        if (qIdx < 0) {
            return "$main?$encodedKey=$encodedVal$fragment"
        }

        val basePath = main.substring(0, qIdx)
        val existingParts = main.substring(qIdx + 1).split('&').filter { it.isNotEmpty() }
        val updated = ArrayList<String>(existingParts.size + 1)
        var replaced = false
        for (part in existingParts) {
            val eq = part.indexOf('=')
            val key = if (eq >= 0) part.substring(0, eq) else part
            if (key == paramName || key == encodedKey) {
                if (!replaced) {
                    updated.add("$encodedKey=$encodedVal")
                    replaced = true
                }
            } else {
                updated.add(part)
            }
        }
        if (!replaced) {
            updated.add("$encodedKey=$encodedVal")
        }
        return "$basePath?${updated.joinToString("&")}$fragment"
    }

    private fun parseFormEncoded(encoded: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (part in encoded.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val k = if (eq >= 0) part.substring(0, eq) else part
            val v = if (eq >= 0) part.substring(eq + 1) else ""
            val decK = runCatching { URLDecoder.decode(k, "UTF-8") }.getOrDefault(k)
            val decV = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
            out[decK] = decV
        }
        return out
    }

    private val BROWSER_SHIM_JS = """
        if (!Object.hasOwn) {
          Object.hasOwn = function(o, k) { return Object.prototype.hasOwnProperty.call(o, k); };
        }
        if (typeof globalThis.XMLHttpRequest === "undefined") {
          globalThis.XMLHttpRequest = function() {};
          globalThis.XMLHttpRequest.prototype = {};
        }
        if (typeof globalThis.URL === "undefined") {
          globalThis.URL = function(u) {
            this.href = String(u || "");
            var m = this.href.match(/^(https?:)\/\/([^\/?#:]+)(?::(\d+))?([^?#]*)?(\?[^#]*)?(#.*)?$/);
            this.protocol = m ? m[1] : "https:";
            this.hostname = m ? m[2] : "www.youtube.com";
            this.port = (m && m[3]) ? m[3] : "";
            this.host = this.hostname + (this.port ? ":" + this.port : "");
            this.origin = this.protocol + "//" + this.host;
            this.pathname = (m && m[4]) ? m[4] : "/watch";
            this.search = (m && m[5]) ? m[5] : "";
            this.hash = (m && m[6]) ? m[6] : "";
          };
        }
        globalThis.location = new globalThis.URL("https://www.youtube.com/watch?v=yt-dlp-wins");
        globalThis.document = {
          createElement: function() { return { style: {} }; },
          body: {},
          documentElement: {}
        };
        globalThis.navigator = { userAgent: "Mozilla/5.0", platform: "MacIntel", language: "en-US" };
        globalThis.self = globalThis;
        globalThis.window = globalThis;
    """.trimIndent()
}
