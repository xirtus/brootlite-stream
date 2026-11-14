//! Bounded `LRU(64)` in-memory poster & thumbnail cache for ultra-low-RAM `iced` rendering.

use bytes::Bytes;
use iced::widget::image::Handle as ImageHandle;
use std::collections::{HashMap, HashSet, VecDeque};

/// Strictly cap resident poster handles to 64 so total desktop RAM stays ~35–55 MB.
pub const MAX_POSTER_CACHE_ENTRIES: usize = 64;
const MAX_FAILED_URLS: usize = 256;
const MAX_POSTER_BYTES: usize = 2 * 1024 * 1024;

pub struct PosterLruCache {
    entries: HashMap<String, ImageHandle>,
    order: VecDeque<String>,
    in_flight: HashSet<String>,
    failed: VecDeque<String>,
}

impl Default for PosterLruCache {
    fn default() -> Self {
        Self::new(MAX_POSTER_CACHE_ENTRIES)
    }
}

impl PosterLruCache {
    pub fn new(capacity: usize) -> Self {
        Self {
            entries: HashMap::with_capacity(capacity.max(1)),
            order: VecDeque::with_capacity(capacity.max(1)),
            in_flight: HashSet::new(),
            failed: VecDeque::new(),
        }
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Non-mutating lookup for `iced`'s `&self` `view()` pass.
    pub fn peek(&self, url: &str) -> Option<&ImageHandle> {
        let key = url.trim();
        if key.is_empty() {
            return None;
        }
        self.entries.get(key)
    }

    /// Mutating lookup that promotes `url` to most-recently-used.
    pub fn get_touch(&mut self, url: &str) -> Option<ImageHandle> {
        let key = url.trim();
        let handle = self.entries.get(key)?.clone();
        self.promote(key);
        Some(handle)
    }

    /// Inserts an `ImageHandle` into the cache, evicting the oldest entry if `len >= 64`.
    pub fn insert(&mut self, url: String, handle: ImageHandle) {
        let key = url.trim().to_string();
        if key.is_empty() {
            return;
        }
        self.in_flight.remove(&key);
        if self.entries.contains_key(&key) {
            self.entries.insert(key.clone(), handle);
            self.promote(&key);
            return;
        }

        while self.entries.len() >= MAX_POSTER_CACHE_ENTRIES {
            if let Some(evicted) = self.order.pop_front() {
                self.entries.remove(&evicted);
            } else {
                break;
            }
        }

        self.order.push_back(key.clone());
        self.entries.insert(key, handle);
    }

    pub fn mark_failed(&mut self, url: &str) {
        let key = url.trim().to_string();
        self.in_flight.remove(&key);
        if !key.is_empty() && !self.failed.iter().any(|u| u == &key) {
            if self.failed.len() >= MAX_FAILED_URLS {
                self.failed.pop_front();
            }
            self.failed.push_back(key);
        }
    }

    /// Marks `url` as in-flight and returns `true` iff it needs to be fetched over HTTP.
    pub fn claim_fetch(&mut self, url: &str) -> bool {
        let key = url.trim();
        if key.is_empty()
            || (!key.starts_with("http://") && !key.starts_with("https://"))
            || self.entries.contains_key(key)
            || self.in_flight.contains(key)
            || self.failed.iter().any(|u| u == key)
        {
            return false;
        }
        // Keep concurrent image downloads bounded so slow CDNs never saturate sockets
        if self.in_flight.len() >= 18 {
            return false;
        }
        self.in_flight.insert(key.to_string());
        true
    }

    fn promote(&mut self, key: &str) {
        if let Some(pos) = self.order.iter().position(|k| k == key) {
            self.order.remove(pos);
        }
        self.order.push_back(key.to_string());
    }
}

pub async fn download_poster_bytes(http: reqwest::Client, url: String) -> Result<Bytes, String> {
    let resp = http
        .get(&url)
        .send()
        .await
        .map_err(|e| format!("poster HTTP error: {e}"))?;
    if !resp.status().is_success() {
        return Err(format!("poster HTTP {}", resp.status()));
    }
    if let Some(len) = resp.content_length() {
        if len as usize > MAX_POSTER_BYTES {
            return Err("poster exceeds 2 MiB cap".into());
        }
    }
    let bytes = resp
        .bytes()
        .await
        .map_err(|e| format!("poster body error: {e}"))?;
    if bytes.is_empty() || bytes.len() > MAX_POSTER_BYTES {
        return Err("invalid poster byte size".into());
    }
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn poster_lru_strictly_caps_at_64_entries_and_evicts_oldest() {
        let mut cache = PosterLruCache::default();
        for i in 0..80 {
            let url = format!("https://images.example.com/poster_{i}.jpg");
            let dummy = ImageHandle::from_rgba(2, 2, vec![255u8; 16]);
            cache.insert(url, dummy);
        }
        assert_eq!(cache.len(), MAX_POSTER_CACHE_ENTRIES);
        assert!(cache.peek("https://images.example.com/poster_0.jpg").is_none());
        assert!(cache.peek("https://images.example.com/poster_15.jpg").is_none());
        assert!(cache.peek("https://images.example.com/poster_16.jpg").is_some());
        assert!(cache.peek("https://images.example.com/poster_79.jpg").is_some());

        // Touch poster_16 so poster_17 becomes oldest
        let _ = cache.get_touch("https://images.example.com/poster_16.jpg");
        cache.insert(
            "https://images.example.com/poster_80.jpg".into(),
            ImageHandle::from_rgba(2, 2, vec![255u8; 16]),
        );
        assert_eq!(cache.len(), MAX_POSTER_CACHE_ENTRIES);
        assert!(cache.peek("https://images.example.com/poster_16.jpg").is_some());
        assert!(cache.peek("https://images.example.com/poster_17.jpg").is_none());
    }
}
