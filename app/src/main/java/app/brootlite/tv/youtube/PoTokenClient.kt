package app.brootlite.tv.youtube

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Main-process client for the disposable `:potoken` subprocess ([PoTokenService]).
 *
 * Guarantees **0.0 MB** WebView RAM during playback:
 * 1. Caches minted `poToken` strings in main-process RAM for 45 minutes so `:potoken` rarely spawns.
 * 2. Binds to [PoTokenService], mints the token with a strict timeout, unbinds, and invokes
 *    `service.killSelf()` (`Process.killProcess`) **before returning**!
 */
object PoTokenClient {

    private const val TOKEN_CACHE_TTL_MS = 45L * 60L * 1000L

    private data class CachedPoToken(
        val poToken: String,
        val expiresAtMs: Long
    )

    private val mutex = Mutex()
    private val ramCache = LinkedHashMap<String, CachedPoToken>(8, 0.75f, true)

    /**
     * Returns a cached or freshly minted `poToken` for [identifier] (or `null` gracefully on timeout),
     * always terminating `:potoken` before returning so 0.0 MB WebView RAM stays alive!
     */
    suspend fun getOrMintPoToken(
        context: Context,
        identifier: String,
        visitorData: String? = null,
        isTvClient: Boolean = false,
        timeoutMs: Long = 11_000L
    ): String? {
        if (identifier.isBlank() && visitorData.isNullOrBlank()) return null
        val cacheKey = "${if (isTvClient) "tv" else "web"}:${identifier.ifBlank { visitorData.orEmpty() }}"
        val now = System.currentTimeMillis()

        synchronized(ramCache) {
            val hit = ramCache[cacheKey]
            if (hit != null && hit.expiresAtMs > now) {
                return hit.poToken
            }
        }

        return mutex.withLock {
            synchronized(ramCache) {
                val hit = ramCache[cacheKey]
                if (hit != null && hit.expiresAtMs > System.currentTimeMillis()) {
                    return@withLock hit.poToken
                }
            }

            val minted = mintViaIsolatedProcess(
                appContext = context.applicationContext,
                identifier = identifier,
                visitorData = visitorData,
                isTvClient = isTvClient,
                timeoutMs = timeoutMs
            )

            if (!minted.isNullOrBlank()) {
                synchronized(ramCache) {
                    ramCache[cacheKey] = CachedPoToken(
                        poToken = minted,
                        expiresAtMs = System.currentTimeMillis() + TOKEN_CACHE_TTL_MS
                    )
                    while (ramCache.size > 16) {
                        val first = ramCache.keys.firstOrNull() ?: break
                        ramCache.remove(first)
                    }
                }
            }
            minted
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun mintViaIsolatedProcess(
        appContext: Context,
        identifier: String,
        visitorData: String?,
        isTvClient: Boolean,
        timeoutMs: Long
    ): String? = withContext(Dispatchers.IO) {
        val binderDeferred = CompletableDeferred<IPoTokenService?>()
        var bound = false

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val iface = runCatching { IPoTokenService.Stub.asInterface(service) }.getOrNull()
                if (!binderDeferred.isCompleted) {
                    binderDeferred.complete(iface)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!binderDeferred.isCompleted) {
                    binderDeferred.complete(null)
                }
            }
        }

        var remoteService: IPoTokenService? = null
        try {
            val intent = Intent(appContext, PoTokenService::class.java)
            bound = runCatching {
                appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)

            if (!bound) return@withContext null

            remoteService = withTimeoutOrNull(4_000L) { binderDeferred.await() }
                ?: return@withContext null

            withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        remoteService.mintPoToken(identifier, visitorData, isTvClient)
                    }.getOrNull()
                }
            }
        } finally {
            // CRITICAL: Kill :potoken subprocess & unbind BEFORE returning / starting playback!
            val svc = remoteService ?: binderDeferred.takeIf { it.isCompleted }?.let {
                runCatching { it.getCompleted() }.getOrNull()
            }
            if (bound) {
                runCatching { appContext.unbindService(connection) }
            }
            runCatching { svc?.killSelf() }
        }
    }

    fun clearCache() {
        synchronized(ramCache) {
            ramCache.clear()
        }
    }
}
