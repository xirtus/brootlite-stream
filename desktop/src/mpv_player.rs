//! Zero-Compile-Time-C-Header Dynamic `libmpv` Bridge (`libloading`).
//!
//! Dynamically resolves `libmpv` v2 (`libmpv.so.2` on Linux, `libmpv-2.dll` on Windows,
//! `libmpv.2.dylib` on macOS) at runtime with zero `-dev` header build dependencies, and
//! supports **Dual-Mode Rendering**:
//! 1. [`MpvRenderMode::GpuWindow`] (Default): Zero-copy hardware decode (`vo=gpu`,
//!    `hwdec=auto-safe` -> `VAAPI`/`NVDEC` on Linux Wayland & X11, `d3d11va` on Windows,
//!    `videotoolbox` on macOS) synced 100% two-way with the `iced` Player Control Deck,
//!    Series Binge Autoplay Card, and Movie Credits Gallery.
//! 2. [`MpvRenderMode::InlineSoftware`]: Embedded `vo=libmpv` + `MPV_RENDER_API_TYPE_SW`
//!    (`rgb0` -> `RGBA8`, 64-byte SIMD-aligned stride) rendered directly into an
//!    `iced::widget::image::Handle::from_rgba` canvas inside the main window.

use iced::widget::image::Handle as ImageHandle;
use libloading::Library;
use serde::Deserialize;
use std::collections::HashMap;
use std::ffi::{c_char, c_double, c_int, c_ulong, c_void, CStr, CString};
use std::ptr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

// ─────────────────────────────────────────────────────────────────────────────
// Exact `libmpv` v2 C ABI Constants & Structs (`mpv/client.h` & `mpv/render.h`)
// ─────────────────────────────────────────────────────────────────────────────

const MPV_EVENT_NONE: c_int = 0;
const MPV_EVENT_SHUTDOWN: c_int = 1;
const MPV_EVENT_END_FILE: c_int = 7;
const MPV_EVENT_FILE_LOADED: c_int = 8;

const MPV_RENDER_PARAM_INVALID: c_int = 0;
const MPV_RENDER_PARAM_API_TYPE: c_int = 1;
const MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME: c_int = 12;
const MPV_RENDER_PARAM_SW_SIZE: c_int = 17;
const MPV_RENDER_PARAM_SW_FORMAT: c_int = 18;
const MPV_RENDER_PARAM_SW_STRIDE: c_int = 19;
const MPV_RENDER_PARAM_SW_POINTER: c_int = 20;

const MPV_RENDER_UPDATE_FRAME: u64 = 1 << 0;

/// 960x540 -> stride `960 * 4 = 3840` bytes (`64 * 60`, exact 64-byte SIMD alignment)
/// while keeping inline SW RGBA upload fast (~2 MB frame buffer) on modest CPUs.
pub const INLINE_SW_WIDTH: u32 = 960;
pub const INLINE_SW_HEIGHT: u32 = 540;

#[repr(C)]
pub struct MpvHandle {
    _private: [u8; 0],
}

#[repr(C)]
pub struct MpvRenderContext {
    _private: [u8; 0],
}

#[repr(C)]
pub struct MpvEvent {
    pub event_id: c_int,
    pub error: c_int,
    pub reply_userdata: u64,
    pub data: *mut c_void,
}

#[repr(C)]
pub struct MpvRenderParam {
    pub param_type: c_int,
    pub data: *mut c_void,
}

type MpvRenderUpdateFn = unsafe extern "C" fn(cb_ctx: *mut c_void);

type FnMpvClientApiVersion = unsafe extern "C" fn() -> c_ulong;
type FnMpvCreate = unsafe extern "C" fn() -> *mut MpvHandle;
type FnMpvInitialize = unsafe extern "C" fn(ctx: *mut MpvHandle) -> c_int;
type FnMpvSetOptionString =
    unsafe extern "C" fn(ctx: *mut MpvHandle, name: *const c_char, data: *const c_char) -> c_int;
type FnMpvSetPropertyString =
    unsafe extern "C" fn(ctx: *mut MpvHandle, name: *const c_char, data: *const c_char) -> c_int;
type FnMpvGetPropertyString =
    unsafe extern "C" fn(ctx: *mut MpvHandle, name: *const c_char) -> *mut c_char;
type FnMpvFree = unsafe extern "C" fn(data: *mut c_void);
type FnMpvCommand = unsafe extern "C" fn(ctx: *mut MpvHandle, args: *const *const c_char) -> c_int;
type FnMpvWaitEvent = unsafe extern "C" fn(ctx: *mut MpvHandle, timeout: c_double) -> *mut MpvEvent;
type FnMpvTerminateDestroy = unsafe extern "C" fn(ctx: *mut MpvHandle);

type FnMpvRenderContextCreate = unsafe extern "C" fn(
    res: *mut *mut MpvRenderContext,
    mpv: *mut MpvHandle,
    params: *mut MpvRenderParam,
) -> c_int;
type FnMpvRenderContextSetUpdateCallback = unsafe extern "C" fn(
    ctx: *mut MpvRenderContext,
    callback: Option<MpvRenderUpdateFn>,
    callback_ctx: *mut c_void,
);
type FnMpvRenderContextUpdate = unsafe extern "C" fn(ctx: *mut MpvRenderContext) -> u64;
type FnMpvRenderContextRender =
    unsafe extern "C" fn(ctx: *mut MpvRenderContext, params: *mut MpvRenderParam) -> c_int;
