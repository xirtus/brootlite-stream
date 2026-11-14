use crate::config::ConfigStore;
use crate::error::{CoreError, CoreResult};
use crate::jellyfin::JellyfinClient;
use crate::models::{
    now_epoch_ms, CreatorFollowEntry, FavoriteEntry, JellyfinServerProfile, MultipassProfile,
    SwarmFilter, TorrentEngineConfig, TwitchAuthProfile, WatchHistoryEntry, YouTubeAccountProfile,
};
use crate::stremio::StremioClient;
use aes_gcm::{
    aead::{Aead, KeyInit, Payload},
    Aes256Gcm, Nonce,
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use hkdf::Hkdf;
use p256::{
    ecdh::diffie_hellman,
    elliptic_curve::sec1::ToEncodedPoint,
    PublicKey, SecretKey,
};
use pbkdf2::pbkdf2_hmac;
use rand::{rngs::OsRng, RngCore};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::Sha256;
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};
use zeroize::Zeroizing;

pub const DEFAULT_PBKDF2_ITERATIONS: u32 = 210_000;
pub const PAIR_WRAP_ALGO: &str = "ECDH-P256-HKDF-SHA256-AES-256-GCM";
pub const PAIR_WRAP_HKDF_INFO: &[u8] = b"leeloo-multipass:pair-wrap:v2";
pub const KEYSLOT_AAD: &str = "leeloo-multipass:keyslot:v2";
pub const MEDIA_SYNC_V2_INFO: &[u8] = b"leeloo-multipass:media-sync:v2";
pub const MEDIA_SYNC_V2_AAD: &str = "leeloo-multipass:media-sync:v2";
pub const MEDIA_SYNC_V1_AAD: &str = "leeloo-multipass:media-sync:v1";

