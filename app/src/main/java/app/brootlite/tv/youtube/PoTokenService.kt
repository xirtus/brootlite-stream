package app.brootlite.tv.youtube

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import app.brootlite.tv.BrootLiteApp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Isolated `:potoken` subprocess service (`android:process=":potoken"`).
 *
 * Only instantiated if a fallback InnerTube Web/TV client ever requires a BotGuard `poToken`.
 * Isolates WebView Chromium allocations strictly inside `:potoken` (`WebView.setDataDirectorySuffix("potoken")`)
 * and terminates its own PID via [IPoTokenService.killSelf] before playback begins so **0.0 MB** WebView RAM remains!
 */
class PoTokenService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                WebView.setDataDirectorySuffix("potoken")
            }
        }
    }

    private val binder = object : IPoTokenService.Stub() {
        override fun mintPoToken(
            identifier: String?,
            visitorData: String?,
            isTvClient: Boolean
        ): String? {
            val targetId = identifier?.takeIf { it.isNotBlank() }
                ?: visitorData?.takeIf { it.isNotBlank() }
                ?: return null
            return runCatching {
                mintBotGuardTokenInternal(targetId, isTvClient)
            }.getOrNull()
        }

        override fun killSelf() {
            mainHandler.postDelayed({
                Process.killProcess(Process.myPid())
            }, 35L)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    @SuppressLint("SetJavaScriptEnabled")
    private fun mintBotGuardTokenInternal(identifier: String, isTvClient: Boolean): String? {
        val challengeBundle = fetchAttChallengeBundle(isTvClient) ?: return null
        val resultRef = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        var webViewRef: WebView? = null

        val bridge = object {
            @JavascriptInterface
            fun onBotGuardSnapshot(botguardResponse: String) {
                Thread {
                    val itData = exchangeIntegrityToken(botguardResponse)
                    if (itData == null) {
                        latch.countDown()
                        return@Thread
                    }
                    val u8Integrity = base64ToJsUint8Array(itData.first)
                    val u8Ident = bytesToJsUint8Array(identifier.toByteArray(Charsets.UTF_8))
                    mainHandler.post {
                        val wv = webViewRef
                        if (wv == null) {
                            latch.countDown()
                            return@post
                        }
                        wv.evaluateJavascript(
                            """
                            (function() {
                              try {
                                var getMinter = window.__bgWebPoSig && window.__bgWebPoSig[0];
                                if (typeof getMinter !== "function") { BotGuardBridge.onError("no_minter"); return; }
                                var mint = getMinter($u8Integrity);
                                var out = mint($u8Ident);
                                if (!out || !out.length) { BotGuardBridge.onError("empty_pot"); return; }
                                var csv = Array.prototype.join.call(out, ",");
                                BotGuardBridge.onMintedCsv(csv);
                              } catch (e) {
                                BotGuardBridge.onError(String(e));
                              }
                            })();
                            """.trimIndent(),
                            null
                        )
                    }
                }.start()
            }

            @JavascriptInterface
            fun onMintedCsv(csvBytes: String) {
                val token = runCatching {
                    val bytes = csvBytes.split(',')
                        .filter { it.isNotBlank() }
                        .map { it.trim().toInt().toByte() }
                        .toByteArray()
                    Base64.encodeToString(
                        bytes,
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                    )
                }.getOrNull()
                resultRef.set(token)
                latch.countDown()
            }

            @JavascriptInterface
            fun onError(reason: String) {
                latch.countDown()
            }
        }

        mainHandler.post {
            try {
                val wv = WebView(applicationContext)
                webViewRef = wv
                wv.settings.apply {
                    javaScriptEnabled = true
                    blockNetworkLoads = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    domStorageEnabled = false
                    userAgentString = BOTGUARD_USER_AGENT
                }
                wv.addJavascriptInterface(bridge, "BotGuardBridge")
                wv.loadDataWithBaseURL(
                    "https://www.youtube.com",
                    buildBotGuardHtml(challengeBundle),
                    "text/html",
                    "utf-8",
                    null
                )
            } catch (_: Throwable) {
                latch.countDown()
            }
        }

        latch.await(9_500L, TimeUnit.MILLISECONDS)

        // Immediately tear down WebView before returning
        val cleanupLatch = CountDownLatch(1)
        mainHandler.post {
            runCatching {
                webViewRef?.apply {
                    stopLoading()
                    clearHistory()
                    clearCache(true)
                    loadUrl("about:blank")
                    onPause()
                    removeAllViews()
                    destroy()
                }
                webViewRef = null
            }
            cleanupLatch.countDown()
        }
        cleanupLatch.await(1_000L, TimeUnit.MILLISECONDS)

        return resultRef.get()
    }

    private data class BotGuardChallengeBundle(
        val interpreterJs: String,
        val program: String,
        val globalName: String
    )

    private fun fetchAttChallengeBundle(isTvClient: Boolean): BotGuardChallengeBundle? {
        val clientName = if (isTvClient) "TVHTML5" else "WEB"
        val clientVer = if (isTvClient) YouTubeAuthClient.TV_CLIENT_VERSION else "2.20260907.06.00"
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", clientName)
                    put("clientVersion", clientVer)
                }
            }
            put("engagementType", "ENGAGEMENT_TYPE_UNBOUND")
        }.toString()

        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/att/get?prettyPrint=false")
            .header("User-Agent", BOTGUARD_USER_AGENT)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val root = BrootLiteApp.sharedHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val text = resp.body?.string().orEmpty()
            json.parseToJsonElement(text).jsonObject
        }

        val bg = root["bgChallenge"] as? JsonObject ?: return null
        val program = bg["program"]?.jsonPrimitive?.contentOrNull ?: return null
        val globalName = bg["globalName"]?.jsonPrimitive?.contentOrNull ?: return null
        val interpUrlObj = bg["interpreterUrl"] as? JsonObject
        val rawUrl = interpUrlObj
            ?.get("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: return null
        val fullJsUrl = when {
            rawUrl.startsWith("https://") -> rawUrl
            rawUrl.startsWith("//") -> "https:$rawUrl"
            else -> "https://www.youtube.com$rawUrl"
        }

        val jsReq = Request.Builder()
            .url(fullJsUrl)
            .header("User-Agent", BOTGUARD_USER_AGENT)
            .get()
            .build()
        val interpJs = BrootLiteApp.sharedHttpClient.newCall(jsReq).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        }?.takeIf { it.isNotBlank() } ?: return null

        return BotGuardChallengeBundle(
            interpreterJs = interpJs,
            program = program,
            globalName = globalName
        )
    }

    private fun exchangeIntegrityToken(botguardResponse: String): Pair<String, Long>? {
        val apiKey = resolveWebApiKey() ?: return null
        val bodyJson = """["$BOTGUARD_REQUEST_KEY","$botguardResponse"]"""
        val req = Request.Builder()
            .url("https://www.youtube.com/api/jnn/v1/GenerateIT")
            .header("User-Agent", BOTGUARD_USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json+protobuf")
            .header("x-goog-api-key", apiKey)
            .header("x-user-agent", "grpc-web-javascript/0.1")
            .post(bodyJson.toRequestBody("application/json+protobuf".toMediaType()))
            .build()

        return BrootLiteApp.sharedHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val arr = json.parseToJsonElement(resp.body?.string().orEmpty()) as? JsonArray ?: return null
            val tokenB64 = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return null
            val ttl = arr.getOrNull(1)?.jsonPrimitive?.longOrNull ?: 3600L
            tokenB64 to ttl
        }
    }

    private fun buildBotGuardHtml(bundle: BotGuardChallengeBundle): String {
        val safeGlobal = bundle.globalName.replace("\"", "\\\"")
        val safeProgram = bundle.program.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            <!DOCTYPE html><html><head><meta charset="utf-8"><script>
            ${bundle.interpreterJs}
            </script><script>
            (function() {
              try {
                var vm = window["$safeGlobal"];
                if (!vm || typeof vm.a !== "function") {
                  BotGuardBridge.onError("missing_vm");
                  return;
                }
                var asyncSnap = null;
                vm.a("$safeProgram", function(snapFn) { asyncSnap = snapFn; }, true, null, function(){}, [[], []]);
                var tries = 0;
                var timer = setInterval(function() {
                  if (asyncSnap) {
                    clearInterval(timer);
                    var webPo = [];
                    window.__bgWebPoSig = webPo;
                    asyncSnap(function(bgResp) {
                      BotGuardBridge.onBotGuardSnapshot(String(bgResp || ""));
                    }, [undefined, undefined, webPo, undefined]);
                  } else if (++tries > 600) {
                    clearInterval(timer);
                    BotGuardBridge.onError("vm_timeout");
                  }
                }, 10);
              } catch (e) {
                BotGuardBridge.onError(String(e));
              }
            })();
            </script></head><body></body></html>
        """.trimIndent()
    }

    private fun base64ToJsUint8Array(ytBase64: String): String {
        val normalized = ytBase64.replace('-', '+').replace('_', '/').replace('.', '=')
        val decoded = Base64.decode(normalized, Base64.DEFAULT)
        return bytesToJsUint8Array(decoded)
    }

    private fun bytesToJsUint8Array(bytes: ByteArray): String {
        val joined = bytes.joinToString(",") { (it.toInt() and 0xff).toString() }
        return "new Uint8Array([$joined])"
    }

    companion object {
        // Public YouTube Web BotGuard WAA key (same public constant used by youtube.com & MediaServiceCore)
        @Volatile
    private var cachedWebApiKey: String? = null

    private fun resolveWebApiKey(): String? {
        cachedWebApiKey?.let { return it }
        return try {
            val request = okhttp3.Request.Builder()
                .url("https://www.youtube.com/tv")
                .header("User-Agent", BOTGUARD_USER_AGENT)
                .build()
            BrootLiteApp.sharedHttpClient.newCall(request).execute().use { response ->
                val html = response.body?.string() ?: return@use null
                Regex("\"INNERTUBE_API_KEY\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1)?.let { key ->
                    cachedWebApiKey = key
                    key
                }
            }
        } catch (e: Exception) {
            null
        }
    }
        private const val BOTGUARD_REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val BOTGUARD_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
