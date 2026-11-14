#![cfg_attr(all(target_os = "windows", not(debug_assertions)), windows_subsystem = "windows")]

//! **BrootLite Desktop (`v2.1.0`)** — Ultra-low-RAM native Linux & Windows desktop application.
//!
//! Powered directly by `brootlite-core` (`rlib`) + `iced 0.13` GPU UI (Wayland / X11 Vulkan & OpenGL
//! on Linux, DirectX 12 / Vulkan on Windows) + zero-header dynamic `libmpv` hardware video playback.

pub mod app;
pub mod mpv_player;
pub mod poster_cache;
pub mod state;
pub mod theme;
pub mod views;

use app::BrootLiteApp;
use iced::Size;
use theme::{
    FONT_REGULAR, GEIST_BOLD_BYTES, GEIST_MONO_BYTES, GEIST_REGULAR_BYTES, GEIST_SEMIBOLD_BYTES,
};

fn main() -> iced::Result {
    iced::application(BrootLiteApp::title, BrootLiteApp::update, BrootLiteApp::view)
        .subscription(BrootLiteApp::subscription)
        .theme(BrootLiteApp::theme)
        .font(GEIST_REGULAR_BYTES)
        .font(GEIST_SEMIBOLD_BYTES)
        .font(GEIST_BOLD_BYTES)
        .font(GEIST_MONO_BYTES)
        .default_font(FONT_REGULAR)
        .window(iced::window::Settings {
            size: Size::new(1420.0, 880.0),
            icon: iced::window::icon::from_file_data(
                include_bytes!("../../branding/icon-256.png"),
                None,
            )
            .ok(),
            ..Default::default()
        })
        .antialiasing(true)
        .run_with(BrootLiteApp::boot)
}

#[cfg(test)]
mod tests {
    use super::*;
    use brootlite_core::binge::StreamSimilarityMatcher;
    use brootlite_core::models::{DiscoverSortMode, MetaItem, StreamCandidate, VideoEpisode};

    #[test]
    fn bundled_geist_fonts_are_non_empty() {
        assert!(GEIST_REGULAR_BYTES.len() > 50_000);
        assert!(GEIST_SEMIBOLD_BYTES.len() > 50_000);
        assert!(GEIST_BOLD_BYTES.len() > 50_000);
        assert!(GEIST_MONO_BYTES.len() > 50_000);
    }

    #[test]
    fn discover_sort_modes_order_catalog_items_deterministically() {
        let mut items = vec![
            MetaItem {
                id: "tt1".into(),
                name: "Zeta".into(),
                imdb_rating: "7.2".into(),
                release_info: "2021".into(),
                ..Default::default()
            },
            MetaItem {
                id: "tt2".into(),
                name: "Alpha".into(),
                imdb_rating: "9.1".into(),
                release_info: "2025".into(),
                ..Default::default()
            },
        ];

        state::apply_discover_sort(&mut items, DiscoverSortMode::TopRated);
        assert_eq!(items[0].id, "tt2");

        state::apply_discover_sort(&mut items, DiscoverSortMode::TitleAsc);
        assert_eq!(items[0].name, "Alpha");
    }

    #[test]
    fn series_binge_matcher_finds_next_episode_and_preserves_release_group() {
        let eps = vec![
            VideoEpisode {
                id: "tt99:0:1".into(),
                title: "Special".into(),
                season: 0,
                episode: 1,
                ..Default::default()
            },
            VideoEpisode {
                id: "tt99:1:1".into(),
                title: "Pilot".into(),
                season: 1,
                episode: 1,
                ..Default::default()
            },
            VideoEpisode {
                id: "tt99:1:2".into(),
                title: "Part Two".into(),
                season: 1,
                episode: 2,
                ..Default::default()
            },
        ];

        let next = StreamSimilarityMatcher::find_next_episode(&eps, "tt99:1:1")
            .expect("should resolve S01E02");
        assert_eq!(next.id, "tt99:1:2");

        let prev_stream = StreamCandidate {
            title: "[SubsPlease] Solo Leveling - 01 (1080p) [ABCD].mkv".into(),
            addon_name: "CommunityAddon".into(),
            info_hash: Some("0123456789abcdef0123456789abcdef01234567".into()),
            resolution_score: 1080,
            seeders: 250,
            ..Default::default()
        };
        let ctx = StreamSimilarityMatcher::build_context_from_stream(&prev_stream);

        let next_cands = vec![
            StreamCandidate {
                title: "[Erai-raws] Solo Leveling - 02 [720p].mkv".into(),
                addon_name: "CustomRSS".into(),
                info_hash: Some("1111111111111111111111111111111111111111".into()),
                resolution_score: 720,
                seeders: 500,
                ..Default::default()
            },
            StreamCandidate {
                title: "[SubsPlease] Solo Leveling - 02 (1080p) [EF01].mkv".into(),
                addon_name: "CommunityAddon".into(),
                info_hash: Some("2222222222222222222222222222222222222222".into()),
                resolution_score: 1080,
                seeders: 180,
                ..Default::default()
            },
        ];
        let best = StreamSimilarityMatcher::pick_best_next_episode_stream(&ctx, next_cands)
            .expect("should match SubsPlease 1080p");
        assert!(best.candidate.title.contains("[SubsPlease]"));
    }

    #[test]
    fn qr_code_generator_handles_multipass_optical_pk_fragment() {
        let url = "https://leeloo.example.com/networks?pair=ABCD-1234#pk=BAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        assert!(state::make_qr_data(url).is_some());
    }
}
