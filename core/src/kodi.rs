use crate::config::ConfigStore;
use crate::error::{CoreError, CoreResult};
use crate::models::{KodiAddonInfo, KodiBridgeResponse, KodiDirectoryItem};
use crate::stremio::split_pipe_headers;
use quick_xml::events::Event;
use quick_xml::Reader;
use serde_json::Value;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Arc;
use std::time::Duration;
use tokio::process::Command;

/// Bundled compile-time copy of `kodi_bridge.py` so `brootlite-core` works out-of-the-box on
/// macOS, Linux, and Windows even when packaged as a standalone binary without external assets!
const BUNDLED_KODI_BRIDGE_PY: &str =
    include_str!("../../app/src/main/python/kodi_bridge.py");

/// Disposable Python 3 Kodi Addon Bridge (`0 MB` idle & playback RAM).
///
/// Scans `kodi_env/addons/plugin.video.*/addon.xml` in pure Rust (`0 MB` Python RAM), and
/// spawns a short-lived `python3` child process ONLY during plugin directory browsing or
/// stream URL resolution, immediately terminating and reaping the child process once JSON
/// returns!
pub struct KodiRunner {
    config: Arc<ConfigStore>,
}

impl KodiRunner {
    pub fn new(config: Arc<ConfigStore>) -> Self {
        Self { config }
    }

    pub fn scan_installed_video_addons(&self) -> Vec<KodiAddonInfo> {
        scan_addons_in_dir(&self.config.kodi_env_dir().join("addons"))
    }

    fn ensure_bridge_script_on_disk(&self) -> CoreResult<PathBuf> {
        let staged = self.config.kodi_env_dir().join("kodi_bridge.py");
        let needs_write = match std::fs::read_to_string(&staged) {
            Ok(existing) => existing != BUNDLED_KODI_BRIDGE_PY,
            Err(_) => true,
        };
        if needs_write {
            std::fs::write(&staged, BUNDLED_KODI_BRIDGE_PY)?;
        }
        Ok(staged)
    }

    /// Executes a Kodi video plugin URL (`plugin://plugin.video.example/?action=...`) inside a
    /// disposable `python3` subprocess (`kill_on_drop(true)`), returning the parsed response and
    /// leaving `0 MB` Python memory resident during playback!
    pub async fn execute_addon(
        &self,
        addon_id: &str,
        plugin_url: Option<&str>,
        timeout_sec: u64,
    ) -> CoreResult<KodiBridgeResponse> {
        let bridge_py = self.ensure_bridge_script_on_disk()?;
        let bridge_dir = bridge_py
            .parent()
            .ok_or_else(|| CoreError::Subprocess("invalid kodi_bridge.py parent dir".into()))?;
        let env_root = self.config.kodi_env_dir();
        let effective_url = plugin_url
            .filter(|s| !s.trim().is_empty())
            .map(String::from)
            .unwrap_or_else(|| format!("plugin://{addon_id}/"));

        let inline_driver = r#"
import sys
bridge_dir, env_root, plugin_url = sys.argv[1], sys.argv[2], sys.argv[3]
sys.path.insert(0, bridge_dir)
import kodi_bridge
out = kodi_bridge.execute_plugin(plugin_url, env_root)
sys.stdout.write("\n__BROOTLITE_KODI_JSON_START__\n" + out + "\n__BROOTLITE_KODI_JSON_END__\n")
sys.stdout.flush()
"#;

        let py_bin = find_python3_binary();
        let mut cmd = Command::new(&py_bin);
        cmd.arg("-I")
            .arg("-B")
            .arg("-c")
            .arg(inline_driver)
            .arg(bridge_dir.to_string_lossy().as_ref())
            .arg(env_root.to_string_lossy().as_ref())
            .arg(&effective_url)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);

        let child = cmd.spawn().map_err(|e| {
            CoreError::Subprocess(format!(
                "Failed to spawn disposable python3 ({py_bin}) for Kodi addon: {e}"
            ))
        })?;

        let output = tokio::time::timeout(
            Duration::from_secs(timeout_sec.clamp(5, 60)),
            child.wait_with_output(),
        )
        .await
        .map_err(|_| CoreError::Subprocess("Kodi addon execution timed out".into()))?
        .map_err(|e| CoreError::Subprocess(format!("Kodi python3 wait error: {e}")))?;

