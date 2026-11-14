package app.brootlite.tv.creators

import kotlinx.serialization.Serializable

@Serializable
enum class CreatorPlatform {
    TWITCH,
    ODYSEE,
    RUMBLE
}

@Serializable
data class CreatorVideoCard(
    val id: String,
    val platform: CreatorPlatform,
    val title: String,
    val channelName: String,
    val channelId: String,
    val channelAvatarUrl: String? = null,
    val thumbnailUrl: String,
    val durationSec: Long? = null,
    val durationText: String? = null,
    val viewerOrViewCount: Long? = null,
    val viewCountText: String? = null,
    val publishedText: String? = null,
    val categoryName: String? = null,
    val isLive: Boolean = false,
    /** Optional platform-specific hint (e.g. Odysee `sdHash`, Rumble page path) for fast stream resolution. */
    val streamUrlHint: String? = null
)

@Serializable
data class CreatorChannel(
    val id: String,
    val platform: CreatorPlatform,
    val displayName: String,
    val handle: String? = null,
    val avatarUrl: String? = null,
    val bannerUrl: String? = null,
    val description: String? = null,
    val followerCount: Long? = null,
    val followerCountText: String? = null,
    val isLive: Boolean = false,
    val liveVideo: CreatorVideoCard? = null
)

@Serializable
data class CreatorCategory(
    val id: String,
    val platform: CreatorPlatform,
    val name: String,
    val slug: String,
    val boxArtUrl: String? = null,
    val viewerCount: Long? = null,
    val viewerCountText: String? = null
)

@Serializable
data class CreatorFeedPage(
    val items: List<CreatorVideoCard>,
    val channels: List<CreatorChannel> = emptyList(),
    val categories: List<CreatorCategory> = emptyList(),
    val nextCursor: String? = null
)

@Serializable
data class CreatorQualityVariant(
    val label: String,
    val url: String,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val bitrate: Long? = null
)

@Serializable
data class CreatorSubtitleTrack(
    val languageCode: String,
    val label: String,
    val url: String,
    val mimeType: String = "text/vtt"
)

@Serializable
data class ResolvedCreatorStream(
    val id: String,
    val platform: CreatorPlatform,
    val title: String,
    val channelName: String,
    val channelId: String? = null,
    val thumbnailUrl: String? = null,
    val isLive: Boolean,
    val primaryUrl: String,
    val primaryMimeType: String,
    val hlsManifestUrl: String? = null,
    val mp4FallbackUrl: String? = null,
    /** Secondary clean HLS manifest (e.g. Twitch `embed` playerType) swapped in during stitched ad breaks. */
    val backupAdFreeHlsUrl: String? = null,
    val qualityVariants: List<CreatorQualityVariant> = emptyList(),
    val subtitleTracks: List<CreatorSubtitleTrack> = emptyList(),
    val headers: Map<String, String> = emptyMap()
) {
    fun selectVariant(maxHeight: Int = 1080): CreatorQualityVariant? {
        if (qualityVariants.isEmpty()) return null
        return qualityVariants
            .filter { (it.height ?: 0) > 0 }
            .sortedWith(compareByDescending<CreatorQualityVariant> { it.height ?: 0 }.thenByDescending { it.fps ?: 30 })
            .firstOrNull { (it.height ?: 0) <= maxHeight }
            ?: qualityVariants.first()
    }
}

/**
 * Auth context for Twitch OAuth2 (never logs raw tokens).
 */
data class TwitchAuthContext(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAtMs: Long = 0L,
    val clientId: String? = null,
    val userId: String? = null,
    val userLogin: String? = null,
    val onTokenRefreshed: (suspend (TwitchOAuthTokens) -> Unit)? = null
) {
    val isSignedIn: Boolean
        get() = !accessToken.isNullOrBlank() || !refreshToken.isNullOrBlank()

    override fun toString(): String =
        "TwitchAuthContext(signedIn=$isSignedIn, userLogin=${userLogin.orEmpty()})"
}

@Serializable
data class TwitchDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String = "https://www.twitch.tv/activate",
    val intervalSec: Int = 5,
    val expiresInSec: Int = 1800,
    val clientId: String = ""
) {
    override fun toString(): String =
        "TwitchDeviceCode(userCode=$userCode, verificationUri=$verificationUri, intervalSec=$intervalSec)"
}

@Serializable
data class TwitchOAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Long,
    val clientId: String,
    val scopes: List<String> = emptyList(),
    val expiresAtMs: Long = System.currentTimeMillis() + (expiresInSec * 1000L)
) {
    override fun toString(): String =
        "TwitchOAuthTokens(expiresInSec=$expiresInSec, expiresAtMs=$expiresAtMs, hasRefresh=${refreshToken.isNotBlank()})"
}

sealed class TwitchDevicePollResult {
    data object Pending : TwitchDevicePollResult()
    data class SlowDown(val extraDelaySec: Int = 5) : TwitchDevicePollResult()
    data class Authorized(val tokens: TwitchOAuthTokens) : TwitchDevicePollResult()
    data class Error(val status: Int, val message: String) : TwitchDevicePollResult()
}
