package app.brootlite.tv.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.brootlite.tv.BrootLiteApp
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hardware-backed local secret sealer using non-exportable `AndroidKeyStore` AES-256-GCM
 * (`alias = "brootlite_multipass_seal_v2"`).
 *
 * Wraps the Multipass Vault Master Key (`VMK`), scoped `mpd_...` device tokens, Stremio auth keys,
 * Jellyfin per-device tokens, and YouTube/Twitch OAuth refresh tokens at rest in `SharedPreferences`.
 * Includes a private `noBackupFilesDir` AES-256-GCM fallback solely for non-CTS OEM firmware where
 * Keymaster/Keymint might fail. Never logs any key material or token.
 */
object MultipassHardwareKeystore {

    const val KEY_ALIAS = "brootlite_multipass_seal_v2"
    const val SEAL_PREFIX = "ks2:"
    private const val FALLBACK_PREFIX = "fb2:"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val DEFAULT_STRING_AAD = "brootlite:local-secret:v2"

    private val lock = Any()
    private val rng = SecureRandom()
    private val b64Enc = Base64.getUrlEncoder().withoutPadding()
    private val b64Dec = Base64.getUrlDecoder()

    fun isSealed(raw: String?): Boolean =
        raw != null && (raw.startsWith(SEAL_PREFIX) || raw.startsWith(FALLBACK_PREFIX))

    fun sealBytes(bytes: ByteArray, aad: ByteArray? = null, context: Context? = null): String {
        if (bytes.isEmpty()) return ""
        synchronized(lock) {
            val hwSealed = runCatching {
                val secretKey = getOrCreateHardwareKey()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                // AndroidKeyStore with randomizedEncryptionRequired=true generates the 12B IV internally
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                if (aad != null && aad.isNotEmpty()) {
                    cipher.updateAAD(aad)
                }
                val ciphertext = cipher.doFinal(bytes)
                val iv = cipher.iv ?: error("Missing GCM IV from AndroidKeyStore")
                require(iv.size == GCM_IV_BYTES) { "Unexpected GCM IV size" }
                val combined = ByteArray(GCM_IV_BYTES + ciphertext.size)
                System.arraycopy(iv, 0, combined, 0, GCM_IV_BYTES)
                System.arraycopy(ciphertext, 0, combined, GCM_IV_BYTES, ciphertext.size)
                SEAL_PREFIX + b64Enc.encodeToString(combined)
            }.getOrNull()

            if (hwSealed != null) return hwSealed

            return sealBytesSoftwareFallback(bytes, aad, context)
        }
    }

    fun unsealBytes(sealed: String?, aad: ByteArray? = null, context: Context? = null): ByteArray? {
        if (sealed.isNullOrBlank()) return null
        synchronized(lock) {
            return when {
                sealed.startsWith(SEAL_PREFIX) -> runCatching {
                    val payload = b64Dec.decode(normalizeBase64Url(sealed.removePrefix(SEAL_PREFIX)))
                    if (payload.size <= GCM_IV_BYTES + 16) return@runCatching null
                    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    val secretKey = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                        ?: return@runCatching null
                    val iv = payload.copyOfRange(0, GCM_IV_BYTES)
                    val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
                    if (aad != null && aad.isNotEmpty()) {
                        cipher.updateAAD(aad)
                    }
                    cipher.doFinal(ciphertext)
                }.getOrNull()

                sealed.startsWith(FALLBACK_PREFIX) -> unsealBytesSoftwareFallback(
                    sealed.removePrefix(FALLBACK_PREFIX),
                    aad,
                    context
                )

                else -> null
            }
        }
    }

    fun sealString(
        plain: String?,
        aad: String = DEFAULT_STRING_AAD,
        context: Context? = null
    ): String {
        if (plain.isNullOrEmpty()) return ""
        if (isSealed(plain)) return plain
        val aadBytes = aad.toByteArray(StandardCharsets.UTF_8)
        return sealBytes(plain.toByteArray(StandardCharsets.UTF_8), aadBytes, context)
    }

    /**
     * Unseals a `"ks2:..."` / `"fb2:..."` string, or transparently returns legacy plaintext as-is
     * if `stored` does not carry a seal prefix (allowing zero-downtime migration on live devices).
     */
    fun unsealString(
        stored: String?,
        aad: String = DEFAULT_STRING_AAD,
        context: Context? = null
    ): String? {
        if (stored.isNullOrEmpty()) return null
        if (!isSealed(stored)) return stored
        val aadBytes = aad.toByteArray(StandardCharsets.UTF_8)
        val clear = unsealBytes(stored, aadBytes, context) ?: return null
        return try {
            String(clear, StandardCharsets.UTF_8)
        } finally {
            clear.fill(0)
        }
    }

    private fun getOrCreateHardwareKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun sealBytesSoftwareFallback(
        bytes: ByteArray,
        aad: ByteArray?,
        context: Context?
    ): String {
        val rawKey = getOrCreateFallbackKey(context)
        try {
            val iv = ByteArray(GCM_IV_BYTES).also(rng::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(rawKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            if (aad != null && aad.isNotEmpty()) {
                cipher.updateAAD(aad)
            }
            val ciphertext = cipher.doFinal(bytes)
            val combined = ByteArray(GCM_IV_BYTES + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, GCM_IV_BYTES)
            System.arraycopy(ciphertext, 0, combined, GCM_IV_BYTES, ciphertext.size)
            return FALLBACK_PREFIX + b64Enc.encodeToString(combined)
        } finally {
            rawKey.fill(0)
        }
    }

    private fun unsealBytesSoftwareFallback(
        encoded: String,
        aad: ByteArray?,
        context: Context?
    ): ByteArray? {
        return runCatching {
            val payload = b64Dec.decode(normalizeBase64Url(encoded))
            if (payload.size <= GCM_IV_BYTES + 16) return@runCatching null
            val rawKey = getOrCreateFallbackKey(context)
            try {
                val iv = payload.copyOfRange(0, GCM_IV_BYTES)
                val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(rawKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
                if (aad != null && aad.isNotEmpty()) {
                    cipher.updateAAD(aad)
                }
                cipher.doFinal(ciphertext)
            } finally {
                rawKey.fill(0)
            }
        }.getOrNull()
    }

    private fun getOrCreateFallbackKey(context: Context?): ByteArray {
        val ctx = context?.applicationContext ?: runCatching { BrootLiteApp.instance.applicationContext }.getOrNull()
            ?: error("Application context unavailable for keystore fallback")
        val file = File(ctx.noBackupFilesDir, ".brootlite_seal_v2.key")
        if (file.isFile && file.length() == 32L) {
            val existing = file.readBytes()
            if (existing.size == 32) return existing
        }
        val fresh = ByteArray(32).also(rng::nextBytes)
        file.parentFile?.mkdirs()
        file.writeBytes(fresh)
        runCatching {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
        return fresh
    }

    private fun normalizeBase64Url(raw: String): String =
        raw.trim().replace('+', '-').replace('/', '_').trimEnd('=')
}
