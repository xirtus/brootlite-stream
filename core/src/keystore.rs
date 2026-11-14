use crate::error::{CoreError, CoreResult};
use aes_gcm::{
    aead::{Aead, KeyInit, Payload},
    Aes256Gcm, Nonce,
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use hkdf::Hkdf;
use rand::RngCore;
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::OnceLock;
use zeroize::Zeroizing;

const SEAL_PREFIX: &str = "blseal2:";
const KEYCHAIN_SERVICE: &str = "app.brootlite.desktop";
const KEYCHAIN_ACCOUNT_PREFIX: &str = "master-seal-key-v2";
const MACHINE_WRAP_INFO: &[u8] = b"brootlite:machine-keystore-wrap:v2";
const MACHINE_WRAP_AAD: &[u8] = b"brootlite:keystore-master-file:v2";

pub mod purpose {
    pub const STREMIO_AUTH: &str = "stremio-auth-key";
    pub const MULTIPASS_TOKEN: &str = "multipass-pair-token";
    pub const MULTIPASS_VMK: &str = "multipass-vmk";
    pub const JELLYFIN_TOKEN: &str = "jellyfin-access-token";
    pub const JELLYFIN_BOOTSTRAP: &str = "jellyfin-bootstrap-token";
    pub const YOUTUBE_REFRESH: &str = "youtube-refresh-token";
    pub const YOUTUBE_ACCESS: &str = "youtube-access-token";
    pub const YOUTUBE_CLIENT_SECRET: &str = "youtube-client-secret";
    pub const TWITCH_ACCESS: &str = "twitch-access-token";
    pub const TWITCH_REFRESH: &str = "twitch-refresh-token";
    pub const TORZNAB_API_KEY: &str = "torznab-api-key";
}

/// Cross-platform keystore protecting all OAuth tokens, Multipass `mpd_...` tokens,
/// Jellyfin tokens, and the 32-byte Multipass Vault Master Key (`VMK`).
///
/// Uses OS Keychain (`security` on macOS, `secret-tool` on Linux) for the 32-byte
/// Local Seal Key (`LSK`), falling back to a machine-bound (`HKDF-SHA256` + `AES-256-GCM`)
/// `0600` sealed key file when running headless, on Windows, or in isolated test directories.
pub struct Keystore {
    data_dir: PathBuf,
    use_os_keychain: bool,
    cached_master_key: OnceLock<Zeroizing<[u8; 32]>>,
}

impl Keystore {
    pub fn new(data_dir: impl Into<PathBuf>, use_os_keychain: bool) -> Self {
        let env_disabled = std::env::var("BROOTLITE_DISABLE_OS_KEYCHAIN")
            .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
            .unwrap_or(false);
        Self {
            data_dir: data_dir.into(),
            use_os_keychain: use_os_keychain && !env_disabled && !cfg!(test),
            cached_master_key: OnceLock::new(),
        }
    }

    pub fn is_sealed_value(val: &str) -> bool {
        val.starts_with(SEAL_PREFIX)
    }

    /// Seals arbitrary bytes under the 32-byte Local Seal Key with domain-separated AAD.
    pub fn seal_bytes(&self, domain_purpose: &str, plaintext: &[u8]) -> CoreResult<String> {
        if plaintext.is_empty() {
            return Ok(String::new());
        }
        let master = self.master_key()?;
        let cipher = Aes256Gcm::new_from_slice(&master[..])
            .map_err(|e| CoreError::Crypto(format!("cipher init failed: {e}")))?;
        let mut iv = [0u8; 12];
        rand::thread_rng().fill_bytes(&mut iv);
        let aad = format!("brootlite:{domain_purpose}:v2");
        let ct = cipher
            .encrypt(
                &Nonce::from(iv),
                Payload {
                    msg: plaintext,
                    aad: aad.as_bytes(),
                },
            )
            .map_err(|_| CoreError::Crypto("keystore AES-256-GCM seal failed".into()))?;

        let mut combined = Vec::with_capacity(12 + ct.len());
        combined.extend_from_slice(&iv);
        combined.extend_from_slice(&ct);
        Ok(format!("{SEAL_PREFIX}{}", URL_SAFE_NO_PAD.encode(&combined)))
    }

    /// Unseals bytes previously sealed with the exact same `domain_purpose`.
    pub fn unseal_bytes(
        &self,
        domain_purpose: &str,
        sealed: &str,
    ) -> CoreResult<Zeroizing<Vec<u8>>> {
        if sealed.trim().is_empty() {
            return Ok(Zeroizing::new(Vec::new()));
        }
        let encoded = sealed.strip_prefix(SEAL_PREFIX).ok_or_else(|| {
            CoreError::Crypto("value is missing blseal2: envelope prefix".into())
        })?;
        let raw = URL_SAFE_NO_PAD
            .decode(encoded.trim())
            .map_err(|e| CoreError::Crypto(format!("invalid base64url in sealed secret: {e}")))?;
        if raw.len() <= 12 + 16 {
            return Err(CoreError::Crypto("truncated sealed secret".into()));
        }
        let (iv, ct) = raw.split_at(12);
        let iv_arr: [u8; 12] = iv
            .try_into()
            .map_err(|_| CoreError::Crypto("invalid 12-byte IV".into()))?;
        let master = self.master_key()?;
        let cipher = Aes256Gcm::new_from_slice(&master[..])
            .map_err(|e| CoreError::Crypto(format!("cipher init failed: {e}")))?;
        let aad = format!("brootlite:{domain_purpose}:v2");
        let pt = cipher
            .decrypt(
                &Nonce::from(iv_arr),
                Payload {
                    msg: ct,
                    aad: aad.as_bytes(),
                },
            )
            .map_err(|_| {
                CoreError::Crypto(format!(
                    "keystore unseal authentication failed for {domain_purpose}"
                ))
            })?;
        Ok(Zeroizing::new(pt))
    }

    pub fn seal_string(&self, domain_purpose: &str, plaintext: &str) -> CoreResult<String> {
        if plaintext.is_empty() {
            return Ok(String::new());
        }
        if Self::is_sealed_value(plaintext) {
            return Ok(plaintext.to_string());
        }
        self.seal_bytes(domain_purpose, plaintext.as_bytes())
    }

    pub fn unseal_string(&self, domain_purpose: &str, maybe_sealed: &str) -> CoreResult<String> {
        if maybe_sealed.is_empty() {
            return Ok(String::new());
        }
        if !Self::is_sealed_value(maybe_sealed) {
            return Err(CoreError::Crypto(format!(
                "refusing unsealed plaintext token for {domain_purpose}"
            )));
        }
        let bytes = self.unseal_bytes(domain_purpose, maybe_sealed)?;
        String::from_utf8(bytes.to_vec())
            .map_err(|e| CoreError::Crypto(format!("invalid UTF-8 in unsealed secret: {e}")))
    }

    fn master_key(&self) -> CoreResult<&Zeroizing<[u8; 32]>> {
        if let Some(k) = self.cached_master_key.get() {
            return Ok(k);
        }
        let loaded = self.load_or_create_master_key()?;
        let _ = self.cached_master_key.set(loaded);
        self.cached_master_key
            .get()
            .ok_or_else(|| CoreError::Crypto("failed to initialize master key".into()))
    }

    fn keychain_account_name(&self) -> String {
        let mut hasher = Sha256::new();
        hasher.update(self.data_dir.to_string_lossy().as_bytes());
        let digest = hex::encode(hasher.finalize());
        format!("{KEYCHAIN_ACCOUNT_PREFIX}-{}", &digest[..12])
    }

    fn load_or_create_master_key(&self) -> CoreResult<Zeroizing<[u8; 32]>> {
        std::fs::create_dir_all(&self.data_dir)?;
        let account = self.keychain_account_name();

        if self.use_os_keychain {
            if let Some(k) = Self::try_os_keychain_lookup(&account) {
                return Ok(k);
            }
        }

        let seal_path = self.data_dir.join(".keystore.seal");
        if seal_path.is_file() {
            if let Ok(existing) = self.read_machine_sealed_master_key(&seal_path) {
                if self.use_os_keychain {
                    let _ = Self::try_os_keychain_store(&account, &existing[..]);
                }
                return Ok(existing);
            }
        }

        let mut fresh = Zeroizing::new([0u8; 32]);
        rand::thread_rng().fill_bytes(&mut fresh[..]);

        // Always persist a machine-bound sealed fallback AND store in OS Keychain when enabled.
        self.write_machine_sealed_master_key(&seal_path, &fresh[..])?;
        if self.use_os_keychain {
            let _ = Self::try_os_keychain_store(&account, &fresh[..]);
        }
        Ok(fresh)
    }

    fn derive_machine_wrap_key(&self, salt: &[u8]) -> CoreResult<Zeroizing<[u8; 32]>> {
        let mut ikm = Vec::with_capacity(256);
        ikm.extend_from_slice(b"brootlite-local-machine-v2:");
        ikm.extend_from_slice(self.data_dir.to_string_lossy().as_bytes());

        // Bind to OS user / hostname / machine-id where available (zero network / zero prompts).
        for env_key in ["USER", "USERNAME", "LOGNAME", "COMPUTERNAME", "HOSTNAME"] {
            if let Ok(v) = std::env::var(env_key) {
                if !v.is_empty() {
                    ikm.push(b'|');
                    ikm.extend_from_slice(v.as_bytes());
                }
            }
        }
        for id_file in ["/etc/machine-id", "/var/lib/dbus/machine-id"] {
            if let Ok(contents) = std::fs::read_to_string(id_file) {
                let trimmed = contents.trim();
                if !trimmed.is_empty() {
                    ikm.push(b'|');
                    ikm.extend_from_slice(trimmed.as_bytes());
                    break;
                }
            }
        }

        let hk = Hkdf::<Sha256>::new(Some(salt), &ikm);
        let mut out = Zeroizing::new([0u8; 32]);
        hk.expand(MACHINE_WRAP_INFO, &mut out[..])
            .map_err(|_| CoreError::Crypto("HKDF expand failed for machine wrap key".into()))?;
        Ok(out)
    }

    fn write_machine_sealed_master_key(&self, path: &Path, key: &[u8]) -> CoreResult<()> {
        let mut salt = [0u8; 16];
        let mut iv = [0u8; 12];
        rand::thread_rng().fill_bytes(&mut salt);
        rand::thread_rng().fill_bytes(&mut iv);

        let wrap_key = self.derive_machine_wrap_key(&salt)?;
        let cipher = Aes256Gcm::new_from_slice(&wrap_key[..])
            .map_err(|e| CoreError::Crypto(format!("wrap cipher init: {e}")))?;
        let ct = cipher
            .encrypt(
                &Nonce::from(iv),
                Payload {
                    msg: key,
                    aad: MACHINE_WRAP_AAD,
                },
            )
            .map_err(|_| CoreError::Crypto("machine wrap encrypt failed".into()))?;

        let mut blob = Vec::with_capacity(4 + 16 + 12 + ct.len());
        blob.extend_from_slice(b"BLK2");
        blob.extend_from_slice(&salt);
        blob.extend_from_slice(&iv);
        blob.extend_from_slice(&ct);

        let tmp = path.with_extension("seal.tmp");
        std::fs::write(&tmp, &blob)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o600));
        }
        std::fs::rename(&tmp, path)?;
        Ok(())
    }

    fn read_machine_sealed_master_key(&self, path: &Path) -> CoreResult<Zeroizing<[u8; 32]>> {
        let blob = std::fs::read(path)?;
        if blob.len() < 4 + 16 + 12 + 32 + 16 || &blob[..4] != b"BLK2" {
            return Err(CoreError::Crypto("corrupt .keystore.seal header".into()));
        }
        let salt = &blob[4..20];
        let iv_arr: [u8; 12] = blob[20..32]
            .try_into()
            .map_err(|_| CoreError::Crypto("invalid 12-byte IV".into()))?;
        let ct = &blob[32..];

        let wrap_key = self.derive_machine_wrap_key(salt)?;
        let cipher = Aes256Gcm::new_from_slice(&wrap_key[..])
            .map_err(|e| CoreError::Crypto(format!("wrap cipher init: {e}")))?;
        let pt = Zeroizing::new(
            cipher
                .decrypt(
                    &Nonce::from(iv_arr),
                    Payload {
                        msg: ct,
                        aad: MACHINE_WRAP_AAD,
                    },
                )
                .map_err(|_| CoreError::Crypto("machine wrap decrypt failed".into()))?,
        );
        if pt.len() != 32 {
            return Err(CoreError::Crypto("invalid master key length".into()));
        }
        let mut arr = Zeroizing::new([0u8; 32]);
        arr.copy_from_slice(&pt);
        Ok(arr)
    }

    fn try_os_keychain_lookup(account: &str) -> Option<Zeroizing<[u8; 32]>> {
        if cfg!(target_os = "macos") {
            let output = Command::new("/usr/bin/security")
                .args([
                    "find-generic-password",
                    "-s",
                    KEYCHAIN_SERVICE,
                    "-a",
                    account,
                    "-w",
                ])
                .stdin(Stdio::null())
                .stderr(Stdio::null())
                .output()
                .ok()?;
            if !output.status.success() {
                return None;
            }
            let encoded = String::from_utf8_lossy(&output.stdout).trim().to_string();
            let raw = URL_SAFE_NO_PAD.decode(encoded).ok()?;
            if raw.len() != 32 {
                return None;
            }
            let mut key = Zeroizing::new([0u8; 32]);
            key.copy_from_slice(&raw);
            return Some(key);
        }

        if cfg!(target_os = "linux") {
            let output = Command::new("secret-tool")
                .args([
                    "lookup",
                    "service",
                    KEYCHAIN_SERVICE,
                    "account",
                    account,
                ])
                .stdin(Stdio::null())
                .stderr(Stdio::null())
                .output()
                .ok()?;
            if !output.status.success() {
                return None;
            }
            let encoded = String::from_utf8_lossy(&output.stdout).trim().to_string();
            let raw = URL_SAFE_NO_PAD.decode(encoded).ok()?;
            if raw.len() != 32 {
                return None;
            }
            let mut key = Zeroizing::new([0u8; 32]);
            key.copy_from_slice(&raw);
            return Some(key);
        }

        None
    }

    fn try_os_keychain_store(account: &str, key: &[u8]) -> bool {
        let encoded = Zeroizing::new(URL_SAFE_NO_PAD.encode(key));
        if cfg!(target_os = "macos") {
            return Command::new("/usr/bin/security")
                .args([
                    "add-generic-password",
                    "-U",
                    "-s",
                    KEYCHAIN_SERVICE,
                    "-a",
                    account,
                    "-w",
                    encoded.as_str(),
                ])
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status()
                .map(|s| s.success())
                .unwrap_or(false);
        }

        if cfg!(target_os = "linux") {
            use std::io::Write;
            if let Ok(mut child) = Command::new("secret-tool")
                .args([
                    "store",
                    "--label=BrootLite Master Seal Key",
                    "service",
                    KEYCHAIN_SERVICE,
                    "account",
                    account,
                ])
                .stdin(Stdio::piped())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()
            {
                if let Some(mut stdin) = child.stdin.take() {
                    let _ = stdin.write_all(encoded.as_bytes());
                }
                return child.wait().map(|s| s.success()).unwrap_or(false);
            }
        }

        false
    }
}
