package app.brootlite.tv.kodi

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.widget.Toast
import app.brootlite.tv.data.AppPrefs
import app.brootlite.tv.data.KodiBridgeResponse
import app.brootlite.tv.data.KodiInstalledAddon
import app.brootlite.tv.ui.TvUiKit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

object PyAddonClient {

    private val bindMutex = Mutex()
    @Volatile
    private var boundService: IPyAddonService? = null
    @Volatile
    private var isBound: Boolean = false
    @Volatile
    private var connectionDeferred: CompletableDeferred<IPyAddonService?>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val iface = IPyAddonService.Stub.asInterface(service)
            boundService = iface
            isBound = true
            connectionDeferred?.complete(iface)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            isBound = false
        }
    }

    private suspend fun ensureConnected(context: Context): IPyAddonService? = bindMutex.withLock {
        val existing = boundService
        if (existing != null && existing.asBinder().isBinderAlive) {
            return existing
        }
        val appCtx = context.applicationContext
        val deferred = CompletableDeferred<IPyAddonService?>()
        connectionDeferred = deferred
        val intent = Intent(appCtx, PyAddonService::class.java)
        val ok = appCtx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!ok) return null
        return withTimeoutOrNull(10_000L) { deferred.await() }
    }

    suspend fun executeUrl(activity: Activity, pluginUrl: String): KodiBridgeResponse = withContext(Dispatchers.IO) {
        val service = ensureConnected(activity)
            ?: return@withContext KodiBridgeResponse(ok = false, error = "Unable to connect to :pyaddon service")

        val kodiEnvPath = AppPrefs.getKodiEnvDir(activity).absolutePath
        val callback = createUiCallback(activity)

        runCatching {
            val rawJson = service.executePluginUrl(pluginUrl, kodiEnvPath, callback)
            AppPrefs.json.decodeFromString<KodiBridgeResponse>(rawJson)
        }.getOrElse { err ->
            // Retry once if :pyaddon died and restarted
            boundService = null
            isBound = false
            KodiBridgeResponse(ok = false, error = err.message ?: err.javaClass.simpleName)
        }
    }

    /**
     * Scans kodi_env/addons/plugin.video.* directly in Kotlin without even spawning :pyaddon!
     */
    fun scanInstalledVideoAddonsFast(context: Context): List<KodiInstalledAddon> {
        val addonsRoot = File(AppPrefs.getKodiEnvDir(context), "addons")
        if (!addonsRoot.isDirectory) return emptyList()
        val out = mutableListOf<KodiInstalledAddon>()
        val dirs = addonsRoot.listFiles()?.filter { it.isDirectory && it.name.startsWith("plugin.video.") }
            ?.sortedBy { it.name } ?: emptyList()

        for (dir in dirs) {
            val xmlFile = File(dir, "addon.xml")
            if (!xmlFile.isFile) continue
            runCatching {
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(StringReader(xmlFile.readText()))
                var id = dir.name
                var name = dir.name
                var ver = ""
                var summary = ""
                var iconRel = "icon.png"
                var currentTag = ""
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            currentTag = parser.name ?: ""
                            if (currentTag == "addon") {
                                id = parser.getAttributeValue(null, "id") ?: dir.name
                                name = parser.getAttributeValue(null, "name") ?: id
                                ver = parser.getAttributeValue(null, "version") ?: ""
                            }
                        }
                        XmlPullParser.TEXT -> {
                            val t = parser.text?.trim().orEmpty()
                            if (t.isNotEmpty()) {
                                if (currentTag == "summary" && summary.isEmpty()) summary = t
                                if (currentTag == "icon") iconRel = t
                            }
                        }
                        XmlPullParser.END_TAG -> currentTag = ""
                    }
                    event = parser.next()
                }
                val iconFile = File(dir, iconRel)
                out.add(
                    KodiInstalledAddon(
                        id = id,
                        name = AppPrefs.stripKodiTags(name),
                        version = ver,
                        summary = AppPrefs.stripKodiTags(summary),
                        icon = if (iconFile.isFile) iconFile.absolutePath else "",
                        pluginUrl = "plugin://$id/"
                    )
                )
            }
        }
        return out
    }

    /**
     * Immediately kills the :pyaddon child process to reclaim ~35 MB RAM before video playback
     * or upon returning to the main BrootLite UI.
     */
    fun killAddonProcessNow(context: Context) {
        val appCtx = context.applicationContext
        val svc = boundService
        boundService = null
        if (isBound) {
            isBound = false
            runCatching { appCtx.unbindService(connection) }
        }
        runCatching { svc?.killSelfNow() }
        runCatching { appCtx.stopService(Intent(appCtx, PyAddonService::class.java)) }
    }

    /**
     * Parses Kodi's "https://cdn.example/video.mp4|User-Agent=Mozilla...&Referer=https%3A%2F%2F..."
     * into a clean URL + HTTP request headers map + sslIgnore flag.
     */
    data class ParsedKodiStream(
        val cleanUrl: String,
        val headers: Map<String, String>,
        val ignoreSsl: Boolean
    )

    fun parseKodiStreamUrl(rawUrl: String): ParsedKodiStream {
        val trimmed = rawUrl.trim()
        val pipeIdx = trimmed.lastIndexOf('|')
        if (pipeIdx < 0) {
            return ParsedKodiStream(trimmed, emptyMap(), ignoreSsl = false)
        }
        val paramPart = trimmed.substring(pipeIdx + 1)
        val eqFirst = paramPart.indexOf('=')
        val slashFirst = paramPart.indexOf('/')
        if (eqFirst <= 0 || (slashFirst in 0 until eqFirst)) {
            return ParsedKodiStream(trimmed, emptyMap(), ignoreSsl = false)
        }
        val cleanUrl = trimmed.substring(0, pipeIdx).trim()
        val headers = LinkedHashMap<String, String>()
        var ignoreSsl = false

        for (pair in paramPart.split('&')) {
            if (pair.isBlank()) continue
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val rawKey = pair.substring(0, eq).trim()
            val rawVal = pair.substring(eq + 1).trim()
            val decodedVal = runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
            when (rawKey.lowercase()) {
                "verifypeer" -> {
                    if (decodedVal.equals("false", ignoreCase = true) || decodedVal == "0") {
                        ignoreSsl = true
                    }
                }
                "seekable", "connection-timeout" -> Unit
                else -> headers[rawKey] = decodedVal
            }
        }
        return ParsedKodiStream(cleanUrl, headers, ignoreSsl)
    }

    private fun createUiCallback(activity: Activity): IPyDialogCallback.Stub {
        return object : IPyDialogCallback.Stub() {
            override fun onSelect(heading: String, options: MutableList<String>?): Int {
                val opts = options?.toList().orEmpty()
                if (opts.isEmpty()) return -1
                if (activity.isFinishing || activity.isDestroyed) return 0
                val latch = CountDownLatch(1)
                val selected = AtomicInteger(-1)
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        latch.countDown()
                        return@runOnUiThread
                    }
                    TvUiKit.showSelectDialog(activity, heading.ifBlank { "Select Option" }, opts) { idx ->
                        selected.set(idx)
                        latch.countDown()
                    }
                }
                runCatching { latch.await() }
                return selected.get()
            }

            override fun onInput(heading: String, defaultText: String?, hidden: Boolean): String {
                if (activity.isFinishing || activity.isDestroyed) return "__CANCELLED__"
                val latch = CountDownLatch(1)
                val result = AtomicReference("__CANCELLED__")
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        latch.countDown()
                        return@runOnUiThread
                    }
                    TvUiKit.showInputDialog(
                        context = activity,
                        title = heading.ifBlank { "Enter Search Text" },
                        initialValue = defaultText.orEmpty(),
                        hint = "Type with remote or voice...",
                        onSubmit = { text ->
                            result.set(text)
                            latch.countDown()
                        },
                        onCancel = {
                            result.set("__CANCELLED__")
                            latch.countDown()
                        }
                    )
                }
                runCatching { latch.await() }
                return result.get()
            }

            override fun onYesNo(heading: String, message: String?, noLabel: String?, yesLabel: String?): Boolean {
                if (activity.isFinishing || activity.isDestroyed) return true
                val latch = CountDownLatch(1)
                val confirmed = AtomicBoolean(false)
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) {
                        latch.countDown()
                        return@runOnUiThread
                    }
                    TvUiKit.showConfirmDialog(
                        context = activity,
                        title = heading.ifBlank { "Confirm" },
                        message = message.orEmpty(),
                        yesText = yesLabel?.takeIf { it.isNotBlank() } ?: "Yes",
                        noText = noLabel?.takeIf { it.isNotBlank() } ?: "No"
                    ) { yes ->
                        confirmed.set(yes)
                        latch.countDown()
                    }
                }
                runCatching { latch.await() }
                return confirmed.get()
            }

            override fun onNotification(heading: String?, message: String?) {
                val txt = listOfNotNull(heading?.takeIf { it.isNotBlank() }, message?.takeIf { it.isNotBlank() })
                    .joinToString(": ")
                if (txt.isNotBlank()) {
                    activity.runOnUiThread {
                        if (!activity.isFinishing) {
                            Toast.makeText(activity, txt, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            override fun onTextBox(heading: String?, body: String?) {
                if (activity.isFinishing || activity.isDestroyed) return
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        TvUiKit.showTextViewerDialog(
                            activity,
                            heading?.takeIf { it.isNotBlank() } ?: "Information",
                            body.orEmpty()
                        )
                    }
                }
            }
        }
    }
}
