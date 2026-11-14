package app.brootlite.tv.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import app.brootlite.tv.BrootLiteApp
import app.brootlite.tv.jellyfin.JellyfinApiClient
import app.brootlite.tv.jellyfin.JellyfinEndpointRouter
import app.brootlite.tv.jellyfin.JellyfinServerProfile
import app.brootlite.tv.kodi.PyAddonClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class MultipassPairingStart(
    val pairingId: String,
    val pollSecret: String,
    val userCode: String,
    val verificationUrl: String,
    val verificationUriComplete: String = "",
    val qrVerificationUrl: String = "",
    val tvPublicKeyB64Url: String = "",
    val requestedScope: String = "media_sync:readwrite",
    val expiresIn: Int = 600,
    val intervalSeconds: Int = 3
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MultipassWrappedVaultKey(
    val algorithm: String = MultipassCrypto.PAIR_WRAP_ALGORITHM,
    val ephemeralPublicKey: String = "",
    val iv: String = "",
    val ciphertext: String = "",
    val aad: String? = null,
    val keySlots: JsonArray? = null
)

@Serializable
data class MultipassPairingPollResponse(
    val status: String, // "pending" | "approved"
    val expiresIn: Int? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val deviceToken: String? = null,
    val scope: String? = null,
    val expiresAt: String? = null,
    val wrappedVaultKey: MultipassWrappedVaultKey? = null,
    val keySlots: JsonArray? = null,
    val vmkUnwrapped: Boolean = false
)

@Serializable
data class MultipassKdfSpec(
    val algorithm: String = "PBKDF2-SHA256",
    val iterations: Int? = null,
    val salt: String,
    val info: String? = null
)

@Serializable
data class MultipassE2eeEnvelope(
    val kdf: MultipassKdfSpec? = null,
    val hkdfSalt: String? = null,
    val cipher: String = "AES-256-GCM",
    val iv: String,
    val aad: String? = MultipassCrypto.AAD_V2,
    val ciphertext: String,
    val keySlots: JsonArray? = null
)

@Serializable
data class MultipassVaultDocument(
    val schemaVersion: Int = 2,
    val mode: String, // "e2ee-aes-256-gcm" | "server-at-rest" (legacy v1 read-only)
    val keySlots: JsonArray? = null,
    val payload: MediaSyncPayload? = null,
    val envelope: MultipassE2eeEnvelope? = null
)

@Serializable
data class MultipassVaultGetResponse(
    val exists: Boolean = false,
    val revision: Int = 0,
    val updatedAt: String? = null,
    val document: JsonObject? = null
)

@Serializable
data class MultipassVaultPutResponse(
    val ok: Boolean = false,
    val revision: Int = 0,
    val updatedAt: String? = null,
    val mode: String = ""
)

@Serializable
data class SyncedStremioAuth(
    val authKey: String? = null,
    val email: String? = null,
    val updatedAtMs: Long = 0L,
    val revokedAtMs: Long = 0L
)

@Serializable
data class SyncedJellyfinServer(
    val serverId: String = "",
    val id: String = "",
    val name: String = "Jellyfin Server",
    val primaryUrl: String = "",
    val serverUrl: String = "",
    val localUrl: String = "",
    val remoteUrl: String = "",
    val userId: String = "",
    val username: String = "",
    val accessToken: String = "",
    val bootstrapToken: String = "",
    val deviceTokens: Map<String, String> = emptyMap(),
    val allowInsecureTls: Boolean = false,
    val maxBitrateMbps: Int = 0,
    val enabled: Boolean = true,
    val addedAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val removedAtMs: Long = 0L
) {
    val effectiveServerId: String
        get() = serverId.ifBlank { id }

    val effectivePrimaryUrl: String
        get() = primaryUrl.ifBlank { serverUrl }

    val effectiveUpdatedAtMs: Long
        get() = if (updatedAtMs > 0L) updatedAtMs else addedAtMs

    val isRemoved: Boolean
        get() = removedAtMs > effectiveUpdatedAtMs
}

@Serializable
data class SyncedStreamingPreferences(
    val sponsorBlockEnabled: Boolean = true,
    val youtubeWatchHistoryEnabled: Boolean = true,
    val returnYouTubeDislikeEnabled: Boolean = true,
    val deArrowEnabled: Boolean = false,
    val updatedAtMs: Long = 0L
)

@Serializable
data class MediaSyncPayload(
    val stremioAuth: SyncedStremioAuth? = null,
    val stremioSync: StremioSyncSection? = null,
    val jellyfinServers: List<SyncedJellyfinServer> = emptyList(),
    val youtubeAccounts: List<SyncedYouTubeOAuth> = emptyList(),
    val youtubeOAuth: SyncedYouTubeOAuth? = null,
    val twitchByDevice: Map<String, SyncedTwitchDeviceOAuth> = emptyMap(),
    val creatorFollows: List<CreatorFollowEntry> = emptyList(),
    val streamingPreferences: SyncedStreamingPreferences? = null,
    val kodiExternal: KodiExternalSection? = null,
    val indexers: List<SyncedIndexer> = emptyList(),
    val torrentConfig: SyncedTorrentConfig? = null
)

@Serializable
data class StremioSyncSection(
    val authKey: String? = null,
    val email: String? = null,
    val addons: List<SyncedStremioAddon> = emptyList(),
    val watchProgress: List<SyncedWatchProgress> = emptyList(),
    val favorites: List<SyncedStremioFavorite> = emptyList()
)

@Serializable
data class SyncedStremioAddon(
    val transportUrl: String,
    val id: String? = null,
    val name: String,
    val version: String? = null,
    val enabled: Boolean = true
)

@Serializable
data class SyncedWatchProgress(
    val itemId: String,
    val videoId: String,
    val type: String,
    val name: String,
    val episodeTitle: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val timeOffsetMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
    val infoHash: String? = null,
    val fileIdx: Int? = null
)

@Serializable
data class SyncedStremioFavorite(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val subtitle: String? = null,
    val addedAtMs: Long? = null
)

@Serializable
data class KodiExternalSection(
    val repositories: List<SyncedKodiRepo> = emptyList(),
    val addons: List<SyncedKodiAddon> = emptyList(),
    val customSites: List<SyncedKodiCustomSite> = emptyList(),
    val favorites: List<SyncedKodiFavorite> = emptyList(),
    val keywords: List<String> = emptyList(),
    val settings: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class SyncedKodiRepo(
    val id: String,
    val name: String,
    val zipUrl: String,
    val checksum: String? = null,
    val checksumType: String? = null,
    val enabled: Boolean = true
)

@Serializable
data class SyncedKodiAddon(
    val id: String,
    val name: String,
    val repositoryId: String? = null,
    val pluginUrl: String? = null,
    val version: String? = null,
    val enabled: Boolean = true
)

@Serializable
data class SyncedKodiCustomSite(
    val addonId: String? = "plugin.video.addon",
    val author: String? = null,
    val name: String,
    val title: String,
    val url: String,
    val imageUrl: String? = null,
    val pluginUrl: String? = null,
    val enabled: Boolean = true
)

@Serializable
data class SyncedKodiFavorite(
    val id: String,
    val type: String? = "kodi",
    val name: String,
    val poster: String? = null,
    val subtitle: String? = null,
    val pluginUrl: String,
    val mode: String? = null,
    val siteUrl: String? = null,
    val addedAtMs: Long? = null
)

@Serializable
data class SyncedIndexer(
    val id: String? = null,
    val name: String,
    val url: String,
    val apiKey: String? = null,
    val type: String = "torznab",
    val categories: List<Int> = emptyList(),
    val enabled: Boolean = true
)

@Serializable
data class SyncedTorrentConfig(
    val preBufferMb: Int? = null,
    val slidingWindowMb: Int? = null,
    val maxConnections: Int? = null
)

/**
 * Byte-for-byte compatible WebCrypto (`leeloo-multipass` v1 & v2) <-> Android JCA E2EE engine:
 * - `v2` Pairing QR Lockbox: `ECDH-P256-HKDF-SHA256-AES-256-GCM` (`#pk=<65B_uncompressed_b64url>`).
 * - `v2` Envelope: 32-byte Vault Master Key (`VMK`) -> `HKDF-SHA256` (`info = "leeloo-multipass:media-sync:v2"`)
 *   + `AES-256-GCM` (`aad = "leeloo-multipass:media-sync:v2"`).
 * - `v2` KeySlot (`primary-passphrase`): `PBKDF2-HMAC-SHA256` (210,000 iterations) + `AES-256-GCM`
 *   (`aad = "leeloo-multipass:keyslot:v2"`).
 * - `v1` Legacy Read Support: `PBKDF2-HMAC-SHA256` (210,000 iterations) + `AES-256-GCM`
 *   (`aad = "leeloo-multipass:media-sync:v1"`).
 */
object MultipassCrypto {
    const val AAD = "leeloo-multipass:media-sync:v1"
    const val AAD_V1 = AAD
    const val AAD_V2 = "leeloo-multipass:media-sync:v2"
    const val INFO_MEDIA_SYNC_V2 = "leeloo-multipass:media-sync:v2"
    const val INFO_PAIR_WRAP_V2 = "leeloo-multipass:pair-wrap:v2"
    const val AAD_KEYSLOT_V2 = "leeloo-multipass:keyslot:v2"
    const val PAIR_WRAP_ALGORITHM = "ECDH-P256-HKDF-SHA256-AES-256-GCM"
    const val PRIMARY_PASSPHRASE_SLOT_ID = "primary-passphrase"
    const val DEFAULT_ITERATIONS = 210_000

    private val rng = SecureRandom()
    private val b64Enc = Base64.getUrlEncoder().withoutPadding()
    private val b64Dec = Base64.getUrlDecoder()

    fun encodeBase64Url(bytes: ByteArray): String = b64Enc.encodeToString(bytes)

    fun decodeBase64Url(raw: String): ByteArray =
        b64Dec.decode(normalizeBase64Url(raw))

    fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also(rng::nextBytes)

    fun normalizeUserCode(raw: String): String {
        val clean8 = raw.trim().uppercase(Locale.US).replace(Regex("[\\s-]"), "")
        require(clean8.length == 8) { "Expected 8-character Multipass pairing code" }
        return "${clean8.take(4)}-${clean8.substring(4, 8)}"
    }

    /**
     * RFC 5869 HKDF-Extract + HKDF-Expand using HMAC-SHA256.
     */
    fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outLen: Int = 32
    ): ByteArray {
        require(outLen in 1..(255 * 32)) { "Invalid HKDF output length" }
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val extractMac = Mac.getInstance("HmacSHA256")
        extractMac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        val prk = extractMac.doFinal(ikm)

        val okm = ByteArray(outLen)
        val expandMac = Mac.getInstance("HmacSHA256")
        expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
        var prev = ByteArray(0)
        var offset = 0
        var blockIndex = 1
        try {
            while (offset < outLen) {
                expandMac.reset()
                expandMac.update(prev)
                expandMac.update(info)
                expandMac.update(blockIndex.toByte())
                prev = expandMac.doFinal()
                val n = minOf(prev.size, outLen - offset)
                System.arraycopy(prev, 0, okm, offset, n)
                offset += n
                blockIndex++
            }
            return okm
        } finally {
            prk.fill(0)
            prev.fill(0)
        }
    }

    fun generateEphemeralP256KeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), rng)
        return kpg.generateKeyPair()
    }

    /**
     * Exports a NIST P-256 `ECPublicKey` as a 65-byte SEC1 uncompressed point:
     * `0x04 || X_32B_unsigned_big_endian || Y_32B_unsigned_big_endian`.
     */
    fun encodeP256UncompressedPoint(pubKey: ECPublicKey): ByteArray {
        val w = pubKey.w
        val xBytes = toFixedUnsigned32(w.affineX)
        val yBytes = toFixedUnsigned32(w.affineY)
        val out = ByteArray(65)
        out[0] = 0x04
        System.arraycopy(xBytes, 0, out, 1, 32)
        System.arraycopy(yBytes, 0, out, 33, 32)
        return out
    }

    /**
     * Reconstructs and curve-validates a NIST P-256 `ECPublicKey` from a 65-byte `0x04 || X || Y` point.
     */
    fun decodeP256UncompressedPoint(raw65: ByteArray, params: ECParameterSpec): ECPublicKey {
        require(raw65.size == 65 && raw65[0] == 0x04.toByte()) {
            "Expected 65-byte uncompressed P-256 public key"
        }
        val x = BigInteger(1, raw65.copyOfRange(1, 33))
        val y = BigInteger(1, raw65.copyOfRange(33, 65))
        val field = params.curve.field as? ECFieldFp
            ?: error("Unsupported EC field type")
        val p = field.p
        val a = params.curve.a
        val b = params.curve.b
        require(x > BigInteger.ZERO && x < p && y > BigInteger.ZERO && y < p) {
            "P-256 point coordinates out of field bounds"
        }
        // Verify y^2 == x^3 + ax + b (mod p) on secp256r1 (cofactor h = 1)
        val lhs = y.multiply(y).mod(p)
        val rhs = x.modPow(BigInteger.valueOf(3L), p).add(a.multiply(x)).add(b).mod(p)
        require(lhs == rhs) { "Peer EC public key is not on secp256r1 curve" }

        val spec = ECPublicKeySpec(ECPoint(x, y), params)
        return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }

    /**
     * Unwraps the 32-byte `VMK` from `pollDevicePairing`'s `wrappedVaultKey` using
     * `ECDH-P256-HKDF-SHA256-AES-256-GCM`.
     */
    fun unwrapPairedVaultMasterKey(
        tvKeyPair: KeyPair,
        userCode: String,
        wrapped: MultipassWrappedVaultKey
    ): ByteArray {
        require(wrapped.algorithm.equals(PAIR_WRAP_ALGORITHM, ignoreCase = true)) {
            "Unsupported pairing key-wrap algorithm: ${wrapped.algorithm}"
        }
        val tvPub = tvKeyPair.public as ECPublicKey
        val tvPriv = tvKeyPair.private as ECPrivateKey
        val peerPubBytes = decodeBase64Url(wrapped.ephemeralPublicKey)
        val peerPubKey = decodeP256UncompressedPoint(peerPubBytes, tvPub.params)

        val rawShared = KeyAgreement.getInstance("ECDH").run {
            init(tvPriv)
            doPhase(peerPubKey, true)
            generateSecret()
        }
        val sharedSecret32 = if (rawShared.size == 32) {
            rawShared
        } else {
            toFixedUnsigned32(BigInteger(1, rawShared)).also { rawShared.fill(0) }
        }

        val normalizedCode = normalizeUserCode(userCode)
        val kek = hkdfSha256(
            ikm = sharedSecret32,
            salt = normalizedCode.toByteArray(StandardCharsets.UTF_8),
            info = INFO_PAIR_WRAP_V2.toByteArray(StandardCharsets.UTF_8),
            outLen = 32
        )
        sharedSecret32.fill(0)

        try {
            val iv = decodeBase64Url(wrapped.iv)
            val ciphertext = decodeBase64Url(wrapped.ciphertext)
            require(iv.size == 12 && ciphertext.size == 48) {
                "Invalid wrapped VMK IV or ciphertext byte length"
            }
            val aadBytes = (wrapped.aad?.takeIf { it.isNotEmpty() } ?: "pair:$normalizedCode")
                .toByteArray(StandardCharsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aadBytes)
            val vmk = cipher.doFinal(ciphertext)
            require(vmk.size == 32) { "Unwrapped VMK must be 32 bytes" }
            return vmk
        } finally {
            kek.fill(0)
        }
    }

    /**
     * Encrypts plaintext JSON with `v2` `VMK` (`HKDF-SHA256` + `AES-256-GCM`).
     */
    fun encryptV2WithVmk(
        payloadJson: String,
        vmk32B: ByteArray,
        envelopeTemplate: JsonObject? = null
    ): JsonObject {
        require(vmk32B.size == 32) { "VMK must be 32 bytes" }
        val hkdfSalt = randomBytes(16)
        val iv = randomBytes(12)
        val kEnc = hkdfSha256(
            ikm = vmk32B,
            salt = hkdfSalt,
            info = INFO_MEDIA_SYNC_V2.toByteArray(StandardCharsets.UTF_8),
            outLen = 32
        )
        val ciphertext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kEnc, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(AAD_V2.toByteArray(StandardCharsets.UTF_8))
            cipher.doFinal(payloadJson.toByteArray(StandardCharsets.UTF_8))
        } finally {
            kEnc.fill(0)
        }

        val saltB64 = encodeBase64Url(hkdfSalt)
        val ivB64 = encodeBase64Url(iv)
        val ctB64 = encodeBase64Url(ciphertext)

        // Emit exact mediaSyncEnvelopeV2Schema (.strict()) format: {hkdfSalt, cipher, iv, aad, ciphertext}
        val usesLegacyNestedKdf = envelopeTemplate != null &&
            envelopeTemplate.containsKey("kdf") &&
            !envelopeTemplate.containsKey("hkdfSalt")
        val templateKdf = envelopeTemplate?.get("kdf") as? JsonObject

        return buildJsonObject {
            if (usesLegacyNestedKdf) {
                put(
                    "kdf",
                    buildJsonObject {
                        put("algorithm", "HKDF-SHA256")
                        put("salt", saltB64)
                        if (templateKdf == null || templateKdf.containsKey("info")) {
                            put("info", INFO_MEDIA_SYNC_V2)
                        }
                    }
                )
            } else {
                put("hkdfSalt", saltB64)
            }
            put("cipher", "AES-256-GCM")
            put("iv", ivB64)
            put("aad", AAD_V2)
            put("ciphertext", ctB64)
        }
    }

    /**
     * Decrypts a `schemaVersion: 2` envelope JSON object using `vmk32B`.
     */
    fun decryptV2WithVmk(envelopeObj: JsonObject, vmk32B: ByteArray): String {
        require(vmk32B.size == 32) { "VMK must be 32 bytes" }
        val kdfObj = envelopeObj["kdf"] as? JsonObject
        val saltB64 = envelopeObj["hkdfSalt"]?.jsonPrimitive?.contentOrNull
            ?: envelopeObj["salt"]?.jsonPrimitive?.contentOrNull
            ?: kdfObj?.get("salt")?.jsonPrimitive?.contentOrNull
            ?: error("Missing HKDF salt in v2 Multipass envelope")
        val infoStr = kdfObj?.get("info")?.jsonPrimitive?.contentOrNull ?: INFO_MEDIA_SYNC_V2
        val ivB64 = envelopeObj["iv"]?.jsonPrimitive?.contentOrNull
            ?: error("Missing IV in v2 Multipass envelope")
        val ctB64 = envelopeObj["ciphertext"]?.jsonPrimitive?.contentOrNull
            ?: error("Missing ciphertext in v2 Multipass envelope")
        val aadStr = envelopeObj["aad"]?.jsonPrimitive?.contentOrNull ?: AAD_V2

        val hkdfSalt = decodeBase64Url(saltB64)
        val iv = decodeBase64Url(ivB64)
        val ciphertext = decodeBase64Url(ctB64)
        require(hkdfSalt.size >= 16 && iv.size == 12 && ciphertext.size >= 18) {
            "Malformed v2 Multipass envelope lengths"
        }

        val kEnc = hkdfSha256(
            ikm = vmk32B,
            salt = hkdfSalt,
            info = infoStr.toByteArray(StandardCharsets.UTF_8),
            outLen = 32
        )
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kEnc, "AES"), GCMParameterSpec(128, iv))
            if (aadStr.isNotEmpty()) {
                cipher.updateAAD(aadStr.toByteArray(StandardCharsets.UTF_8))
            }
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } finally {
            kEnc.fill(0)
        }
    }

    /**
     * Creates a `primary-passphrase` keySlot matching `vmkKeySlotSchema.strict()`
     * (`type = "pbkdf2-sha256"`, `PBKDF2WithHmacSHA256` 210,000 iterations,
     * `aad = "leeloo-multipass:keyslot:v2"`, `wrappedVmk = 48B base64url`).
     */
    fun createPassphraseKeySlot(
        vmk32B: ByteArray,
        passphrase: String,
        iterations: Int = DEFAULT_ITERATIONS
    ): JsonObject {
        require(vmk32B.size == 32) { "VMK must be 32 bytes" }
        require(passphrase.length >= 8) { "Vault passphrase must be at least 8 characters" }
        val salt = randomBytes(16)
        val iv = randomBytes(12)
        val kek = derivePbkdf2Key(passphrase, salt, iterations)
        val wrapped = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(128, iv))
            cipher.updateAAD(AAD_KEYSLOT_V2.toByteArray(StandardCharsets.UTF_8))
            cipher.doFinal(vmk32B)
        } finally {
            kek.encoded?.fill(0)
        }
        return buildJsonObject {
            put("slotId", PRIMARY_PASSPHRASE_SLOT_ID)
            put("type", "pbkdf2-sha256")
            put(
                "kdf",
                buildJsonObject {
                    put("algorithm", "PBKDF2-SHA256")
                    put("iterations", iterations)
                    put("salt", encodeBase64Url(salt))
                }
            )
            put("iv", encodeBase64Url(iv))
            put("aad", AAD_KEYSLOT_V2)
            put("wrappedVmk", encodeBase64Url(wrapped))
        }
    }

    /**
     * Unwraps `vmk32B` from a `keySlots` array using the user's `passphrase`.
     */
    fun unwrapVmkFromKeySlots(keySlots: JsonArray, passphrase: String): ByteArray? {
        if (passphrase.isEmpty()) return null
        for (elem in keySlots) {
            val slot = elem as? JsonObject ?: continue
            val kdfObj = slot["kdf"] as? JsonObject
            val alg = kdfObj?.get("algorithm")?.jsonPrimitive?.contentOrNull
                ?: slot["algorithm"]?.jsonPrimitive?.contentOrNull
                ?: ""
            val slotId = slot["slotId"]?.jsonPrimitive?.contentOrNull
                ?: slot["id"]?.jsonPrimitive?.contentOrNull
                ?: ""
            val slotType = slot["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val isPbkdf2Slot = slotType.equals("pbkdf2-sha256", ignoreCase = true) ||
                slotType.equals("passphrase", ignoreCase = true) ||
                alg.equals("PBKDF2-SHA256", ignoreCase = true) ||
                slotId == PRIMARY_PASSPHRASE_SLOT_ID
            if (!isPbkdf2Slot) continue

            val unwrapped = runCatching {
                val saltB64 = kdfObj?.get("salt")?.jsonPrimitive?.contentOrNull
                    ?: slot["salt"]?.jsonPrimitive?.contentOrNull
                    ?: return@runCatching null
                val iterations = (kdfObj?.get("iterations")?.jsonPrimitive?.intOrNull
                    ?: slot["iterations"]?.jsonPrimitive?.intOrNull
                    ?: DEFAULT_ITERATIONS).coerceIn(100_000, 1_000_000)
                val ivB64 = slot["iv"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                val ctB64 = slot["wrappedVmk"]?.jsonPrimitive?.contentOrNull
                    ?: slot["wrappedKey"]?.jsonPrimitive?.contentOrNull
                    ?: slot["ciphertext"]?.jsonPrimitive?.contentOrNull
                    ?: return@runCatching null
                val aadStr = slot["aad"]?.jsonPrimitive?.contentOrNull ?: AAD_KEYSLOT_V2

                val salt = decodeBase64Url(saltB64)
                val iv = decodeBase64Url(ivB64)
                val ct = decodeBase64Url(ctB64)
                if (salt.size < 16 || iv.size != 12 || ct.size != 48) return@runCatching null

                val kek = derivePbkdf2Key(passphrase, salt, iterations)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(128, iv))
                if (aadStr.isNotEmpty()) {
                    cipher.updateAAD(aadStr.toByteArray(StandardCharsets.UTF_8))
                }
                val vmk = cipher.doFinal(ct)
                if (vmk.size == 32) vmk else null
            }.getOrNull()

            if (unwrapped != null) return unwrapped
        }
        return null
    }

    // Legacy v1 passphrase-direct encrypt/decrypt helpers preserved for backward compatibility
    fun encrypt(payloadJson: String, passphrase: String, iterations: Int = DEFAULT_ITERATIONS): MultipassE2eeEnvelope {
        require(passphrase.length >= 8) { "Vault passphrase must be at least 8 characters" }
        val salt = randomBytes(16)
        val iv = randomBytes(12)
        val key = derivePbkdf2Key(passphrase, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD_V1.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(payloadJson.toByteArray(StandardCharsets.UTF_8))
        return MultipassE2eeEnvelope(
            kdf = MultipassKdfSpec(
                algorithm = "PBKDF2-SHA256",
                iterations = iterations,
                salt = encodeBase64Url(salt)
            ),
            iv = encodeBase64Url(iv),
            aad = AAD_V1,
            ciphertext = encodeBase64Url(ciphertext)
        )
    }

    fun decrypt(envelope: MultipassE2eeEnvelope, passphrase: String): String {
        val kdf = envelope.kdf ?: error("Missing KDF in v1 Multipass envelope")
        val salt = decodeBase64Url(kdf.salt)
        val iv = decodeBase64Url(envelope.iv)
        val ciphertext = decodeBase64Url(envelope.ciphertext)
        val key = derivePbkdf2Key(passphrase, salt, (kdf.iterations ?: DEFAULT_ITERATIONS).coerceIn(100_000, 1_000_000))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val aadStr = envelope.aad
        if (!aadStr.isNullOrEmpty()) {
            cipher.updateAAD(aadStr.toByteArray(StandardCharsets.UTF_8))
        }
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun toFixedUnsigned32(bi: BigInteger): ByteArray {
        require(bi.signum() >= 0) { "Negative EC coordinate" }
        val raw = bi.toByteArray()
        val out = ByteArray(32)
        when {
            raw.size == 32 -> System.arraycopy(raw, 0, out, 0, 32)
            raw.size == 33 && raw[0] == 0.toByte() -> System.arraycopy(raw, 1, out, 0, 32)
            raw.size < 32 -> System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
            else -> error("Invalid P-256 coordinate byte length: ${raw.size}")
        }
        return out
    }

    private fun normalizeBase64Url(raw: String): String =
        raw.trim().replace('+', '-').replace('/', '_').trimEnd('=')

    private fun derivePbkdf2Key(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        val encoded = skf.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(encoded, "AES")
    }
}

/**
 * Xirtus Leeloo Multipass `/v1/media-sync` v2 Zero-Knowledge E2EE Client.
 *
 * Features:
 * - Ephemeral `P-256` ECDH QR Lockbox (`#pk=<tvPubB64Url>` URL fragment -> zero-server-visibility `VMK` unwrap).
 * - Hardware `AndroidKeyStore` local sealing of `VMK`, `mpd_...` token, and all synced OAuth/server tokens.
 * - Backward-compatible read of `schemaVersion: 1` & `schemaVersion: 2`; mandatory `schemaVersion: 2` E2EE write.
 * - Deterministic multi-device merge (`GET` -> decrypt -> CRDT/LWW merge -> encrypt fresh 12B IV -> CAS `PUT`
 *   with automatic `409` conflict retry) + Jellyfin per-device QuickConnect auto-bootstrap!
 */
object MultipassSyncClient {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private const val MAX_CAS_ATTEMPTS = 3
    private const val PENDING_ECDH_TTL_MS = 15L * 60_000L

    @OptIn(ExperimentalSerializationApi::class)
    private val vaultJson = Json(AppPrefs.json) {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    private data class PendingEcdhPairing(
        val keyPair: KeyPair,
        val userCode: String,
        val createdAtMs: Long = System.currentTimeMillis()
    ) {
        fun destroyQuietly() {
            runCatching { (keyPair.private as? ECPrivateKey)?.destroy() }
        }
    }

    private val pendingPairings = ConcurrentHashMap<String, PendingEcdhPairing>()

    data class SyncSummary(
        val ok: Boolean,
        val needsPassphrase: Boolean = false,
        val handle: String = "",
        val installedAddonsCount: Int = 0,
        val jellyfinServersCount: Int = 0,
        val creatorFollowsCount: Int = 0,
        val revision: Int = 0,
        val schemaVersion: Int = 2,
        val mode: String = "",
        val message: String = ""
    )

    fun hasLocalVmk(context: Context): Boolean {
        val vmk = AppPrefs.getMultipassVmk(context) ?: return false
        vmk.fill(0)
        return true
    }

    fun normalizeOrigin(input: String): String {
        var clean = input.trim().trimEnd('/')
        if (clean.isBlank()) return ""
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) {
            clean = "https://$clean"
        }
        clean = clean.removeSuffix("/v1/media-sync").removeSuffix("/v1/networks/ui").trimEnd('/')
        return clean
    }

    private fun isStaticJsonUrl(url: String): Boolean {
        val path = url.substringBefore('?').lowercase(Locale.US)
        return path.endsWith(".json")
    }

    private fun effectiveSyncDeviceId(context: Context): String {
        val mp = AppPrefs.getMultipassProfile(context)
        return mp.deviceId.takeIf { it.isNotBlank() } ?: AppPrefs.getOrCreateJellyfinDeviceId(context)
    }

    fun tryAutoImportMultipassFile(context: Context): Boolean {
        val existing = AppPrefs.getMultipassProfile(context)
        if (existing.serverUrl.isNotBlank() || existing.pairToken.isNotBlank()) return false

        val candidates = buildList {
            context.getExternalFilesDir(null)?.let {
                add(File(it, "multipass.json"))
                add(File(it, "multipass_vault_export.json"))
            }
            runCatching {
                @Suppress("DEPRECATION")
                val sd = Environment.getExternalStorageDirectory()
                add(File(sd, "Android/data/${context.packageName}/files/multipass.json"))
                add(File(sd, "BrootLite/multipass.json"))
            }
        }
        for (file in candidates) {
            if (file.isFile && file.canRead()) {
                val ok = runCatching {
                    val root = AppPrefs.json.parseToJsonElement(file.readText()).jsonObject
                    applyLegacyJsonMetadataOnly(context, root, sourceUrl = "file://${file.name}")
                }.getOrDefault(false)
                if (ok) return true
            }
        }
        return false
    }

    /**
     * Step 1 of Scoped TV Pairing (`v2` Lockbox):
     * Generates an ephemeral in-memory `P-256` (`secp256r1`) keypair, calls `POST /v1/media-sync/pair/start`,
     * and appends `#pk=<65B_uncompressed_b64url>` to `verificationUriComplete` (`qrVerificationUrl`)
     * so the public key travels purely in the QR URL fragment and is never transmitted to the server.
     */
    suspend fun startDevicePairing(
        originUrl: String,
        deviceName: String = "${android.os.Build.MODEL} · BrootLite",
        requestedScope: String = "media_sync:readwrite"
    ): Result<MultipassPairingStart> = withContext(Dispatchers.IO) {
        runCatching {
            pruneExpiredPendingPairings()
            val origin = normalizeOrigin(originUrl)
            require(origin.isNotBlank()) { "Multipass origin URL is empty" }

            val keyPair = MultipassCrypto.generateEphemeralP256KeyPair()
            val pubBytes65 = MultipassCrypto.encodeP256UncompressedPoint(keyPair.public as ECPublicKey)
            val tvPubB64Url = MultipassCrypto.encodeBase64Url(pubBytes65)

            val bodyJson = buildJsonObject {
                put("deviceName", deviceName.trim().take(80))
                put("requestedScope", requestedScope)
            }.toString()

            val req = Request.Builder()
                .url("$origin/v1/media-sync/pair/start")
                .post(bodyJson.toRequestBody(JSON_MEDIA))
                .build()

            BrootLiteApp.buildPermissiveHttpClient(15L).newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    runCatching { (keyPair.private as? ECPrivateKey)?.destroy() }
                    error("Multipass HTTP ${resp.code}: ${extractServerError(raw)}")
                }
                val parsed = AppPrefs.json.decodeFromString<MultipassPairingStart>(raw)
                val absVerifyUrl = if (parsed.verificationUrl.startsWith("http://", true) ||
                    parsed.verificationUrl.startsWith("https://", true)
                ) {
                    parsed.verificationUrl.substringBefore('#')
                } else {
                    "$origin/${parsed.verificationUrl.trimStart('/').substringBefore('#')}"
                }
                val qrWithFragment = "$absVerifyUrl#pk=$tvPubB64Url"

                pendingPairings[parsed.pairingId] = PendingEcdhPairing(
                    keyPair = keyPair,
                    userCode = parsed.userCode
                )

                parsed.copy(
                    verificationUrl = absVerifyUrl,
                    verificationUriComplete = qrWithFragment,
                    qrVerificationUrl = qrWithFragment,
                    tvPublicKeyB64Url = tvPubB64Url
                )
            }
        }
    }

    /**
     * Step 2 of Scoped TV Pairing (`v2` Lockbox):
     * Polls `POST /v1/media-sync/pair/poll`; when `status == "approved"`, unwraps `wrappedVaultKey`
     * (`ECDH-P256-HKDF-SHA256-AES-256-GCM`) into the 32-byte `VMK`, seals `VMK` + `keySlots` into
     * `AppPrefs` via `MultipassHardwareKeystore`, and zeroizes the ephemeral private key.
     */
    suspend fun pollDevicePairing(
        originUrl: String,
        pairingId: String,
        pollSecret: String,
        context: Context? = null
    ): Result<MultipassPairingPollResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val origin = normalizeOrigin(originUrl)
            val bodyJson = buildJsonObject {
                put("pairingId", pairingId)
                put("pollSecret", pollSecret)
            }.toString()
            val req = Request.Builder()
                .url("$origin/v1/media-sync/pair/poll")
                .post(bodyJson.toRequestBody(JSON_MEDIA))
                .build()
            BrootLiteApp.buildPermissiveHttpClient(15L).newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    if (resp.code == 400 || resp.code == 403 || resp.code == 404) {
                        pendingPairings.remove(pairingId)?.destroyQuietly()
                    }
                    error("Multipass poll HTTP ${resp.code}: ${extractServerError(raw)}")
                }
                val parsed = AppPrefs.json.decodeFromString<MultipassPairingPollResponse>(raw)
                if (!parsed.status.equals("approved", ignoreCase = true)) {
                    return@use parsed
                }

                val pending = pendingPairings.remove(pairingId)
                var unwrappedOk = false
                try {
                    val appCtx = context?.applicationContext
                        ?: runCatching { BrootLiteApp.instance.applicationContext }.getOrNull()
                    val wrapped = parsed.wrappedVaultKey
                    if (appCtx != null &&
                        pending != null &&
                        wrapped != null &&
                        wrapped.ephemeralPublicKey.isNotBlank() &&
                        wrapped.ciphertext.isNotBlank()
                    ) {
                        val vmk = runCatching {
                            MultipassCrypto.unwrapPairedVaultMasterKey(
                                tvKeyPair = pending.keyPair,
                                userCode = pending.userCode,
                                wrapped = wrapped
                            )
                        }.getOrNull()

                        if (vmk != null) {
                            try {
                                AppPrefs.saveMultipassVmk(appCtx, vmk)
                                unwrappedOk = true
                            } finally {
                                vmk.fill(0)
                            }
                        }
                    }

                    val slots = parsed.keySlots ?: wrapped?.keySlots
                    if (appCtx != null && slots != null && slots.isNotEmpty()) {
                        AppPrefs.saveMultipassKeySlotsJson(appCtx, slots.toString())
                    }
                } finally {
                    pending?.destroyQuietly()
                }

                parsed.copy(vmkUnwrapped = unwrappedOk)
            }
        }
    }

    suspend fun pollDevicePairing(
        context: Context,
        originUrl: String,
        pairingId: String,
        pollSecret: String
    ): Result<MultipassPairingPollResponse> =
        pollDevicePairing(originUrl, pairingId, pollSecret, context)

    /**
     * Pulls and hydrates the user's Private Media Vault from `GET {origin}/v1/media-sync`,
     * supporting both `schemaVersion: 2` (`VMK` / `keySlots`) and legacy `schemaVersion: 1`.
     */
    suspend fun syncFromEndpoint(
        context: Context,
        endpointUrl: String,
        pairToken: String = "",
        passphrase: String? = null
    ): SyncSummary = withContext(Dispatchers.IO) {
        val rawUrl = endpointUrl.trim()
        if (rawUrl.isBlank()) {
            return@withContext SyncSummary(false, message = "Multipass origin URL is empty")
        }

        if (isStaticJsonUrl(rawUrl)) {
            return@withContext syncFromStaticJsonUrl(context, rawUrl, pairToken, passphrase)
        }

        val origin = normalizeOrigin(rawUrl)
        val vaultUrl = "$origin/v1/media-sync"
        val effectiveToken = pairToken.ifBlank { AppPrefs.getMultipassProfile(context).pairToken }

        runCatching {
            val reqBuilder = Request.Builder().url(vaultUrl).get()
            if (effectiveToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${effectiveToken.trim()}")
            }
            BrootLiteApp.buildPermissiveHttpClient(20L).newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext SyncSummary(
                        ok = false,
                        message = "Multipass HTTP ${resp.code}: ${extractServerError(body)}"
                    )
                }

                val parsed = AppPrefs.json.decodeFromString<MultipassVaultGetResponse>(body)
                if (!parsed.exists || parsed.document == null) {
                    val cur = AppPrefs.getMultipassProfile(context)
                    AppPrefs.saveMultipassProfile(
                        context,
                        cur.copy(
                            serverUrl = origin,
                            pairToken = effectiveToken.ifBlank { cur.pairToken },
                            vaultRevision = 0,
                            lastSyncedAtMs = System.currentTimeMillis()
                        )
                    )
                    return@withContext SyncSummary(
                        ok = true,
                        revision = 0,
                        installedAddonsCount = AppPrefs.getInstalledAddons(context).size,
                        jellyfinServersCount = AppPrefs.getJellyfinServers(context).size,
                        creatorFollowsCount = AppPrefs.getCreatorFollows(context).size,
                        message = "Multipass vault is currently empty — use 'Push Setup' to encrypt & store your setup!"
                    )
                }

                hydrateVaultDocumentJson(
                    context = context,
                    docObj = parsed.document,
                    revision = parsed.revision,
                    origin = origin,
                    pairToken = effectiveToken,
                    passphrase = passphrase
                )
            }
        }.getOrElse { err ->
            SyncSummary(false, message = "Multipass sync error: ${err.message ?: "unreachable"}")
        }
    }

    /**
     * Deterministically merges remote + local state (`GET /v1/media-sync` -> decrypt -> merge ->
     * encrypt `schemaVersion: 2` with fresh 12-byte IV -> `PUT` with `expectedRevision`, retrying
     * up to 2 times on `409` optimistic concurrency conflicts).
     */
    suspend fun pushCurrentSetupToVault(
        context: Context,
        originUrl: String,
        pairToken: String,
        passphrase: String? = null
    ): SyncSummary = withContext(Dispatchers.IO) {
        val origin = normalizeOrigin(originUrl)
        if (origin.isBlank()) {
            return@withContext SyncSummary(false, message = "Configure your Multipass origin URL first")
        }
        val effectiveToken = pairToken.ifBlank { AppPrefs.getMultipassProfile(context).pairToken }.trim()
        if (effectiveToken.isBlank()) {
            return@withContext SyncSummary(false, message = "Pair this projector (`mpd_...` token) first via 'Pair QR'")
        }

        val cleanPass = passphrase?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanPass != null && cleanPass.length < 8) {
            return@withContext SyncSummary(false, message = "Vault passphrase must be at least 8 characters")
        }

        runCatching {
            for (attempt in 0 until MAX_CAS_ATTEMPTS) {
                val remoteState = fetchRemoteVaultOrError(origin, effectiveToken)
                var remotePayload: MediaSyncPayload? = null
                var remoteEnvelopeObj: JsonObject? = null
                var remoteKeySlots: JsonArray? = null
                var keySlotsOnEnvelope = false

                if (remoteState.exists && remoteState.document != null) {
                    val rDoc = remoteState.document
                    remoteEnvelopeObj = rDoc["envelope"] as? JsonObject
                    val topSlots = rDoc["keySlots"] as? JsonArray
                    val envSlots = remoteEnvelopeObj?.get("keySlots") as? JsonArray
                    if (topSlots != null) {
                        remoteKeySlots = topSlots
                    } else if (envSlots != null) {
                        remoteKeySlots = envSlots
                        keySlotsOnEnvelope = true
                    }

                    val decryptRes = decryptDocumentPayload(context, rDoc, cleanPass)
                    if (decryptRes.needsPassphrase) {
                        return@withContext SyncSummary(
                            ok = false,
                            needsPassphrase = true,
                            revision = remoteState.revision,
                            schemaVersion = decryptRes.schemaVersion,
                            mode = decryptRes.mode,
                            message = decryptRes.errorMessage
                                ?: "Vault is E2EE encrypted — unlock with your passphrase first before pushing"
                        )
                    }
                    if (decryptRes.payload == null) {
                        return@withContext SyncSummary(
                            ok = false,
                            revision = remoteState.revision,
                            message = decryptRes.errorMessage ?: "Unable to read existing remote Multipass vault"
                        )
                    }
                    remotePayload = decryptRes.payload
                }

                // Ensure we have (or can create) at least 1 vmkKeySlotSchema for mediaSyncDocumentV2Schema (.min(1).max(5))
                val cachedSlots = AppPrefs.getMultipassKeySlotsJson(context)
                    ?.let { runCatching { AppPrefs.json.parseToJsonElement(it).jsonArray }.getOrNull() }
                val availableBaseSlots = when {
                    remoteKeySlots != null && remoteKeySlots.isNotEmpty() -> remoteKeySlots
                    cachedSlots != null && cachedSlots.isNotEmpty() -> cachedSlots
                    else -> null
                }

                if (availableBaseSlots.isNullOrEmpty() && cleanPass == null) {
                    return@withContext SyncSummary(
                        ok = false,
                        needsPassphrase = true,
                        revision = remoteState.revision,
                        schemaVersion = 2,
                        mode = "e2ee-aes-256-gcm",
                        message = "Enter an 8+ character passphrase once to create your v2 E2EE recovery key slot"
                    )
                }

                // Resolve or initialize 32-byte VMK for schemaVersion 2 E2EE
                var vmk32B = AppPrefs.getMultipassVmk(context)
                if (vmk32B == null && cleanPass != null && availableBaseSlots != null) {
                    vmk32B = MultipassCrypto.unwrapVmkFromKeySlots(availableBaseSlots, cleanPass)
                    if (vmk32B != null) {
                        AppPrefs.saveMultipassVmk(context, vmk32B)
                    }
                }
                if (vmk32B == null) {
                    // Brand-new vault (or upgrading v1 -> v2 with passphrase): mint fresh 32B CSPRNG VMK and seal in Hardware Keystore
                    vmk32B = MultipassCrypto.randomBytes(32)
                    AppPrefs.saveMultipassVmk(context, vmk32B)
                }

                val finalKeySlots: JsonArray = try {
                    resolveOrBuildKeySlots(context, vmk32B, availableBaseSlots, cleanPass)
                } catch (e: Exception) {
                    vmk32B.fill(0)
                    throw e
                }

                val localPayload = captureCurrentMediaSyncPayload(context)
                val mergedPayload = if (remotePayload != null) {
                    mergeMediaSyncPayloads(context, remotePayload, localPayload)
                } else {
                    localPayload
                }

                val plaintextJson = vaultJson.encodeToString(mergedPayload)
                val v2EnvelopeObj = try {
                    MultipassCrypto.encryptV2WithVmk(
                        payloadJson = plaintextJson,
                        vmk32B = vmk32B,
                        envelopeTemplate = if ((remoteState.document?.get("schemaVersion")?.jsonPrimitive?.intOrNull ?: 0) >= 2) {
                            remoteEnvelopeObj
                        } else {
                            null
                        }
                    )
                } finally {
                    vmk32B.fill(0)
                }

                val docJsonObj = buildV2DocumentJson(
                    envelopeObj = v2EnvelopeObj,
                    keySlots = finalKeySlots,
                    keySlotsInsideEnvelope = keySlotsOnEnvelope
                )

                val putBody = buildJsonObject {
                    put("expectedRevision", if (remoteState.exists) remoteState.revision else 0)
                    put("document", docJsonObj)
                }.toString()

                val putReq = Request.Builder()
                    .url("$origin/v1/media-sync")
                    .header("Authorization", "Bearer $effectiveToken")
                    .put(putBody.toRequestBody(JSON_MEDIA))
                    .build()

                val conflictRetry = BrootLiteApp.sharedPermissiveHttpClient.newCall(putReq).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (resp.code == 409 && attempt + 1 < MAX_CAS_ATTEMPTS) {
                        return@use true
                    }
                    if (!resp.isSuccessful) {
                        return@withContext SyncSummary(
                            ok = false,
                            message = "Multipass PUT HTTP ${resp.code}: ${extractServerError(raw)}"
                        )
                    }

                    val putResp = vaultJson.decodeFromString<MultipassVaultPutResponse>(raw)
                    if (finalKeySlots.isNotEmpty()) {
                        AppPrefs.saveMultipassKeySlotsJson(context, finalKeySlots.toString())
                    }

                    // Keep local storage synchronized with merged multi-device state
                    if (remotePayload != null) {
                        applyMediaSyncPayload(
                            context = context,
                            payload = mergedPayload,
                            revision = putResp.revision,
                            schemaVersion = 2,
                            mode = "e2ee-aes-256-gcm",
                            origin = origin,
                            pairToken = effectiveToken
                        )
                    } else {
                        val prev = AppPrefs.getMultipassProfile(context)
                        AppPrefs.saveMultipassProfile(
                            context,
                            prev.copy(
                                serverUrl = origin,
                                pairToken = effectiveToken,
                                vaultRevision = putResp.revision,
                                vaultMode = "e2ee-aes-256-gcm",
                                lastSyncedAtMs = System.currentTimeMillis()
                            )
                        )
                    }

                    val addonCount = mergedPayload.stremioSync?.addons?.size ?: 0
                    val jfCount = mergedPayload.jellyfinServers.count { !it.isRemoved }
                    val cfCount = mergedPayload.creatorFollows.count { it.isActive }
                    return@withContext SyncSummary(
                        ok = true,
                        revision = putResp.revision,
                        schemaVersion = 2,
                        mode = "e2ee-aes-256-gcm",
                        installedAddonsCount = addonCount,
                        jellyfinServersCount = jfCount,
                        creatorFollowsCount = cfCount,
                        message = "Pushed v2 E2EE rev #${putResp.revision} ($addonCount addons · $jfCount Jellyfin · $cfCount follows)"
                    )
                }

                if (!conflictRetry) break
            }

            SyncSummary(false, message = "Vault revision conflict (HTTP 409) persisted after retries — pull vault and try again")
        }.getOrElse { err ->
            SyncSummary(false, message = "Vault push failed: ${err.message ?: "unknown error"}")
        }
    }

    /**
     * Exports a portable `schemaVersion: 2` E2EE `MultipassVaultDocument` snapshot to app external
     * storage (`files/multipass_vault_export.json`).
     */
    suspend fun exportLocalVaultSnapshot(
        context: Context,
        passphrase: String? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = captureCurrentMediaSyncPayload(context)
            val payloadStr = vaultJson.encodeToString(payload)
            val cleanPass = passphrase?.trim()?.takeIf { it.isNotEmpty() }

            val existingVmk = AppPrefs.getMultipassVmk(context)
            val cachedSlots = AppPrefs.getMultipassKeySlotsJson(context)
                ?.let { runCatching { AppPrefs.json.parseToJsonElement(it).jsonArray }.getOrNull() }
            val canExportV2 = cleanPass != null || (existingVmk != null && !cachedSlots.isNullOrEmpty())

            val outText = if (canExportV2) {
                val vmk = existingVmk ?: MultipassCrypto.randomBytes(32).also {
                    AppPrefs.saveMultipassVmk(context, it)
                }
                try {
                    val slots = resolveOrBuildKeySlots(context, vmk, cachedSlots, cleanPass)
                    if (slots.isNotEmpty()) {
                        AppPrefs.saveMultipassKeySlotsJson(context, slots.toString())
                    }
                    val env = MultipassCrypto.encryptV2WithVmk(payloadStr, vmk)
                    buildV2DocumentJson(env, slots, keySlotsInsideEnvelope = false).toString()
                } finally {
                    vmk.fill(0)
                }
            } else {
                existingVmk?.fill(0)
                val strictV1Payload = MediaSyncPayload(
                    stremioSync = payload.stremioSync,
                    kodiExternal = payload.kodiExternal,
                    indexers = payload.indexers,
                    torrentConfig = payload.torrentConfig
                )
                val doc = MultipassVaultDocument(
                    schemaVersion = 1,
                    mode = "server-at-rest",
                    payload = strictV1Payload
                )
                vaultJson.encodeToString(doc)
            }

            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val outFile = File(dir, "multipass_vault_export.json")
            outFile.writeText(outText)
            outFile
        }
    }

    fun captureCurrentMediaSyncPayload(context: Context): MediaSyncPayload {
        val hex40 = Regex("^[A-Fa-f0-9]{40}$")
        fun validHttpOrNull(raw: String?): String? =
            raw?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }

        val myDeviceKey = effectiveSyncDeviceId(context)
        val authKey = AppPrefs.getStremioAuthKey(context)?.ifBlank { null }
        val email = AppPrefs.getStremioEmail(context)?.ifBlank { null }
        val authUpdatedAt = AppPrefs.getStremioAuthUpdatedAtMs(context)
        val authRevokedAt = AppPrefs.getStremioAuthRevokedAtMs(context)

        val stremioAuth = if (authKey != null || authRevokedAt > 0L) {
            SyncedStremioAuth(
                authKey = if (authRevokedAt > authUpdatedAt) null else authKey,
                email = if (authRevokedAt > authUpdatedAt) null else email,
                updatedAtMs = authUpdatedAt,
                revokedAtMs = authRevokedAt
            )
        } else {
            null
        }

        val addons = AppPrefs.getInstalledAddons(context).map { a ->
            SyncedStremioAddon(
                transportUrl = a.transportUrl,
                id = a.id.ifBlank { null },
                name = a.name,
                version = a.version.ifBlank { null },
                enabled = true
            )
        }
        val history = AppPrefs.getWatchHistory(context).take(40).map { h ->
            SyncedWatchProgress(
                itemId = h.itemId,
                videoId = h.videoId,
                type = h.type,
                name = h.name,
                episodeTitle = h.episodeTitle.ifBlank { null },
                poster = validHttpOrNull(h.poster),
                background = validHttpOrNull(h.background),
                timeOffsetMs = h.timeOffsetMs,
                durationMs = h.durationMs,
                updatedAtMs = h.updatedAtMs,
                infoHash = h.infoHash?.trim()?.lowercase(Locale.US)?.takeIf { hex40.matches(it) },
                fileIdx = h.fileIdx
            )
        }
        val allFavs = AppPrefs.getFavorites(context)
        val stremioFavs = allFavs
            .filter { it.type != "kodi" && it.type != "torrent" && it.pluginUrl.isNullOrBlank() && it.magnetUri.isNullOrBlank() }
            .take(150)
            .map { f ->
                SyncedStremioFavorite(
                    id = f.id,
                    type = f.type,
                    name = f.name,
                    poster = validHttpOrNull(f.poster),
                    subtitle = f.subtitle.ifBlank { null },
                    addedAtMs = f.addedAtMs
                )
            }

        // Jellyfin Multi-Server profiles + tombstones
        val jfActive = AppPrefs.getJellyfinServers(context).map { srv ->
            val devMap = if (srv.accessToken.isNotBlank()) {
                mapOf(myDeviceKey to srv.accessToken)
            } else {
                emptyMap()
            }
            val effectiveTs = if (srv.updatedAtMs > 0L) srv.updatedAtMs else 1L
            SyncedJellyfinServer(
                serverId = srv.serverId,
                id = srv.serverId,
                name = srv.name,
                primaryUrl = srv.primaryUrl,
                serverUrl = srv.primaryUrl,
                localUrl = srv.localUrl,
                remoteUrl = srv.remoteUrl,
                userId = srv.userId,
                username = srv.username,
                bootstrapToken = srv.bootstrapToken.ifBlank { srv.accessToken },
                deviceTokens = devMap,
                allowInsecureTls = srv.allowInsecureTls,
                maxBitrateMbps = srv.maxBitrateMbps,
                enabled = srv.enabled,
                addedAtMs = effectiveTs,
                updatedAtMs = effectiveTs,
                removedAtMs = 0L
            )
        }
        val jfTombstones = AppPrefs.getJellyfinTombstones(context).map { (srvId, removedAt) ->
            SyncedJellyfinServer(
                serverId = srvId,
                id = srvId,
                name = "Removed Server",
                primaryUrl = "http://removed.invalid",
                serverUrl = "http://removed.invalid",
                updatedAtMs = 0L,
                removedAtMs = removedAt
            )
        }

        val ytAccounts = AppPrefs.getYouTubeAccounts(context)
        val localTwitch = AppPrefs.getTwitchDeviceOAuth(context)
        val twitchMap = if (localTwitch != null && (localTwitch.isActive || localTwitch.revokedAtMs > 0L)) {
            mapOf(myDeviceKey to localTwitch)
        } else {
            emptyMap()
        }
        val creatorFollows = AppPrefs.getCreatorFollows(context, includeTombstones = true)
        val streamingPrefs = SyncedStreamingPreferences(
            sponsorBlockEnabled = AppPrefs.isSponsorBlockEnabled(context),
            youtubeWatchHistoryEnabled = AppPrefs.isYouTubeWatchHistoryEnabled(context),
            returnYouTubeDislikeEnabled = AppPrefs.isReturnYouTubeDislikeEnabled(context),
            deArrowEnabled = AppPrefs.isDeArrowEnabled(context),
            updatedAtMs = AppPrefs.getStreamingPrefsUpdatedAtMs(context)
        )

        val kodiAddons = PyAddonClient.scanInstalledVideoAddonsFast(context).map { ka ->
            SyncedKodiAddon(
                id = ka.id,
                name = ka.name,
                pluginUrl = ka.pluginUrl,
                version = ka.version.ifBlank { null },
                enabled = true
            )
        }
        val customSites = AppPrefs.readCustomSites(context).map { triple ->
            val title = triple.first
            val pluginUrl = triple.second
            val slug = title.lowercase(java.util.Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "site" }
            val rawUrl = Regex("""[?&]url=([^&]+)""").find(pluginUrl)?.groupValues?.getOrNull(1)
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault("") }
                .orEmpty()
                .let { validHttpOrNull(it) ?: "https://example.invalid/$slug" }
            SyncedKodiCustomSite(
                addonId = pluginUrl.removePrefix("plugin://").substringBefore('/'),
                author = "Custom",
                name = slug,
                title = title,
                url = rawUrl,
                pluginUrl = pluginUrl,
                enabled = true
            )
        }
        val kodiFavs = allFavs.filter { it.type == "kodi" && !it.pluginUrl.isNullOrBlank() }.take(150).map { f ->
            SyncedKodiFavorite(
                id = f.id,
                type = "kodi",
                name = f.name,
                poster = validHttpOrNull(f.poster),
                subtitle = f.subtitle.ifBlank { null },
                pluginUrl = f.pluginUrl!!,
                addedAtMs = f.addedAtMs
            )
        }
        val keywords = AppPrefs.readCustomSiteKeywords(context).take(60)

        val tCfg = AppPrefs.getTorrentConfig(context)
        val indexers = if (tCfg.torznabUrl.isNotBlank()) {
            listOf(
                SyncedIndexer(
                    id = "primary-torznab",
                    name = "Configured Torznab Indexer",
                    url = tCfg.torznabUrl,
                    apiKey = tCfg.torznabApiKey.ifBlank { null },
                    type = "torznab",
                    enabled = true
                )
            )
        } else {
            emptyList()
        }

        return MediaSyncPayload(
            stremioAuth = stremioAuth,
            stremioSync = StremioSyncSection(
                authKey = stremioAuth?.authKey,
                email = stremioAuth?.email,
                addons = addons,
                watchProgress = history,
                favorites = stremioFavs
            ),
            jellyfinServers = jfActive + jfTombstones,
            youtubeAccounts = ytAccounts,
            youtubeOAuth = ytAccounts.firstOrNull { it.isActive },
            twitchByDevice = twitchMap,
            creatorFollows = creatorFollows,
            streamingPreferences = streamingPrefs,
            kodiExternal = KodiExternalSection(
                repositories = emptyList(),
                addons = kodiAddons,
                customSites = customSites,
                favorites = kodiFavs,
                keywords = keywords
            ),
            indexers = indexers,
            torrentConfig = SyncedTorrentConfig(
                preBufferMb = tCfg.preBufferMb,
                slidingWindowMb = tCfg.slidingWindowMb,
                maxConnections = tCfg.maxConnections
            )
        )
    }

    // =========================================================================
    // DETERMINISTIC MULTI-DEVICE CRDT / LWW MERGE ENGINE
    // =========================================================================

    fun mergeMediaSyncPayloads(
        context: Context,
        remote: MediaSyncPayload,
        local: MediaSyncPayload
    ): MediaSyncPayload {
        // 1. Stremio Auth (LWW + Tombstones)
        val remAuth = remote.stremioAuth ?: remote.stremioSync?.let {
            if (!it.authKey.isNullOrBlank()) {
                SyncedStremioAuth(
                    authKey = it.authKey,
                    email = it.email,
                    updatedAtMs = 1L,
                    revokedAtMs = 0L
                )
            } else null
        }
        val locAuth = local.stremioAuth
        val mergedAuth = mergeStremioAuthLww(remAuth, locAuth)

        // Stremio Addons union by transportUrl + WatchProgress LWW + Favorites union
        val addonsMap = LinkedHashMap<String, SyncedStremioAddon>()
        for (a in remote.stremioSync?.addons.orEmpty()) addonsMap[a.transportUrl] = a
        for (a in local.stremioSync?.addons.orEmpty()) addonsMap[a.transportUrl] = a

        val wpMap = LinkedHashMap<String, SyncedWatchProgress>()
        for (w in remote.stremioSync?.watchProgress.orEmpty()) wpMap[w.itemId] = w
        for (w in local.stremioSync?.watchProgress.orEmpty()) {
            val prev = wpMap[w.itemId]
            if (prev == null || w.updatedAtMs >= prev.updatedAtMs) {
                wpMap[w.itemId] = w
            }
        }

        val favMap = LinkedHashMap<String, SyncedStremioFavorite>()
        for (f in remote.stremioSync?.favorites.orEmpty()) favMap[f.id] = f
        for (f in local.stremioSync?.favorites.orEmpty()) {
            val prev = favMap[f.id]
            if (prev == null || (f.addedAtMs ?: 0L) >= (prev.addedAtMs ?: 0L)) {
                favMap[f.id] = f
            }
        }

        // 2. Jellyfin Multi-Server merge by serverId + deviceTokens[multipassDeviceId] map union
        val jfById = LinkedHashMap<String, SyncedJellyfinServer>()
        for (r in remote.jellyfinServers) {
            val rKey = r.effectiveServerId
            if (rKey.isNotBlank()) {
                jfById[rKey] = r.copy(
                    serverId = rKey,
                    id = rKey,
                    primaryUrl = r.effectivePrimaryUrl,
                    serverUrl = r.effectivePrimaryUrl
                )
            }
        }
        for (l in local.jellyfinServers) {
            val lKey = l.effectiveServerId
            if (lKey.isBlank()) continue
            val prev = jfById[lKey]
            if (prev == null) {
                jfById[lKey] = l.copy(
                    serverId = lKey,
                    id = lKey,
                    primaryUrl = l.effectivePrimaryUrl,
                    serverUrl = l.effectivePrimaryUrl
                )
            } else {
                val prevTs = maxOf(prev.effectiveUpdatedAtMs, prev.removedAtMs)
                val locTs = maxOf(l.effectiveUpdatedAtMs, l.removedAtMs)
                val unionTokens = LinkedHashMap<String, String>().apply {
                    for ((k, v) in prev.deviceTokens) if (k.isNotBlank() && v.isNotBlank()) put(k, v)
                    for ((k, v) in l.deviceTokens) if (k.isNotBlank() && v.isNotBlank()) put(k, v)
                }
                val winner = if (locTs >= prevTs) l else prev
                val runnerUp = if (locTs >= prevTs) prev else l
                val winUrl = winner.effectivePrimaryUrl.ifBlank { runnerUp.effectivePrimaryUrl }
                jfById[lKey] = if (winner.isRemoved) {
                    winner.copy(
                        serverId = lKey,
                        id = lKey,
                        deviceTokens = emptyMap(),
                        bootstrapToken = "",
                        accessToken = ""
                    )
                } else {
                    winner.copy(
                        serverId = lKey,
                        id = lKey,
                        primaryUrl = winUrl,
                        serverUrl = winUrl,
                        localUrl = winner.localUrl.ifBlank { runnerUp.localUrl },
                        remoteUrl = winner.remoteUrl.ifBlank { runnerUp.remoteUrl },
                        userId = winner.userId.ifBlank { runnerUp.userId },
                        username = winner.username.ifBlank { runnerUp.username },
                        bootstrapToken = winner.bootstrapToken.ifBlank {
                            runnerUp.bootstrapToken.ifBlank { winner.accessToken.ifBlank { runnerUp.accessToken } }
                        },
                        deviceTokens = unionTokens
                    )
                }
            }
        }

        // 3. YouTube TV OAuth accounts (LWW + Tombstones, merging both youtubeAccounts[] and single youtubeOAuth)
        val ytByKey = LinkedHashMap<String, SyncedYouTubeOAuth>()
        for (r in (remote.youtubeAccounts + listOfNotNull(remote.youtubeOAuth))) {
            val prev = ytByKey[r.stableKey]
            if (prev == null || maxOf(r.updatedAtMs, r.revokedAtMs) >= maxOf(prev.updatedAtMs, prev.revokedAtMs)) {
                ytByKey[r.stableKey] = r
            }
        }
        for (l in (local.youtubeAccounts + listOfNotNull(local.youtubeOAuth))) {
            val prev = ytByKey[l.stableKey]
            if (prev == null || maxOf(l.updatedAtMs, l.revokedAtMs) >= maxOf(prev.updatedAtMs, prev.revokedAtMs)) {
                ytByKey[l.stableKey] = if (l.revokedAtMs > l.updatedAtMs) {
                    l.copy(refreshToken = "", accessToken = "", accessTokenExpiresAtMs = 0L)
                } else {
                    l
                }
            }
        }

        // 4. Twitch per-device OAuth map union by multipassDeviceId
        val twitchMerged = LinkedHashMap<String, SyncedTwitchDeviceOAuth>()
        for ((devId, rTw) in remote.twitchByDevice) {
            if (devId.isNotBlank()) twitchMerged[devId] = rTw
        }
        for ((devId, lTw) in local.twitchByDevice) {
            if (devId.isBlank()) continue
            val prev = twitchMerged[devId]
            if (prev == null || maxOf(lTw.updatedAtMs, lTw.revokedAtMs) >= maxOf(prev.updatedAtMs, prev.revokedAtMs)) {
                twitchMerged[devId] = lTw
            }
        }

        // 5. Creator Follows LWW-Element-Set by platform:channelId/handle
        val cfByKey = LinkedHashMap<String, CreatorFollowEntry>()
        for (r in remote.creatorFollows) {
            if (r.platform.isNotBlank() && (r.channelId.isNotBlank() || r.handle.isNotBlank() || r.id.isNotBlank())) {
                cfByKey[r.canonicalKey] = r
            }
        }
        for (l in local.creatorFollows) {
            if (l.platform.isBlank() || (l.channelId.isBlank() && l.handle.isBlank() && l.id.isBlank())) continue
            val k = l.canonicalKey
            val prev = cfByKey[k]
            if (prev == null || maxOf(l.addedAtMs, l.removedAtMs) >= maxOf(prev.addedAtMs, prev.removedAtMs)) {
                cfByKey[k] = l
            }
        }

        // 6. Streaming preferences LWW
        val remPrefs = remote.streamingPreferences
        val locPrefs = local.streamingPreferences
        val mergedPrefs = when {
            remPrefs == null -> locPrefs
            locPrefs == null -> remPrefs
            locPrefs.updatedAtMs >= remPrefs.updatedAtMs -> locPrefs
            else -> remPrefs
        }

        // 7. Kodi repositories/addons/customSites/favorites/keywords union (never wipe remote .zip repos!)
        val remKodi = remote.kodiExternal
        val locKodi = local.kodiExternal
        val kodiRepos = LinkedHashMap<String, SyncedKodiRepo>().apply {
            for (r in remKodi?.repositories.orEmpty()) put(r.id, r)
            for (r in locKodi?.repositories.orEmpty()) put(r.id, r)
        }.values.toList()
        val kodiAddons = LinkedHashMap<String, SyncedKodiAddon>().apply {
            for (a in remKodi?.addons.orEmpty()) put(a.id, a)
            for (a in locKodi?.addons.orEmpty()) put(a.id, a)
        }.values.toList()
        val kodiSites = LinkedHashMap<String, SyncedKodiCustomSite>().apply {
            for (s in remKodi?.customSites.orEmpty()) put(s.name, s)
            for (s in locKodi?.customSites.orEmpty()) put(s.name, s)
        }.values.toList()
        val kodiFavs = LinkedHashMap<String, SyncedKodiFavorite>().apply {
            for (f in remKodi?.favorites.orEmpty()) put(f.id, f)
            for (f in locKodi?.favorites.orEmpty()) put(f.id, f)
        }.values.take(300)
        val kodiKeywords = LinkedHashSet<String>().apply {
            addAll(locKodi?.keywords.orEmpty())
            addAll(remKodi?.keywords.orEmpty())
        }.take(150)
        val kodiSettings = LinkedHashMap<String, JsonElement>().apply {
            putAll(remKodi?.settings.orEmpty())
            putAll(locKodi?.settings.orEmpty())
        }

        // 8. Indexers union
        val idxMap = LinkedHashMap<String, SyncedIndexer>()
        for (idx in remote.indexers) idxMap[idx.id ?: idx.url] = idx
        for (idx in local.indexers) idxMap[idx.id ?: idx.url] = idx

        val mergedYtList = ytByKey.values.toList()
        return MediaSyncPayload(
            stremioAuth = mergedAuth,
            stremioSync = StremioSyncSection(
                authKey = if (mergedAuth != null && mergedAuth.revokedAtMs <= mergedAuth.updatedAtMs) mergedAuth.authKey else null,
                email = if (mergedAuth != null && mergedAuth.revokedAtMs <= mergedAuth.updatedAtMs) mergedAuth.email else null,
                addons = addonsMap.values.take(150),
                watchProgress = wpMap.values.sortedByDescending { it.updatedAtMs }.take(100),
                favorites = favMap.values.sortedByDescending { it.addedAtMs ?: 0L }.take(300)
            ),
            jellyfinServers = jfById.values.toList(),
            youtubeAccounts = mergedYtList,
            youtubeOAuth = mergedYtList.firstOrNull { it.isActive },
            twitchByDevice = twitchMerged,
            creatorFollows = cfByKey.values
                .sortedByDescending { maxOf(it.addedAtMs, it.removedAtMs) }
                .take(500),
            streamingPreferences = mergedPrefs,
            kodiExternal = KodiExternalSection(
                repositories = kodiRepos,
                addons = kodiAddons,
                customSites = kodiSites,
                favorites = kodiFavs,
                keywords = kodiKeywords,
                settings = kodiSettings
            ),
            indexers = idxMap.values.take(50),
            torrentConfig = local.torrentConfig ?: remote.torrentConfig
        )
    }

    private fun mergeStremioAuthLww(
        rem: SyncedStremioAuth?,
        loc: SyncedStremioAuth?
    ): SyncedStremioAuth? {
        if (rem == null) return loc
        if (loc == null) return rem
        val remTs = maxOf(rem.updatedAtMs, rem.revokedAtMs)
        val locTs = maxOf(loc.updatedAtMs, loc.revokedAtMs)
        val winner = if (locTs >= remTs) loc else rem
        return if (winner.revokedAtMs > winner.updatedAtMs) {
            winner.copy(authKey = null, email = null)
        } else {
            winner
        }
    }

    // =========================================================================
    // DECRYPTION, HYDRATION & JELLYFIN ZERO-PASSWORD AUTO-BOOTSTRAP
    // =========================================================================

    private data class DecryptOutcome(
        val payload: MediaSyncPayload? = null,
        val needsPassphrase: Boolean = false,
        val schemaVersion: Int = 2,
        val mode: String = "",
        val errorMessage: String? = null
    )

    private fun decryptDocumentPayload(
        context: Context,
        docObj: JsonObject,
        passphrase: String?
    ): DecryptOutcome {
        val schemaVersion = docObj["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1
        val mode = docObj["mode"]?.jsonPrimitive?.contentOrNull.orEmpty()

        if (mode == "server-at-rest") {
            val pObj = docObj["payload"]
            val decoded = if (pObj != null) {
                runCatching { vaultJson.decodeFromJsonElement(MediaSyncPayload.serializer(), pObj) }.getOrNull()
            } else {
                MediaSyncPayload()
            }
            return DecryptOutcome(
                payload = decoded ?: MediaSyncPayload(),
                schemaVersion = schemaVersion,
                mode = mode
            )
        }

        if (mode != "e2ee-aes-256-gcm") {
            return DecryptOutcome(
                schemaVersion = schemaVersion,
                mode = mode,
                errorMessage = "Unsupported Multipass vault mode: $mode"
            )
        }

        val envObj = docObj["envelope"] as? JsonObject
            ?: return DecryptOutcome(schemaVersion = schemaVersion, mode = mode, errorMessage = "Missing E2EE envelope")

        if (schemaVersion >= 2) {
            val docSlots = (docObj["keySlots"] as? JsonArray)
                ?: (envObj["keySlots"] as? JsonArray)
            if (docSlots != null && docSlots.isNotEmpty()) {
                AppPrefs.saveMultipassKeySlotsJson(context, docSlots.toString())
            }

            // 1. Try Hardware-Keystore sealed VMK first (zero-passphrase UX after QR #pk pairing)
            val localVmk = AppPrefs.getMultipassVmk(context)
            if (localVmk != null) {
                val clearJson = try {
                    runCatching { MultipassCrypto.decryptV2WithVmk(envObj, localVmk) }.getOrNull()
                } finally {
                    localVmk.fill(0)
                }
                if (clearJson != null) {
                    val parsed = runCatching { vaultJson.decodeFromString<MediaSyncPayload>(clearJson) }.getOrNull()
                    if (parsed != null) {
                        return DecryptOutcome(payload = parsed, schemaVersion = schemaVersion, mode = mode)
                    }
                }
            }

            // 2. Fallback: unwrap VMK from primary-passphrase keySlot if user entered passphrase
            val cleanPass = passphrase?.trim()?.takeIf { it.isNotEmpty() }
            val candidateSlots = docSlots
                ?: AppPrefs.getMultipassKeySlotsJson(context)?.let {
                    runCatching { AppPrefs.json.parseToJsonElement(it).jsonArray }.getOrNull()
                }

            if (cleanPass == null) {
                return DecryptOutcome(
                    needsPassphrase = true,
                    schemaVersion = schemaVersion,
                    mode = mode,
                    errorMessage = "Vault is v2 E2EE locked — pair via QR on an unlocked phone or enter your vault passphrase"
                )
            }

            if (candidateSlots == null || candidateSlots.isEmpty()) {
                return DecryptOutcome(
                    needsPassphrase = false,
                    schemaVersion = schemaVersion,
                    mode = mode,
                    errorMessage = "Vault has no passphrase keySlot — re-pair via QR from an authorized device"
                )
            }

            val unwrappedVmk = MultipassCrypto.unwrapVmkFromKeySlots(candidateSlots, cleanPass)
                ?: return DecryptOutcome(
                    needsPassphrase = true,
                    schemaVersion = schemaVersion,
                    mode = mode,
                    errorMessage = "Incorrect vault passphrase"
                )

            return try {
                val clearJson = MultipassCrypto.decryptV2WithVmk(envObj, unwrappedVmk)
                val parsed = vaultJson.decodeFromString<MediaSyncPayload>(clearJson)
                AppPrefs.saveMultipassVmk(context, unwrappedVmk)
                DecryptOutcome(payload = parsed, schemaVersion = schemaVersion, mode = mode)
            } catch (_: Exception) {
                DecryptOutcome(
                    needsPassphrase = true,
                    schemaVersion = schemaVersion,
                    mode = mode,
                    errorMessage = "Tampered or unreadable v2 E2EE envelope"
                )
            } finally {
                unwrappedVmk.fill(0)
            }
        }

        // Legacy schemaVersion == 1 E2EE (PBKDF2 direct envelope)
        val cleanPass = passphrase?.trim()?.takeIf { it.isNotEmpty() }
            ?: return DecryptOutcome(
                needsPassphrase = true,
                schemaVersion = 1,
                mode = mode,
                errorMessage = "Vault is E2EE encrypted — enter your vault passphrase to unlock"
            )

        return runCatching {
            val env = vaultJson.decodeFromJsonElement(MultipassE2eeEnvelope.serializer(), envObj)
            val clearJson = MultipassCrypto.decrypt(env, cleanPass)
            val parsed = vaultJson.decodeFromString<MediaSyncPayload>(clearJson)
            DecryptOutcome(payload = parsed, schemaVersion = 1, mode = mode)
        }.getOrElse {
            DecryptOutcome(
                needsPassphrase = true,
                schemaVersion = 1,
                mode = mode,
                errorMessage = "Incorrect vault passphrase or tampered E2EE envelope"
            )
        }
    }

    private suspend fun hydrateVaultDocumentJson(
        context: Context,
        docObj: JsonObject,
        revision: Int,
        origin: String,
        pairToken: String,
        passphrase: String?
    ): SyncSummary {
        val outcome = decryptDocumentPayload(context, docObj, passphrase)
        if (outcome.needsPassphrase || outcome.payload == null) {
            return SyncSummary(
                ok = false,
                needsPassphrase = outcome.needsPassphrase,
                revision = revision,
                schemaVersion = outcome.schemaVersion,
                mode = outcome.mode,
                message = outcome.errorMessage ?: "Unable to unlock Multipass vault"
            )
        }
        return applyMediaSyncPayload(
            context = context,
            payload = outcome.payload,
            revision = revision,
            schemaVersion = outcome.schemaVersion,
            mode = outcome.mode,
            origin = origin,
            pairToken = pairToken
        )
    }

    private suspend fun applyMediaSyncPayload(
        context: Context,
        payload: MediaSyncPayload,
        revision: Int,
        schemaVersion: Int,
        mode: String,
        origin: String,
        pairToken: String
    ): SyncSummary {
        val myDeviceKey = effectiveSyncDeviceId(context)

        // 1. Stremio Auth (LWW + Tombstones) + Addons + Watch History + Favorites
        val stremio = payload.stremioSync
        val remoteAuth = payload.stremioAuth ?: stremio?.let {
            if (!it.authKey.isNullOrBlank()) {
                SyncedStremioAuth(
                    authKey = it.authKey,
                    email = it.email,
                    updatedAtMs = 1L,
                    revokedAtMs = 0L
                )
            } else null
        }

        if (remoteAuth != null) {
            val localTs = maxOf(
                AppPrefs.getStremioAuthUpdatedAtMs(context),
                AppPrefs.getStremioAuthRevokedAtMs(context)
            )
            val remoteTs = maxOf(remoteAuth.updatedAtMs, remoteAuth.revokedAtMs)
            if (remoteTs >= localTs) {
                if (remoteAuth.revokedAtMs > remoteAuth.updatedAtMs) {
                    AppPrefs.setStremioAccountWithMeta(
                        context,
                        authKey = "",
                        email = "",
                        updatedAtMs = remoteAuth.updatedAtMs,
                        revokedAtMs = remoteAuth.revokedAtMs
                    )
                } else if (!remoteAuth.authKey.isNullOrBlank()) {
                    AppPrefs.setStremioAccountWithMeta(
                        context,
                        authKey = remoteAuth.authKey,
                        email = remoteAuth.email ?: "Multipass Synced",
                        updatedAtMs = remoteAuth.updatedAtMs,
                        revokedAtMs = 0L
                    )
                    StremioApiClient.syncAccountEverything(context, remoteAuth.authKey)
                }
            }
        }

        if (stremio != null) {
            for (addon in stremio.addons) {
                if (addon.enabled && addon.transportUrl.isNotBlank()) {
                    StremioApiClient.installAddonFromUrl(context, addon.transportUrl)
                }
            }
            if (stremio.watchProgress.isNotEmpty()) {
                AppPrefs.mergeRemoteWatchHistory(
                    context,
                    stremio.watchProgress.map { wp ->
                        WatchHistoryEntry(
                            itemId = wp.itemId,
                            videoId = wp.videoId,
                            type = wp.type,
                            name = wp.name,
                            episodeTitle = wp.episodeTitle.orEmpty(),
                            poster = wp.poster.orEmpty(),
                            background = wp.background.orEmpty(),
                            timeOffsetMs = wp.timeOffsetMs,
                            durationMs = wp.durationMs,
                            updatedAtMs = wp.updatedAtMs,
                            infoHash = wp.infoHash,
                            fileIdx = wp.fileIdx
                        )
                    }
                )
            }
            if (stremio.favorites.isNotEmpty()) {
                AppPrefs.mergeFavorites(
                    context,
                    stremio.favorites.map { f ->
                        FavoriteEntry(
                            id = f.id,
                            type = f.type,
                            name = f.name,
                            poster = f.poster.orEmpty(),
                            subtitle = f.subtitle ?: "Multipass · Stremio",
                            addedAtMs = f.addedAtMs ?: System.currentTimeMillis()
                        )
                    }
                )
            }
        }

        // 2. Multi-Server Jellyfin Hydration + Per-Device QuickConnect Auto-Bootstrap
        if (payload.jellyfinServers.isNotEmpty()) {
            val localById = AppPrefs.getJellyfinServers(context).associateBy { it.serverId }.toMutableMap()
            val localTombstones = AppPrefs.getJellyfinTombstones(context).toMutableMap()

            for (remoteSrv in payload.jellyfinServers) {
                val rServerId = remoteSrv.effectiveServerId
                val rPrimaryUrl = remoteSrv.effectivePrimaryUrl
                if (rServerId.isBlank()) continue
                val existingLocal = localById[rServerId]
                val localRemovedAt = localTombstones[rServerId] ?: 0L
                val localEffectiveTs = maxOf(existingLocal?.updatedAtMs ?: 0L, localRemovedAt)
                val remoteEffectiveTs = maxOf(remoteSrv.effectiveUpdatedAtMs, remoteSrv.removedAtMs)

                if (remoteSrv.isRemoved) {
                    if (remoteEffectiveTs >= localEffectiveTs) {
                        localById.remove(rServerId)
                        localTombstones[rServerId] = remoteSrv.removedAtMs
                        JellyfinEndpointRouter.invalidate(rServerId)
                    }
                    continue
                }

                if (localRemovedAt > remoteEffectiveTs || rPrimaryUrl.isBlank()) {
                    continue
                }

                var myToken = existingLocal?.accessToken?.takeIf { it.isNotBlank() }
                    ?: remoteSrv.deviceTokens[myDeviceKey]?.takeIf { it.isNotBlank() }
                    ?: ""
                var resolvedUserId = existingLocal?.userId?.takeIf { it.isNotBlank() } ?: remoteSrv.userId
                var resolvedUsername = existingLocal?.username?.takeIf { it.isNotBlank() } ?: remoteSrv.username

                val candidateBootstrap = remoteSrv.bootstrapToken
                    .ifBlank { remoteSrv.accessToken }
                    .ifBlank { existingLocal?.bootstrapToken.orEmpty() }

                // Newly paired BrootLite TV lacks its own isolated per-device Jellyfin token -> self-bootstrap via QuickConnect!
                if (myToken.isBlank() && candidateBootstrap.isNotBlank()) {
                    val tempProfile = JellyfinServerProfile(
                        serverId = rServerId,
                        name = remoteSrv.name,
                        primaryUrl = rPrimaryUrl,
                        localUrl = remoteSrv.localUrl,
                        remoteUrl = remoteSrv.remoteUrl,
                        allowInsecureTls = remoteSrv.allowInsecureTls
                    )
                    val activeBase = JellyfinEndpointRouter.resolveBaseUrl(tempProfile)
                    if (activeBase.isNotBlank()) {
                        val minted = JellyfinApiClient.mintDeviceTokenViaBootstrap(
                            context = context,
                            baseUrl = activeBase,
                            bootstrapToken = candidateBootstrap,
                            allowInsecureTls = remoteSrv.allowInsecureTls
                        )
                        if (minted != null) {
                            myToken = minted.AccessToken
                            resolvedUserId = minted.User.Id.ifBlank { resolvedUserId }
                            resolvedUsername = minted.User.Name.ifBlank { resolvedUsername }
                        } else if (remoteSrv.accessToken.isNotBlank()) {
                            // Web SDK server entry without QuickConnect enabled: fall back to explicit synced accessToken
                            myToken = remoteSrv.accessToken
                        }
                    }
                }

                localTombstones.remove(rServerId)
                localById[rServerId] = JellyfinServerProfile(
                    serverId = rServerId,
                    name = remoteSrv.name,
                    primaryUrl = rPrimaryUrl,
                    localUrl = remoteSrv.localUrl.ifBlank { existingLocal?.localUrl.orEmpty() },
                    remoteUrl = remoteSrv.remoteUrl.ifBlank { existingLocal?.remoteUrl.orEmpty() },
                    userId = resolvedUserId,
                    username = resolvedUsername,
                    accessToken = myToken,
                    bootstrapToken = candidateBootstrap.ifBlank { myToken },
                    allowInsecureTls = remoteSrv.allowInsecureTls,
                    maxBitrateMbps = remoteSrv.maxBitrateMbps,
                    enabled = remoteSrv.enabled,
                    updatedAtMs = maxOf(remoteSrv.effectiveUpdatedAtMs, existingLocal?.updatedAtMs ?: 0L)
                )
            }

            AppPrefs.saveJellyfinServers(context, localById.values.toList())
            AppPrefs.saveJellyfinTombstones(context, localTombstones)
        }

        // 3. YouTube TV OAuth (LWW + Tombstones, supporting both youtubeAccounts[] and single youtubeOAuth)
        val incomingYt = payload.youtubeAccounts + listOfNotNull(payload.youtubeOAuth)
        if (incomingYt.isNotEmpty()) {
            val byKey = AppPrefs.getYouTubeAccounts(context).associateBy { it.stableKey }.toMutableMap()
            for (rAcc in incomingYt) {
                val cur = byKey[rAcc.stableKey]
                if (cur == null || maxOf(rAcc.updatedAtMs, rAcc.revokedAtMs) >= maxOf(cur.updatedAtMs, cur.revokedAtMs)) {
                    byKey[rAcc.stableKey] = rAcc
                }
            }
            AppPrefs.saveYouTubeAccounts(context, byKey.values.toList())
        }

        // 4. Twitch per-device OAuth (myDeviceKey)
        val myRemoteTwitch = payload.twitchByDevice[myDeviceKey]
        if (myRemoteTwitch != null) {
            val localTw = AppPrefs.getTwitchDeviceOAuth(context)
            if (localTw == null ||
                maxOf(myRemoteTwitch.updatedAtMs, myRemoteTwitch.revokedAtMs) >= maxOf(localTw.updatedAtMs, localTw.revokedAtMs)
            ) {
                AppPrefs.saveTwitchDeviceOAuth(context, myRemoteTwitch)
            }
        }

        // 5. Creator Follows LWW-Element-Set
        if (payload.creatorFollows.isNotEmpty()) {
            val mergedFollows = LinkedHashMap<String, CreatorFollowEntry>()
            for (l in AppPrefs.getCreatorFollows(context, includeTombstones = true)) {
                mergedFollows[l.canonicalKey] = l
            }
            for (r in payload.creatorFollows) {
                val resolvedId = r.channelId.ifBlank { r.handle.ifBlank { r.id } }.trim()
                if (r.platform.isBlank() || resolvedId.isBlank()) continue
                val normalized = r.copy(channelId = resolvedId)
                val k = normalized.canonicalKey
                val prev = mergedFollows[k]
                if (prev == null || maxOf(normalized.addedAtMs, normalized.removedAtMs) >= maxOf(prev.addedAtMs, prev.removedAtMs)) {
                    mergedFollows[k] = normalized
                }
            }
            AppPrefs.saveCreatorFollows(context, mergedFollows.values.toList())
        }

        // 6. Streaming / YouTube Settings Toggles
        val sp = payload.streamingPreferences
        if (sp != null && sp.updatedAtMs > AppPrefs.getStreamingPrefsUpdatedAtMs(context)) {
            AppPrefs.applySyncedStreamingPrefs(
                context = context,
                sponsorBlock = sp.sponsorBlockEnabled,
                youtubeWatchHistory = sp.youtubeWatchHistoryEnabled,
                returnYouTubeDislike = sp.returnYouTubeDislikeEnabled,
                deArrow = sp.deArrowEnabled,
                updatedAtMs = sp.updatedAtMs
            )
        }

        // 7. Torrent Engine & Private Indexers (Torznab / Prowlarr / Jackett)
        val curTorrent = AppPrefs.getTorrentConfig(context)
        val primaryIdx = payload.indexers.firstOrNull { it.enabled && it.url.isNotBlank() }
        val tc = payload.torrentConfig
        AppPrefs.saveTorrentConfig(
            context,
            curTorrent.copy(
                preBufferMb = tc?.preBufferMb ?: curTorrent.preBufferMb,
                slidingWindowMb = tc?.slidingWindowMb ?: curTorrent.slidingWindowMb,
                maxConnections = tc?.maxConnections ?: curTorrent.maxConnections,
                torznabUrl = primaryIdx?.url ?: curTorrent.torznabUrl,
                torznabApiKey = primaryIdx?.apiKey ?: curTorrent.torznabApiKey
            )
        )

        // 8. External Kodi Repositories (.zip extraction), Favorites & Kodi Addon DB hydration
        var extractedKodiZips = 0
        val kodi = payload.kodiExternal
        if (kodi != null) {
            for (repo in kodi.repositories) {
                if (repo.enabled && repo.zipUrl.isNotBlank()) {
                    if (installKodiZipIfMissing(context, repo.id, repo.zipUrl, repo.checksum, repo.checksumType)) {
                        extractedKodiZips++
                    }
                }
            }
            if (kodi.favorites.isNotEmpty()) {
                AppPrefs.mergeFavorites(
                    context,
                    kodi.favorites.map { kf ->
                        FavoriteEntry(
                            id = kf.id,
                            type = "kodi",
                            name = kf.name,
                            poster = kf.poster.orEmpty(),
                            subtitle = kf.subtitle ?: "Multipass · Kodi Vault",
                            pluginUrl = kf.pluginUrl,
                            addedAtMs = kf.addedAtMs ?: System.currentTimeMillis()
                        )
                    }
                )
            }
            if (kodi.keywords.isNotEmpty() || kodi.customSites.isNotEmpty()) {
                hydrateCustomSitesSqliteIfNeeded(context, kodi.keywords, kodi.customSites)
            }
        }

        val prev = AppPrefs.getMultipassProfile(context)
        val resolvedHandle = prev.handle.ifBlank {
            remoteAuth?.email?.takeIf { it.isNotBlank() }
                ?: stremio?.email?.takeIf { it.isNotBlank() }
                ?: "@vault:multipass"
        }
        AppPrefs.saveMultipassProfile(
            context,
            prev.copy(
                serverUrl = origin,
                handle = resolvedHandle,
                pairToken = pairToken.ifBlank { prev.pairToken },
                vaultRevision = revision,
                vaultMode = mode,
                lastSyncedAtMs = System.currentTimeMillis()
            )
        )

        val totalAddons = AppPrefs.getInstalledAddons(context).size
        val jfTotal = AppPrefs.getJellyfinServers(context).size
        val cfTotal = AppPrefs.getCreatorFollows(context).size
        val extras = buildList {
            add("$totalAddons addons")
            if (jfTotal > 0) add("$jfTotal Jellyfin")
            if (cfTotal > 0) add("$cfTotal follows")
            if (extractedKodiZips > 0) add("$extractedKodiZips Kodi ZIPs")
        }.joinToString(" · ")

        return SyncSummary(
            ok = true,
            handle = resolvedHandle,
            installedAddonsCount = totalAddons,
            jellyfinServersCount = jfTotal,
            creatorFollowsCount = cfTotal,
            revision = revision,
            schemaVersion = schemaVersion,
            mode = mode,
            message = "Hydrated Multipass v$schemaVersion rev #$revision ($extras)"
        )
    }

    // =========================================================================
    // INTERNAL VAULT TRANSPORT, KEYSLOT BUILDERS & KODI EXTRACTION HELPERS
    // =========================================================================

    private fun resolveOrBuildKeySlots(
        context: Context,
        vmk32B: ByteArray,
        remoteKeySlots: JsonArray?,
        passphrase: String?
    ): JsonArray {
        val baseSlots: List<JsonElement> = when {
            remoteKeySlots != null && remoteKeySlots.isNotEmpty() -> remoteKeySlots.toList()
            else -> AppPrefs.getMultipassKeySlotsJson(context)
                ?.let { runCatching { AppPrefs.json.parseToJsonElement(it).jsonArray.toList() }.getOrNull() }
                .orEmpty()
        }

        if (passphrase.isNullOrBlank()) {
            return JsonArray(baseSlots.take(5))
        }

        // Add or refresh primary-passphrase keySlot wrapping vmk32B (capped at 5 slots per mediaSyncDocumentV2Schema)
        val newSlot = MultipassCrypto.createPassphraseKeySlot(vmk32B, passphrase)
        val filtered = baseSlots.filterNot { el ->
            val obj = el as? JsonObject ?: return@filterNot false
            val id = obj["slotId"]?.jsonPrimitive?.contentOrNull ?: obj["id"]?.jsonPrimitive?.contentOrNull
            id == MultipassCrypto.PRIMARY_PASSPHRASE_SLOT_ID
        }.take(4)
        return buildJsonArray {
            add(newSlot)
            for (item in filtered) add(item)
        }
    }

    private fun buildV2DocumentJson(
        envelopeObj: JsonObject,
        keySlots: JsonArray,
        keySlotsInsideEnvelope: Boolean
    ): JsonObject {
        return buildJsonObject {
            put("schemaVersion", 2)
            put("mode", "e2ee-aes-256-gcm")
            if (keySlotsInsideEnvelope) {
                put(
                    "envelope",
                    buildJsonObject {
                        for ((k, v) in envelopeObj) put(k, v)
                        if (keySlots.isNotEmpty()) put("keySlots", keySlots)
                    }
                )
            } else {
                if (keySlots.isNotEmpty()) {
                    put("keySlots", keySlots)
                }
                put("envelope", envelopeObj)
            }
        }
    }

    private fun fetchRemoteVaultOrError(
        origin: String,
        pairToken: String
    ): MultipassVaultGetResponse {
        val req = Request.Builder()
            .url("$origin/v1/media-sync")
            .header("Authorization", "Bearer ${pairToken.trim()}")
            .get()
            .build()
        return BrootLiteApp.buildPermissiveHttpClient(15L).newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("Multipass GET HTTP ${resp.code}: ${extractServerError(raw)}")
            }
            AppPrefs.json.decodeFromString<MultipassVaultGetResponse>(raw)
        }
    }

    private fun pruneExpiredPendingPairings() {
        val now = System.currentTimeMillis()
        val iter = pendingPairings.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (now - entry.value.createdAtMs > PENDING_ECDH_TTL_MS) {
                entry.value.destroyQuietly()
                iter.remove()
            }
        }
    }

    /**
     * Safely downloads and extracts a Kodi repository/addon `.zip` archive into `files/kodi_env/addons/`
     * with optional SHA-256 verification and strict canonical-path Zip-Slip protection.
     */
    private fun installKodiZipIfMissing(
        context: Context,
        addonId: String,
        zipUrl: String,
        checksum: String?,
        checksumType: String?
    ): Boolean {
        val addonsRoot = File(AppPrefs.getKodiEnvDir(context), "addons").apply { mkdirs() }
        if (addonId.isNotBlank() && File(addonsRoot, addonId).isDirectory) {
            return false
        }
        return runCatching {
            val req = Request.Builder().url(zipUrl.trim()).get().build()
            BrootLiteApp.buildPermissiveHttpClient(30L).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val bytes = resp.body?.bytes() ?: return false
                if (bytes.isEmpty() || bytes.size > 60 * 1024 * 1024) return false

                if (!checksum.isNullOrBlank() && (checksumType == null || checksumType.equals("sha256", true))) {
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                    if (!digest.equals(checksum.trim(), ignoreCase = true)) {
                        return false
                    }
                }

                val canonicalRoot = addonsRoot.canonicalPath + File.separator
                ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(addonsRoot, entry.name)
                        val canonicalOut = outFile.canonicalPath
                        if (!canonicalOut.startsWith(canonicalRoot)) {
                            throw SecurityException("Blocked zip-slip entry: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
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

    private fun hydrateCustomSitesSqliteIfNeeded(
        context: Context,
        keywords: List<String>,
        customSites: List<SyncedKodiCustomSite>
    ) {
        runCatching {
            val targetAddonId = customSites.firstNotNullOfOrNull { it.addonId }?.takeIf { it.isNotBlank() } ?: "plugin.video.custom"
            val addonDir = File(AppPrefs.getKodiEnvDir(context), "userdata/addon_data/$targetAddonId").apply { mkdirs() }
            val dbFile = File(addonDir, "favorites.db")
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS keywords (keyword TEXT)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS custom_sites (" +
                        "author TEXT, name TEXT, title TEXT, url TEXT, image TEXT, about TEXT, " +
                        "version TEXT, module_file TEXT, enabled INTEGER DEFAULT 1)"
                )
                for (kw in keywords) {
                    val enc = java.net.URLEncoder.encode(kw.trim(), "UTF-8")
                    db.rawQuery("SELECT 1 FROM keywords WHERE keyword = ? LIMIT 1", arrayOf(enc)).use { c ->
                        if (!c.moveToFirst()) {
                            db.execSQL("INSERT INTO keywords (keyword) VALUES (?)", arrayOf(enc))
                        }
                    }
                }
                for (cs in customSites) {
                    db.rawQuery("SELECT 1 FROM custom_sites WHERE name = ? LIMIT 1", arrayOf(cs.name)).use { c ->
                        if (!c.moveToFirst()) {
                            db.execSQL(
                                "INSERT INTO custom_sites (author, name, title, url, image, enabled) VALUES (?, ?, ?, ?, ?, ?)",
                                arrayOf(
                                    cs.author ?: "Custom",
                                    cs.name,
                                    cs.title,
                                    cs.url,
                                    "",
                                    if (cs.enabled) 1 else 0
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun syncFromStaticJsonUrl(
        context: Context,
        cleanUrl: String,
        pairToken: String,
        passphrase: String?
    ): SyncSummary {
        return runCatching {
            val reqBuilder = Request.Builder().url(cleanUrl).get()
            if (pairToken.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${pairToken.trim()}")
            }
            BrootLiteApp.buildPermissiveHttpClient(15L).newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return SyncSummary(false, message = "Multipass HTTP ${resp.code}")
                }
                val body = resp.body?.string().orEmpty()
                val root = AppPrefs.json.parseToJsonElement(body).jsonObject
                if (root.containsKey("schemaVersion") && root.containsKey("mode")) {
                    return hydrateVaultDocumentJson(context, root, 1, cleanUrl, pairToken, passphrase)
                }
                applyLegacyJsonMetadataOnly(context, root, cleanUrl)
                val authKey = AppPrefs.getStremioAuthKey(context)
                if (!authKey.isNullOrBlank()) {
                    StremioApiClient.syncAccountEverything(context, authKey)
                }
                val addonUrls = (root["addons"] as? JsonArray)?.mapNotNull { el ->
                    when (el) {
                        is JsonObject -> el["transportUrl"]?.jsonPrimitive?.contentOrNull
                            ?: el["url"]?.jsonPrimitive?.contentOrNull
                        else -> el.jsonPrimitive.contentOrNull
                    }
                }.orEmpty()
                for (mUrl in addonUrls) {
                    StremioApiClient.installAddonFromUrl(context, mUrl)
                }
                val total = AppPrefs.getInstalledAddons(context).size
                SyncSummary(ok = true, installedAddonsCount = total, message = "Synced Multipass JSON ($total addons)")
            }
        }.getOrElse { err ->
            SyncSummary(false, message = "Multipass JSON error: ${err.message ?: "unreachable"}")
        }
    }

    private fun applyLegacyJsonMetadataOnly(
        context: Context,
        root: JsonObject,
        sourceUrl: String
    ): Boolean {
        val handle = root["handle"]?.jsonPrimitive?.contentOrNull
            ?: root["identity"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val serverUrl = root["serverUrl"]?.jsonPrimitive?.contentOrNull ?: sourceUrl
        val token = root["pairToken"]?.jsonPrimitive?.contentOrNull
            ?: root["deviceToken"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val authKey = root["stremioAuthKey"]?.jsonPrimitive?.contentOrNull
            ?: root["authKey"]?.jsonPrimitive?.contentOrNull
        val email = root["stremioEmail"]?.jsonPrimitive?.contentOrNull
            ?: root["email"]?.jsonPrimitive?.contentOrNull

        if (!authKey.isNullOrBlank()) {
            AppPrefs.setStremioAccount(context, authKey, email ?: handle.ifBlank { "Multipass Linked" })
        }

        val torznabUrl = root["torznabUrl"]?.jsonPrimitive?.contentOrNull
        val torznabKey = root["torznabApiKey"]?.jsonPrimitive?.contentOrNull
        if (!torznabUrl.isNullOrBlank()) {
            val cur = AppPrefs.getTorrentConfig(context)
            AppPrefs.saveTorrentConfig(
                context,
                cur.copy(torznabUrl = torznabUrl, torznabApiKey = torznabKey ?: cur.torznabApiKey)
            )
        }

        AppPrefs.saveMultipassProfile(
            context,
            MultipassProfile(
                serverUrl = serverUrl,
                handle = handle,
                pairToken = token,
                lastSyncedAtMs = System.currentTimeMillis()
            )
        )
        return true
    }

    private fun extractServerError(rawBody: String): String {
        if (rawBody.isBlank()) return "empty response"
        return runCatching {
            val obj = AppPrefs.json.parseToJsonElement(rawBody).jsonObject
            obj["message"]?.jsonPrimitive?.contentOrNull
                ?: obj["error"]?.jsonPrimitive?.contentOrNull
                ?: rawBody.take(80)
        }.getOrDefault(rawBody.take(80))
    }
}