type FnMpvRenderContextFree = unsafe extern "C" fn(ctx: *mut MpvRenderContext);

unsafe extern "C" fn mpv_sw_frame_ready_callback(cb_ctx: *mut c_void) {
    if !cb_ctx.is_null() {
        let flag = &*(cb_ctx as *const AtomicBool);
        flag.store(true, Ordering::Release);
    }
}

/// Ensures `LC_NUMERIC` is `"C"` on Unix before calling `mpv_create()`, as required by `libmpv`.
#[cfg(unix)]
fn ensure_c_numeric_locale() {
    const LC_NUMERIC: c_int = if cfg!(any(
        target_os = "macos",
        target_os = "freebsd",
        target_os = "openbsd",
        target_os = "netbsd"
    )) {
        4
    } else {
        1
    };
    extern "C" {
        fn setlocale(category: c_int, locale: *const c_char) -> *mut c_char;
    }
    let c_str = b"C\0".as_ptr() as *const c_char;
    unsafe {
        let _ = setlocale(LC_NUMERIC, c_str);
    }
}

#[cfg(not(unix))]
fn ensure_c_numeric_locale() {}

/// Dynamically loaded `libmpv` symbol table. Kept alive in an `Arc` so symbols outlive handles.
pub struct DynamicMpvApi {
    _lib: Library,
    pub loaded_path: String,
    pub api_version: u64,
    mpv_create: FnMpvCreate,
    mpv_initialize: FnMpvInitialize,
    mpv_set_option_string: FnMpvSetOptionString,
    mpv_set_property_string: FnMpvSetPropertyString,
    mpv_get_property_string: FnMpvGetPropertyString,
    mpv_free: FnMpvFree,
    mpv_command: FnMpvCommand,
    mpv_wait_event: FnMpvWaitEvent,
    mpv_terminate_destroy: FnMpvTerminateDestroy,
    mpv_render_context_create: FnMpvRenderContextCreate,
    mpv_render_context_set_update_callback: Option<FnMpvRenderContextSetUpdateCallback>,
    mpv_render_context_update: Option<FnMpvRenderContextUpdate>,
    mpv_render_context_render: FnMpvRenderContextRender,
    mpv_render_context_free: FnMpvRenderContextFree,
}

impl DynamicMpvApi {
    pub fn candidate_library_paths() -> Vec<String> {
        let mut out = Vec::new();
        if let Ok(explicit) = std::env::var("BROOTLITE_LIBMPV_PATH") {
            let trimmed = explicit.trim();
            if !trimmed.is_empty() {
                out.push(trimmed.to_string());
            }
        }

        #[cfg(target_os = "linux")]
        {
            for cand in [
                "libmpv.so.2",
                "libmpv.so",
                "/usr/lib/x86_64-linux-gnu/libmpv.so.2",
                "/usr/lib/aarch64-linux-gnu/libmpv.so.2",
                "/usr/lib64/libmpv.so.2",
                "/usr/lib/libmpv.so.2",
            ] {
                out.push(cand.to_string());
            }
        }

        #[cfg(target_os = "windows")]
        {
            for cand in ["libmpv-2.dll", "mpv-2.dll", "mpv-1.dll"] {
                out.push(cand.to_string());
            }
        }

        #[cfg(target_os = "macos")]
        {
            for cand in [
                "/opt/homebrew/opt/mpv/lib/libmpv.2.dylib",
                "/usr/local/opt/mpv/lib/libmpv.2.dylib",
                "libmpv.2.dylib",
                "libmpv.dylib",
            ] {
                out.push(cand.to_string());
            }
        }

        #[cfg(not(any(target_os = "linux", target_os = "windows", target_os = "macos")))]
        {
            out.push("libmpv.so.2".to_string());
            out.push("libmpv.so".to_string());
        }

        out
    }

