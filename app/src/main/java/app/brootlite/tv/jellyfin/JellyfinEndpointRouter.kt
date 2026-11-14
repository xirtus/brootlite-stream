package app.brootlite.tv.jellyfin

import android.os.SystemClock
import app.brootlite.tv.BrootLiteApp
import app.brootlite.tv.data.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Multi-Server Jellyfin LAN auto-discovery (`UDP 7359`) + split-horizon LAN/WAN endpoint router.
 *
 * Probes `localUrl` with a `1,200 ms` timeout (verifying `System/Info/Public` `Id == profile.serverId`
 * so foreign LANs never spoof a server) and caches the winning `(baseUrl, isLan)` route for 5 minutes.
 */
object JellyfinEndpointRouter {

    const val DISCOVERY_PORT = 7359
    private const val DISCOVERY_MESSAGE = "Who is JellyfinServer?"
    private const val ROUTE_TTL_MS = 5L * 60_000L
    private const val LAN_PROBE_TIMEOUT_MS = 1_200L

    @Serializable
    data class ResolvedEndpoint(
        val baseUrl: String,
        val isLan: Boolean,
        val resolvedAtMs: Long = System.currentTimeMillis()
    )

    private val routeCache = ConcurrentHashMap<String, ResolvedEndpoint>()

