//! # `brootlite-core`
//!
//! Ultra-lightweight shared Rust core engine for **BrootLite** across:
//! - **macOS** (`macos/` Native Swift 6 + Metal `libmpv`, via `staticlib`/`cdylib` C ABI in `ffi.rs`)
//! - **Linux** (`desktop/` Native Rust `iced` GPU + `libmpv`, via direct `rlib` crate import)
//! - **Windows** (via direct `rlib` or `cdylib`)

pub mod binge;
pub mod config;
pub mod creators;
pub mod engine;
pub mod error;
pub mod ffi;
pub mod jellyfin;
pub mod keystore;
pub mod kodi;
pub mod models;
pub mod multipass;
pub mod pack;
pub mod stremio;
pub mod swarm;
pub mod torrent;
pub mod youtube;

pub use engine::BrootLiteEngine;
pub use error::{CoreError, CoreResult};

pub fn core_version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}
