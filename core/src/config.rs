use crate::error::{CoreError, CoreResult};
use crate::keystore::{purpose, Keystore};
use crate::models::{
    now_epoch_ms, CreatorFollowEntry, DiscoverSortMode, FavoriteEntry, InstalledAddon,
    JellyfinServerProfile, MultipassProfile, StreamSelectionContext, SwarmFilter,
    TorrentEngineConfig, TwitchAuthProfile, WatchHistoryEntry, YouTubeAccountProfile,
};
use crate::stremio::default_addons;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::RwLock;
use uuid::Uuid;
use zeroize::Zeroizing;

/// Resolves the canonical OS app data directory:
/// - macOS: `~/Library/Application Support/app.brootlite.desktop/`
/// - Linux: `~/.local/share/brootlite/` (or `$XDG_DATA_HOME/brootlite/`)
/// - Windows: `%APPDATA%\BrootLite\`
pub fn default_app_data_dir() -> PathBuf {
    #[cfg(target_os = "macos")]
    {
        if let Some(home) = dirs::home_dir() {
            return home
                .join("Library")
                .join("Application Support")
                .join("app.brootlite.desktop");
        }
    }
    #[cfg(target_os = "windows")]
    {
        if let Some(roaming) = dirs::data_dir() {
            return roaming.join("BrootLite");
        }
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    {
        if let Some(data) = dirs::data_local_dir() {
            return data.join("brootlite");
        }
    }
    std::env::temp_dir().join("brootlite-core")
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PersistedStateDisk {
    #[serde(default = "default_installation_id")]
    pub installation_id: String,
    #[serde(default)]
    pub stremio_email: String,
    #[serde(default)]
    pub sealed_stremio_auth_key: String,
    #[serde(default)]
    pub stremio_auth_updated_at_ms: u64,
    #[serde(default)]
    pub stremio_auth_revoked_at_ms: Option<u64>,

    #[serde(default)]
    pub addons: Vec<InstalledAddon>,
    #[serde(default)]
    pub watch_history: Vec<WatchHistoryEntry>,
    #[serde(default)]
    pub favorites: Vec<FavoriteEntry>,

    #[serde(default)]
    pub jellyfin_servers: Vec<JellyfinServerProfile>,
    /// Tombstones (`server_id -> removed_at_ms`) for Multipass CRDT sync
    #[serde(default)]
    pub jellyfin_tombstones: HashMap<String, u64>,
    #[serde(default)]
    pub jellyfin_device_id: String,

    #[serde(default)]
    pub youtube_accounts: Vec<YouTubeAccountProfile>,
    #[serde(default)]
    pub active_youtube_account_id: String,
    #[serde(default)]
    pub youtube_tombstones: HashMap<String, u64>,

    #[serde(default)]
    pub twitch_profile: Option<TwitchAuthProfile>,
    #[serde(default)]
    pub creator_follows: Vec<CreatorFollowEntry>,

    #[serde(default)]
    pub multipass: MultipassProfile,
    #[serde(default)]
    pub sealed_multipass_vmk: String,

    #[serde(default)]
    pub swarm_filter: SwarmFilter,
    #[serde(default)]
    pub discover_sort_mode: DiscoverSortMode,
    #[serde(default)]
    pub torrent_config: TorrentEngineConfig,
    #[serde(default)]
    pub torznab_url: String,
    #[serde(default)]
    pub sealed_torznab_api_key: String,
    #[serde(default)]
    pub streaming_prefs_updated_at_ms: u64,
}

fn default_installation_id() -> String {
    format!("bl-{}", Uuid::new_v4())
}

impl Default for PersistedStateDisk {
    fn default() -> Self {
        Self {
            installation_id: default_installation_id(),
            stremio_email: String::new(),
            sealed_stremio_auth_key: String::new(),
            stremio_auth_updated_at_ms: 0,
            stremio_auth_revoked_at_ms: None,
            addons: default_addons(),
            watch_history: Vec::new(),
            favorites: Vec::new(),
            jellyfin_servers: Vec::new(),
            jellyfin_tombstones: HashMap::new(),
            jellyfin_device_id: format!("brootlite-{}", Uuid::new_v4()),
            youtube_accounts: Vec::new(),
            active_youtube_account_id: String::new(),
            youtube_tombstones: HashMap::new(),
            twitch_profile: None,
            creator_follows: Vec::new(),
            multipass: MultipassProfile::default(),
            sealed_multipass_vmk: String::new(),
            swarm_filter: SwarmFilter::SafeOnly,
            discover_sort_mode: DiscoverSortMode::Popular,
            torrent_config: TorrentEngineConfig::default(),
            torznab_url: String::new(),
            sealed_torznab_api_key: String::new(),
            streaming_prefs_updated_at_ms: 0,
        }
    }
}

/// Thread-safe persistent configuration & state store (`state.json`) backed by [`Keystore`].
/// Every token, refresh secret, API key, and 32-byte Multipass VMK is encrypted via `blseal2:`
/// before touching disk.
pub struct ConfigStore {
    data_dir: PathBuf,
    state_file: PathBuf,
    keystore: Keystore,
    state: RwLock<PersistedStateDisk>,
    provider_pack: RwLock<Option<crate::pack::ProviderPack>>,
}

impl ConfigStore {
    pub fn open(explicit_data_dir: Option<PathBuf>) -> CoreResult<Self> {
        let is_default_dir = explicit_data_dir.is_none();
        let data_dir = explicit_data_dir.unwrap_or_else(default_app_data_dir);
        std::fs::create_dir_all(&data_dir)?;
        let state_file = data_dir.join("state.json");
        let keystore = Keystore::new(&data_dir, is_default_dir);

        let mut loaded = if state_file.is_file() {
            let raw = std::fs::read_to_string(&state_file)?;
            serde_json::from_str::<PersistedStateDisk>(&raw).unwrap_or_default()
        } else {
            PersistedStateDisk::default()
        };

        if loaded.addons.is_empty() {
            loaded.addons = default_addons();
        }
        let startup_pack = if is_default_dir || data_dir.join("provider_pack.json").is_file() {
            crate::pack::load_startup_provider_pack(&data_dir)
        } else {
            None
        };
        if let Some(ref pack) = startup_pack {
            crate::pack::apply_pack_stremio_addons(pack, &mut loaded.addons);
        }
        if loaded.installation_id.is_empty() {
            loaded.installation_id = default_installation_id();
        }
        if loaded.jellyfin_device_id.is_empty() {
            loaded.jellyfin_device_id = format!("brootlite-{}", Uuid::new_v4());
        }

        let store = Self {
            data_dir,
            state_file,
            keystore,
            state: RwLock::new(loaded),
            provider_pack: RwLock::new(startup_pack),
        };
        Ok(store)
    }

    pub fn provider_pack(&self) -> Option<crate::pack::ProviderPack> {
        self.provider_pack.read().unwrap().clone()
    }

    pub fn set_provider_pack(&self, pack: Option<crate::pack::ProviderPack>) -> CoreResult<()> {
        *self.provider_pack.write().unwrap() = pack;
        Ok(())
    }

    pub fn data_dir(&self) -> &Path {
        &self.data_dir
    }

    pub fn cache_dir(&self) -> PathBuf {
        let p = self.data_dir.join("cache");
        let _ = std::fs::create_dir_all(&p);
        p
    }

    pub fn kodi_env_dir(&self) -> PathBuf {
        let p = self.data_dir.join("kodi_env");
        let _ = std::fs::create_dir_all(p.join("addons"));
        let _ = std::fs::create_dir_all(p.join("userdata"));
        p
    }

    pub fn keystore(&self) -> &Keystore {
        &self.keystore
    }

    pub fn installation_id(&self) -> String {
        self.state.read().unwrap().installation_id.clone()
    }

    pub fn jellyfin_device_id(&self) -> String {
        self.state.read().unwrap().jellyfin_device_id.clone()
    }

    fn flush_locked(&self, disk: &PersistedStateDisk) -> CoreResult<()> {
        let serialized = serde_json::to_vec_pretty(disk)?;
        let tmp = self.state_file.with_extension("json.tmp");
        std::fs::write(&tmp, serialized)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o600));
        }
        std::fs::rename(&tmp, &self.state_file)?;
        Ok(())
    }

    // ── Stremio Account & Addons ──────────────────────────────────────────────

    pub fn stremio_auth(&self) -> (Option<String>, String, u64, Option<u64>) {
        let guard = self.state.read().unwrap();
        let key = if guard.sealed_stremio_auth_key.is_empty() {
            None
        } else {
            self.keystore
                .unseal_string(purpose::STREMIO_AUTH, &guard.sealed_stremio_auth_key)
                .ok()
                .filter(|s| !s.is_empty())
        };
        (
            key,
            guard.stremio_email.clone(),
            guard.stremio_auth_updated_at_ms,
            guard.stremio_auth_revoked_at_ms,
        )
    }

    pub fn set_stremio_auth(
        &self,
        auth_key: Option<&str>,
        email: &str,
        updated_at_ms: Option<u64>,
    ) -> CoreResult<()> {
        let now = updated_at_ms.unwrap_or_else(now_epoch_ms);
        let mut guard = self.state.write().unwrap();
        match auth_key.map(str::trim).filter(|s| !s.is_empty()) {
            Some(k) => {
                guard.sealed_stremio_auth_key =
                    self.keystore.seal_string(purpose::STREMIO_AUTH, k)?;
                guard.stremio_email = email.trim().to_string();
                guard.stremio_auth_updated_at_ms = now;
                guard.stremio_auth_revoked_at_ms = None;
            }
            None => {
                guard.sealed_stremio_auth_key.clear();
                guard.stremio_email.clear();
                guard.stremio_auth_updated_at_ms = now;
                guard.stremio_auth_revoked_at_ms = Some(now);
            }
        }
        self.flush_locked(&guard)
    }

    pub fn addons(&self) -> Vec<InstalledAddon> {
        self.state.read().unwrap().addons.clone()
    }

    pub fn set_addons(&self, addons: Vec<InstalledAddon>) -> CoreResult<()> {
        let mut guard = self.state.write().unwrap();
        let mut merged = addons;
        // Guarantee Cinemeta stays installed so search & catalog metadata never break
        let defaults = default_addons();
        if let Some(cinemeta) = defaults.into_iter().next() {
            if !merged.iter().any(|a| a.id == cinemeta.id) {
                merged.insert(0, cinemeta);
            }
        }
        guard.addons = merged;
        self.flush_locked(&guard)
    }

    pub fn upsert_addon(&self, addon: InstalledAddon) -> CoreResult<()> {
        let mut current = self.addons();
        if let Some(idx) = current
            .iter()
            .position(|a| a.id == addon.id || a.transport_url == addon.transport_url)
        {
            current[idx] = addon;
        } else {
            current.push(addon);
        }
        self.set_addons(current)
    }

    pub fn remove_addon(&self, addon_id: &str) -> CoreResult<()> {
        let current: Vec<InstalledAddon> = self
            .addons()
            .into_iter()
            .filter(|a| a.id != addon_id)
            .collect();
        self.set_addons(current)
    }

    // ── Watch History & Favorites ─────────────────────────────────────────────

    pub fn watch_history(&self) -> Vec<WatchHistoryEntry> {
        let mut items = self.state.read().unwrap().watch_history.clone();
        items.sort_by(|a, b| b.updated_at_ms.cmp(&a.updated_at_ms));
        items
    }

    pub fn upsert_watch_history(&self, mut entry: WatchHistoryEntry) -> CoreResult<()> {
        if entry.updated_at_ms == 0 {
            entry.updated_at_ms = now_epoch_ms();
        }
        let mut guard = self.state.write().unwrap();
        // Preserve previous last_stream_context if caller didn't pass one
        if entry.last_stream_context.is_none() {
            if let Some(prev) = guard
                .watch_history
                .iter()
                .find(|h| h.item_id == entry.item_id)
            {
                entry.last_stream_context = prev.last_stream_context.clone();
            }
        }
        guard.watch_history.retain(|h| h.item_id != entry.item_id);
        // Auto-clear items watched >= 94% unless Series (keep series context for Next Episode)
        let ratio = entry.progress_ratio();
        if ratio < 0.94 || entry.media_type == "series" {
            guard.watch_history.insert(0, entry);
        }
        guard
            .watch_history
            .sort_by(|a, b| b.updated_at_ms.cmp(&a.updated_at_ms));
        guard.watch_history.truncate(60);
        self.flush_locked(&guard)
    }

    pub fn record_stream_selection(
        &self,
        item_id: &str,
        ctx: StreamSelectionContext,
    ) -> CoreResult<()> {
        let mut guard = self.state.write().unwrap();
        if let Some(existing) = guard.watch_history.iter_mut().find(|h| h.item_id == item_id) {
            existing.last_stream_context = Some(ctx);
        } else {
            guard.watch_history.insert(
                0,
                WatchHistoryEntry {
                    item_id: item_id.to_string(),
                    media_type: "series".to_string(),
                    title: item_id.to_string(),
                    updated_at_ms: now_epoch_ms(),
                    last_stream_context: Some(ctx),
                    ..Default::default()
                },
            );
        }
        self.flush_locked(&guard)
    }

    pub fn last_stream_context(&self, item_id: &str) -> Option<StreamSelectionContext> {
        self.state
            .read()
            .unwrap()
            .watch_history
            .iter()
            .find(|h| h.item_id == item_id)
            .and_then(|h| h.last_stream_context.clone())
    }

    pub fn replace_watch_history(&self, mut entries: Vec<WatchHistoryEntry>) -> CoreResult<()> {
        entries.sort_by(|a, b| b.updated_at_ms.cmp(&a.updated_at_ms));
        entries.truncate(60);
        let mut guard = self.state.write().unwrap();
        guard.watch_history = entries;
        self.flush_locked(&guard)
    }

    pub fn favorites(&self) -> Vec<FavoriteEntry> {
        let mut favs = self.state.read().unwrap().favorites.clone();
        favs.sort_by(|a, b| b.added_at_ms.cmp(&a.added_at_ms));
        favs
    }

    pub fn toggle_favorite(&self, mut fav: FavoriteEntry) -> CoreResult<bool> {
        let mut guard = self.state.write().unwrap();
        if let Some(pos) = guard.favorites.iter().position(|f| f.item_id == fav.item_id) {
            guard.favorites.remove(pos);
            self.flush_locked(&guard)?;
            Ok(false)
        } else {
            if fav.added_at_ms == 0 {
                fav.added_at_ms = now_epoch_ms();
            }
            guard.favorites.insert(0, fav);
            self.flush_locked(&guard)?;
            Ok(true)
        }
    }

    pub fn replace_favorites(&self, mut entries: Vec<FavoriteEntry>) -> CoreResult<()> {
        entries.sort_by(|a, b| b.added_at_ms.cmp(&a.added_at_ms));
        let mut guard = self.state.write().unwrap();
        guard.favorites = entries;
        self.flush_locked(&guard)
    }

    // ── Jellyfin Multi-Server Profiles ────────────────────────────────────────

    pub fn jellyfin_servers(&self) -> Vec<JellyfinServerProfile> {
        let guard = self.state.read().unwrap();
        guard
            .jellyfin_servers
            .iter()
            .map(|p| self.unseal_jellyfin_profile(p))
            .collect()
    }

    pub fn jellyfin_server(&self, server_id: &str) -> Option<JellyfinServerProfile> {
        self.jellyfin_servers()
            .into_iter()
            .find(|s| s.server_id == server_id)
    }

    pub fn jellyfin_tombstones(&self) -> HashMap<String, u64> {
        self.state.read().unwrap().jellyfin_tombstones.clone()
    }

    pub fn upsert_jellyfin_server(&self, mut profile: JellyfinServerProfile) -> CoreResult<()> {
        if profile.updated_at_ms == 0 {
            profile.updated_at_ms = now_epoch_ms();
        }
        let sealed = self.seal_jellyfin_profile(&profile)?;
        let mut guard = self.state.write().unwrap();
        guard.jellyfin_tombstones.remove(&sealed.server_id);
        if let Some(pos) = guard
            .jellyfin_servers
            .iter()
            .position(|s| s.server_id == sealed.server_id)
        {
            guard.jellyfin_servers[pos] = sealed;
        } else {
            guard.jellyfin_servers.push(sealed);
        }
        self.flush_locked(&guard)
    }

    pub fn remove_jellyfin_server(&self, server_id: &str) -> CoreResult<()> {
        let mut guard = self.state.write().unwrap();
        guard.jellyfin_servers.retain(|s| s.server_id != server_id);
        guard
            .jellyfin_tombstones
            .insert(server_id.to_string(), now_epoch_ms());
        self.flush_locked(&guard)
    }

    fn seal_jellyfin_profile(
        &self,
        p: &JellyfinServerProfile,
    ) -> CoreResult<JellyfinServerProfile> {
        let mut copy = p.clone();
        if !copy.access_token.is_empty() {
            copy.access_token = self
                .keystore
                .seal_string(purpose::JELLYFIN_TOKEN, &copy.access_token)?;
        }
        if !copy.bootstrap_token.is_empty() {
            copy.bootstrap_token = self
                .keystore
                .seal_string(purpose::JELLYFIN_BOOTSTRAP, &copy.bootstrap_token)?;
        }
        Ok(copy)
    }

    fn unseal_jellyfin_profile(&self, p: &JellyfinServerProfile) -> JellyfinServerProfile {
        let mut copy = p.clone();
        copy.access_token = self
            .keystore
            .unseal_string(purpose::JELLYFIN_TOKEN, &copy.access_token)
            .unwrap_or_default();
        copy.bootstrap_token = self
            .keystore
            .unseal_string(purpose::JELLYFIN_BOOTSTRAP, &copy.bootstrap_token)
            .unwrap_or_default();
        copy
    }

    // ── YouTube & Creator Profiles ────────────────────────────────────────────

    pub fn youtube_accounts(&self) -> Vec<YouTubeAccountProfile> {
        let guard = self.state.read().unwrap();
        guard
            .youtube_accounts
            .iter()
            .map(|a| self.unseal_youtube_account(a))
            .collect()
    }

    pub fn active_youtube_account(&self) -> Option<YouTubeAccountProfile> {
        let active_id = self.state.read().unwrap().active_youtube_account_id.clone();
        let all = self.youtube_accounts();
        if !active_id.is_empty() {
            if let Some(found) = all.iter().find(|a| a.account_id == active_id) {
                return Some(found.clone());
            }
        }
        all.into_iter().next()
    }

    pub fn set_active_youtube_account_id(&self, account_id: &str) -> CoreResult<()> {
        let mut guard = self.state.write().unwrap();
        guard.active_youtube_account_id = account_id.to_string();
        self.flush_locked(&guard)
    }

    pub fn upsert_youtube_account(&self, mut acc: YouTubeAccountProfile) -> CoreResult<()> {
        if acc.updated_at_ms == 0 {
            acc.updated_at_ms = now_epoch_ms();
        }
        let sealed = self.seal_youtube_account(&acc)?;
        let mut guard = self.state.write().unwrap();
        guard.youtube_tombstones.remove(&sealed.account_id);
        if let Some(pos) = guard
            .youtube_accounts
            .iter()
            .position(|a| a.account_id == sealed.account_id)
        {
            guard.youtube_accounts[pos] = sealed.clone();
        } else {
            guard.youtube_accounts.push(sealed.clone());
        }
        if guard.active_youtube_account_id.is_empty() {
            guard.active_youtube_account_id = sealed.account_id;
        }
        self.flush_locked(&guard)
    }

    pub fn remove_youtube_account(&self, account_id: &str) -> CoreResult<()> {
        let mut guard = self.state.write().unwrap();
        guard.youtube_accounts.retain(|a| a.account_id != account_id);
        guard
            .youtube_tombstones
            .insert(account_id.to_string(), now_epoch_ms());
        if guard.active_youtube_account_id == account_id {
            guard.active_youtube_account_id = guard
                .youtube_accounts
                .first()
                .map(|a| a.account_id.clone())
                .unwrap_or_default();
        }
        self.flush_locked(&guard)
    }

    pub fn youtube_tombstones(&self) -> HashMap<String, u64> {
        self.state.read().unwrap().youtube_tombstones.clone()
    }

    fn seal_youtube_account(
        &self,
        a: &YouTubeAccountProfile,
    ) -> CoreResult<YouTubeAccountProfile> {
        let mut copy = a.clone();
        if !copy.refresh_token.is_empty() {
            copy.refresh_token = self
                .keystore
                .seal_string(purpose::YOUTUBE_REFRESH, &copy.refresh_token)?;
        }
        if !copy.access_token.is_empty() {
            copy.access_token = self
                .keystore
                .seal_string(purpose::YOUTUBE_ACCESS, &copy.access_token)?;
        }
        if !copy.oauth_client_secret.is_empty() {
            copy.oauth_client_secret = self
                .keystore
                .seal_string(purpose::YOUTUBE_CLIENT_SECRET, &copy.oauth_client_secret)?;
        }
        Ok(copy)
    }

    fn unseal_youtube_account(&self, a: &YouTubeAccountProfile) -> YouTubeAccountProfile {
        let mut copy = a.clone();
        copy.refresh_token = self
            .keystore
            .unseal_string(purpose::YOUTUBE_REFRESH, &copy.refresh_token)
            .unwrap_or_default();
        copy.access_token = self
            .keystore
            .unseal_string(purpose::YOUTUBE_ACCESS, &copy.access_token)
            .unwrap_or_default();
        copy.oauth_client_secret = self
            .keystore
            .unseal_string(purpose::YOUTUBE_CLIENT_SECRET, &copy.oauth_client_secret)
            .unwrap_or_default();
        copy
    }

    pub fn twitch_profile(&self) -> Option<TwitchAuthProfile> {
        let guard = self.state.read().unwrap();
        let p = guard.twitch_profile.as_ref()?;
        let mut copy = p.clone();
        copy.access_token = self
            .keystore
            .unseal_string(purpose::TWITCH_ACCESS, &copy.access_token)
            .unwrap_or_default();
        copy.refresh_token = self
            .keystore
            .unseal_string(purpose::TWITCH_REFRESH, &copy.refresh_token)
            .unwrap_or_default();
        Some(copy)
    }

    pub fn set_twitch_profile(&self, profile: Option<TwitchAuthProfile>) -> CoreResult<()> {
        let mut guard = self.state.write().unwrap();
        guard.twitch_profile = match profile {
            Some(mut p) => {
                if p.updated_at_ms == 0 {
                    p.updated_at_ms = now_epoch_ms();
                }
                if !p.access_token.is_empty() {
                    p.access_token = self
                        .keystore
                        .seal_string(purpose::TWITCH_ACCESS, &p.access_token)?;
                }
                if !p.refresh_token.is_empty() {
                    p.refresh_token = self
                        .keystore
                        .seal_string(purpose::TWITCH_REFRESH, &p.refresh_token)?;
                }
                Some(p)
            }
            None => None,
        };
        self.flush_locked(&guard)
    }

    pub fn creator_follows(&self) -> Vec<CreatorFollowEntry> {
        self.state.read().unwrap().creator_follows.clone()
    }

    pub fn active_creator_follows(&self, platform: Option<&str>) -> Vec<CreatorFollowEntry> {
        self.state
            .read()
            .unwrap()
            .creator_follows
            .iter()
            .filter(|f| f.removed_at_ms.unwrap_or(0) <= f.added_at_ms)
            .filter(|f| platform.map(|p| f.platform == p).unwrap_or(true))
            .cloned()
            .collect()
    }

    pub fn toggle_creator_follow(&self, mut entry: CreatorFollowEntry) -> CoreResult<bool> {
        let now = now_epoch_ms();
        let mut guard = self.state.write().unwrap();
        if let Some(existing) = guard
            .creator_follows
            .iter_mut()
            .find(|f| f.platform == entry.platform && f.channel_id == entry.channel_id)
        {
            let active = existing.removed_at_ms.unwrap_or(0) <= existing.added_at_ms;
            if active {
                existing.removed_at_ms = Some(now);
                self.flush_locked(&guard)?;
                return Ok(false);
            } else {
                existing.added_at_ms = now;
                existing.removed_at_ms = None;
                existing.name = entry.name;
                existing.handle = entry.handle;
                existing.avatar_url = entry.avatar_url;
                self.flush_locked(&guard)?;
                return Ok(true);
            }
        }
        entry.added_at_ms = now;
        entry.removed_at_ms = None;
        guard.creator_follows.push(entry);
        self.flush_locked(&guard)?;
        Ok(true)
    }

    pub fn replace_creator_follows(&self, entries: Vec<CreatorFollowEntry>) -> CoreResult<()> {
        let mut guard = self.state.write().unwrap();
        guard.creator_follows = entries;
        self.flush_locked(&guard)
    }

    // ── Multipass v2 Profile & Sealed 32-Byte VMK ────────────────────────────

    pub fn multipass_profile(&self) -> MultipassProfile {
        let guard = self.state.read().unwrap();
        let mut p = guard.multipass.clone();
        p.pair_token = self
            .keystore
            .unseal_string(purpose::MULTIPASS_TOKEN, &p.pair_token)
            .unwrap_or_default();
        p.vault_unlocked = !guard.sealed_multipass_vmk.is_empty();
        p
    }

    pub fn save_multipass_profile(&self, mut profile: MultipassProfile) -> CoreResult<()> {
        if !profile.pair_token.is_empty() {
            profile.pair_token = self
                .keystore
                .seal_string(purpose::MULTIPASS_TOKEN, &profile.pair_token)?;
        }
        let mut guard = self.state.write().unwrap();
        profile.vault_unlocked = !guard.sealed_multipass_vmk.is_empty();
        guard.multipass = profile;
        self.flush_locked(&guard)
    }

    pub fn clear_multipass_profile(&self) -> CoreResult<()> {
        let mut guard = self.state.write().unwrap();
        guard.multipass = MultipassProfile::default();
        guard.sealed_multipass_vmk.clear();
        self.flush_locked(&guard)
    }

    pub fn seal_multipass_vmk(&self, vmk: &[u8]) -> CoreResult<()> {
        if vmk.len() != 32 {
            return Err(CoreError::Crypto(
                "Multipass Vault Master Key (VMK) must be exactly 32 bytes".into(),
            ));
        }
        let sealed = self.keystore.seal_bytes(purpose::MULTIPASS_VMK, vmk)?;
        let mut guard = self.state.write().unwrap();
        guard.sealed_multipass_vmk = sealed;
        guard.multipass.vault_unlocked = true;
        self.flush_locked(&guard)
    }

    pub fn unseal_multipass_vmk(&self) -> Option<Zeroizing<[u8; 32]>> {
        let sealed = self.state.read().unwrap().sealed_multipass_vmk.clone();
        if sealed.is_empty() {
            return None;
        }
        let bytes = self
            .keystore
            .unseal_bytes(purpose::MULTIPASS_VMK, &sealed)
            .ok()?;
        if bytes.len() != 32 {
            return None;
        }
        let mut out = Zeroizing::new([0u8; 32]);
        out.copy_from_slice(&bytes);
        Some(out)
    }

    // ── Swarm, Torznab, & Torrent Engine Settings ────────────────────────────

    pub fn swarm_filter(&self) -> SwarmFilter {
        self.state.read().unwrap().swarm_filter
    }

    pub fn set_swarm_filter(&self, filter: SwarmFilter) -> CoreResult<()> {
        let mut guard = self.state.write().unwrap();
        guard.swarm_filter = filter;
        guard.streaming_prefs_updated_at_ms = now_epoch_ms();
        self.flush_locked(&guard)
    }

    pub fn discover_sort_mode(&self) -> DiscoverSortMode {
        self.state.read().unwrap().discover_sort_mode
    }

    pub fn set_discover_sort_mode(&self, mode: DiscoverSortMode) -> CoreResult<()> {
        let mut guard = self.state.write().unwrap();
        guard.discover_sort_mode = mode;
        self.flush_locked(&guard)
    }

    pub fn torrent_config(&self) -> TorrentEngineConfig {
        self.state.read().unwrap().torrent_config.clone()
    }

    pub fn set_torrent_config(&self, cfg: TorrentEngineConfig) -> CoreResult<()> {
        let mut guard = self.state.write().unwrap();
        guard.torrent_config = TorrentEngineConfig {
            max_cache_mb: cfg.max_cache_mb.clamp(256, 4096),
            window_ahead_mb: cfg.window_ahead_mb.clamp(16, 128),
            prebuffer_mb: cfg.prebuffer_mb.clamp(6, 64),
            max_connections: cfg.max_connections.clamp(30, 300),
        };
        guard.streaming_prefs_updated_at_ms = now_epoch_ms();
        self.flush_locked(&guard)
    }

    pub fn torznab_config(&self) -> (String, String) {
        let guard = self.state.read().unwrap();
        let key = self
            .keystore
            .unseal_string(purpose::TORZNAB_API_KEY, &guard.sealed_torznab_api_key)
            .unwrap_or_default();
        (guard.torznab_url.clone(), key)
    }

    pub fn set_torznab_config(&self, url: &str, api_key: &str) -> CoreResult<()> {
        let mut guard = self.state.write().unwrap();
        guard.torznab_url = url.trim().to_string();
        guard.sealed_torznab_api_key = if api_key.trim().is_empty() {
            String::new()
        } else {
            self.keystore
                .seal_string(purpose::TORZNAB_API_KEY, api_key.trim())?
        };
        guard.streaming_prefs_updated_at_ms = now_epoch_ms();
        self.flush_locked(&guard)
    }

    pub fn streaming_prefs_updated_at_ms(&self) -> u64 {
        self.state.read().unwrap().streaming_prefs_updated_at_ms
    }

    pub fn raw_disk_json_for_audit(&self) -> CoreResult<String> {
        let guard = self.state.read().unwrap();
        self.flush_locked(&guard)?;
        Ok(std::fs::read_to_string(&self.state_file)?)
    }
}