        let stdout = String::from_utf8_lossy(&output.stdout);
        if let Some((_, after)) = stdout.split_once("__BROOTLITE_KODI_JSON_START__") {
            if let Some((json_body, _)) = after.split_once("__BROOTLITE_KODI_JSON_END__") {
                let raw: Value = serde_json::from_str(json_body.trim())?;
                let ok = raw.get("ok").and_then(|v| v.as_bool()).unwrap_or(false);
                let error = raw
                    .get("error")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let resolved_url = raw
                    .pointer("/resolved/url")
                    .and_then(|v| v.as_str())
                    .or_else(|| raw.get("resolvedUrl").and_then(|v| v.as_str()))
                    .unwrap_or("")
                    .to_string();

                let mut items = Vec::new();
                if let Some(arr) = raw.get("items").and_then(|v| v.as_array()) {
                    for item in arr {
                        let label = item
                            .get("cleanLabel")
                            .and_then(|v| v.as_str())
                            .filter(|s| !s.is_empty())
                            .or_else(|| item.get("label").and_then(|v| v.as_str()))
                            .unwrap_or("")
                            .to_string();
                        let url = item
                            .get("url")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string();
                        let is_folder = item
                            .get("isFolder")
                            .and_then(|v| v.as_bool())
                            .unwrap_or(false);
                        let is_playable = item
                            .get("isPlayable")
                            .and_then(|v| v.as_bool())
                            .unwrap_or(false);
                        let thumb = item
                            .get("thumb")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string();
                        let fanart = item
                            .get("fanart")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string();
                        let plot = item
                            .get("plot")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string();
                        let year = item
                            .get("year")
                            .and_then(|v| {
                                v.as_str()
                                    .map(String::from)
                                    .or_else(|| v.as_i64().filter(|&y| y > 0).map(|y| y.to_string()))
                            })
                            .unwrap_or_default();
                        items.push(KodiDirectoryItem {
                            label,
                            url,
                            is_folder,
                            is_playable,
                            thumb,
                            fanart,
                            plot,
                            year,
                        });
                    }
                }

                return Ok(KodiBridgeResponse {
                    ok,
                    error,
                    resolved_url,
                    items,
                });
            }
        }

        let stderr = String::from_utf8_lossy(&output.stderr);
        Err(CoreError::Subprocess(format!(
            "Kodi addon did not return valid bridge JSON (exit {:?}): {}",
            output.status.code(),
            stderr.lines().last().unwrap_or("unknown error")
        )))
    }
}

/// Parses Kodi stream URLs containing `|Header=Value&Referer=...` pipe suffixes.
pub fn parse_kodi_stream_url(raw_url: &str) -> (String, HashMap<String, String>) {
    split_pipe_headers(raw_url)
}

pub fn scan_addons_in_dir(addons_dir: &Path) -> Vec<KodiAddonInfo> {
    let Ok(entries) = std::fs::read_dir(addons_dir) else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for entry in entries.flatten() {
        let dir_path = entry.path();
        if !dir_path.is_dir() {
            continue;
        }
        let xml_path = dir_path.join("addon.xml");
        if !xml_path.is_file() {
            continue;
        }
        if let Ok(xml) = std::fs::read_to_string(&xml_path) {
            if let Some(info) = parse_kodi_addon_xml(&xml, &dir_path) {
                out.push(info);
            }
        }
    }
    out.sort_by(|a, b| a.name.cmp(&b.name));
    out
}

pub fn parse_kodi_addon_xml(xml: &str, addon_dir: &Path) -> Option<KodiAddonInfo> {
    let mut reader = Reader::from_str(xml);
    reader.config_mut().trim_text(true);

    let mut id = String::new();
    let mut name = String::new();
    let mut version = String::new();
    let mut summary = String::new();
    let mut is_video_plugin = false;
    let mut cur_tag = String::new();
    let mut buf = Vec::new();

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(e)) | Ok(Event::Empty(e)) => {
                let tag = String::from_utf8_lossy(e.name().as_ref()).to_string();
                if tag == "addon" && id.is_empty() {
                    for attr in e.attributes().flatten() {
                        match attr.key.as_ref() {
                            b"id" => id = String::from_utf8_lossy(&attr.value).to_string(),
                            b"name" => name = String::from_utf8_lossy(&attr.value).to_string(),
                            b"version" => version = String::from_utf8_lossy(&attr.value).to_string(),
                            _ => {}
                        }
                    }
                    if id.starts_with("plugin.video.") {
                        is_video_plugin = true;
                    }
                } else if tag == "provides" || tag.starts_with("summary") {
                    cur_tag = tag;
                }
            }
            Ok(Event::Text(t)) => {
                let txt = t.unescape().map(|c| c.into_owned()).unwrap_or_default();
                if cur_tag == "provides" && txt.contains("video") {
                    is_video_plugin = true;
                } else if cur_tag.starts_with("summary") && summary.is_empty() {
                    summary = txt;
                }
            }
            Ok(Event::End(_)) => cur_tag.clear(),
            Ok(Event::Eof) | Err(_) => break,
            _ => {}
        }
        buf.clear();
    }

    if id.is_empty() || !is_video_plugin {
        return None;
    }
    let icon_candidate = addon_dir.join("icon.png");
    let icon_path = if icon_candidate.is_file() {
        icon_candidate.to_string_lossy().into_owned()
    } else {
        String::new()
    };

    Some(KodiAddonInfo {
        name: if name.is_empty() { id.clone() } else { name },
        id,
        version: if version.is_empty() {
            "1.0.0".into()
        } else {
            version
        },
        summary,
        icon_path,
    })
}

fn find_python3_binary() -> String {
    if let Ok(explicit) = std::env::var("BROOTLITE_PYTHON3") {
        if !explicit.trim().is_empty() {
            return explicit;
        }
    }
    for cand in ["/opt/homebrew/bin/python3", "/usr/local/bin/python3", "/usr/bin/python3"] {
        if Path::new(cand).is_file() {
            return cand.to_string();
        }
    }
    "python3".to_string()
}