// ─────────────────────────────────────────────────────────────────────────────
// Strict Wire Models Matching `leeloo-multipass/src/lib/media-sync.ts` (.strict())
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VmkKdfParams {
    pub algorithm: String,
    pub iterations: u32,
    pub salt: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct VmkKeySlot {
    pub slot_id: String,
    #[serde(rename = "type")]
    pub slot_type: String,
    pub kdf: VmkKdfParams,
    pub iv: String,
    pub aad: String,
    pub wrapped_vmk: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MediaSyncEnvelopeV2 {
    pub hkdf_salt: String,
    pub cipher: String,
    pub iv: String,
    pub aad: String,
    pub ciphertext: String,
}

/// Strictly serialized v2 vault document (`mediaSyncDocumentV2Schema.strict()`).
/// Contains NO extra or nullable keys so Zod `.strict()` on Leeloo Multipass accepts it 100%.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct MediaSyncDocumentV2 {
    pub schema_version: u8,
    pub mode: String,
    pub key_slots: Vec<VmkKeySlot>,
    pub envelope: MediaSyncEnvelopeV2,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct WrappedVaultKey {
    pub algorithm: String,
    pub ephemeral_public_key: String,
    pub iv: String,
    pub ciphertext: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub aad: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub key_slots: Option<Vec<VmkKeySlot>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MultipassPairingSession {
    pub origin_url: String,
    pub pairing_id: String,
    pub poll_secret: String,
    pub user_code: String,
    pub verification_url: String,
    pub qr_verification_url: String,
    pub tv_public_key_b64_url: String,
    pub expires_in_sec: u64,
    pub interval_sec: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "status", rename_all = "camelCase")]
pub enum MultipassPairingPollOutcome {
    Pending,
    Approved {
        device_id: String,
        device_name: String,
        scope: String,
        e2ee_unlocked: bool,
    },
    Denied,
    Expired,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct MultipassSyncReport {
    pub stremio_linked: bool,
    pub addons_imported: usize,
    pub jellyfin_servers_synced: usize,
    pub youtube_accounts_synced: usize,
    pub twitch_synced: bool,
    pub creator_follows_synced: usize,
    pub watch_history_merged: usize,
    pub favorites_merged: usize,
    pub revision: u64,
    pub vault_locked_needs_passphrase: bool,
}

// ─────────────────────────────────────────────────────────────────────────────
// Inner Encrypted Vault Payload (CRDT / LWW Multi-Device State)
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SyncedStremioAuth {
    #[serde(default)]
    pub auth_key: String,
    #[serde(default)]
    pub email: String,
    #[serde(default)]
    pub updated_at_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub revoked_at_ms: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SyncedAddonRef {
    pub transport_url: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub added_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SyncedJellyfinServer {
    pub server_id: String,
    pub server_name: String,
    #[serde(default)]
    pub lan_url: String,
    #[serde(default)]
    pub remote_url: String,
    #[serde(default)]
    pub user_id: String,
    #[serde(default)]
    pub username: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub access_token: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub bootstrap_token: String,
    #[serde(default, skip_serializing_if = "HashMap::is_empty")]
    pub device_tokens: HashMap<String, String>,
    #[serde(default)]
    pub updated_at_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub removed_at_ms: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SyncedYouTubeAccount {
    pub account_id: String,
    pub account_name: String,
    #[serde(default)]
    pub channel_handle: String,
    #[serde(default)]
    pub avatar_url: String,
    #[serde(default)]
    pub page_id: String,
    #[serde(default)]
    pub refresh_token: String,
    #[serde(default)]
    pub oauth_client_id: String,
    #[serde(default)]
    pub oauth_client_secret: String,
    #[serde(default)]
    pub updated_at_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub removed_at_ms: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SyncedStreamingPrefs {
    pub swarm_filter: SwarmFilter,
    pub torrent_config: TorrentEngineConfig,
    #[serde(default)]
    pub torznab_url: String,
    #[serde(default)]
    pub torznab_api_key: String,
    #[serde(default)]
    pub updated_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct MediaSyncPayload {
    #[serde(default = "default_payload_version")]
    pub version: u32,
    #[serde(default)]
    pub exported_at: u64,
    #[serde(default)]
    pub source: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub stremio_auth: Option<SyncedStremioAuth>,
    #[serde(default)]
    pub addons: Vec<SyncedAddonRef>,
    #[serde(default)]
    pub watch_progress: Vec<WatchHistoryEntry>,
    #[serde(default)]
    pub favorites: Vec<FavoriteEntry>,
    #[serde(default)]
    pub jellyfin_servers: Vec<SyncedJellyfinServer>,
    #[serde(default)]
    pub youtube_accounts: Vec<SyncedYouTubeAccount>,
    #[serde(default)]
    pub twitch_by_device: HashMap<String, TwitchAuthProfile>,
    #[serde(default)]
    pub creator_follows: Vec<CreatorFollowEntry>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub streaming_preferences: Option<SyncedStreamingPrefs>,
}

fn default_payload_version() -> u32 {
    2
}

// ─────────────────────────────────────────────────────────────────────────────
// Pure-Rust NIST P-256 ECDH + HKDF-SHA256 + PBKDF2-SHA256 + AES-256-GCM Crypto
// ─────────────────────────────────────────────────────────────────────────────

pub struct EphemeralP256KeyPair {
    secret_key: SecretKey,
    pub public_key_b64_url: String,
}

pub struct MultipassCrypto;

impl MultipassCrypto {
    /// Canonicalizes an 8-char pairing code to `XXXX-XXXX` uppercase ASCII.
    pub fn normalize_user_code(raw: &str) -> CoreResult<String> {
        let cleaned: String = raw
            .chars()
            .filter(|c| !c.is_whitespace() && *c != '-')
            .map(|c| c.to_ascii_uppercase())
            .collect();
        if cleaned.len() != 8 || !cleaned.chars().all(|c| c.is_ascii_alphanumeric()) {
            return Err(CoreError::InvalidInput(
                "Pairing userCode must be 8 alphanumeric characters (XXXX-XXXX)".into(),
            ));
        }
        Ok(format!("{}-{}", &cleaned[..4], &cleaned[4..8]))
    }

    /// Generates a fresh ephemeral NIST `P-256` (`secp256r1`) keypair and encodes the
    /// uncompressed 65-byte SEC1 public point (`0x04 || X_32B_BE || Y_32B_BE`) as base64url.
    pub fn generate_ephemeral_p256_keypair() -> EphemeralP256KeyPair {
        let secret_key = SecretKey::random(&mut OsRng);
        let pub_point = secret_key.public_key().to_encoded_point(false);
        let public_key_b64_url = URL_SAFE_NO_PAD.encode(pub_point.as_bytes());
        EphemeralP256KeyPair {
            secret_key,
            public_key_b64_url,
        }
    }

    pub fn generate_vault_master_key() -> Zeroizing<[u8; 32]> {
        let mut vmk = Zeroizing::new([0u8; 32]);
        OsRng.fill_bytes(&mut vmk[..]);
        vmk
    }

    /// Wraps a 32-byte VMK to a peer's 65-byte uncompressed SEC1 P-256 public key (`#pk=...`),
    /// matching the Leeloo Multipass phone/web approval flow byte-for-byte.
    pub fn wrap_paired_vault_master_key(
        vmk: &[u8],
        tv_public_key_b64_url: &str,
        user_code: &str,
        key_slots: Option<Vec<VmkKeySlot>>,
    ) -> CoreResult<WrappedVaultKey> {
        if vmk.len() != 32 {
            return Err(CoreError::Crypto("VMK must be 32 bytes".into()));
        }
        let normalized_code = Self::normalize_user_code(user_code)?;
        let peer_bytes = URL_SAFE_NO_PAD
            .decode(tv_public_key_b64_url.trim())
            .map_err(|e| CoreError::Crypto(format!("invalid peer public key base64url: {e}")))?;
        if peer_bytes.len() != 65 || peer_bytes[0] != 0x04 {
            return Err(CoreError::Crypto(
                "peer P-256 public key must be 65-byte uncompressed SEC1 (0x04 || X || Y)".into(),
            ));
        }
        let peer_pub = PublicKey::from_sec1_bytes(&peer_bytes)
            .map_err(|e| CoreError::Crypto(format!("invalid P-256 curve point: {e}")))?;

        let eph_secret = SecretKey::random(&mut OsRng);
        let eph_pub_bytes = eph_secret.public_key().to_encoded_point(false);

        let shared = diffie_hellman(eph_secret.to_nonzero_scalar(), peer_pub.as_affine());
        let hk = Hkdf::<Sha256>::new(
            Some(normalized_code.as_bytes()),
            &shared.raw_secret_bytes()[..],
        );
        let mut kek = Zeroizing::new([0u8; 32]);
        hk.expand(PAIR_WRAP_HKDF_INFO, &mut kek[..])
            .map_err(|_| CoreError::Crypto("HKDF expand failed".into()))?;

        let mut iv = [0u8; 12];
        OsRng.fill_bytes(&mut iv);
        let aad = format!("pair:{normalized_code}");
        let cipher = Aes256Gcm::new_from_slice(&kek[..])
            .map_err(|e| CoreError::Crypto(format!("AES-256-GCM init: {e}")))?;
        let ct = cipher
            .encrypt(
                &Nonce::from(iv),
                Payload {
                    msg: vmk,
                    aad: aad.as_bytes(),
                },
            )
            .map_err(|_| CoreError::Crypto("ECDH VMK wrap encrypt failed".into()))?;

        Ok(WrappedVaultKey {
            algorithm: PAIR_WRAP_ALGO.to_string(),
            ephemeral_public_key: URL_SAFE_NO_PAD.encode(eph_pub_bytes.as_bytes()),
            iv: URL_SAFE_NO_PAD.encode(iv),
            ciphertext: URL_SAFE_NO_PAD.encode(ct),
            aad: None,
            key_slots,
        })
    }

    /// Unwraps the 32-byte VMK from `wrappedVaultKey` returned by `/v1/media-sync/pair/poll`.
    pub fn unwrap_paired_vault_master_key(
        tv_keypair: &EphemeralP256KeyPair,
        wrapped: &WrappedVaultKey,
        user_code: &str,
    ) -> CoreResult<Zeroizing<[u8; 32]>> {
        if wrapped.algorithm != PAIR_WRAP_ALGO {
            return Err(CoreError::Crypto(format!(
                "unsupported wrappedVaultKey algorithm: {}",
                wrapped.algorithm
            )));
        }
        let normalized_code = Self::normalize_user_code(user_code)?;
        let peer_bytes = URL_SAFE_NO_PAD
            .decode(wrapped.ephemeral_public_key.trim())
            .map_err(|e| CoreError::Crypto(format!("invalid ephemeralPublicKey b64url: {e}")))?;
        if peer_bytes.len() != 65 || peer_bytes[0] != 0x04 {
            return Err(CoreError::Crypto(
                "ephemeralPublicKey must be 65-byte uncompressed SEC1 (0x04 || X || Y)".into(),
            ));
        }
        let peer_pub = PublicKey::from_sec1_bytes(&peer_bytes)
            .map_err(|e| CoreError::Crypto(format!("ephemeralPublicKey off P-256 curve: {e}")))?;

        let iv = URL_SAFE_NO_PAD
            .decode(wrapped.iv.trim())
            .map_err(|e| CoreError::Crypto(format!("invalid wrapped iv: {e}")))?;
        let iv_arr: [u8; 12] = iv
            .try_into()
            .map_err(|_| CoreError::Crypto("wrapped iv must be 12 bytes".into()))?;
        let ct = URL_SAFE_NO_PAD
            .decode(wrapped.ciphertext.trim())
            .map_err(|e| CoreError::Crypto(format!("invalid wrapped ciphertext: {e}")))?;
        if ct.len() != 48 {
            return Err(CoreError::Crypto(
                "wrapped VMK ciphertext must be 48 bytes (32B VMK + 16B GCM tag)".into(),
            ));
        }

        let shared = diffie_hellman(
            tv_keypair.secret_key.to_nonzero_scalar(),
            peer_pub.as_affine(),
        );
        let hk = Hkdf::<Sha256>::new(
            Some(normalized_code.as_bytes()),
            &shared.raw_secret_bytes()[..],
        );
        let mut kek = Zeroizing::new([0u8; 32]);
        hk.expand(PAIR_WRAP_HKDF_INFO, &mut kek[..])
            .map_err(|_| CoreError::Crypto("HKDF expand failed".into()))?;

        let default_aad = format!("pair:{normalized_code}");
        let aad = wrapped.aad.as_deref().unwrap_or(&default_aad);

        let cipher = Aes256Gcm::new_from_slice(&kek[..])
            .map_err(|e| CoreError::Crypto(format!("AES-256-GCM init: {e}")))?;
        let pt = Zeroizing::new(
            cipher
                .decrypt(
                    &Nonce::from(iv_arr),
                    Payload {
                        msg: &ct,
                        aad: aad.as_bytes(),
                    },
                )
                .map_err(|_| CoreError::Crypto("wrappedVaultKey GCM authentication failed".into()))?,
        );
        if pt.len() != 32 {
            return Err(CoreError::Crypto("unwrapped VMK is not 32 bytes".into()));
        }
        let mut out = Zeroizing::new([0u8; 32]);
        out.copy_from_slice(&pt);
        Ok(out)
    }

    pub fn create_passphrase_key_slot(
        vmk: &[u8],
        passphrase: &str,
        slot_id: &str,
        iterations: u32,
    ) -> CoreResult<VmkKeySlot> {
        if vmk.len() != 32 {
            return Err(CoreError::Crypto("VMK must be 32 bytes".into()));
        }
        if passphrase.len() < 8 {
            return Err(CoreError::InvalidInput(
                "Vault recovery passphrase must be at least 8 characters".into(),
            ));
        }
        let iters = iterations.clamp(100_000, 1_000_000);
        let mut salt = [0u8; 16];
        let mut iv = [0u8; 12];
        OsRng.fill_bytes(&mut salt);
        OsRng.fill_bytes(&mut iv);

        let mut kek = Zeroizing::new([0u8; 32]);
        pbkdf2_hmac::<Sha256>(passphrase.as_bytes(), &salt, iters, &mut kek[..]);

        let cipher = Aes256Gcm::new_from_slice(&kek[..])
            .map_err(|e| CoreError::Crypto(format!("AES-256-GCM init: {e}")))?;
        let wrapped_vmk = cipher
            .encrypt(
                &Nonce::from(iv),
                Payload {
                    msg: vmk,
                    aad: KEYSLOT_AAD.as_bytes(),
                },
            )
            .map_err(|_| CoreError::Crypto("keySlot wrap encrypt failed".into()))?;

        Ok(VmkKeySlot {
            slot_id: slot_id.to_string(),
            slot_type: "pbkdf2-sha256".to_string(),
            kdf: VmkKdfParams {
                algorithm: "PBKDF2-SHA256".to_string(),
                iterations: iters,
                salt: URL_SAFE_NO_PAD.encode(salt),
            },
            iv: URL_SAFE_NO_PAD.encode(iv),
            aad: KEYSLOT_AAD.to_string(),
            wrapped_vmk: URL_SAFE_NO_PAD.encode(wrapped_vmk),
        })
    }

    pub fn unwrap_vmk_from_key_slots(
        key_slots: &[VmkKeySlot],
        passphrase: &str,
    ) -> CoreResult<Zeroizing<[u8; 32]>> {
        if key_slots.is_empty() {
            return Err(CoreError::Crypto("no keySlots present on v2 document".into()));
        }
        for slot in key_slots {
            if slot.slot_type != "pbkdf2-sha256" || slot.kdf.algorithm != "PBKDF2-SHA256" {
                continue;
            }
            if !(100_000..=1_000_000).contains(&slot.kdf.iterations) {
                continue;
            }
            let Ok(salt) = URL_SAFE_NO_PAD.decode(slot.kdf.salt.trim()) else {
                continue;
            };
            let Ok(iv) = URL_SAFE_NO_PAD.decode(slot.iv.trim()) else {
                continue;
            };
            let Ok(ct) = URL_SAFE_NO_PAD.decode(slot.wrapped_vmk.trim()) else {
                continue;
            };
            let Ok(iv_arr): Result<[u8; 12], _> = iv.try_into() else {
                continue;
            };
            if salt.len() < 16 || ct.len() != 48 {
                continue;
            }
            let mut kek = Zeroizing::new([0u8; 32]);
            pbkdf2_hmac::<Sha256>(
                passphrase.as_bytes(),
                &salt,
                slot.kdf.iterations,
                &mut kek[..],
            );
            let Ok(cipher) = Aes256Gcm::new_from_slice(&kek[..]) else {
                continue;
            };
            if let Ok(pt) = cipher.decrypt(
                &Nonce::from(iv_arr),
                Payload {
                    msg: &ct,
                    aad: slot.aad.as_bytes(),
                },
            ) {
                if pt.len() == 32 {
                    let mut vmk = Zeroizing::new([0u8; 32]);
                    vmk.copy_from_slice(&pt);
                    return Ok(vmk);
                }
            }
        }
        Err(CoreError::Crypto(
            "Invalid recovery passphrase for all keySlots".into(),
        ))
    }

    pub fn encrypt_v2_with_vmk(
        payload: &MediaSyncPayload,
        vmk: &[u8],
        key_slots: Vec<VmkKeySlot>,
    ) -> CoreResult<MediaSyncDocumentV2> {
        if vmk.len() != 32 {
            return Err(CoreError::Crypto("VMK must be 32 bytes".into()));
        }
        if key_slots.is_empty() || key_slots.len() > 5 {
            return Err(CoreError::InvalidInput(
                "v2 vault document requires 1..=5 keySlots".into(),
            ));
        }

        let plaintext = serde_json::to_vec(payload)?;
        let mut hkdf_salt = [0u8; 16];
        let mut iv = [0u8; 12];
        OsRng.fill_bytes(&mut hkdf_salt);
        OsRng.fill_bytes(&mut iv);

        let hk = Hkdf::<Sha256>::new(Some(&hkdf_salt), vmk);
        let mut k_enc = Zeroizing::new([0u8; 32]);
        hk.expand(MEDIA_SYNC_V2_INFO, &mut k_enc[..])
            .map_err(|_| CoreError::Crypto("HKDF expand failed for media-sync:v2".into()))?;

        let cipher = Aes256Gcm::new_from_slice(&k_enc[..])
            .map_err(|e| CoreError::Crypto(format!("AES-256-GCM init: {e}")))?;
        let ct = cipher
            .encrypt(
                &Nonce::from(iv),
                Payload {
                    msg: &plaintext,
                    aad: MEDIA_SYNC_V2_AAD.as_bytes(),
                },
            )
            .map_err(|_| CoreError::Crypto("v2 envelope AES-256-GCM encrypt failed".into()))?;

        Ok(MediaSyncDocumentV2 {
            schema_version: 2,
            mode: "e2ee-aes-256-gcm".to_string(),
            key_slots,
            envelope: MediaSyncEnvelopeV2 {
                hkdf_salt: URL_SAFE_NO_PAD.encode(hkdf_salt),
                cipher: "AES-256-GCM".to_string(),
                iv: URL_SAFE_NO_PAD.encode(iv),
                aad: MEDIA_SYNC_V2_AAD.to_string(),
                ciphertext: URL_SAFE_NO_PAD.encode(ct),
            },
        })
    }

    pub fn decrypt_v2_with_vmk(
        doc: &MediaSyncDocumentV2,
        vmk: &[u8],
    ) -> CoreResult<MediaSyncPayload> {
        if vmk.len() != 32 {
            return Err(CoreError::Crypto("VMK must be 32 bytes".into()));
        }
        if doc.schema_version != 2 || doc.mode != "e2ee-aes-256-gcm" {
            return Err(CoreError::Protocol("not a schemaVersion 2 E2EE document".into()));
        }
        let hkdf_salt = URL_SAFE_NO_PAD
            .decode(doc.envelope.hkdf_salt.trim())
            .map_err(|e| CoreError::Crypto(format!("invalid hkdfSalt: {e}")))?;
        let iv = URL_SAFE_NO_PAD
            .decode(doc.envelope.iv.trim())
            .map_err(|e| CoreError::Crypto(format!("invalid envelope iv: {e}")))?;
        let iv_arr: [u8; 12] = iv
            .try_into()
            .map_err(|_| CoreError::Crypto("envelope iv must be 12 bytes".into()))?;
        let ct = URL_SAFE_NO_PAD
            .decode(doc.envelope.ciphertext.trim())
            .map_err(|e| CoreError::Crypto(format!("invalid envelope ciphertext: {e}")))?;
        if hkdf_salt.len() < 16 || ct.len() <= 16 {
            return Err(CoreError::Crypto("malformed v2 envelope lengths".into()));
        }

        let hk = Hkdf::<Sha256>::new(Some(&hkdf_salt), vmk);
        let mut k_enc = Zeroizing::new([0u8; 32]);
        hk.expand(MEDIA_SYNC_V2_INFO, &mut k_enc[..])
            .map_err(|_| CoreError::Crypto("HKDF expand failed".into()))?;

        let cipher = Aes256Gcm::new_from_slice(&k_enc[..])
            .map_err(|e| CoreError::Crypto(format!("AES-256-GCM init: {e}")))?;
        let pt = Zeroizing::new(
            cipher
                .decrypt(
                    &Nonce::from(iv_arr),
                    Payload {
                        msg: &ct,
                        aad: doc.envelope.aad.as_bytes(),
                    },
                )
                .map_err(|_| CoreError::Crypto("v2 envelope GCM authentication failed".into()))?,
        );
        Ok(serde_json::from_slice(&pt)?)
    }

    /// Decrypts any Multipass vault document (`v2` with VMK or passphrase, or legacy `v1`).
    pub fn decrypt_any_document(
        doc_val: &Value,
        vmk: Option<&[u8]>,
        passphrase: Option<&str>,
    ) -> CoreResult<(MediaSyncPayload, Option<Zeroizing<[u8; 32]>>, Vec<VmkKeySlot>)> {
        let schema_version = doc_val
            .get("schemaVersion")
            .and_then(|v| v.as_u64())
            .unwrap_or(1);

        if schema_version == 2 {
            let doc_v2: MediaSyncDocumentV2 = serde_json::from_value(doc_val.clone())?;
            if let Some(existing_vmk) = vmk {
                if let Ok(payload) = Self::decrypt_v2_with_vmk(&doc_v2, existing_vmk) {
                    let mut vmk_arr = Zeroizing::new([0u8; 32]);
                    vmk_arr.copy_from_slice(existing_vmk);
                    return Ok((payload, Some(vmk_arr), doc_v2.key_slots));
                }
            }
            if let Some(pass) = passphrase.filter(|p| !p.is_empty()) {
                let unwrapped = Self::unwrap_vmk_from_key_slots(&doc_v2.key_slots, pass)?;
                let payload = Self::decrypt_v2_with_vmk(&doc_v2, &unwrapped[..])?;
                return Ok((payload, Some(unwrapped), doc_v2.key_slots));
            }
            return Err(CoreError::Auth(
                "VAULT_LOCKED: Multipass v2 vault requires paired VMK or recovery passphrase"
                    .into(),
            ));
        }

        // Legacy schemaVersion == 1 backward compatibility
        let mode = doc_val
            .get("mode")
            .and_then(|v| v.as_str())
            .unwrap_or("server-at-rest");
        if mode == "server-at-rest" {
            let payload_val = doc_val
                .get("payload")
                .cloned()
                .unwrap_or_else(|| json!({}));
            let payload: MediaSyncPayload = serde_json::from_value(payload_val)?;
            return Ok((payload, None, Vec::new()));
        }

        if mode == "e2ee-aes-256-gcm" {
            let pass = passphrase.filter(|p| !p.is_empty()).ok_or_else(|| {
                CoreError::Auth("VAULT_LOCKED: Legacy v1 E2EE vault requires passphrase".into())
            })?;
            let kdf = doc_val
                .get("kdf")
                .ok_or_else(|| CoreError::Protocol("v1 E2EE missing kdf".into()))?;
            let iterations = kdf
                .get("iterations")
                .and_then(|v| v.as_u64())
                .unwrap_or(DEFAULT_PBKDF2_ITERATIONS as u64) as u32;
            let salt = URL_SAFE_NO_PAD
                .decode(kdf.get("salt").and_then(|v| v.as_str()).unwrap_or(""))
                .map_err(|e| CoreError::Crypto(format!("v1 salt decode: {e}")))?;
            let iv = URL_SAFE_NO_PAD
                .decode(doc_val.get("iv").and_then(|v| v.as_str()).unwrap_or(""))
                .map_err(|e| CoreError::Crypto(format!("v1 iv decode: {e}")))?;
            let iv_arr: [u8; 12] = iv
                .try_into()
                .map_err(|_| CoreError::Crypto("v1 iv must be 12 bytes".into()))?;
            let ct = URL_SAFE_NO_PAD
                .decode(
                    doc_val
                        .get("ciphertext")
                        .and_then(|v| v.as_str())
                        .unwrap_or(""),
                )
                .map_err(|e| CoreError::Crypto(format!("v1 ciphertext decode: {e}")))?;
            let aad = doc_val
                .get("aad")
                .and_then(|v| v.as_str())
                .unwrap_or(MEDIA_SYNC_V1_AAD);

            let mut key = Zeroizing::new([0u8; 32]);
            pbkdf2_hmac::<Sha256>(pass.as_bytes(), &salt, iterations, &mut key[..]);
            let cipher = Aes256Gcm::new_from_slice(&key[..])
                .map_err(|e| CoreError::Crypto(format!("AES-256-GCM init: {e}")))?;
            let pt = Zeroizing::new(
                cipher
                    .decrypt(
                        &Nonce::from(iv_arr),
                        Payload {
                            msg: &ct,
                            aad: aad.as_bytes(),
                        },
                    )
                    .map_err(|_| CoreError::Crypto("v1 E2EE passphrase decrypt failed".into()))?,
            );
            let payload: MediaSyncPayload = serde_json::from_slice(&pt)?;
            return Ok((payload, None, Vec::new()));
        }

        Err(CoreError::Protocol(format!("unknown vault mode: {mode}")))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Deterministic CRDT / Last-Write-Wins Payload Merge
// ─────────────────────────────────────────────────────────────────────────────

pub fn merge_media_sync_payloads(
    remote: &MediaSyncPayload,
    local: &MediaSyncPayload,
) -> MediaSyncPayload {
    // 1. Stremio Auth: LWW considering revokedAtMs tombstones
    let stremio_auth = match (&remote.stremio_auth, &local.stremio_auth) {
        (Some(r), Some(l)) => {
            let r_ts = r.updated_at_ms.max(r.revoked_at_ms.unwrap_or(0));
            let l_ts = l.updated_at_ms.max(l.revoked_at_ms.unwrap_or(0));
            if l_ts >= r_ts {
                Some(l.clone())
            } else {
                Some(r.clone())
            }
        }
        (Some(r), None) => Some(r.clone()),
        (None, Some(l)) => Some(l.clone()),
        (None, None) => None,
    };

    // 2. Addons: deterministic union keyed by normalized transportUrl
    let mut addons_map: HashMap<String, SyncedAddonRef> = HashMap::new();
    for a in remote.addons.iter().chain(local.addons.iter()) {
        let key = a.transport_url.trim().trim_end_matches('/').to_ascii_lowercase();
        if key.is_empty() {
            continue;
        }
        match addons_map.get(&key) {
            Some(prev) if prev.added_at_ms > a.added_at_ms => {}
            _ => {
                addons_map.insert(key, a.clone());
            }
        }
    }
    let mut addons: Vec<SyncedAddonRef> = addons_map.into_values().collect();
    addons.sort_by(|a, b| a.transport_url.cmp(&b.transport_url));

    // 3. Watch Progress: LWW by itemId
    let mut progress_map: HashMap<String, WatchHistoryEntry> = HashMap::new();
    for w in remote.watch_progress.iter().chain(local.watch_progress.iter()) {
        if w.item_id.is_empty() {
            continue;
        }
        match progress_map.get(&w.item_id) {
            Some(prev) if prev.updated_at_ms > w.updated_at_ms => {}
            _ => {
                progress_map.insert(w.item_id.clone(), w.clone());
            }
        }
    }
    let mut watch_progress: Vec<WatchHistoryEntry> = progress_map.into_values().collect();
    watch_progress.sort_by(|a, b| b.updated_at_ms.cmp(&a.updated_at_ms));
    watch_progress.truncate(60);

    // 4. Favorites: union by itemId keeping newest addedAtMs
    let mut fav_map: HashMap<String, FavoriteEntry> = HashMap::new();
    for f in remote.favorites.iter().chain(local.favorites.iter()) {
        if f.item_id.is_empty() {
            continue;
        }
        match fav_map.get(&f.item_id) {
            Some(prev) if prev.added_at_ms > f.added_at_ms => {}
            _ => {
                fav_map.insert(f.item_id.clone(), f.clone());
            }
        }
    }
    let mut favorites: Vec<FavoriteEntry> = fav_map.into_values().collect();
    favorites.sort_by(|a, b| b.added_at_ms.cmp(&a.added_at_ms));

    // 5. Jellyfin Servers: LWW + removedAtMs tombstones + union of per-device `deviceTokens`
    let mut jf_map: HashMap<String, SyncedJellyfinServer> = HashMap::new();
    for s in remote
        .jellyfin_servers
        .iter()
        .chain(local.jellyfin_servers.iter())
    {
        if s.server_id.is_empty() {
            continue;
        }
        if let Some(prev) = jf_map.get(&s.server_id).cloned() {
            let prev_ts = prev.updated_at_ms.max(prev.removed_at_ms.unwrap_or(0));
            let cur_ts = s.updated_at_ms.max(s.removed_at_ms.unwrap_or(0));
            let mut merged = if cur_ts >= prev_ts {
                s.clone()
            } else {
                prev.clone()
            };
            let mut combined_tokens = prev.device_tokens;
            for (dev_id, tok) in &s.device_tokens {
                if !tok.is_empty() {
                    combined_tokens.insert(dev_id.clone(), tok.clone());
                }
            }
            merged.device_tokens = combined_tokens;
            if merged.bootstrap_token.is_empty() {
                merged.bootstrap_token = if !s.bootstrap_token.is_empty() {
                    s.bootstrap_token.clone()
                } else {
                    prev.bootstrap_token
                };
            }
            jf_map.insert(s.server_id.clone(), merged);
        } else {
            jf_map.insert(s.server_id.clone(), s.clone());
        }
    }
    let mut jellyfin_servers: Vec<SyncedJellyfinServer> = jf_map.into_values().collect();
    jellyfin_servers.sort_by(|a, b| a.server_id.cmp(&b.server_id));

    // 6. YouTube Accounts: LWW + removedAtMs tombstones
    let mut yt_map: HashMap<String, SyncedYouTubeAccount> = HashMap::new();
    for a in remote
        .youtube_accounts
        .iter()
        .chain(local.youtube_accounts.iter())
    {
        if a.account_id.is_empty() {
            continue;
        }
        match yt_map.get(&a.account_id) {
            Some(prev)
                if prev.updated_at_ms.max(prev.removed_at_ms.unwrap_or(0))
                    > a.updated_at_ms.max(a.removed_at_ms.unwrap_or(0)) => {}
            _ => {
                yt_map.insert(a.account_id.clone(), a.clone());
            }
        }
    }
    let mut youtube_accounts: Vec<SyncedYouTubeAccount> = yt_map.into_values().collect();
    youtube_accounts.sort_by(|a, b| a.account_id.cmp(&b.account_id));

    // 7. Twitch per-device map (protects single-use Twitch refresh tokens per installationId!)
    let mut twitch_by_device = remote.twitch_by_device.clone();
    for (dev_id, prof) in &local.twitch_by_device {
        match twitch_by_device.get(dev_id) {
            Some(prev) if prev.updated_at_ms > prof.updated_at_ms => {}
            _ => {
                twitch_by_device.insert(dev_id.clone(), prof.clone());
            }
        }
    }

    // 8. Creator Follows: LWW + removedAtMs tombstones keyed by `(platform, channelId)`
    let mut follow_map: HashMap<(String, String), CreatorFollowEntry> = HashMap::new();
    for f in remote
        .creator_follows
        .iter()
        .chain(local.creator_follows.iter())
    {
        let key = (f.platform.clone(), f.channel_id.clone());
        let f_ts = f.added_at_ms.max(f.removed_at_ms.unwrap_or(0));
        match follow_map.get(&key) {
            Some(prev) if prev.added_at_ms.max(prev.removed_at_ms.unwrap_or(0)) > f_ts => {}
            _ => {
                follow_map.insert(key, f.clone());
            }
        }
    }
    let mut creator_follows: Vec<CreatorFollowEntry> = follow_map.into_values().collect();
    creator_follows.sort_by(|a, b| {
        (a.platform.as_str(), a.channel_id.as_str())
            .cmp(&(b.platform.as_str(), b.channel_id.as_str()))
    });

    // 9. Streaming Preferences: LWW
    let streaming_preferences = match (&remote.streaming_preferences, &local.streaming_preferences)
    {
        (Some(r), Some(l)) => {
            if l.updated_at_ms >= r.updated_at_ms {
                Some(l.clone())
            } else {
                Some(r.clone())
            }
        }
        (Some(r), None) => Some(r.clone()),
        (None, Some(l)) => Some(l.clone()),
        (None, None) => None,
    };

    MediaSyncPayload {
        version: 2,
        exported_at: now_epoch_ms(),
        source: local.source.clone(),
        stremio_auth,
        addons,
        watch_progress,
        favorites,
        jellyfin_servers,
        youtube_accounts,
        twitch_by_device,
        creator_follows,
        streaming_preferences,
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Multipass Network & Sync Client
// ─────────────────────────────────────────────────────────────────────────────

pub struct MultipassClient {
    http: reqwest::Client,
    config: Arc<ConfigStore>,
    pending_keypairs: Mutex<HashMap<String, (EphemeralP256KeyPair, String)>>,
}

impl MultipassClient {
    pub fn new(http: reqwest::Client, config: Arc<ConfigStore>) -> Self {
        Self {
            http,
            config,
            pending_keypairs: Mutex::new(HashMap::new()),
        }
    }

    pub fn normalize_origin(raw_url: &str) -> CoreResult<String> {
        let trimmed = raw_url.trim().trim_end_matches('/');
        if trimmed.is_empty() {
            return Err(CoreError::InvalidInput("Multipass endpoint URL is empty".into()));
        }
        let with_scheme = if trimmed.starts_with("http://") || trimmed.starts_with("https://") {
            trimmed.to_string()
        } else {
            format!("https://{trimmed}")
        };
        let parsed = url::Url::parse(&with_scheme)?;
        let host = parsed
            .host_str()
            .ok_or_else(|| CoreError::InvalidInput("Multipass URL missing host".into()))?;
        let mut origin = format!("{}://{}", parsed.scheme(), host);
        if let Some(port) = parsed.port() {
            origin.push_str(&format!(":{port}"));
        }
        Ok(origin)
    }

    /// Starts RFC-8628-style QR pairing (`POST /v1/media-sync/pair/start`) and embeds a fresh
    /// ephemeral NIST P-256 uncompressed public key in `#pk=<b64url>` so the server NEVER sees it!
    pub async fn start_device_pairing(
        &self,
        endpoint_url: &str,
        device_name: &str,
    ) -> CoreResult<MultipassPairingSession> {
        let origin = Self::normalize_origin(endpoint_url)?;
        let url = format!("{origin}/v1/media-sync/pair/start");
        let safe_name = if device_name.trim().is_empty() {
            "BrootLite Desktop"
        } else {
            device_name.trim()
        };

        // Strict body: ONLY deviceName + requestedScope
        let body = json!({
            "deviceName": safe_name,
            "requestedScope": "media_sync:readwrite"
        });

        let resp = self.http.post(&url).json(&body).send().await?;
        if !resp.status().is_success() {
            let status = resp.status();
            let err_txt = resp.text().await.unwrap_or_default();
            return Err(CoreError::Protocol(format!(
                "Multipass pair/start failed ({status}): {err_txt}"
            )));
        }

        let data: Value = resp.json().await?;
        let pairing_id = data
            .get("pairingId")
            .and_then(|v| v.as_str())
            .ok_or_else(|| CoreError::Protocol("missing pairingId".into()))?
            .to_string();
        let poll_secret = data
            .get("pollSecret")
            .and_then(|v| v.as_str())
            .ok_or_else(|| CoreError::Protocol("missing pollSecret".into()))?
            .to_string();
        let user_code = data
            .get("userCode")
            .and_then(|v| v.as_str())
            .ok_or_else(|| CoreError::Protocol("missing userCode".into()))?
            .to_string();
        let raw_verify = data
            .get("verificationUrl")
            .and_then(|v| v.as_str())
            .unwrap_or("/networks#media-sync");
        let expires_in_sec = data
            .get("expiresIn")
            .and_then(|v| v.as_u64())
            .unwrap_or(600);
        let interval_sec = data
            .get("intervalSeconds")
            .and_then(|v| v.as_u64())
            .unwrap_or(3);

        let abs_verify = if raw_verify.starts_with("http://") || raw_verify.starts_with("https://")
        {
            raw_verify.to_string()
        } else if raw_verify.starts_with('/') {
            format!("{origin}{raw_verify}")
        } else {
            format!("{origin}/{raw_verify}")
        };
        let base_no_frag = abs_verify.split('#').next().unwrap_or(&abs_verify);

        let keypair = MultipassCrypto::generate_ephemeral_p256_keypair();
        let pub_b64 = keypair.public_key_b64_url.clone();
        let qr_verification_url = format!("{base_no_frag}#pk={pub_b64}");

        self.pending_keypairs
            .lock()
            .unwrap()
            .insert(pairing_id.clone(), (keypair, user_code.clone()));

        Ok(MultipassPairingSession {
            origin_url: origin,
            pairing_id,
            poll_secret,
            user_code,
            verification_url: abs_verify,
            qr_verification_url,
            tv_public_key_b64_url: pub_b64,
            expires_in_sec,
            interval_sec,
        })
    }

    /// Polls `/v1/media-sync/pair/poll`, unwraps `wrappedVaultKey` via P-256 ECDH + HKDF-SHA256 +
    /// AES-256-GCM, seals the 32-byte VMK & `mpd_...` token into [`ConfigStore`], and erases
    /// the ephemeral private key immediately!
    pub async fn poll_device_pairing(
        &self,
        origin_url: &str,
        pairing_id: &str,
        poll_secret: &str,
        user_code_fallback: &str,
    ) -> CoreResult<MultipassPairingPollOutcome> {
        let origin = Self::normalize_origin(origin_url)?;
        let url = format!("{origin}/v1/media-sync/pair/poll");
        let body = json!({
            "pairingId": pairing_id,
            "pollSecret": poll_secret
        });

        let resp = self.http.post(&url).json(&body).send().await?;
        if resp.status().as_u16() == 404 || resp.status().as_u16() == 410 {
            self.pending_keypairs.lock().unwrap().remove(pairing_id);
            return Ok(MultipassPairingPollOutcome::Expired);
        }
        if !resp.status().is_success() {
            return Err(CoreError::Protocol(format!(
                "Multipass pair/poll HTTP {}",
                resp.status()
            )));
        }

        let data: Value = resp.json().await?;
        let status = data
            .get("status")
            .and_then(|v| v.as_str())
            .unwrap_or("pending");
        match status {
            "pending" => Ok(MultipassPairingPollOutcome::Pending),
            "denied" => {
                self.pending_keypairs.lock().unwrap().remove(pairing_id);
                Ok(MultipassPairingPollOutcome::Denied)
            }
            "expired" => {
                self.pending_keypairs.lock().unwrap().remove(pairing_id);
                Ok(MultipassPairingPollOutcome::Expired)
            }
            "approved" => {
                let device_token = data
                    .get("deviceToken")
                    .and_then(|v| v.as_str())
                    .ok_or_else(|| CoreError::Protocol("approved poll missing deviceToken".into()))?
                    .to_string();
                let device_id = data
                    .get("deviceId")
                    .and_then(|v| v.as_str())
                    .unwrap_or_default()
                    .to_string();
                let device_name = data
                    .get("deviceName")
                    .and_then(|v| v.as_str())
                    .unwrap_or("BrootLite Desktop")
                    .to_string();
                let scope = data
                    .get("scope")
                    .and_then(|v| v.as_str())
                    .unwrap_or("media_sync:readwrite")
                    .to_string();

                let mut e2ee_unlocked = false;
                let maybe_kp = self.pending_keypairs.lock().unwrap().remove(pairing_id);
                if let (Some((kp, stored_code)), Some(wrapped_val)) =
                    (maybe_kp, data.get("wrappedVaultKey"))
                {
                    if !wrapped_val.is_null() {
                        let wrapped: WrappedVaultKey = serde_json::from_value(wrapped_val.clone())?;
                        let code = if !stored_code.is_empty() {
                            &stored_code
                        } else {
                            user_code_fallback
                        };
                        let vmk =
                            MultipassCrypto::unwrap_paired_vault_master_key(&kp, &wrapped, code)?;
                        self.config.seal_multipass_vmk(&vmk[..])?;
                        e2ee_unlocked = true;
                    }
                }

                self.config.save_multipass_profile(MultipassProfile {
                    endpoint_url: origin,
                    pair_token: device_token,
                    vault_unlocked: e2ee_unlocked || self.config.unseal_multipass_vmk().is_some(),
                    device_id: device_id.clone(),
                    device_name: device_name.clone(),
                    handle: String::new(),
                    last_synced_ms: now_epoch_ms(),
                    last_synced_revision: 0,
                })?;

                Ok(MultipassPairingPollOutcome::Approved {
                    device_id,
                    device_name,
                    scope,
                    e2ee_unlocked,
                })
            }
            other => Err(CoreError::Protocol(format!(
                "unexpected pairing status: {other}"
            ))),
        }
    }

    pub fn export_local_vault_snapshot(&self) -> MediaSyncPayload {
        let installation_id = self.config.installation_id();
        let (stremio_key, stremio_email, stremio_ts, stremio_revoked) = self.config.stremio_auth();
        let stremio_auth = if stremio_key.is_some() || stremio_revoked.is_some() {
            Some(SyncedStremioAuth {
                auth_key: stremio_key.unwrap_or_default(),
                email: stremio_email,
                updated_at_ms: stremio_ts,
                revoked_at_ms: stremio_revoked,
            })
        } else {
            None
        };

        let addons: Vec<SyncedAddonRef> = self
            .config
            .addons()
            .into_iter()
            .map(|a| SyncedAddonRef {
                transport_url: a.transport_url,
                name: a.name,
                id: a.id,
                added_at_ms: 1,
            })
            .collect();

        let mut jellyfin_servers: Vec<SyncedJellyfinServer> = self
            .config
            .jellyfin_servers()
            .into_iter()
            .map(|s| {
                let mut device_tokens = HashMap::new();
                if !s.access_token.is_empty() {
                    device_tokens.insert(installation_id.clone(), s.access_token.clone());
                }
                SyncedJellyfinServer {
                    server_id: s.server_id,
                    server_name: s.server_name,
                    lan_url: s.lan_url,
                    remote_url: s.remote_url,
                    user_id: s.user_id,
                    username: s.username,
                    access_token: s.access_token,
                    bootstrap_token: s.bootstrap_token,
                    device_tokens,
                    updated_at_ms: s.updated_at_ms,
                    removed_at_ms: None,
                }
            })
            .collect();
        for (removed_id, removed_ts) in self.config.jellyfin_tombstones() {
            jellyfin_servers.push(SyncedJellyfinServer {
                server_id: removed_id,
                updated_at_ms: removed_ts,
                removed_at_ms: Some(removed_ts),
                ..Default::default()
            });
        }

        let mut youtube_accounts: Vec<SyncedYouTubeAccount> = self
            .config
            .youtube_accounts()
            .into_iter()
            .map(|a| SyncedYouTubeAccount {
                account_id: a.account_id,
                account_name: a.account_name,
                channel_handle: a.channel_handle,
                avatar_url: a.avatar_url,
                page_id: a.page_id,
                refresh_token: a.refresh_token,
                oauth_client_id: a.oauth_client_id,
                oauth_client_secret: a.oauth_client_secret,
                updated_at_ms: a.updated_at_ms,
                removed_at_ms: None,
            })
            .collect();
        for (removed_id, removed_ts) in self.config.youtube_tombstones() {
            youtube_accounts.push(SyncedYouTubeAccount {
                account_id: removed_id,
                updated_at_ms: removed_ts,
                removed_at_ms: Some(removed_ts),
                ..Default::default()
            });
        }

        let mut twitch_by_device = HashMap::new();
        if let Some(tw) = self.config.twitch_profile() {
            twitch_by_device.insert(installation_id.clone(), tw);
        }

        let (torznab_url, torznab_api_key) = self.config.torznab_config();

        MediaSyncPayload {
            version: 2,
            exported_at: now_epoch_ms(),
            source: format!("brootlite-core:{installation_id}"),
            stremio_auth,
            addons,
            watch_progress: self.config.watch_history(),
            favorites: self.config.favorites(),
            jellyfin_servers,
            youtube_accounts,
            twitch_by_device,
            creator_follows: self.config.creator_follows(),
            streaming_preferences: Some(SyncedStreamingPrefs {
                swarm_filter: self.config.swarm_filter(),
                torrent_config: self.config.torrent_config(),
                torznab_url,
                torznab_api_key,
                updated_at_ms: self.config.streaming_prefs_updated_at_ms(),
            }),
        }
    }

    /// Pulls and merges the zero-knowledge Multipass vault (`GET /v1/media-sync`).
    pub async fn sync_from_endpoint(
        &self,
        passphrase: Option<&str>,
        stremio_client: &StremioClient,
        jellyfin_client: &JellyfinClient,
    ) -> CoreResult<MultipassSyncReport> {
        let mut profile = self.config.multipass_profile();
        if !profile.is_paired() {
            return Err(CoreError::Auth("Multipass is not paired yet".into()));
        }
        let origin = Self::normalize_origin(&profile.endpoint_url)?;
        let url = format!("{origin}/v1/media-sync");

        let resp = self
            .http
            .get(&url)
            .bearer_auth(&profile.pair_token)
            .send()
            .await?;
        if resp.status().as_u16() == 401 || resp.status().as_u16() == 403 {
            return Err(CoreError::Auth(
                "Multipass device visa revoked or unauthorized".into(),
            ));
        }
        if !resp.status().is_success() {
            return Err(CoreError::Protocol(format!(
                "GET /v1/media-sync failed: {}",
                resp.status()
            )));
        }

        let root: Value = resp.json().await?;
        let exists = root.get("exists").and_then(|v| v.as_bool()).unwrap_or(false);
        let revision = root.get("revision").and_then(|v| v.as_u64()).unwrap_or(0);
        if let Some(h) = root.get("handle").and_then(|v| v.as_str()) {
            profile.handle = h.to_string();
        }
        if !exists || root.get("document").map(|d| d.is_null()).unwrap_or(true) {
            profile.last_synced_ms = now_epoch_ms();
            profile.last_synced_revision = revision;
            self.config.save_multipass_profile(profile)?;
            return Ok(MultipassSyncReport {
                revision,
                ..Default::default()
            });
        }

        let doc_val = root.get("document").unwrap();
        let existing_vmk = self.config.unseal_multipass_vmk();
        let (remote_payload, maybe_new_vmk, _) = match MultipassCrypto::decrypt_any_document(
            doc_val,
            existing_vmk.as_ref().map(|z| &z[..]),
            passphrase,
        ) {
            Ok(tuple) => tuple,
            Err(CoreError::Auth(_)) => {
                return Ok(MultipassSyncReport {
                    revision,
                    vault_locked_needs_passphrase: true,
                    ..Default::default()
                });
            }
            Err(e) => return Err(e),
        };

        if let Some(vmk) = maybe_new_vmk {
            self.config.seal_multipass_vmk(&vmk[..])?;
        }

        let local_payload = self.export_local_vault_snapshot();
        let merged = merge_media_sync_payloads(&remote_payload, &local_payload);
        let report = self
            .apply_merged_payload(&merged, revision, stremio_client, jellyfin_client)
            .await?;

        profile.vault_unlocked = self.config.unseal_multipass_vmk().is_some();
        profile.last_synced_ms = now_epoch_ms();
        profile.last_synced_revision = revision;
        self.config.save_multipass_profile(profile)?;
        Ok(report)
    }

    async fn apply_merged_payload(
        &self,
        merged: &MediaSyncPayload,
        revision: u64,
        stremio_client: &StremioClient,
        jellyfin_client: &JellyfinClient,
    ) -> CoreResult<MultipassSyncReport> {
        let mut report = MultipassSyncReport {
            revision,
            ..Default::default()
        };
        let installation_id = self.config.installation_id();

        // 1. Stremio Auth
        if let Some(sa) = &merged.stremio_auth {
            let revoked = sa.revoked_at_ms.unwrap_or(0) > sa.updated_at_ms;
            if revoked {
                self.config
                    .set_stremio_auth(None, "", sa.revoked_at_ms)?;
            } else if !sa.auth_key.is_empty() {
                self.config.set_stremio_auth(
                    Some(&sa.auth_key),
                    &sa.email,
                    Some(sa.updated_at_ms),
                )?;
                report.stremio_linked = true;
            }
        }

        // 2. Addons (install any transport_url not yet installed locally)
        let existing_urls: HashSet<String> = self
            .config
            .addons()
            .into_iter()
            .map(|a| a.transport_url.trim_end_matches('/').to_ascii_lowercase())
            .collect();
        for addon_ref in &merged.addons {
            let norm = addon_ref
                .transport_url
                .trim()
                .trim_end_matches('/')
                .to_ascii_lowercase();
            if !norm.is_empty() && !existing_urls.contains(&norm) {
                if stremio_client
                    .install_addon_from_url(&addon_ref.transport_url)
                    .await
                    .is_ok()
                {
                    report.addons_imported += 1;
                }
            }
        }

        // 3. Watch History & Favorites
        self.config
            .replace_watch_history(merged.watch_progress.clone())?;
        report.watch_history_merged = merged.watch_progress.len();
        self.config.replace_favorites(merged.favorites.clone())?;
        report.favorites_merged = merged.favorites.len();

        // 4. Jellyfin Servers (with zero-password per-device bootstrapToken minting!)
        for srv in &merged.jellyfin_servers {
            if srv.removed_at_ms.unwrap_or(0) > srv.updated_at_ms {
                let _ = self.config.remove_jellyfin_server(&srv.server_id);
                continue;
            }
            let mut my_token = srv
                .device_tokens
                .get(&installation_id)
                .cloned()
                .unwrap_or_default();
            if my_token.is_empty() && !srv.bootstrap_token.is_empty() {
                let base = if !srv.lan_url.is_empty() {
                    &srv.lan_url
                } else {
                    &srv.remote_url
                };
                if let Ok(minted) = jellyfin_client
                    .mint_device_token_via_bootstrap(base, &srv.bootstrap_token)
                    .await
                {
                    my_token = minted.access_token;
                }
            }
            if my_token.is_empty() {
                my_token = srv.access_token.clone();
            }
            if !my_token.is_empty() {
                self.config.upsert_jellyfin_server(JellyfinServerProfile {
                    server_id: srv.server_id.clone(),
                    server_name: srv.server_name.clone(),
                    lan_url: srv.lan_url.clone(),
                    remote_url: srv.remote_url.clone(),
                    user_id: srv.user_id.clone(),
                    username: srv.username.clone(),
                    access_token: my_token,
                    bootstrap_token: srv.bootstrap_token.clone(),
                    last_verified_ms: now_epoch_ms(),
                    updated_at_ms: srv.updated_at_ms,
                })?;
                report.jellyfin_servers_synced += 1;
            }
        }

        // 5. YouTube Accounts
        for yt in &merged.youtube_accounts {
            if yt.removed_at_ms.unwrap_or(0) > yt.updated_at_ms {
                let _ = self.config.remove_youtube_account(&yt.account_id);
                continue;
            }
            if !yt.refresh_token.is_empty() {
                self.config.upsert_youtube_account(YouTubeAccountProfile {
                    account_id: yt.account_id.clone(),
                    account_name: yt.account_name.clone(),
                    channel_handle: yt.channel_handle.clone(),
                    avatar_url: yt.avatar_url.clone(),
                    page_id: yt.page_id.clone(),
                    refresh_token: yt.refresh_token.clone(),
                    access_token: String::new(),
                    expires_at_ms: 0,
                    oauth_client_id: yt.oauth_client_id.clone(),
                    oauth_client_secret: yt.oauth_client_secret.clone(),
                    updated_at_ms: yt.updated_at_ms,
                })?;
                report.youtube_accounts_synced += 1;
            }
        }

        // 6. Twitch per-device token (fall back to another device entry only if unauthenticated)
        if let Some(my_tw) = merged.twitch_by_device.get(&installation_id) {
            self.config.set_twitch_profile(Some(my_tw.clone()))?;
            report.twitch_synced = true;
        } else if self.config.twitch_profile().is_none() {
            if let Some(any_tw) = merged.twitch_by_device.values().next() {
                self.config.set_twitch_profile(Some(any_tw.clone()))?;
                report.twitch_synced = true;
            }
        }

        // 7. Creator Follows & Streaming Preferences
        self.config
            .replace_creator_follows(merged.creator_follows.clone())?;
        report.creator_follows_synced = merged.creator_follows.len();

        if let Some(prefs) = &merged.streaming_preferences {
            if prefs.updated_at_ms > self.config.streaming_prefs_updated_at_ms() {
                let _ = self.config.set_swarm_filter(prefs.swarm_filter);
                let _ = self.config.set_torrent_config(prefs.torrent_config.clone());
                let _ = self
                    .config
                    .set_torznab_config(&prefs.torznab_url, &prefs.torznab_api_key);
            }
        }

        Ok(report)
    }

    /// Encrypts and pushes the local merged state to `PUT /v1/media-sync` using optimistic CAS
    /// (`expectedRevision`), automatically retrying up to 3 times on HTTP `409 Conflict`.
    pub async fn push_current_setup_to_vault(
        &self,
        new_vault_passphrase: Option<&str>,
    ) -> CoreResult<u64> {
        let mut profile = self.config.multipass_profile();
        if !profile.is_paired() {
            return Err(CoreError::Auth("Multipass is not paired".into()));
        }
        let origin = Self::normalize_origin(&profile.endpoint_url)?;
        let url = format!("{origin}/v1/media-sync");

        for _attempt in 0..3 {
            let get_resp = self
                .http
                .get(&url)
                .bearer_auth(&profile.pair_token)
                .send()
                .await?;
            if !get_resp.status().is_success() {
                return Err(CoreError::Protocol(format!(
                    "GET /v1/media-sync before push failed: {}",
                    get_resp.status()
                )));
            }
            let root: Value = get_resp.json().await?;
            let exists = root.get("exists").and_then(|v| v.as_bool()).unwrap_or(false);
            let expected_revision = if !exists {
                0
            } else {
                root.get("revision").and_then(|v| v.as_u64()).unwrap_or(0)
            };

            let local_snapshot = self.export_local_vault_snapshot();
            let mut vmk_opt = self.config.unseal_multipass_vmk();
            let mut key_slots: Vec<VmkKeySlot> = Vec::new();

            let merged_payload = if exists
                && !root
                    .get("document")
                    .map(|d| d.is_null())
                    .unwrap_or(true)
            {
                let doc_val = root.get("document").unwrap();
                let (remote_payload, maybe_vmk, existing_slots) =
                    MultipassCrypto::decrypt_any_document(
                        doc_val,
                        vmk_opt.as_ref().map(|z| &z[..]),
                        new_vault_passphrase,
                    )?;
                if let Some(unwrapped) = maybe_vmk {
                    self.config.seal_multipass_vmk(&unwrapped[..])?;
                    vmk_opt = Some(unwrapped);
                }
                key_slots = existing_slots;
                merge_media_sync_payloads(&remote_payload, &local_snapshot)
            } else {
                local_snapshot
            };

            let vmk = match vmk_opt {
                Some(k) => k,
                None => {
                    let fresh = MultipassCrypto::generate_vault_master_key();
                    self.config.seal_multipass_vmk(&fresh[..])?;
                    fresh
                }
            };

            if key_slots.is_empty() {
                let pass = new_vault_passphrase
                    .filter(|p| p.len() >= 8)
                    .ok_or_else(|| {
                        CoreError::InvalidInput(
                            "Initializing a brand-new Multipass v2 vault requires an 8+ char recovery passphrase"
                                .into(),
                        )
                    })?;
                key_slots.push(MultipassCrypto::create_passphrase_key_slot(
                    &vmk[..],
                    pass,
                    "primary-passphrase",
                    DEFAULT_PBKDF2_ITERATIONS,
                )?);
            }

            let doc_v2 = MultipassCrypto::encrypt_v2_with_vmk(&merged_payload, &vmk[..], key_slots)?;
            let put_body = json!({
                "expectedRevision": expected_revision,
                "document": doc_v2
            });

            let put_resp = self
                .http
                .put(&url)
                .bearer_auth(&profile.pair_token)
                .json(&put_body)
                .send()
                .await?;

            if put_resp.status().as_u16() == 409 {
                continue;
            }
            if !put_resp.status().is_success() {
                let status = put_resp.status();
                let txt = put_resp.text().await.unwrap_or_default();
                return Err(CoreError::Protocol(format!(
                    "PUT /v1/media-sync failed ({status}): {txt}"
                )));
            }

            let put_json: Value = put_resp.json().await?;
            let new_rev = put_json
                .get("revision")
                .and_then(|v| v.as_u64())
                .unwrap_or(expected_revision + 1);

            profile.vault_unlocked = true;
            profile.last_synced_ms = now_epoch_ms();
            profile.last_synced_revision = new_rev;
            self.config.save_multipass_profile(profile)?;
            return Ok(new_rev);
        }

        Err(CoreError::Protocol(
            "PUT /v1/media-sync exceeded max 409 CAS retries".into(),
        ))
    }
}