    pub fn default_hwdec() -> &'static str {
        #[cfg(target_os = "windows")]
        {
            "d3d11va"
        }
        #[cfg(target_os = "macos")]
        {
            "videotoolbox"
        }
        #[cfg(not(any(target_os = "windows", target_os = "macos")))]
        {
            "auto-safe"
        }
    }

    pub fn load() -> Result<Arc<Self>, String> {
        let candidates = Self::candidate_library_paths();
        let mut errors = Vec::new();

        for path in &candidates {
            let lib = match unsafe { Library::new(path) } {
                Ok(l) => l,
                Err(e) => {
                    errors.push(format!("{path}: {e}"));
                    continue;
                }
            };

            unsafe {
                let api_version = lib
                    .get::<FnMpvClientApiVersion>(b"mpv_client_api_version\0")
                    .map(|f| (*f)() as u64)
                    .unwrap_or(0);

                let mpv_create = *lib
                    .get::<FnMpvCreate>(b"mpv_create\0")
                    .map_err(|e| format!("missing mpv_create in {path}: {e}"))?;
                let mpv_initialize = *lib
                    .get::<FnMpvInitialize>(b"mpv_initialize\0")
                    .map_err(|e| format!("missing mpv_initialize in {path}: {e}"))?;
                let mpv_set_option_string = *lib
                    .get::<FnMpvSetOptionString>(b"mpv_set_option_string\0")
                    .map_err(|e| format!("missing mpv_set_option_string in {path}: {e}"))?;
                let mpv_set_property_string = *lib
                    .get::<FnMpvSetPropertyString>(b"mpv_set_property_string\0")
                    .map_err(|e| format!("missing mpv_set_property_string in {path}: {e}"))?;
                let mpv_get_property_string = *lib
                    .get::<FnMpvGetPropertyString>(b"mpv_get_property_string\0")
                    .map_err(|e| format!("missing mpv_get_property_string in {path}: {e}"))?;
                let mpv_free = *lib
                    .get::<FnMpvFree>(b"mpv_free\0")
                    .map_err(|e| format!("missing mpv_free in {path}: {e}"))?;
                let mpv_command = *lib
                    .get::<FnMpvCommand>(b"mpv_command\0")
                    .map_err(|e| format!("missing mpv_command in {path}: {e}"))?;
                let mpv_wait_event = *lib
                    .get::<FnMpvWaitEvent>(b"mpv_wait_event\0")
                    .map_err(|e| format!("missing mpv_wait_event in {path}: {e}"))?;
                let mpv_terminate_destroy = *lib
                    .get::<FnMpvTerminateDestroy>(b"mpv_terminate_destroy\0")
                    .map_err(|e| format!("missing mpv_terminate_destroy in {path}: {e}"))?;

                let mpv_render_context_create = *lib
                    .get::<FnMpvRenderContextCreate>(b"mpv_render_context_create\0")
                    .map_err(|e| format!("missing mpv_render_context_create in {path}: {e}"))?;
                let mpv_render_context_set_update_callback = lib
                    .get::<FnMpvRenderContextSetUpdateCallback>(
                        b"mpv_render_context_set_update_callback\0",
                    )
                    .ok()
                    .map(|s| *s);
                let mpv_render_context_update = lib
                    .get::<FnMpvRenderContextUpdate>(b"mpv_render_context_update\0")
                    .ok()
                    .map(|s| *s);
                let mpv_render_context_render = *lib
                    .get::<FnMpvRenderContextRender>(b"mpv_render_context_render\0")
                    .map_err(|e| format!("missing mpv_render_context_render in {path}: {e}"))?;
                let mpv_render_context_free = *lib
                    .get::<FnMpvRenderContextFree>(b"mpv_render_context_free\0")
                    .map_err(|e| format!("missing mpv_render_context_free in {path}: {e}"))?;

                return Ok(Arc::new(Self {
                    _lib: lib,
                    loaded_path: path.clone(),
                    api_version,
                    mpv_create,
                    mpv_initialize,
                    mpv_set_option_string,
                    mpv_set_property_string,
                    mpv_get_property_string,
                    mpv_free,
                    mpv_command,
                    mpv_wait_event,
                    mpv_terminate_destroy,
                    mpv_render_context_create,
                    mpv_render_context_set_update_callback,
                    mpv_render_context_update,
                    mpv_render_context_render,
                    mpv_render_context_free,
                }));
            }
        }

        Err(format!(
            "Could not dynamically load libmpv ({}). Install `libmpv2` / `mpv-libs` on Linux or place `libmpv-2.dll` beside the executable on Windows.",
            errors.join(" | ")
        ))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// High-Level Dual-Mode `MpvPlayer` Controller & Telemetry Snapshot
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MpvRenderMode {
    /// Zero-copy native GPU window (`vo=gpu`, `hwdec=auto-safe` / `d3d11va` / `videotoolbox`).
    GpuWindow,
    /// Inline embedded software renderer (`vo=libmpv`, `MPV_RENDER_API_TYPE_SW` -> `iced` RGBA image).
    InlineSoftware,
}

impl MpvRenderMode {
    pub fn label(self) -> &'static str {
        match self {
            Self::GpuWindow => "GPU Window (Zero-Copy HW)",
            Self::InlineSoftware => "Inline Canvas (SW Pipe)",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MpvTrackInfo {
    pub id: i64,
    pub track_type: String,
    pub title: String,
    pub lang: String,
    pub codec: String,
    pub selected: bool,
    pub external: bool,
    pub demux_w: Option<u32>,
    pub demux_h: Option<u32>,
}

impl MpvTrackInfo {
    pub fn display_label(&self) -> String {
        let mut parts = Vec::new();
        if !self.title.is_empty() {
            parts.push(self.title.clone());
        }
        if !self.lang.is_empty() {
            parts.push(format!("[{}]", self.lang.to_uppercase()));
        }
        if let (Some(w), Some(h)) = (self.demux_w, self.demux_h) {
            if h > 0 {
                parts.push(format!("{w}x{h}"));
            }
        }
        if !self.codec.is_empty() {
            parts.push(self.codec.to_uppercase());
        }
        if parts.is_empty() {
            format!("Track #{}", self.id)
        } else {
            parts.join(" · ")
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct MpvChapterInfo {
    pub index: usize,
    pub title: String,
    pub time_sec: f64,
}

#[derive(Debug, Clone, Default)]
pub struct MpvTelemetrySnapshot {
    pub position_sec: f64,
    pub duration_sec: f64,
    pub paused: bool,
    pub eof_reached: bool,
    pub volume: f64,
    pub muted: bool,
    pub audio_preset: String,
    pub speed: f64,
    pub hwdec_current: String,
    pub audio_tracks: Vec<MpvTrackInfo>,
    pub subtitle_tracks: Vec<MpvTrackInfo>,
    pub video_tracks: Vec<MpvTrackInfo>,
    pub chapters: Vec<MpvChapterInfo>,
    pub current_chapter: Option<usize>,
    pub window_closed_by_user: bool,
}

#[derive(Deserialize)]
struct RawMpvTrack {
    #[serde(default)]
    id: i64,
    #[serde(rename = "type", default)]
    track_type: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    lang: String,
    #[serde(default)]
    codec: String,
    #[serde(default)]
    selected: bool,
    #[serde(default)]
    external: bool,
    #[serde(rename = "demux-w", default)]
    demux_w: Option<u32>,
    #[serde(rename = "demux-h", default)]
    demux_h: Option<u32>,
}

#[derive(Deserialize)]
struct RawMpvChapter {
    #[serde(default)]
    title: String,
    #[serde(default)]
    time: f64,
}

#[derive(Clone, Default)]
pub struct LoadMediaRequest {
    pub url: String,
    pub title: String,
    pub headers: HashMap<String, String>,
    pub external_subtitles: Vec<(String, String)>, // (url, lang_or_label)
    pub start_position_sec: f64,
}

pub struct MpvPlayer {
    api: Arc<DynamicMpvApi>,
    handle: *mut MpvHandle,
    render_ctx: *mut MpvRenderContext,
    mode: MpvRenderMode,
    sw_dirty: Arc<AtomicBool>,
    sw_buffer: Vec<u8>,
    last_loaded: Option<LoadMediaRequest>,
    tracks_poll_counter: u32,
    active_audio_preset: String,
    cached_audio_tracks: Vec<MpvTrackInfo>,
    cached_sub_tracks: Vec<MpvTrackInfo>,
    cached_video_tracks: Vec<MpvTrackInfo>,
    cached_chapters: Vec<MpvChapterInfo>,
}

// `mpv_handle` is thread-safe per `mpv/client.h`, and `MpvPlayer` is owned exclusively by `App` on the main UI thread.
unsafe impl Send for MpvPlayer {}

impl MpvPlayer {
    pub fn new(api: Arc<DynamicMpvApi>, mode: MpvRenderMode) -> Result<Self, String> {
        ensure_c_numeric_locale();

        let handle = unsafe { (api.mpv_create)() };
        if handle.is_null() {
            return Err("mpv_create() returned NULL (check LC_NUMERIC=C and system audio/video libraries)".into());
        }

        let sw_dirty = Arc::new(AtomicBool::new(false));
        let mut player = Self {
            api,
            handle,
            render_ctx: ptr::null_mut(),
            mode,
            sw_dirty,
            sw_buffer: Vec::new(),
            last_loaded: None,
            tracks_poll_counter: 0,
            active_audio_preset: "flat".to_string(),
            cached_audio_tracks: Vec::new(),
            cached_sub_tracks: Vec::new(),
            cached_video_tracks: Vec::new(),
            cached_chapters: Vec::new(),
        };

        player.configure_pre_init(mode)?;
        let rc = unsafe { (player.api.mpv_initialize)(player.handle) };
        if rc < 0 {
            return Err(format!("mpv_initialize() failed with code {rc}"));
        }

        if mode == MpvRenderMode::InlineSoftware {
            player.init_software_render_context()?;
        }

        Ok(player)
    }

    fn configure_pre_init(&mut self, mode: MpvRenderMode) -> Result<(), String> {
        self.set_option("keep-open", "yes")?;
        self.set_option("ytdl", "no")?;
        self.set_option("cache", "yes")?;
        self.set_option("demuxer-max-bytes", "96MiB")?;
        self.set_option("demuxer-max-back-bytes", "32MiB")?;
        self.set_option("sub-auto", "fuzzy")?;
        self.set_option("sub-font-size", "42")?;
        self.set_option("volume-max", "300")?;
        self.set_option("audio-client-name", "BrootLite")?;
        self.set_option("title", "BrootLite — Hardware Video Player")?;

        match mode {
            MpvRenderMode::GpuWindow => {
                let _ = self.set_option("vo", "gpu");
                let _ = self.set_option("hwdec", DynamicMpvApi::default_hwdec());
                let _ = self.set_option("force-window", "immediate");
                let _ = self.set_option("osc", "yes");
                let _ = self.set_option("osd-bar", "yes");
                let _ = self.set_option("input-default-bindings", "yes");
                let _ = self.set_option("input-vo-keyboard", "yes");
                let _ = self.set_option("autofit-larger", "85%x85%");
            }
            MpvRenderMode::InlineSoftware => {
                self.set_option("vo", "libmpv")?;
                let _ = self.set_option("hwdec", "no");
                let _ = self.set_option("force-window", "no");
                let _ = self.set_option("osc", "no");
                let _ = self.set_option("video-timing-offset", "0");
            }
        }
        Ok(())
    }

    fn init_software_render_context(&mut self) -> Result<(), String> {
        let sw_api = CString::new("sw").unwrap();
        let mut params = [
            MpvRenderParam {
                param_type: MPV_RENDER_PARAM_API_TYPE,
                data: sw_api.as_ptr() as *mut c_void,
            },
            MpvRenderParam {
                param_type: MPV_RENDER_PARAM_INVALID,
                data: ptr::null_mut(),
            },
        ];

        let mut ctx_out: *mut MpvRenderContext = ptr::null_mut();
        let rc = unsafe {
            (self.api.mpv_render_context_create)(
                &mut ctx_out,
                self.handle,
                params.as_mut_ptr(),
            )
        };
        if rc < 0 || ctx_out.is_null() {
            return Err(format!(
                "mpv_render_context_create(MPV_RENDER_API_TYPE_SW) failed with code {rc}"
            ));
        }
        self.render_ctx = ctx_out;
        self.sw_buffer
            .resize((INLINE_SW_WIDTH * INLINE_SW_HEIGHT * 4) as usize, 0);

        if let Some(set_cb) = self.api.mpv_render_context_set_update_callback {
            let flag_ptr = Arc::as_ptr(&self.sw_dirty) as *mut c_void;
            unsafe {
                set_cb(
                    self.render_ctx,
                    Some(mpv_sw_frame_ready_callback),
                    flag_ptr,
                );
            }
        } else {
            self.sw_dirty.store(true, Ordering::Release);
        }
        Ok(())
    }

    pub fn mode(&self) -> MpvRenderMode {
        self.mode
    }

    pub fn api(&self) -> &Arc<DynamicMpvApi> {
        &self.api
    }

    /// Seamlessly switches between `GpuWindow` (`vo=gpu` + `VAAPI`/`NVDEC`/`D3D11VA`/`VideoToolbox`)
    /// and `InlineSoftware` (`MPV_RENDER_API_TYPE_SW` RGBA canvas), preserving current URL,
    /// HTTP headers, subtitles, and playback timestamp!
    pub fn switch_mode(&mut self, target_mode: MpvRenderMode) -> Result<(), String> {
        if self.mode == target_mode {
            return Ok(());
        }
        let resume_pos = self.get_f64_property("time-pos").unwrap_or(0.0);
        let was_paused = self.get_bool_property("pause").unwrap_or(false);
        let saved_req = self.last_loaded.clone();

        let mut replacement = MpvPlayer::new(self.api.clone(), target_mode)?;
        if let Some(mut req) = saved_req {
            if resume_pos > 1.0 {
                req.start_position_sec = resume_pos;
            }
            replacement.load_media(req)?;
            if was_paused {
                replacement.set_paused(true);
            }
        }
        *self = replacement;
        Ok(())
    }

    pub fn load_media(&mut self, req: LoadMediaRequest) -> Result<(), String> {
        // Apply custom per-stream HTTP headers (User-Agent, Referer, Authorization, Cookie, etc.)
        if req.headers.is_empty() {
            let _ = self.set_property("http-header-fields", "");
        } else {
            let mut fields = Vec::new();
            for (k, v) in &req.headers {
                let clean_k = k.trim();
                let clean_v = v.replace(['\r', '\n'], " ").trim().to_string();
                if clean_k.eq_ignore_ascii_case("user-agent") {
                    let _ = self.set_property("user-agent", &clean_v);
                } else if clean_k.eq_ignore_ascii_case("referer") {
                    let _ = self.set_property("referrer", &clean_v);
                }
                // Escape commas inside header values for mpv's string-list parser if needed
                fields.push(format!("{clean_k}: {}", clean_v.replace(',', "\\,")));
            }
            let joined = fields.join(",");
            let _ = self.set_property("http-header-fields", &joined);
        }

        if !req.title.is_empty() {
            let _ = self.set_property("force-media-title", &req.title);
            let _ = self.set_property("title", &format!("BrootLite — {}", req.title));
        }

        if req.start_position_sec > 1.0 {
            let _ = self.set_property("start", &format!("+{:.1}", req.start_position_sec));
        } else {
            let _ = self.set_property("start", "none");
        }

        self.command(&["loadfile", &req.url, "replace"])?;
        self.set_paused(false);

        // Reset cached tracks until MPV_EVENT_FILE_LOADED fires
        self.cached_audio_tracks.clear();
        self.cached_sub_tracks.clear();
        self.cached_video_tracks.clear();
        self.cached_chapters.clear();
        self.tracks_poll_counter = 0;

        // Queue external subtitles (will also re-attach on FILE_LOADED if needed)
        for (idx, (sub_url, lang)) in req.external_subtitles.iter().take(12).enumerate() {
            let flag = if idx == 0 { "auto" } else { "cached" };
            let _ = self.command(&["sub-add", sub_url, flag, lang]);
        }

        self.last_loaded = Some(req);
        Ok(())
    }

    pub fn add_external_subtitle(&self, sub_url: &str, title: &str, select_now: bool) {
        let mode = if select_now { "select" } else { "cached" };
        let _ = self.command(&["sub-add", sub_url, mode, title]);
    }

    pub fn toggle_pause(&self) {
        let paused = self.get_bool_property("pause").unwrap_or(false);
        self.set_paused(!paused);
    }

    pub fn set_paused(&self, paused: bool) {
        let _ = self.set_property("pause", if paused { "yes" } else { "no" });
    }

    pub fn seek_absolute(&self, seconds: f64) {
        let clamped = seconds.max(0.0);
        let _ = self.command(&["seek", &format!("{clamped:.2}"), "absolute"]);
    }

    pub fn seek_relative(&self, delta_sec: f64) {
        let _ = self.command(&["seek", &format!("{delta_sec:.2}"), "relative"]);
    }

    pub fn set_volume(&self, vol: f64) {
        let clamped = vol.clamp(0.0, 300.0);
        if clamped > 0.0 && self.get_bool_property("mute").unwrap_or(false) {
            let _ = self.set_property("mute", "no");
        }
        let _ = self.set_property("volume", &format!("{clamped:.0}"));
    }

    pub fn set_audio_preset(&mut self, preset_id: &str) {
        let (norm_id, af) = match preset_id {
            "projector" => (
                "projector",
                "lavfi=[bass=g=2:f=100,equalizer=f=1800:t=q:w=1.0:g=4,treble=g=2:f=7500,dynaudnorm=f=150:g=13:p=0.92]",
            ),
            "dialogue" => (
                "dialogue",
                "lavfi=[bass=g=-2:f=100,equalizer=f=1800:t=q:w=1.0:g=6,treble=g=2:f=7500,dynaudnorm=f=125:g=11:p=0.93]",
            ),
            "cinema" => (
                "cinema",
                "lavfi=[bass=g=6:f=100,equalizer=f=1800:t=q:w=1.0:g=1,treble=g=3:f=7500,alimiter=limit=0.95]",
            ),
            "night" => (
                "night",
                "lavfi=[bass=g=-3:f=100,equalizer=f=1800:t=q:w=1.0:g=5,treble=g=1:f=7500,acompressor=threshold=-22dB:ratio=3.5:makeup=4dB,alimiter=limit=0.93]",
            ),
            _ => ("flat", ""),
        };
        self.active_audio_preset = norm_id.to_string();
        let _ = self.set_property("af", af);
    }

    pub fn toggle_mute(&self) {
        let muted = self.get_bool_property("mute").unwrap_or(false);
        let _ = self.set_property("mute", if muted { "no" } else { "yes" });
    }

    pub fn set_speed(&self, speed: f64) {
        let clamped = speed.clamp(0.25, 3.0);
        let _ = self.set_property("speed", &format!("{clamped:.2}"));
    }

    pub fn toggle_fullscreen(&self) {
        let fs = self.get_bool_property("fullscreen").unwrap_or(false);
        let _ = self.set_property("fullscreen", if fs { "no" } else { "yes" });
    }

    pub fn select_audio_track(&mut self, id: i64) {
        let _ = self.set_property("aid", &id.to_string());
        self.refresh_tracks_and_chapters();
    }

    pub fn select_subtitle_track(&mut self, id: Option<i64>) {
        match id {
            Some(sid) => {
                let _ = self.set_property("sid", &sid.to_string());
            }
            None => {
                let _ = self.set_property("sid", "no");
            }
        }
        self.refresh_tracks_and_chapters();
    }

    pub fn select_video_track(&mut self, id: i64) {
        let _ = self.set_property("vid", &id.to_string());
        self.refresh_tracks_and_chapters();
    }

    pub fn select_chapter(&self, index: usize) {
        let _ = self.set_property("chapter", &index.to_string());
    }

    pub fn stop(&mut self) {
        let _ = self.command(&["stop"]);
        self.last_loaded = None;
    }

    /// Drains non-blocking `mpv_wait_event(0.0)` and reads live playback properties.
    pub fn poll_telemetry(&mut self) -> MpvTelemetrySnapshot {
        let mut window_closed_by_user = false;
        let mut file_just_loaded = false;

        // Drain up to 32 queued libmpv events so the event ringbuffer never saturates
        for _ in 0..32 {
            let ev_ptr = unsafe { (self.api.mpv_wait_event)(self.handle, 0.0) };
            if ev_ptr.is_null() {
                break;
            }
            let ev_id = unsafe { (*ev_ptr).event_id };
            if ev_id == MPV_EVENT_NONE {
                break;
            }
            match ev_id {
                MPV_EVENT_SHUTDOWN => {
                    window_closed_by_user = true;
                }
                MPV_EVENT_FILE_LOADED => {
                    file_just_loaded = true;
                    if let Some(req) = &self.last_loaded {
                        for (idx, (sub_url, lang)) in
                            req.external_subtitles.iter().take(12).enumerate()
                        {
                            let flag = if idx == 0 { "auto" } else { "cached" };
                            let _ = self.command(&["sub-add", sub_url, flag, lang]);
                        }
                    }
                }
                MPV_EVENT_END_FILE => {}
                _ => {}
            }
        }

        self.tracks_poll_counter = self.tracks_poll_counter.wrapping_add(1);
        if file_just_loaded || self.tracks_poll_counter % 8 == 1 {
            self.refresh_tracks_and_chapters();
        }

        let position_sec = self.get_f64_property("time-pos").unwrap_or(0.0).max(0.0);
        let duration_sec = self.get_f64_property("duration").unwrap_or(0.0).max(0.0);
        let paused = self.get_bool_property("pause").unwrap_or(false);
        let eof_reached = self.get_bool_property("eof-reached").unwrap_or(false);
        let volume = self.get_f64_property("volume").unwrap_or(100.0);
        let muted = self.get_bool_property("mute").unwrap_or(false);
        let speed = self.get_f64_property("speed").unwrap_or(1.0);
        let hwdec_current = self
            .get_string_property("hwdec-current")
            .filter(|s| !s.is_empty() && s != "no")
            .unwrap_or_else(|| {
                if self.mode == MpvRenderMode::GpuWindow {
                    DynamicMpvApi::default_hwdec().to_string()
                } else {
                    "sw".to_string()
                }
            });
        let current_chapter = self
            .get_string_property("chapter")
            .and_then(|s| s.parse::<isize>().ok())
            .filter(|&c| c >= 0)
            .map(|c| c as usize);

        MpvTelemetrySnapshot {
            position_sec,
            duration_sec,
            paused,
            eof_reached,
            volume,
            muted,
            audio_preset: self.active_audio_preset.clone(),
            speed,
            hwdec_current,
            audio_tracks: self.cached_audio_tracks.clone(),
            subtitle_tracks: self.cached_sub_tracks.clone(),
            video_tracks: self.cached_video_tracks.clone(),
            chapters: self.cached_chapters.clone(),
            current_chapter,
            window_closed_by_user,
        }
    }

    /// Pulls a fresh `iced::widget::image::Handle` RGBA frame ONLY when `InlineSoftware` mode
    /// is active and `libmpv` signals a new video frame (`MPV_RENDER_UPDATE_FRAME`).
    pub fn poll_inline_frame(&mut self) -> Option<ImageHandle> {
        if self.mode != MpvRenderMode::InlineSoftware || self.render_ctx.is_null() {
            return None;
        }

        if let Some(update_fn) = self.api.mpv_render_context_update {
            if !self.sw_dirty.swap(false, Ordering::AcqRel) {
                return None;
            }
            let flags = unsafe { update_fn(self.render_ctx) };
            if (flags & MPV_RENDER_UPDATE_FRAME) == 0 {
                return None;
            }
        }

        let mut dims: [c_int; 2] = [INLINE_SW_WIDTH as c_int, INLINE_SW_HEIGHT as c_int];
        let fmt = CString::new("rgb0").unwrap();
        let mut stride: usize = (INLINE_SW_WIDTH * 4) as usize;
        let mut block_for_target: c_int = 0;

        let expected_len = stride * (INLINE_SW_HEIGHT as usize);
        if self.sw_buffer.len() != expected_len {
            self.sw_buffer.resize(expected_len, 0);
        }

        let mut params = [
            MpvRenderParam {
                param_type: MPV_RENDER_PARAM_SW_SIZE,
                data: dims.as_mut_ptr() as *mut c_void,
            },
            MpvRenderParam {
                param_type: MPV_RENDER_PARAM_SW_FORMAT,
                data: fmt.as_ptr() as *mut c_void,
            },
            MpvRenderParam {
                param_type: MPV_RENDER_PARAM_SW_STRIDE,
                data: (&mut stride as *mut usize) as *mut c_void,
            },
            MpvRenderParam {
                param_type: MPV_RENDER_PARAM_SW_POINTER,
                data: self.sw_buffer.as_mut_ptr() as *mut c_void,
            },
            MpvRenderParam {
                param_type: MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME,
                data: (&mut block_for_target as *mut c_int) as *mut c_void,
            },
            MpvRenderParam {
                param_type: MPV_RENDER_PARAM_INVALID,
                data: ptr::null_mut(),
            },
        ];

        let rc =
            unsafe { (self.api.mpv_render_context_render)(self.render_ctx, params.as_mut_ptr()) };
        if rc < 0 {
            return None;
        }

        // `rgb0` leaves the 4th byte undefined; force opaque alpha (`0xFF`) for `iced` RGBA8.
        let mut rgba = self.sw_buffer.clone();
        for px in rgba.chunks_exact_mut(4) {
            px[3] = 0xFF;
        }
        Some(ImageHandle::from_rgba(
            INLINE_SW_WIDTH,
            INLINE_SW_HEIGHT,
            rgba,
        ))
    }

    fn refresh_tracks_and_chapters(&mut self) {
        if let Some(raw_json) = self.get_string_property("track-list") {
            if let Ok(parsed) = serde_json::from_str::<Vec<RawMpvTrack>>(&raw_json) {
                let mut audio = Vec::new();
                let mut subs = Vec::new();
                let mut video = Vec::new();
                for t in parsed {
                    let info = MpvTrackInfo {
                        id: t.id,
                        track_type: t.track_type.clone(),
                        title: t.title,
                        lang: t.lang,
                        codec: t.codec,
                        selected: t.selected,
                        external: t.external,
                        demux_w: t.demux_w,
                        demux_h: t.demux_h,
                    };
                    match t.track_type.as_str() {
                        "audio" => audio.push(info),
                        "sub" => subs.push(info),
                        "video" => video.push(info),
                        _ => {}
                    }
                }
                self.cached_audio_tracks = audio;
                self.cached_sub_tracks = subs;
                self.cached_video_tracks = video;
            }
        }

        if let Some(chap_json) = self.get_string_property("chapter-list") {
            if let Ok(parsed) = serde_json::from_str::<Vec<RawMpvChapter>>(&chap_json) {
                self.cached_chapters = parsed
                    .into_iter()
                    .enumerate()
                    .map(|(idx, c)| MpvChapterInfo {
                        index: idx,
                        title: if c.title.trim().is_empty() {
                            format!("Chapter {}", idx + 1)
                        } else {
                            c.title
                        },
                        time_sec: c.time.max(0.0),
                    })
                    .collect();
            }
        }
    }

    fn set_option(&self, name: &str, val: &str) -> Result<(), String> {
        let c_name = CString::new(name).map_err(|_| "invalid option name CString")?;
        let c_val = CString::new(val).map_err(|_| "invalid option val CString")?;
        let rc = unsafe {
            (self.api.mpv_set_option_string)(self.handle, c_name.as_ptr(), c_val.as_ptr())
        };
        if rc < 0 {
            Err(format!("mpv_set_option_string({name}={val}) -> {rc}"))
        } else {
            Ok(())
        }
    }

    fn set_property(&self, name: &str, val: &str) -> Result<(), String> {
        let c_name = CString::new(name).map_err(|_| "invalid prop name CString")?;
        let c_val = CString::new(val).map_err(|_| "invalid prop val CString")?;
        let rc = unsafe {
            (self.api.mpv_set_property_string)(self.handle, c_name.as_ptr(), c_val.as_ptr())
        };
        if rc < 0 {
            Err(format!("mpv_set_property_string({name}) -> {rc}"))
        } else {
            Ok(())
        }
    }

    pub fn get_string_property(&self, name: &str) -> Option<String> {
        let c_name = CString::new(name).ok()?;
        let raw = unsafe { (self.api.mpv_get_property_string)(self.handle, c_name.as_ptr()) };
        if raw.is_null() {
            return None;
        }
        let val = unsafe { CStr::from_ptr(raw).to_string_lossy().into_owned() };
        unsafe {
            (self.api.mpv_free)(raw as *mut c_void);
        }
        Some(val)
    }

    fn get_f64_property(&self, name: &str) -> Option<f64> {
        self.get_string_property(name)?.trim().parse::<f64>().ok()
    }

    fn get_bool_property(&self, name: &str) -> Option<bool> {
        match self.get_string_property(name)?.trim() {
            "yes" | "true" | "1" => Some(true),
            "no" | "false" | "0" => Some(false),
            _ => None,
        }
    }

    fn command(&self, args: &[&str]) -> Result<(), String> {
        let c_strings: Vec<CString> = args
            .iter()
            .map(|s| CString::new(*s).unwrap_or_else(|_| CString::new("").unwrap()))
            .collect();
        let mut ptrs: Vec<*const c_char> = c_strings.iter().map(|cs| cs.as_ptr()).collect();
        ptrs.push(ptr::null());
        let rc = unsafe { (self.api.mpv_command)(self.handle, ptrs.as_ptr()) };
        if rc < 0 {
            Err(format!("mpv_command({:?}) -> {rc}", args.first()))
        } else {
            Ok(())
        }
    }
}

impl Drop for MpvPlayer {
    fn drop(&mut self) {
        unsafe {
            if !self.render_ctx.is_null() {
                if let Some(set_cb) = self.api.mpv_render_context_set_update_callback {
                    set_cb(self.render_ctx, None, ptr::null_mut());
                }
                (self.api.mpv_render_context_free)(self.render_ctx);
                self.render_ctx = ptr::null_mut();
            }
            if !self.handle.is_null() {
                (self.api.mpv_terminate_destroy)(self.handle);
                self.handle = ptr::null_mut();
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn candidate_paths_are_non_empty_and_stride_is_64_byte_aligned() {
        let paths = DynamicMpvApi::candidate_library_paths();
        assert!(!paths.is_empty());
        assert_eq!((INLINE_SW_WIDTH * 4) % 64, 0);
    }

    #[test]
    fn dynamic_libmpv_loads_and_initializes_sw_render_context_when_installed() {
        if let Ok(api) = DynamicMpvApi::load() {
            assert!(api.api_version > 0);
            let mut player = MpvPlayer::new(api, MpvRenderMode::InlineSoftware)
                .expect("InlineSoftware MpvPlayer should initialize cleanly without GUI window");
            player.set_volume(85.0);
            let snap = player.poll_telemetry();
            assert!((snap.volume - 85.0).abs() < 1.0);
        }
    }
}
