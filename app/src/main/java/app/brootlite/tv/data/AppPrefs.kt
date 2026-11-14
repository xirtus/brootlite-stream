package app.brootlite.tv.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import app.brootlite.tv.jellyfin.JellyfinEndpointRouter
import app.brootlite.tv.jellyfin.JellyfinServerProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class SyncedYouTubeOAuth(
    val accountLabel: String = "",
    val channelId: String = "",
    val pageId: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val refreshToken: String = "",
    val accessToken: String = "",
    @kotlinx.serialization.json.JsonNames("accessTokenExpiresAtMs", "expiresAtMs")
    val accessTokenExpiresAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val revokedAtMs: Long = 0L
) {
    val isActive: Boolean
        get() = refreshToken.isNotBlank() && revokedAtMs <= updatedAtMs

    val stableKey: String
        get() = channelId.ifBlank { accountLabel.ifBlank { "primary" } }
}

@Serializable
data class SyncedTwitchDeviceOAuth(
    val twitchUserId: String = "",
    val login: String = "",
    val displayName: String = "",
    val clientId: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val updatedAtMs: Long = 0L,
    val revokedAtMs: Long = 0L
) {
    val isActive: Boolean
        get() = (accessToken.isNotBlank() || refreshToken.isNotBlank()) && revokedAtMs <= updatedAtMs
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class CreatorFollowEntry(
    @kotlinx.serialization.json.JsonNames("platform", "provider")
    val platform: String, // "youtube" | "twitch" | "odysee" | "rumble"
    val channelId: String = "",
    val handle: String = "",
    val displayName: String = "",
    val avatarUrl: String = "",
    val addedAtMs: Long = System.currentTimeMillis(),
    val removedAtMs: Long = 0L,
    val id: String = "",
    val profileUrl: String = ""
) {
    val canonicalKey: String
        get() = "${platform.trim().lowercase(Locale.US)}:${channelId.ifBlank { handle.ifBlank { id } }.trim()}"

    val isActive: Boolean
        get() = removedAtMs <= addedAtMs
}

object AppPrefs {
    private const val PREFS_NAME = "brootlite_prefs"
    private const val KEY_AUTH = "stremio_auth_key"
    private const val KEY_EMAIL = "stremio_user_email"
    private const val KEY_AUTH_UPDATED_AT = "stremio_auth_updated_at_ms"
    private const val KEY_AUTH_REVOKED_AT = "stremio_auth_revoked_at_ms"
    private const val KEY_ADDONS = "installed_addons_json"
    private const val KEY_HISTORY = "watch_history_json"
    private const val KEY_FAVORITES = "favorites_json"
    private const val KEY_PREBUFFER_MB = "torrent_prebuffer_mb"
    private const val KEY_WINDOW_MB = "torrent_window_mb"
    private const val KEY_MAX_CONN = "torrent_max_conn"
    private const val KEY_TORZNAB_URL = "torznab_url"
    private const val KEY_TORZNAB_KEY = "torznab_api_key"
    private const val KEY_MULTIPASS = "multipass_profile_json"
    private const val KEY_MULTIPASS_VMK = "multipass_vmk_sealed_v2"
    private const val KEY_MULTIPASS_KEYSLOTS = "multipass_keyslots_json_v2"

    private const val KEY_JELLYFIN_DEVICE_ID = "jellyfin_device_id"
    private const val KEY_JELLYFIN_SERVERS = "jellyfin_servers_json"
    private const val KEY_JELLYFIN_TOMBSTONES = "jellyfin_tombstones_json"

    private const val KEY_YOUTUBE_ACCOUNTS = "youtube_oauth_accounts_json"
    private const val KEY_TWITCH_DEVICE_OAUTH = "twitch_device_oauth_json"
    private const val KEY_CREATOR_FOLLOWS = "creator_follows_json"

    private const val KEY_SPONSORBLOCK_ENABLED = "yt_sponsorblock_enabled"
    private const val KEY_YT_WATCH_HISTORY_ENABLED = "yt_watch_history_enabled"
    private const val KEY_RYD_ENABLED = "yt_return_dislike_enabled"
    private const val KEY_DEARROW_ENABLED = "yt_dearrow_enabled"
    private const val KEY_STREAMING_PREFS_UPDATED_AT = "streaming_prefs_updated_at_ms"

    private const val AAD_STREMIO_AUTH = "brootlite:stremio-auth:v2"
    private const val AAD_MULTIPASS_TOKEN = "brootlite:multipass-token:v2"
    private const val AAD_MULTIPASS_VMK = "brootlite:multipass-vmk:v2"
    private const val AAD_JELLYFIN_TOKEN = "brootlite:jellyfin-token:v2"
    private const val AAD_YOUTUBE_OAUTH = "brootlite:youtube-oauth:v2"
    private const val AAD_TWITCH_OAUTH = "brootlite:twitch-oauth:v2"
    private const val AAD_TORZNAB_KEY = "brootlite:torznab-key:v2"

    const val STREAMING_CATALOGS_MANIFEST_URL =
        "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/bmZ4LGhibSxkbnAsYW1wLGF0cCxwbXAscGNwLGhsdSxjcnUsc3R6LGRwZSxtYmksc2hkLGNyYzo6VVM6MTczMTM2MDAwMDAwMDoxOjA6/manifest.json"

    val CINEMETA_MOVIE_GENRES = listOf(
        "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime",
        "Documentary", "Drama", "Family", "Fantasy", "History", "Horror",
        "Mystery", "Romance", "Sci-Fi", "Sport", "Thriller", "War", "Western"
    )

    val CINEMETA_SERIES_GENRES = listOf(
        "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime",
        "Documentary", "Drama", "Family", "Fantasy", "Game-Show", "History",
        "Horror", "Mystery", "Reality-TV", "Romance", "Sci-Fi", "Sport",
        "Talk-Show", "Thriller", "War", "Western"
    )

    val CINEMETA_YEARS: List<String> = (2026 downTo 1950).map { it.toString() }

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // =========================================================================
    // STREMIO AUTH (Keystore-sealed with automatic transparent migration)
    // =========================================================================

    fun getStremioAuthKey(context: Context): String? {
        val p = prefs(context)
        val raw = p.getString(KEY_AUTH, null)?.takeIf { it.isNotBlank() } ?: return null
        if (!MultipassHardwareKeystore.isSealed(raw)) {
            val sealed = MultipassHardwareKeystore.sealString(raw, AAD_STREMIO_AUTH, context)
            if (sealed.isNotEmpty()) {
                val ed = p.edit().putString(KEY_AUTH, sealed)
                if (p.getLong(KEY_AUTH_UPDATED_AT, 0L) == 0L) {
                    ed.putLong(KEY_AUTH_UPDATED_AT, 1L)
                }
                ed.apply()
            }
            return raw
        }
        return MultipassHardwareKeystore.unsealString(raw, AAD_STREMIO_AUTH, context)?.takeIf { it.isNotBlank() }
    }

    fun getStremioEmail(context: Context): String? =
        prefs(context).getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() }

    fun getStremioAuthUpdatedAtMs(context: Context): Long =
        prefs(context).getLong(KEY_AUTH_UPDATED_AT, 0L)

    fun getStremioAuthRevokedAtMs(context: Context): Long =
        prefs(context).getLong(KEY_AUTH_REVOKED_AT, 0L)

    fun setStremioAccount(context: Context, authKey: String?, email: String?) {
        val now = System.currentTimeMillis()
        val cleanKey = authKey?.trim().orEmpty()
        if (cleanKey.isEmpty()) {
            setStremioAccountWithMeta(context, "", "", updatedAtMs = 0L, revokedAtMs = now)
        } else {
            setStremioAccountWithMeta(context, cleanKey, email, updatedAtMs = now, revokedAtMs = 0L)
        }
    }

    fun setStremioAccountWithMeta(
        context: Context,
        authKey: String?,
        email: String?,
        updatedAtMs: Long,
        revokedAtMs: Long = 0L
    ) {
        val cleanKey = authKey?.trim().orEmpty()
        val sealedKey = if (cleanKey.isNotEmpty()) {
            MultipassHardwareKeystore.sealString(cleanKey, AAD_STREMIO_AUTH, context)
        } else {
            ""
        }
        prefs(context).edit()
            .putString(KEY_AUTH, sealedKey)
            .putString(KEY_EMAIL, if (cleanKey.isNotEmpty()) (email ?: "") else "")
            .putLong(KEY_AUTH_UPDATED_AT, updatedAtMs.coerceAtLeast(0L))
            .putLong(KEY_AUTH_REVOKED_AT, revokedAtMs.coerceAtLeast(0L))
            .apply()
    }

    // =========================================================================
    // MULTIPASS PROFILE, VMK (32B) & KEYSLOTS (Keystore-sealed)
    // =========================================================================

    fun getMultipassProfile(context: Context): MultipassProfile {
        val p = prefs(context)
        val raw = p.getString(KEY_MULTIPASS, null) ?: return MultipassProfile()
        val decoded = runCatching { json.decodeFromString<MultipassProfile>(raw) }
            .getOrDefault(MultipassProfile())
        if (decoded.pairToken.isBlank()) return decoded

        return if (!MultipassHardwareKeystore.isSealed(decoded.pairToken)) {
            // Auto-migrate existing plaintext mpd_... pairToken seamlessly
            val sealed = MultipassHardwareKeystore.sealString(decoded.pairToken, AAD_MULTIPASS_TOKEN, context)
            if (sealed.isNotEmpty()) {
                p.edit()
                    .putString(KEY_MULTIPASS, json.encodeToString(decoded.copy(pairToken = sealed)))
                    .apply()
            }
            decoded
        } else {
            val unsealed = MultipassHardwareKeystore.unsealString(
                decoded.pairToken,
                AAD_MULTIPASS_TOKEN,
                context
            ).orEmpty()
            decoded.copy(pairToken = unsealed)
        }
    }

    fun saveMultipassProfile(context: Context, profile: MultipassProfile) {
        val sealedToken = if (profile.pairToken.isNotBlank()) {
            MultipassHardwareKeystore.sealString(profile.pairToken.trim(), AAD_MULTIPASS_TOKEN, context)
        } else {
            ""
        }
        val toStore = profile.copy(pairToken = sealedToken)
        prefs(context).edit()
            .putString(KEY_MULTIPASS, json.encodeToString(toStore))
            .apply()
    }

    fun saveMultipassVmk(context: Context, vmk32B: ByteArray) {
        require(vmk32B.size == 32) { "Multipass VMK must be 32 bytes" }
        val sealed = MultipassHardwareKeystore.sealBytes(
            bytes = vmk32B,
            aad = AAD_MULTIPASS_VMK.toByteArray(StandardCharsets.UTF_8),
            context = context
        )
        prefs(context).edit()
            .putString(KEY_MULTIPASS_VMK, sealed)
            .apply()
    }

    fun getMultipassVmk(context: Context): ByteArray? {
        val sealed = prefs(context).getString(KEY_MULTIPASS_VMK, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val bytes = MultipassHardwareKeystore.unsealBytes(
            sealed = sealed,
            aad = AAD_MULTIPASS_VMK.toByteArray(StandardCharsets.UTF_8),
            context = context
        ) ?: return null
        return if (bytes.size == 32) bytes else {
            bytes.fill(0)
            null
        }
    }

    fun clearMultipassVmk(context: Context) {
        prefs(context).edit()
            .remove(KEY_MULTIPASS_VMK)
            .remove(KEY_MULTIPASS_KEYSLOTS)
            .apply()
    }

    fun saveMultipassKeySlotsJson(context: Context, keySlotsJson: String) {
        prefs(context).edit()
            .putString(KEY_MULTIPASS_KEYSLOTS, keySlotsJson.trim())
            .apply()
    }

    fun getMultipassKeySlotsJson(context: Context): String? =
        prefs(context).getString(KEY_MULTIPASS_KEYSLOTS, null)?.takeIf { it.isNotBlank() }

    // =========================================================================
    // MULTI-SERVER JELLYFIN PROFILES (Keystore-sealed tokens, strictly separated)
    // =========================================================================

    @Synchronized
    fun getOrCreateJellyfinDeviceId(context: Context): String {
        val p = prefs(context)
        val existing = p.getString(KEY_JELLYFIN_DEVICE_ID, null)?.takeIf { it.isNotBlank() }
        if (existing != null) return existing
        val created = "brootlite-${UUID.randomUUID()}"
        p.edit().putString(KEY_JELLYFIN_DEVICE_ID, created).apply()
        return created
    }

    fun getJellyfinServers(context: Context): List<JellyfinServerProfile> {
        val p = prefs(context)
        val raw = p.getString(KEY_JELLYFIN_SERVERS, null) ?: return emptyList()
        val storedList = runCatching {
            json.decodeFromString<List<JellyfinServerProfile>>(raw)
        }.getOrDefault(emptyList())
        if (storedList.isEmpty()) return emptyList()

        var needsReseal = false
        val unsealed = storedList.map { srv ->
            if ((srv.accessToken.isNotBlank() && !MultipassHardwareKeystore.isSealed(srv.accessToken)) ||
                (srv.bootstrapToken.isNotBlank() && !MultipassHardwareKeystore.isSealed(srv.bootstrapToken))
            ) {
                needsReseal = true
            }
            val clearAccess = MultipassHardwareKeystore.unsealString(
                srv.accessToken,
                "$AAD_JELLYFIN_TOKEN:${srv.serverId}:access",
                context
            ).orEmpty()
            val clearBootstrap = MultipassHardwareKeystore.unsealString(
                srv.bootstrapToken,
                "$AAD_JELLYFIN_TOKEN:${srv.serverId}:bootstrap",
                context
            ).orEmpty()
            srv.copy(accessToken = clearAccess, bootstrapToken = clearBootstrap)
        }

        if (needsReseal) {
            saveJellyfinServers(context, unsealed)
        }
        return unsealed
    }

    fun getJellyfinServer(context: Context, serverId: String): JellyfinServerProfile? =
        getJellyfinServers(context).firstOrNull { it.serverId == serverId }

    fun saveJellyfinServers(context: Context, servers: List<JellyfinServerProfile>) {
        val deduped = LinkedHashMap<String, JellyfinServerProfile>()
        for (srv in servers) {
            if (srv.serverId.isBlank()) continue
            val sealedAccess = if (srv.accessToken.isNotBlank()) {
                MultipassHardwareKeystore.sealString(
                    srv.accessToken.trim(),
                    "$AAD_JELLYFIN_TOKEN:${srv.serverId}:access",
                    context
                )
            } else {
                ""
            }
            val sealedBootstrap = if (srv.bootstrapToken.isNotBlank()) {
                MultipassHardwareKeystore.sealString(
                    srv.bootstrapToken.trim(),
                    "$AAD_JELLYFIN_TOKEN:${srv.serverId}:bootstrap",
                    context
                )
            } else {
                ""
            }
            deduped[srv.serverId] = srv.copy(
                accessToken = sealedAccess,
                bootstrapToken = sealedBootstrap
            )
        }
        prefs(context).edit()
            .putString(KEY_JELLYFIN_SERVERS, json.encodeToString(deduped.values.toList()))
            .apply()
    }

    fun upsertJellyfinServer(context: Context, server: JellyfinServerProfile) {
        if (server.serverId.isBlank()) return
        val now = System.currentTimeMillis()
        val stamped = server.copy(
            updatedAtMs = if (server.updatedAtMs > 0L) server.updatedAtMs else now
        )
        val list = getJellyfinServers(context).toMutableList()
        val idx = list.indexOfFirst { it.serverId == stamped.serverId }
        if (idx >= 0) {
            list[idx] = stamped
        } else {
            list.add(stamped)
        }
        saveJellyfinServers(context, list)

        val tombstones = getJellyfinTombstones(context).toMutableMap()
        if (tombstones.remove(stamped.serverId) != null) {
            saveJellyfinTombstones(context, tombstones)
        }
        JellyfinEndpointRouter.invalidate(stamped.serverId)
    }

    fun removeJellyfinServer(context: Context, serverId: String) {
        if (serverId.isBlank()) return
        val now = System.currentTimeMillis()
        val remaining = getJellyfinServers(context).filterNot { it.serverId == serverId }
        saveJellyfinServers(context, remaining)

        val tombstones = getJellyfinTombstones(context).toMutableMap()
        tombstones[serverId] = now
        saveJellyfinTombstones(context, tombstones)
        JellyfinEndpointRouter.invalidate(serverId)
    }

    fun getJellyfinTombstones(context: Context): Map<String, Long> {
        val raw = prefs(context).getString(KEY_JELLYFIN_TOMBSTONES, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, Long>>(raw)
        }.getOrDefault(emptyMap())
    }

    fun saveJellyfinTombstones(context: Context, tombstones: Map<String, Long>) {
        prefs(context).edit()
            .putString(KEY_JELLYFIN_TOMBSTONES, json.encodeToString(tombstones))
            .apply()
    }

    // =========================================================================
    // YOUTUBE TV OAUTH, TWITCH PER-DEVICE OAUTH & CREATOR FOLLOWS
    // =========================================================================

    fun getYouTubeAccounts(context: Context): List<SyncedYouTubeOAuth> {
        val raw = prefs(context).getString(KEY_YOUTUBE_ACCOUNTS, null) ?: return emptyList()
        val stored = runCatching {
            json.decodeFromString<List<SyncedYouTubeOAuth>>(raw)
        }.getOrDefault(emptyList())
        return stored.map { acc ->
            acc.copy(
                clientSecret = MultipassHardwareKeystore.unsealString(
                    acc.clientSecret,
                    "$AAD_YOUTUBE_OAUTH:secret",
                    context
                ).orEmpty(),
                refreshToken = MultipassHardwareKeystore.unsealString(
                    acc.refreshToken,
                    "$AAD_YOUTUBE_OAUTH:refresh",
                    context
                ).orEmpty(),
                accessToken = MultipassHardwareKeystore.unsealString(
                    acc.accessToken,
                    "$AAD_YOUTUBE_OAUTH:access",
                    context
                ).orEmpty()
            )
        }
    }

    fun saveYouTubeAccounts(context: Context, accounts: List<SyncedYouTubeOAuth>) {
        val byKey = LinkedHashMap<String, SyncedYouTubeOAuth>()
        for (acc in accounts) {
            val sealed = acc.copy(
                clientSecret = MultipassHardwareKeystore.sealString(acc.clientSecret, "$AAD_YOUTUBE_OAUTH:secret", context),
                refreshToken = MultipassHardwareKeystore.sealString(acc.refreshToken, "$AAD_YOUTUBE_OAUTH:refresh", context),
                accessToken = MultipassHardwareKeystore.sealString(acc.accessToken, "$AAD_YOUTUBE_OAUTH:access", context)
            )
            byKey[acc.stableKey] = sealed
        }
        prefs(context).edit()
            .putString(KEY_YOUTUBE_ACCOUNTS, json.encodeToString(byKey.values.toList()))
            .apply()
    }

    fun getYouTubeOAuth(context: Context): SyncedYouTubeOAuth? =
        getYouTubeAccounts(context).firstOrNull { it.isActive }

    fun saveYouTubeOAuth(context: Context, oauth: SyncedYouTubeOAuth?) {
        if (oauth == null) {
            revokeYouTubeOAuth(context)
            return
        }
        val now = System.currentTimeMillis()
        val stamped = oauth.copy(updatedAtMs = if (oauth.updatedAtMs > 0L) oauth.updatedAtMs else now)
        val existing = getYouTubeAccounts(context).toMutableList()
        val idx = existing.indexOfFirst { it.stableKey == stamped.stableKey }
        if (idx >= 0) {
            existing[idx] = stamped
        } else {
            existing.add(0, stamped)
        }
        saveYouTubeAccounts(context, existing)
    }

    fun revokeYouTubeOAuth(context: Context) {
        val now = System.currentTimeMillis()
        val updated = getYouTubeAccounts(context).map { acc ->
            val ts = maxOf(now, acc.updatedAtMs + 1L)
            acc.copy(
                refreshToken = "",
                accessToken = "",
                accessTokenExpiresAtMs = 0L,
                revokedAtMs = ts
            )
        }
        saveYouTubeAccounts(context, updated)
    }

    fun getTwitchDeviceOAuth(context: Context): SyncedTwitchDeviceOAuth? {
        val raw = prefs(context).getString(KEY_TWITCH_DEVICE_OAUTH, null) ?: return null
        val stored = runCatching {
            json.decodeFromString<SyncedTwitchDeviceOAuth>(raw)
        }.getOrNull() ?: return null
        return stored.copy(
            accessToken = MultipassHardwareKeystore.unsealString(
                stored.accessToken,
                "$AAD_TWITCH_OAUTH:access",
                context
            ).orEmpty(),
            refreshToken = MultipassHardwareKeystore.unsealString(
                stored.refreshToken,
                "$AAD_TWITCH_OAUTH:refresh",
                context
            ).orEmpty()
        )
    }

    fun saveTwitchDeviceOAuth(context: Context, oauth: SyncedTwitchDeviceOAuth?) {
        if (oauth == null) {
            revokeTwitchDeviceOAuth(context)
            return
        }
        val now = System.currentTimeMillis()
        val stamped = oauth.copy(
            updatedAtMs = if (oauth.updatedAtMs > 0L) oauth.updatedAtMs else now,
            accessToken = MultipassHardwareKeystore.sealString(oauth.accessToken, "$AAD_TWITCH_OAUTH:access", context),
            refreshToken = MultipassHardwareKeystore.sealString(oauth.refreshToken, "$AAD_TWITCH_OAUTH:refresh", context)
        )
        prefs(context).edit()
            .putString(KEY_TWITCH_DEVICE_OAUTH, json.encodeToString(stamped))
            .apply()
    }

    fun revokeTwitchDeviceOAuth(context: Context) {
        val prev = getTwitchDeviceOAuth(context) ?: return
        val ts = maxOf(System.currentTimeMillis(), prev.updatedAtMs + 1L)
        saveTwitchDeviceOAuth(
            context,
            prev.copy(
                accessToken = "",
                refreshToken = "",
                revokedAtMs = ts
            )
        )
    }

    fun getCreatorFollows(context: Context, includeTombstones: Boolean = false): List<CreatorFollowEntry> {
        val raw = prefs(context).getString(KEY_CREATOR_FOLLOWS, null) ?: return emptyList()
        val parsed = runCatching {
            json.decodeFromString<List<CreatorFollowEntry>>(raw)
        }.getOrDefault(emptyList())
        return if (includeTombstones) {
            parsed
        } else {
            parsed.filter { it.isActive }.sortedByDescending { it.addedAtMs }
        }
    }

    fun saveCreatorFollows(context: Context, entries: List<CreatorFollowEntry>) {
        val byKey = LinkedHashMap<String, CreatorFollowEntry>()
        for (e in entries) {
            val resolvedId = e.channelId.ifBlank { e.handle.ifBlank { e.id } }.trim()
            if (e.platform.isBlank() || resolvedId.isBlank()) continue
            val k = e.canonicalKey
            val prev = byKey[k]
            if (prev == null || maxOf(e.addedAtMs, e.removedAtMs) >= maxOf(prev.addedAtMs, prev.removedAtMs)) {
                byKey[k] = e.copy(
                    platform = e.platform.trim().lowercase(Locale.US),
                    channelId = resolvedId
                )
            }
        }
        val trimmed = byKey.values
            .sortedByDescending { maxOf(it.addedAtMs, it.removedAtMs) }
            .take(500)
        prefs(context).edit()
            .putString(KEY_CREATOR_FOLLOWS, json.encodeToString(trimmed))
            .apply()
    }

    fun isCreatorFollowed(context: Context, platform: String, channelId: String): Boolean {
        val target = "${platform.trim().lowercase(Locale.US)}:${channelId.trim()}"
        return getCreatorFollows(context, includeTombstones = false).any { it.canonicalKey == target }
    }

    fun followCreator(context: Context, entry: CreatorFollowEntry) {
        val all = getCreatorFollows(context, includeTombstones = true).toMutableList()
        val key = entry.canonicalKey
        val existing = all.firstOrNull { it.canonicalKey == key }
        val now = System.currentTimeMillis()
        val minTs = if (existing != null) maxOf(now, existing.removedAtMs + 1L) else now
        all.removeAll { it.canonicalKey == key }
        all.add(
            0,
            entry.copy(
                platform = entry.platform.trim().lowercase(Locale.US),
                channelId = entry.channelId.trim(),
                addedAtMs = minTs,
                removedAtMs = 0L
            )
        )
        saveCreatorFollows(context, all)
    }

    fun unfollowCreator(context: Context, platform: String, channelId: String) {
        val key = "${platform.trim().lowercase(Locale.US)}:${channelId.trim()}"
        val all = getCreatorFollows(context, includeTombstones = true).toMutableList()
        val existing = all.firstOrNull { it.canonicalKey == key } ?: return
        val removeTs = maxOf(System.currentTimeMillis(), existing.addedAtMs + 1L)
        all.removeAll { it.canonicalKey == key }
        all.add(0, existing.copy(removedAtMs = removeTs))
        saveCreatorFollows(context, all)
    }

    fun toggleCreatorFollow(context: Context, entry: CreatorFollowEntry): Boolean {
        return if (isCreatorFollowed(context, entry.platform, entry.channelId)) {
            unfollowCreator(context, entry.platform, entry.channelId)
            false
        } else {
            followCreator(context, entry)
            true
        }
    }

    // =========================================================================
    // STREAMING / YOUTUBE SETTINGS TOGGLES
    // =========================================================================

    fun isSponsorBlockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPONSORBLOCK_ENABLED, true)

    fun setSponsorBlockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SPONSORBLOCK_ENABLED, enabled)
            .putLong(KEY_STREAMING_PREFS_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun isYouTubeWatchHistoryEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_YT_WATCH_HISTORY_ENABLED, true)

    fun setYouTubeWatchHistoryEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_YT_WATCH_HISTORY_ENABLED, enabled)
            .putLong(KEY_STREAMING_PREFS_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun isReturnYouTubeDislikeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RYD_ENABLED, true)

    fun setReturnYouTubeDislikeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_RYD_ENABLED, enabled)
            .putLong(KEY_STREAMING_PREFS_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun isDeArrowEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEARROW_ENABLED, false)

    fun setDeArrowEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_DEARROW_ENABLED, enabled)
            .putLong(KEY_STREAMING_PREFS_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun getStreamingPrefsUpdatedAtMs(context: Context): Long =
        prefs(context).getLong(KEY_STREAMING_PREFS_UPDATED_AT, 0L)

    fun applySyncedStreamingPrefs(
        context: Context,
        sponsorBlock: Boolean,
        youtubeWatchHistory: Boolean,
        returnYouTubeDislike: Boolean,
        deArrow: Boolean,
        updatedAtMs: Long
    ) {
        prefs(context).edit()
            .putBoolean(KEY_SPONSORBLOCK_ENABLED, sponsorBlock)
            .putBoolean(KEY_YT_WATCH_HISTORY_ENABLED, youtubeWatchHistory)
            .putBoolean(KEY_RYD_ENABLED, returnYouTubeDislike)
            .putBoolean(KEY_DEARROW_ENABLED, deArrow)
            .putLong(KEY_STREAMING_PREFS_UPDATED_AT, updatedAtMs.coerceAtLeast(0L))
            .apply()
    }

    // =========================================================================
    // KODI ENVIRONMENT, STREMIO ADDONS, WATCH HISTORY, FAVORITES & TORRENT
    // =========================================================================

    fun getKodiEnvDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "kodi_env")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun tryAutoImportStremioAuth(context: Context): Boolean {
        if (getStremioAuthKey(context) != null) return true
        val candidates = buildList {
            context.getExternalFilesDir(null)?.let { add(File(it, "stremio_auth.json")) }
            runCatching {
                @Suppress("DEPRECATION")
                val sd = Environment.getExternalStorageDirectory()
                add(File(sd, "Android/data/${context.packageName}/files/stremio_auth.json"))
                add(File(sd, "BrootLite/stremio_auth.json"))
            }
        }
        for (file in candidates) {
            if (file.isFile && file.canRead()) {
                runCatching {
                    val raw = file.readText().trim()
                    val obj = json.parseToJsonElement(raw).jsonObject
                    val key = obj["authKey"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    val email = obj["email"]?.jsonPrimitive?.content
                    if (key != null) {
                        setStremioAccount(context, key, email ?: "Synced Account")
                        return true
                    }
                }
            }
        }
        return false
    }

    fun defaultCinemetaAddon(): InstalledAddon = InstalledAddon(
        transportUrl = "https://v3-cinemeta.strem.io/manifest.json",
        id = "com.linvo.cinemeta",
        name = "Cinemeta",
        version = "3.0.14",
        description = "Official Stremio Movie & Series catalog (Popular, Top Rated, By Year & Genre)",
        types = listOf("movie", "series"),
        resources = listOf(
            AddonResourceSpec("catalog", listOf("movie", "series"), listOf("tt")),
            AddonResourceSpec("meta", listOf("movie", "series"), listOf("tt"))
        ),
        catalogs = listOf(
            AddonCatalogDef(
                type = "movie",
                id = "top",
                name = "Popular",
                isSearchSupported = true,
                isSkipSupported = true,
                isGenreSupported = true,
                genreOptions = CINEMETA_MOVIE_GENRES
            ),
            AddonCatalogDef(
                type = "series",
                id = "top",
                name = "Popular",
                isSearchSupported = true,
                isSkipSupported = true,
                isGenreSupported = true,
                genreOptions = CINEMETA_SERIES_GENRES
            ),
            AddonCatalogDef(
                type = "movie",
                id = "imdbRating",
                name = "Top Rated",
                isSkipSupported = true,
                isGenreSupported = true,
                genreOptions = CINEMETA_MOVIE_GENRES
            ),
            AddonCatalogDef(
                type = "series",
                id = "imdbRating",
                name = "Top Rated",
                isSkipSupported = true,
                isGenreSupported = true,
                genreOptions = CINEMETA_SERIES_GENRES
            ),
            AddonCatalogDef(
                type = "movie",
                id = "year",
                name = "Browse By Year",
                isSkipSupported = true,
                isGenreSupported = true,
                isGenreRequired = true,
                genreOptions = CINEMETA_YEARS
            ),
            AddonCatalogDef(
                type = "series",
                id = "year",
                name = "Browse By Year",
                isSkipSupported = true,
                isGenreSupported = true,
                isGenreRequired = true,
                genreOptions = CINEMETA_YEARS
            )
        ),
        idPrefixes = listOf("tt")
    )

    fun defaultStreamingCatalogsAddon(): InstalledAddon = InstalledAddon(
        transportUrl = STREAMING_CATALOGS_MANIFEST_URL,
        id = "pw.ers.netflix-catalog",
        name = "Streaming Catalogs",
        version = "1.0.0",
        description = "Netflix, HBO Max, Disney+, Prime Video, Apple TV+, Hulu, Peacock, Paramount+, Crunchyroll, Mubi & Criterion",
        types = listOf("movie", "series"),
        resources = listOf(
            AddonResourceSpec("catalog", listOf("movie", "series"), listOf("tt"))
        ),
        catalogs = listOf(
            AddonCatalogDef("movie", "netflix-top10-global", "Netflix Top 10"),
            AddonCatalogDef("series", "netflix-top10-global", "Netflix Top 10"),
            AddonCatalogDef("movie", "nfx", "Netflix"),
            AddonCatalogDef("series", "nfx", "Netflix"),
            AddonCatalogDef("movie", "amp", "Prime Video"),
            AddonCatalogDef("series", "amp", "Prime Video"),
            AddonCatalogDef("movie", "dnp", "Disney+"),
            AddonCatalogDef("series", "dnp", "Disney+"),
            AddonCatalogDef("movie", "hbm", "HBO Max"),
            AddonCatalogDef("series", "hbm", "HBO Max"),
            AddonCatalogDef("movie", "atp", "Apple TV+"),
            AddonCatalogDef("series", "atp", "Apple TV+"),
            AddonCatalogDef("movie", "hlu", "Hulu"),
            AddonCatalogDef("series", "hlu", "Hulu"),
            AddonCatalogDef("movie", "pcp", "Peacock"),
            AddonCatalogDef("series", "pcp", "Peacock"),
            AddonCatalogDef("movie", "pmp", "Paramount+"),
            AddonCatalogDef("series", "pmp", "Paramount+"),
            AddonCatalogDef("movie", "cru", "Crunchyroll"),
            AddonCatalogDef("series", "cru", "Crunchyroll"),
            AddonCatalogDef("movie", "mbi", "Mubi"),
            AddonCatalogDef("movie", "crc", "Criterion Channel"),
            AddonCatalogDef("movie", "shd", "Shudder"),
            AddonCatalogDef("series", "shd", "Shudder"),
            AddonCatalogDef("movie", "stz", "Starz"),
            AddonCatalogDef("series", "stz", "Starz"),
            AddonCatalogDef("series", "dpe", "Discovery+")
        ),
        idPrefixes = listOf("tt")
    )

    fun defaultOpenSubtitlesAddon(): InstalledAddon = InstalledAddon(
        transportUrl = "https://opensubtitles-v3.strem.io/manifest.json",
        id = "org.stremio.opensubtitlesv3",
        name = "OpenSubtitles v3",
        version = "1.0.0",
        description = "Official multi-language subtitles",
        types = listOf("movie", "series"),
        resources = listOf(
            AddonResourceSpec("subtitles", listOf("movie", "series"), listOf("tt"))
        ),
        idPrefixes = listOf("tt")
    )

    fun defaultAddons(): List<InstalledAddon> = listOf(
        defaultCinemetaAddon(),
        defaultStreamingCatalogsAddon(),
        defaultOpenSubtitlesAddon()
    )

    fun getInstalledAddons(context: Context): List<InstalledAddon> {
        val raw = prefs(context).getString(KEY_ADDONS, null)
        if (raw.isNullOrBlank()) return defaultAddons()
        return runCatching {
            val parsed = json.decodeFromString<List<InstalledAddon>>(raw)
            if (parsed.isEmpty()) defaultAddons() else mergeWithCoreDefaults(parsed)
        }.getOrElse { defaultAddons() }
    }

    fun saveInstalledAddons(context: Context, addons: List<InstalledAddon>) {
        val merged = mergeWithCoreDefaults(addons)
        prefs(context).edit()
            .putString(KEY_ADDONS, json.encodeToString(merged))
            .apply()
    }

    private fun mergeWithCoreDefaults(addons: List<InstalledAddon>): List<InstalledAddon> {
        val byKey = LinkedHashMap<String, InstalledAddon>()
        val freshCinemeta = defaultCinemetaAddon()

        for (addon in addons) {
            val needsCinemetaUpgrade = addon.id == "com.linvo.cinemeta" && (
                addon.catalogs.none { it.id == "year" } ||
                    addon.catalogs.any { it.id == "top" && it.genreOptions.isEmpty() }
                )
            val upgraded = if (needsCinemetaUpgrade) {
                freshCinemeta.copy(transportUrl = addon.transportUrl)
            } else {
                addon
            }
            if (byKey.values.none { it.id == upgraded.id && it.transportUrl == upgraded.transportUrl }) {
                byKey[upgraded.transportUrl] = upgraded
            }
        }

        for (def in defaultAddons()) {
            val alreadyHasEquivalent = byKey.values.any { existing ->
                existing.id == def.id ||
                    (def.id == "pw.ers.netflix-catalog" && (
                        existing.id.contains("netflix-catalog", ignoreCase = true) ||
                            existing.id.contains("streamingcatalogs", ignoreCase = true) ||
                            existing.transportUrl.contains("netflix-catalog", ignoreCase = true)
                        ))
            }
            if (!alreadyHasEquivalent) {
                byKey[def.transportUrl] = def
            }
        }
        return byKey.values.toList()
    }

    fun getWatchHistory(context: Context): List<WatchHistoryEntry> {
        val raw = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<WatchHistoryEntry>>(raw)
                .sortedByDescending { it.updatedAtMs }
        }.getOrDefault(emptyList())
    }

    fun upsertWatchHistory(context: Context, entry: WatchHistoryEntry) {
        val existing = getWatchHistory(context).toMutableList()
        existing.removeAll { it.itemId == entry.itemId }
        val finished = entry.durationMs > 30_000L && entry.timeOffsetMs >= (entry.durationMs * 0.95).toLong()
        if (!finished && entry.timeOffsetMs > 5_000L) {
            existing.add(0, entry)
        }
        val trimmed = existing.take(40)
        prefs(context).edit()
            .putString(KEY_HISTORY, json.encodeToString(trimmed))
            .apply()
    }

    fun mergeRemoteWatchHistory(context: Context, remoteEntries: List<WatchHistoryEntry>) {
        if (remoteEntries.isEmpty()) return
        val map = LinkedHashMap<String, WatchHistoryEntry>()
        for (local in getWatchHistory(context)) {
            map[local.itemId] = local
        }
        for (rem in remoteEntries) {
            val cur = map[rem.itemId]
            if (cur == null || rem.updatedAtMs > cur.updatedAtMs) {
                map[rem.itemId] = rem
            }
        }
        val merged = map.values.sortedByDescending { it.updatedAtMs }.take(40)
        prefs(context).edit()
            .putString(KEY_HISTORY, json.encodeToString(merged))
            .apply()
    }

    fun getFavorites(context: Context): List<FavoriteEntry> {
        val raw = prefs(context).getString(KEY_FAVORITES, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<FavoriteEntry>>(raw)
                .sortedByDescending { it.addedAtMs }
        }.getOrDefault(emptyList())
    }

    fun isFavorite(context: Context, id: String): Boolean =
        getFavorites(context).any { it.id == id }

    fun toggleFavorite(context: Context, entry: FavoriteEntry): Boolean {
        val list = getFavorites(context).toMutableList()
        val existed = list.removeAll { it.id == entry.id }
        val added = !existed
        if (added) {
            list.add(0, entry)
        }
        prefs(context).edit()
            .putString(KEY_FAVORITES, json.encodeToString(list.take(200)))
            .apply()
        return added
    }

    fun mergeFavorites(context: Context, items: List<FavoriteEntry>) {
        if (items.isEmpty()) return
        val map = LinkedHashMap<String, FavoriteEntry>()
        for (f in getFavorites(context)) map[f.id] = f
        for (f in items) {
            if (!map.containsKey(f.id)) map[f.id] = f
        }
        prefs(context).edit()
            .putString(KEY_FAVORITES, json.encodeToString(map.values.sortedByDescending { it.addedAtMs }.take(250)))
            .apply()
    }

    fun tryImportKodiFavoritesOnce(context: Context): List<FavoriteEntry> {
        val imported = mutableListOf<FavoriteEntry>()
        val kodiEnv = getKodiEnvDir(context)

        val favXml = File(kodiEnv, "userdata/favourites.xml")
        if (favXml.isFile) {
            runCatching {
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(StringReader(favXml.readText()))
                var event = parser.eventType
                var curName = ""
                var curThumb = ""
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "favourite") {
                        curName = parser.getAttributeValue(null, "name") ?: ""
                        curThumb = parser.getAttributeValue(null, "thumb") ?: ""
                    } else if (event == XmlPullParser.TEXT && curName.isNotBlank()) {
                        val rawAction = parser.text?.trim() ?: ""
                        val pluginMatch = Regex("""plugin://[^\s"')]+""").find(rawAction)?.value
                        if (pluginMatch != null) {
                            val cleanName = stripKodiTags(curName)
                            imported.add(
                                FavoriteEntry(
                                    id = "kodi:$pluginMatch",
                                    type = "kodi",
                                    name = cleanName,
                                    poster = curThumb,
                                    subtitle = pluginMatch.removePrefix("plugin://").substringBefore('/'),
                                    pluginUrl = pluginMatch
                                )
                            )
                        }
                        curName = ""
                    }
                    event = parser.next()
                }
            }
        }

        val addonDataDir = File(kodiEnv, "userdata/addon_data")
        val addonDirs = addonDataDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (dir in addonDirs) {
            val addonId = dir.name
            val favDb = File(dir, "favorites.db")
            if (!favDb.isFile) continue
            runCatching {
                SQLiteDatabase.openDatabase(favDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    val hasTable = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='favorites'", null).use { it.moveToFirst() }
                    if (!hasTable) return@use
                    db.rawQuery("SELECT name, url, mode, image FROM favorites", null).use { c ->
                        while (c.moveToNext()) {
                            val name = stripKodiTags(c.getString(0) ?: "Favorite Video")
                            val url = c.getString(1) ?: continue
                            val mode = c.getString(2) ?: ""
                            val image = c.getString(3) ?: ""
                            val encUrl = URLEncoder.encode(url, "UTF-8")
                            val encName = URLEncoder.encode(name, "UTF-8")
                            val pluginUrl = "plugin://$addonId/?url=$encUrl&mode=$mode&name=$encName"
                            imported.add(
                                FavoriteEntry(
                                    id = "$addonId:$url",
                                    type = "kodi",
                                    name = name,
                                    poster = image,
                                    subtitle = "$addonId · ${mode.substringBefore('.')}",
                                    pluginUrl = pluginUrl
                                )
                            )
                        }
                    }
                }
            }
        }

        if (imported.isNotEmpty()) {
            mergeFavorites(context, imported)
        }
        return imported
    }

    fun readCustomSiteKeywords(context: Context): List<String> {
        val list = mutableListOf<String>()
        val addonDataDir = File(getKodiEnvDir(context), "userdata/addon_data")
        val addonDirs = addonDataDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("plugin.video.") } ?: emptyList()
        for (dir in addonDirs) {
            val favDb = File(dir, "favorites.db")
            if (!favDb.isFile) continue
            runCatching {
                SQLiteDatabase.openDatabase(favDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    val hasTable = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='keywords'", null).use { it.moveToFirst() }
                    if (!hasTable) return@use
                    db.rawQuery("SELECT keyword FROM keywords ORDER BY rowid DESC", null).use { c ->
                        while (c.moveToNext()) {
                            val kw = java.net.URLDecoder.decode(c.getString(0) ?: "", "UTF-8").trim()
                            if (kw.isNotBlank()) list.add(kw)
                        }
                    }
                }
            }
        }
        return list
    }

    fun readCustomSites(context: Context): List<Triple<String, String, String>> {
        val list = mutableListOf<Triple<String, String, String>>()
        val addonDataDir = File(getKodiEnvDir(context), "userdata/addon_data")
        val addonDirs = addonDataDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("plugin.video.") } ?: emptyList()
        for (dir in addonDirs) {
            val addonId = dir.name
            val favDb = File(dir, "favorites.db")
            if (!favDb.isFile) continue
            runCatching {
                SQLiteDatabase.openDatabase(favDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    val hasTable = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='custom_sites'", null).use { it.moveToFirst() }
                    if (!hasTable) return@use
                    db.rawQuery("SELECT author, name, title, url, image FROM custom_sites WHERE enabled = 1", null).use { c ->
                        while (c.moveToNext()) {
                            val author = c.getString(0) ?: addonId
                            val name = c.getString(1) ?: continue
                            val title = stripKodiTags(c.getString(2) ?: name)
                            val url = c.getString(3) ?: ""
                            val imgName = c.getString(4) ?: ""
                            val imgPath = if (imgName.isNotBlank()) File(dir, "custom_sites/$imgName").absolutePath else ""
                            val encUrl = URLEncoder.encode(url, "UTF-8")
                            val encTitle = URLEncoder.encode(title, "UTF-8")
                            val mode = "custom_${name}_by_${author}.Main"
                            val pluginUrl = "plugin://$addonId/?mode=$mode&url=$encUrl&name=$encTitle"
                            list.add(Triple(title, pluginUrl, imgPath))
                        }
                    }
                }
            }
        }
        return list
    }

    fun getTorrentConfig(context: Context): TorrentEngineConfig {
        val p = prefs(context)
        val rawKey = p.getString(KEY_TORZNAB_KEY, "") ?: ""
        val clearKey = if (rawKey.isNotBlank() && !MultipassHardwareKeystore.isSealed(rawKey)) {
            val sealed = MultipassHardwareKeystore.sealString(rawKey, AAD_TORZNAB_KEY, context)
            if (sealed.isNotEmpty()) {
                p.edit().putString(KEY_TORZNAB_KEY, sealed).apply()
            }
            rawKey
        } else {
            MultipassHardwareKeystore.unsealString(rawKey, AAD_TORZNAB_KEY, context).orEmpty()
        }
        return TorrentEngineConfig(
            preBufferMb = p.getInt(KEY_PREBUFFER_MB, 16).coerceIn(4, 32),
            slidingWindowMb = p.getInt(KEY_WINDOW_MB, 28).coerceIn(12, 64),
            maxConnections = p.getInt(KEY_MAX_CONN, 120).coerceIn(20, 180),
            torznabUrl = p.getString(KEY_TORZNAB_URL, "") ?: "",
            torznabApiKey = clearKey
        )
    }

    fun saveTorrentConfig(context: Context, cfg: TorrentEngineConfig) {
        val cleanKey = cfg.torznabApiKey.trim()
        val sealedKey = if (cleanKey.isNotEmpty()) {
            MultipassHardwareKeystore.sealString(cleanKey, AAD_TORZNAB_KEY, context)
        } else {
            ""
        }
        prefs(context).edit()
            .putInt(KEY_PREBUFFER_MB, cfg.preBufferMb.coerceIn(4, 32))
            .putInt(KEY_WINDOW_MB, cfg.slidingWindowMb.coerceIn(12, 64))
            .putInt(KEY_MAX_CONN, cfg.maxConnections.coerceIn(20, 180))
            .putString(KEY_TORZNAB_URL, cfg.torznabUrl.trim())
            .putString(KEY_TORZNAB_KEY, sealedKey)
            .apply()
    }

    private const val KEY_AUDIO_DSP_JSON = "player_audio_dsp_config_v1"

    fun getAudioDspConfig(context: Context): AudioDspConfig {
        val raw = prefs(context).getString(KEY_AUDIO_DSP_JSON, null)?.takeIf { it.isNotBlank() }
            ?: return AudioDspConfig()
        return runCatching {
            val decoded = json.decodeFromString<AudioDspConfig>(raw)
            decoded.copy(
                boostPercent = decoded.boostPercent.coerceIn(50, 300),
                bassDb = decoded.bassDb.coerceIn(-8, 8),
                voiceDb = decoded.voiceDb.coerceIn(-8, 8),
                trebleDb = decoded.trebleDb.coerceIn(-8, 8)
            )
        }.getOrDefault(AudioDspConfig())
    }

    fun saveAudioDspConfig(context: Context, cfg: AudioDspConfig) {
        val sanitized = cfg.copy(
            boostPercent = cfg.boostPercent.coerceIn(50, 300),
            bassDb = cfg.bassDb.coerceIn(-8, 8),
            voiceDb = cfg.voiceDb.coerceIn(-8, 8),
            trebleDb = cfg.trebleDb.coerceIn(-8, 8)
        )
        prefs(context).edit()
            .putString(KEY_AUDIO_DSP_JSON, json.encodeToString(sanitized))
            .apply()
    }

    fun stripKodiTags(raw: String): String {
        return raw
            .replace(Regex("""\[COLOR[^\]]*]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[/COLOR]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[/?(?:B|I|LIGHT|UPPERCASE|LOWERCASE)]""", RegexOption.IGNORE_CASE), "")
            .replace("[CR]", " ")
            .trim()
    }
}