    private val fastStandardProbeClient: OkHttpClient by lazy {
        BrootLiteApp.sharedHttpClient.newBuilder()
            .connectTimeout(LAN_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(LAN_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(LAN_PROBE_TIMEOUT_MS + 250L, TimeUnit.MILLISECONDS)
            .build()
    }

    private val fastPermissiveProbeClient: OkHttpClient by lazy {
        BrootLiteApp.sharedPermissiveHttpClient.newBuilder()
            .connectTimeout(LAN_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(LAN_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(LAN_PROBE_TIMEOUT_MS + 250L, TimeUnit.MILLISECONDS)
            .build()
    }

    fun invalidate(serverId: String? = null) {
        if (serverId.isNullOrBlank()) {
            routeCache.clear()
        } else {
            val prefix = "${serverId.trim()}|"
            routeCache.keys.removeIf { it.startsWith(prefix) }
        }
    }

    fun normalizeServerUrl(raw: String): String {
        var clean = raw.trim()
        if (clean.isEmpty()) return ""
        if (!clean.startsWith("http://", ignoreCase = true) &&
            !clean.startsWith("https://", ignoreCase = true)
        ) {
            val hostPart = clean.substringBefore('/')
            val useHttp = isLikelyPrivateHost(hostPart) || hostPart.endsWith(":8096")
            clean = if (useHttp) "http://$clean" else "https://$clean"
        }
        val webIdx = clean.lowercase(Locale.US).indexOf("/web/")
        if (webIdx > 8) {
            clean = clean.substring(0, webIdx)
        } else if (clean.lowercase(Locale.US).endsWith("/web")) {
            clean = clean.dropLast(4)
        }
        return clean.trimEnd('/')
    }

    fun candidateProbeUrls(rawInput: String): List<String> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return emptyList()
        val primary = normalizeServerUrl(trimmed)
        val out = LinkedHashSet<String>()
        if (primary.isNotEmpty()) out.add(primary)

        val noScheme = trimmed.removePrefix("http://").removePrefix("https://").trimEnd('/')
        val hasExplicitPort = noScheme.substringBefore('/').contains(':')
        if (!hasExplicitPort) {
            val hostOnly = noScheme.substringBefore('/')
            val subPath = noScheme.substringAfter('/', "").let { if (it.isEmpty()) "" else "/$it" }
            if (isLikelyPrivateHost(hostOnly)) {
                out.add("http://$hostOnly:8096$subPath")
                out.add("https://$hostOnly:8920$subPath")
            } else {
                out.add("https://$hostOnly$subPath")
                out.add("http://$hostOnly:8096$subPath")
            }
        }
        return out.toList()
    }

    fun isLikelyLanUrl(url: String): Boolean {
        val hostPort = url
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
        return isLikelyPrivateHost(hostPort)
    }

    private fun isLikelyPrivateHost(hostPort: String): Boolean {
        val host = hostPort.substringBefore(':').lowercase(Locale.US).trim()
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "127.0.0.1" || host.endsWith(".local") || host.endsWith(".lan") || !host.contains('.')) {
            return true
        }
        if (host.startsWith("192.168.") || host.startsWith("10.")) return true
        if (host.startsWith("172.")) {
            val secondOctet = host.removePrefix("172.").substringBefore('.').toIntOrNull()
            if (secondOctet != null && secondOctet in 16..31) return true
        }
        if (host.startsWith("100.")) {
            val secondOctet = host.removePrefix("100.").substringBefore('.').toIntOrNull()
            if (secondOctet != null && secondOctet in 64..127) return true // Tailscale / CGNAT
        }
        return false
    }

    /**
     * Broadcasts `"Who is JellyfinServer?"` over UDP port `7359` on `255.255.255.255` and all
     * active IPv4 subnet broadcast addresses, collecting responses within `timeoutMs`.
     */
    suspend fun discoverLanServers(timeoutMs: Long = 900L): List<JfLanDiscoveredServer> =
        withContext(Dispatchers.IO) {
            val discovered = LinkedHashMap<String, JfLanDiscoveredServer>()
            val effectiveTimeout = timeoutMs.coerceIn(250L, 4_000L)
            val probeBytes = DISCOVERY_MESSAGE.toByteArray(StandardCharsets.UTF_8)

            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.reuseAddress = true
                    socket.soTimeout = 200

                    val targets = collectBroadcastTargets()
                    for (addr in targets) {
                        runCatching {
                            val packet = DatagramPacket(probeBytes, probeBytes.size, addr, DISCOVERY_PORT)
                            socket.send(packet)
                        }
                    }

                    val deadline = SystemClock.elapsedRealtime() + effectiveTimeout
                    val recvBuf = ByteArray(2048)

                    while (SystemClock.elapsedRealtime() < deadline) {
                        val remaining = (deadline - SystemClock.elapsedRealtime()).toInt().coerceIn(60, 250)
                        socket.soTimeout = remaining
                        val pkt = DatagramPacket(recvBuf, recvBuf.size)
                        try {
                            socket.receive(pkt)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        if (pkt.length <= 0) continue
                        val jsonText = String(pkt.data, pkt.offset, pkt.length, StandardCharsets.UTF_8).trim()
                        val parsed = runCatching {
                            AppPrefs.json.decodeFromString<JfLanDiscoveredServer>(jsonText)
                        }.getOrNull() ?: continue

                        val normalizedAddr = normalizeServerUrl(
                            parsed.Address.ifBlank {
                                pkt.address?.hostAddress?.let { "http://$it:8096" }.orEmpty()
                            }
                        )
                        if (normalizedAddr.isBlank()) continue
                        val dedupeKey = parsed.Id.ifBlank { normalizedAddr.lowercase(Locale.US) }
                        if (!discovered.containsKey(dedupeKey)) {
                            discovered[dedupeKey] = parsed.copy(
                                Address = normalizedAddr,
                                Name = parsed.Name.ifBlank { "Jellyfin LAN Server" }
                            )
                        }
                    }
                }
            }
            discovered.values.toList()
        }

    /**
     * Resolves the active base URL for `profile`, probing `localUrl` first (`1,200 ms` max, verifying
     * `System/Info/Public.Id == profile.serverId`) and caching the resolved route for 5 minutes.
     */
    suspend fun resolveActiveEndpoint(
        profile: JellyfinServerProfile,
        forceRefresh: Boolean = false
    ): ResolvedEndpoint = withContext(Dispatchers.IO) {
        val local = normalizeServerUrl(profile.localUrl)
        val remote = normalizeServerUrl(profile.remoteUrl)
        val primary = normalizeServerUrl(profile.primaryUrl)
        val cacheKey = "${profile.serverId}|$local|$remote|$primary|${profile.allowInsecureTls}"

        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = routeCache[cacheKey]
            if (cached != null && now - cached.resolvedAtMs < ROUTE_TTL_MS && cached.baseUrl.isNotBlank()) {
                return@withContext cached
            }
        }

        if (local.isNotBlank()) {
            if (probeExpectedServer(local, profile.serverId, profile.allowInsecureTls)) {
                val hit = ResolvedEndpoint(baseUrl = local, isLan = true, resolvedAtMs = now)
                routeCache[cacheKey] = hit
                return@withContext hit
            }
        }

        // If localUrl failed (or was unset) and primaryUrl is distinct from localUrl, choose between remoteUrl & primaryUrl
        val fallbackCandidates = listOf(remote, primary, local)
            .filter { it.isNotBlank() }
            .distinct()

        val chosen = when {
            remote.isNotBlank() && local.isNotBlank() -> remote
            primary.isNotBlank() && primary != local -> primary
            remote.isNotBlank() -> remote
            else -> fallbackCandidates.firstOrNull().orEmpty()
        }

        val resolved = ResolvedEndpoint(
            baseUrl = chosen,
            isLan = isLikelyLanUrl(chosen) && chosen == local,
            resolvedAtMs = now
        )
        if (chosen.isNotBlank()) {
            routeCache[cacheKey] = resolved
        }
        resolved
    }

    suspend fun resolveBaseUrl(profile: JellyfinServerProfile, forceRefresh: Boolean = false): String =
        resolveActiveEndpoint(profile, forceRefresh).baseUrl

    private fun probeExpectedServer(
        candidateBaseUrl: String,
        expectedServerId: String,
        allowInsecureTls: Boolean
    ): Boolean {
        return runCatching {
            val client = if (allowInsecureTls) fastPermissiveProbeClient else fastStandardProbeClient
            val req = Request.Builder()
                .url("${candidateBaseUrl.trimEnd('/')}/System/Info/Public")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) return@use false
                val info = AppPrefs.json.decodeFromString<JfPublicSystemInfo>(raw)
                if (info.Id.isBlank()) return@use false
                expectedServerId.isBlank() || info.Id.equals(expectedServerId, ignoreCase = true)
            }
        }.getOrDefault(false)
    }

    private fun collectBroadcastTargets(): List<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()
        runCatching {
            targets.add(InetAddress.getByName("255.255.255.255"))
        }
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    val bcast = ia.broadcast
                    if (bcast is Inet4Address) {
                        targets.add(bcast)
                    }
                }
            }
        }
        return targets.toList()
    }
}
