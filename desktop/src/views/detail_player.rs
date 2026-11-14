//! Detail View & Active Hardware Player Controller (with Series Binge Autoplay Card & Movie Credits Gallery).

use crate::mpv_player::MpvRenderMode;
use crate::poster_cache::PosterLruCache;
use crate::state::{
    format_seconds_hms, format_signed_delta_sec, ActivePlayerState, BingeNextCardState,
    DetailStreamFilter, DetailViewState, Message, PlaybackSourceKind,
};
use crate::theme::{
    accent_indigo_button_style, active_cyan_card_style, cyan_progress_style, danger_button_style,
    glass_card_style, indigo_banner_style, pill_button_style, poster_placeholder_style,
    primary_cyan_button_style, ACCENT_AMBER, ACCENT_CYAN, ACCENT_EMERALD, ACCENT_INDIGO, FONT_BOLD,
    FONT_MONO, FONT_SEMIBOLD, TEXT_DIM, TEXT_PRIMARY, TEXT_SECONDARY,
};
use crate::views::components::{
    creator_video_card, poster_card, section_header, status_pill, stream_candidate_row,
    truncate_label,
};
use brootlite_core::jellyfin::JellyfinIds;
use brootlite_core::models::RankedStreamCandidate;
use iced::widget::{
    button, column, container, image, progress_bar, row, scrollable, slider, text, Space,
};
use iced::{Alignment, ContentFit, Element, Length};
use std::collections::{BTreeSet, HashMap};

pub fn view_detail<'a>(
    detail: &'a DetailViewState,
    is_favorite: bool,
    posters: &'a PosterLruCache,
) -> Element<'a, Message> {
    let meta = &detail.meta;
    let is_jf = JellyfinIds::is_jellyfin_id(&meta.id);

    // Poster artwork
    let poster_box: Element<'a, Message> = if let Some(handle) = posters.peek(&meta.poster) {
        container(
            image(handle.clone())
                .width(Length::Fixed(210.0))
                .height(Length::Fixed(310.0))
                .content_fit(ContentFit::Cover),
        )
        .width(Length::Fixed(210.0))
        .height(Length::Fixed(310.0))
        .style(poster_placeholder_style)
        .into()
    } else {
        container(
            column![
                Space::with_height(Length::Fill),
                text(meta.media_type.to_uppercase())
                    .size(16)
                    .font(FONT_BOLD)
                    .color(ACCENT_CYAN),
                Space::with_height(Length::Fill),
            ]
            .align_x(Alignment::Center)
            .width(Length::Fill),
        )
        .width(Length::Fixed(210.0))
        .height(Length::Fixed(310.0))
        .style(poster_placeholder_style)
        .into()
    };

    // Badges row
    let mut badges = row![status_pill(meta.media_type.to_uppercase(), ACCENT_CYAN)]
        .spacing(8)
        .align_y(Alignment::Center);
    if !meta.imdb_rating.trim().is_empty() {
        badges = badges.push(status_pill(
            format!("★ IMDb {}", meta.imdb_rating.trim()),
            ACCENT_AMBER,
        ));
    }
    if !meta.release_info.trim().is_empty() {
        badges = badges.push(status_pill(meta.release_info.clone(), TEXT_SECONDARY));
    }
    if !meta.runtime.trim().is_empty() {
        badges = badges.push(status_pill(meta.runtime.clone(), TEXT_SECONDARY));
    }
    for g in meta.genres.iter().take(5) {
        badges = badges.push(status_pill(g.clone(), ACCENT_INDIGO));
    }

    // Action buttons
    let mut actions = row![
        button(text("← Back").size(13).font(FONT_SEMIBOLD))
            .padding([8, 16])
            .style(pill_button_style(false))
            .on_press(Message::CloseOverlay),
    ]
    .spacing(10)
    .align_y(Alignment::Center);

    if is_jf {
        if let Some((srv_id, _)) = JellyfinIds::parse_id(&meta.id) {
            let play_vid = if detail.selected_video_id.is_empty() {
                meta.id.clone()
            } else {
                detail.selected_video_id.clone()
            };
            actions = actions.push(
                button(
                    text("▶ DirectPlay / Stream from Jellyfin")
                        .size(13)
                        .font(FONT_SEMIBOLD),
                )
                .padding([9, 18])
                .style(primary_cyan_button_style)
                .on_press(Message::PlayJellyfinItem {
                    server_id: srv_id,
                    meta: meta.clone(),
                    video_id: play_vid,
                    episode_title: detail.selected_episode_title.clone(),
                    resume_ms: detail.resume_position_ms,
                }),
            );
        }
    } else if !detail.streams.is_empty() {
        actions = actions.push(
            button(
                text("▶ Quick Play Best Matched Stream")
                    .size(13)
                    .font(FONT_SEMIBOLD),
            )
            .padding([9, 18])
            .style(primary_cyan_button_style)
            .on_press(Message::DetailQuickPlayBest),
        );
    }

    actions = actions.push(
        button(
            text(if is_favorite {
                "♥ Saved in Library"
            } else {
                "♡ Add to Library"
            })
            .size(13)
            .font(FONT_SEMIBOLD),
        )
        .padding([8, 16])
        .style(pill_button_style(is_favorite))
        .on_press(Message::ToggleFavoriteMeta(meta.clone())),
    );

    let hero_info = column![
        badges,
        text(&meta.name).size(28).font(FONT_BOLD).color(TEXT_PRIMARY),
        if !detail.selected_episode_title.is_empty() {
            Element::from(
                text(format!("Selected Episode: {}", detail.selected_episode_title))
                    .size(14)
                    .font(FONT_SEMIBOLD)
                    .color(ACCENT_CYAN),
            )
        } else {
            Element::from(Space::with_height(Length::Shrink))
        },
        text(if meta.description.trim().is_empty() {
            "Select an episode or stream below to begin hardware-accelerated playback."
        } else {
            &meta.description
        })
        .size(14)
        .color(TEXT_SECONDARY),
        Space::with_height(Length::Fixed(6.0)),
        actions,
    ]
    .spacing(10)
    .width(Length::Fill);

    let hero_card = container(row![poster_box, hero_info].spacing(22))
        .padding(18)
        .width(Length::Fill)
        .style(glass_card_style);

    let mut col = column![hero_card].spacing(16);

    // ── Season & Episode Selector for Series / Anime ─────────────────────────
    if !meta.videos.is_empty() {
        let seasons: BTreeSet<i32> = meta.videos.iter().map(|v| v.season).collect();
        let mut season_strip = row![text("Season:")
            .size(12)
            .font(FONT_MONO)
            .color(TEXT_DIM)]
        .spacing(8)
        .align_y(Alignment::Center);

        for s in seasons {
            let lbl = if s == 0 {
                "Specials".to_string()
            } else {
                format!("Season {s}")
            };
            season_strip = season_strip.push(
                button(text(lbl).size(12).font(FONT_SEMIBOLD))
                    .padding([5, 12])
                    .style(pill_button_style(detail.selected_season == s))
                    .on_press(Message::DetailSelectSeason(s)),
            );
        }

        let mut ep_strip = row![].spacing(10);
        for ep in meta
            .videos
            .iter()
            .filter(|e| e.season == detail.selected_season)
        {
            let active = ep.id == detail.selected_video_id;
            let ep_label = format!("E{:02} · {}", ep.episode, truncate_label(&ep.title, 26));
            ep_strip = ep_strip.push(
                button(
                    column![
                        text(ep_label)
                            .size(12)
                            .font(FONT_SEMIBOLD)
                            .color(if active { ACCENT_CYAN } else { TEXT_PRIMARY }),
                        text(truncate_label(&ep.overview, 34))
                            .size(11)
                            .color(TEXT_SECONDARY),
                    ]
                    .spacing(4)
                    .width(Length::Fixed(220.0)),
                )
                .padding(10)
                .style(pill_button_style(active))
                .on_press(Message::DetailSelectEpisode(ep.clone())),
            );
        }

        col = col.push(
            container(
                column![
                    scrollable(season_strip).direction(scrollable::Direction::Horizontal(
                        scrollable::Scrollbar::new().width(4).scroller_width(4),
                    )),
                    scrollable(ep_strip).direction(scrollable::Direction::Horizontal(
                        scrollable::Scrollbar::new().width(5).scroller_width(5),
                    )),
                ]
                .spacing(10),
            )
            .padding(14)
            .width(Length::Fill)
            .style(glass_card_style),
        );
    }

    // ── Stream Candidates List (`All · Direct/Mirrors · P2P Swarm`) ──────────
    if !is_jf {
        let filter_header = row![
            section_header(
                "Available Streams",
                if detail.loading_streams {
                    "Querying installed Stremio stream addons + P2P Swarm..."
                } else {
                    "Sorted by Binge Release-Group Similarity, Resolution, & Seeder Health"
                }
            ),
            Space::with_width(Length::Fill),
            button(text("All").size(12))
                .padding([5, 12])
                .style(pill_button_style(
                    detail.stream_filter == DetailStreamFilter::All
                ))
                .on_press(Message::DetailSelectStreamFilter(DetailStreamFilter::All)),
            button(text("⚡ Direct / Mirrors").size(12))
                .padding([5, 12])
                .style(pill_button_style(
                    detail.stream_filter == DetailStreamFilter::DirectMirrors
                ))
                .on_press(Message::DetailSelectStreamFilter(
                    DetailStreamFilter::DirectMirrors
                )),
            button(text("🧲 P2P Swarm").size(12))
                .padding([5, 12])
                .style(pill_button_style(
                    detail.stream_filter == DetailStreamFilter::P2PSwarm
                ))
                .on_press(Message::DetailSelectStreamFilter(
                    DetailStreamFilter::P2PSwarm
                )),
        ]
        .spacing(8)
        .align_y(Alignment::Center);

        col = col.push(filter_header);

        let ranked_lookup: Vec<(&brootlite_core::models::StreamCandidate, Option<&RankedStreamCandidate>)> =
            if !detail.ranked_streams.is_empty() {
                detail
                    .ranked_streams
                    .iter()
                    .map(|rk| (&rk.candidate, Some(rk)))
                    .collect()
            } else {
                detail.streams.iter().map(|c| (c, None)).collect()
            };

        let filtered: Vec<_> = ranked_lookup
            .into_iter()
            .filter(|(c, _)| match detail.stream_filter {
                DetailStreamFilter::All => true,
                DetailStreamFilter::DirectMirrors => c.is_direct_http(),
                DetailStreamFilter::P2PSwarm => c.is_p2p(),
            })
            .collect();

        if filtered.is_empty() {
            col = col.push(
                container(
                    text(if detail.loading_streams {
                        "Resolving streams..."
                    } else {
                        "No streams matched this filter. Install a stream addon in Passport ↗ or try P2P Swarm."
                    })
                    .size(13)
                    .color(TEXT_SECONDARY),
                )
                .padding(20)
                .width(Length::Fill)
                .style(glass_card_style),
            );
        } else {
            let mut stream_col = column![].spacing(8);
            let vid = if detail.selected_video_id.is_empty() {
                meta.id.clone()
            } else {
                detail.selected_video_id.clone()
            };
            for (cand, rk_opt) in filtered.into_iter().take(250) {
                stream_col = stream_col.push(stream_candidate_row(
                    cand,
                    rk_opt,
                    Some(meta.clone()),
                    Some(vid.clone()),
                    Some(detail.selected_episode_title.clone()),
                    detail.resume_position_ms,
                ));
            }
            col = col.push(stream_col);
        }
    }

    scrollable(col.padding([16, 22])).height(Length::Fill).into()
}

pub fn view_player<'a>(
    player_state: &'a ActivePlayerState,
    render_mode: MpvRenderMode,
    mpv_available: bool,
    posters: &'a PosterLruCache,
    dearrow_enabled: bool,
    dearrow_map: &'a HashMap<String, String>,
) -> Element<'a, Message> {
    let tel = &player_state.telemetry;
    let display_title = player_state
        .dearrow_title
        .as_deref()
        .filter(|s| !s.is_empty())
        .unwrap_or(&player_state.title);

    // 1. Top Player Header Bar
    let top_bar = container(
        row![
            button(text("← Browse While Playing").size(12).font(FONT_SEMIBOLD))
                .padding([7, 14])
                .style(pill_button_style(false))
                .on_press(Message::CloseOverlay),
            column![
                text(truncate_label(display_title, 68))
                    .size(16)
                    .font(FONT_BOLD)
                    .color(TEXT_PRIMARY),
                text(truncate_label(&player_state.subtitle, 84))
                    .size(12)
                    .color(TEXT_SECONDARY),
            ]
            .spacing(2)
            .width(Length::Fill),
            button(
                text(if render_mode == MpvRenderMode::GpuWindow {
                    "⚡ GPU Window (Zero-Copy HW)"
                } else {
                    "○ Switch to GPU Window"
                })
                .size(11)
                .font(FONT_MONO)
            )
            .padding([6, 12])
            .style(pill_button_style(render_mode == MpvRenderMode::GpuWindow))
            .on_press(Message::PlayerSwitchRenderMode(MpvRenderMode::GpuWindow)),
            button(
                text(if render_mode == MpvRenderMode::InlineSoftware {
                    "🖼 Inline Canvas (SW)"
                } else {
                    "○ Inline Canvas (SW)"
                })
                .size(11)
                .font(FONT_MONO)
            )
            .padding([6, 12])
            .style(pill_button_style(
                render_mode == MpvRenderMode::InlineSoftware
            ))
            .on_press(Message::PlayerSwitchRenderMode(
                MpvRenderMode::InlineSoftware
            )),
            button(text("⏹ Stop & Close").size(12).font(FONT_SEMIBOLD))
                .padding([7, 14])
                .style(danger_button_style)
                .on_press(Message::PlayerStopAndClose),
        ]
        .spacing(10)
        .align_y(Alignment::Center),
    )
    .padding(12)
    .width(Length::Fill)
    .style(glass_card_style);

    // 2. Dual-Mode Video Viewport + Bottom-Right Series Binge Card Overlay Row
    let viewport_left: Element<'a, Message> = if render_mode == MpvRenderMode::InlineSoftware {
        if let Some(frame_handle) = &player_state.inline_frame {
            container(
                image(frame_handle.clone())
                    .width(Length::Fill)
                    .height(Length::Fixed(390.0))
                    .content_fit(ContentFit::Contain),
            )
            .width(Length::Fill)
            .height(Length::Fixed(390.0))
            .style(glass_card_style)
            .into()
        } else {
            container(
                column![
                    Space::with_height(Length::Fill),
                    text("Initializing Inline libmpv Software Render Surface (960×540 RGBA)...")
                        .size(14)
                        .color(TEXT_SECONDARY),
                    Space::with_height(Length::Fill),
                ]
                .align_x(Alignment::Center)
                .width(Length::Fill),
            )
            .width(Length::Fill)
            .height(Length::Fixed(390.0))
            .style(glass_card_style)
            .into()
        }
    } else {
        // Zero-Copy GPU Window Telemetry Deck
        let mut ryd_row = row![
            status_pill(
                format!("HWDEC: {}", tel.hwdec_current.to_uppercase()),
                ACCENT_EMERALD
            ),
            status_pill("ZERO-COPY GPU VO".into(), ACCENT_CYAN),
        ]
        .spacing(8)
        .align_y(Alignment::Center);

        if let Some(ryd) = &player_state.ryd_votes {
            ryd_row = ryd_row.push(status_pill(
                format!("👍 {}  ·  👎 {}", ryd.likes, ryd.dislikes),
                ACCENT_INDIGO,
            ));
        }
        if player_state.dearrow_title.is_some() {
            ryd_row = ryd_row.push(status_pill("DeArrow Clean Title".into(), ACCENT_CYAN));
        }

        container(
            column![
                ryd_row,
                text(if mpv_available {
                    "Hardware Video Surface Active in Native libmpv GPU Window"
                } else {
                    "libmpv shared library not detected — install `libmpv2` (Linux) or `libmpv-2.dll` (Windows)"
                })
                .size(18)
                .font(FONT_BOLD)
                .color(TEXT_PRIMARY),
                text("Wayland / X11 VAAPI & NVDEC · Windows D3D11VA · macOS VideoToolbox — synced two-way with this Control Deck.")
                    .size(13)
                    .color(TEXT_SECONDARY),
                row![
                    button(text("⛶ Toggle GPU Window Fullscreen (F)").size(12).font(FONT_SEMIBOLD))
                        .padding([7, 14])
                        .style(primary_cyan_button_style)
                        .on_press(Message::PlayerToggleFullscreen),
                    button(text("✨ Next Recommended / Credits").size(12))
                        .padding([7, 14])
                        .style(pill_button_style(player_state.show_credits_drawer))
                        .on_press(Message::PlayerToggleCreditsDrawer),
                ]
                .spacing(10),
            ]
            .spacing(12),
        )
        .padding(20)
        .width(Length::Fill)
        .style(glass_card_style)
        .into()
    };

    let viewport_and_binge: Element<'a, Message> = if let Some(binge) = &player_state.binge_card {
        if !binge.dismissed {
            row![viewport_left, render_binge_next_card(binge)]
                .spacing(14)
                .align_y(Alignment::End)
                .into()
        } else {
            viewport_left
        }
    } else {
        viewport_left
    };

    let mut col = column![top_bar, viewport_and_binge].spacing(12);

    // 3. Skip Intro / SponsorBlock Undo Banners
    if let Some(seg) = &player_state.active_skip_prompt {
        col = col.push(
            container(
                row![
                    status_pill("SKIP SEGMENT DETECTED".into(), ACCENT_CYAN),
                    text(format!("{} available right now", seg.label))
                        .size(14)
                        .font(FONT_SEMIBOLD)
                        .color(TEXT_PRIMARY),
                    Space::with_width(Length::Fill),
                    button(
                        text(format!("{} ⏭", seg.label))
                            .size(13)
                            .font(FONT_BOLD)
                    )
                    .padding([8, 18])
                    .style(primary_cyan_button_style)
                    .on_press(Message::PlayerConfirmSkipSegment(seg.clone())),
                ]
                .spacing(12)
                .align_y(Alignment::Center),
            )
            .padding(12)
            .width(Length::Fill)
            .style(active_cyan_card_style),
        );
    }

    if let Some((seg_label, prev_sec)) = &player_state.undo_skip_target {
        col = col.push(
            container(
                row![
                    status_pill("SPONSORBLOCK AUTO-SKIPPED".into(), ACCENT_INDIGO),
                    text(format!(
                        "Skipped {} (from {})",
                        seg_label,
                        format_seconds_hms(*prev_sec)
                    ))
                    .size(13)
                    .color(TEXT_PRIMARY),
                    Space::with_width(Length::Fill),
                    button(text("↩ Undo Skip").size(12).font(FONT_SEMIBOLD))
                        .padding([6, 14])
                        .style(accent_indigo_button_style)
                        .on_press(Message::PlayerUndoSponsorSkip),
                ]
                .spacing(12)
                .align_y(Alignment::Center),
            )
            .padding(10)
            .width(Length::Fill)
            .style(indigo_banner_style),
        );
    }

    // 4. Live P2P Torrent Swarm Telemetry Strip
    if let Some(stats) = &player_state.swarm_stats {
        let buf_pct = if stats.total_bytes > 0 {
            ((stats.progress_bytes as f64 / stats.total_bytes as f64) * 100.0).clamp(0.0, 100.0)
                as f32
        } else {
            0.0
        };
        col = col.push(
            container(
                column![
                    row![
                        status_pill("🧲 P2P SWARM TELEMETRY".into(), ACCENT_EMERALD),
                        text(&stats.formatted_overlay)
                            .size(13)
                            .font(FONT_MONO)
                            .color(ACCENT_CYAN),
                        Space::with_width(Length::Fill),
                        text(format!("Peers: {}  ·  {:.1} Mbps", stats.live_peers, stats.download_speed_mbps))
                            .size(12)
                            .font(FONT_MONO)
                            .color(TEXT_SECONDARY),
                    ]
                    .spacing(10)
                    .align_y(Alignment::Center),
                    progress_bar(0.0..=100.0, buf_pct)
                        .height(Length::Fixed(4.0))
                        .style(cyan_progress_style),
                ]
                .spacing(6),
            )
            .padding(10)
            .width(Length::Fill)
            .style(glass_card_style),
        );
    }

    // 5. Scrub Bar + Transport + Audio / Subtitle / Quality / Chapter Controls
    let dur_max = tel.duration_sec.max(1.0) as f32;
    let display_sec = player_state
        .effective_display_position_sec()
        .clamp(0.0, tel.duration_sec.max(1.0));
    let cur_pos = (display_sec as f32).clamp(0.0, dur_max);

    let scrub_row = container(
        row![
            text(format_seconds_hms(display_sec))
                .size(12)
                .font(FONT_MONO)
                .color(if player_state.is_scrubbing {
                    ACCENT_AMBER
                } else {
                    ACCENT_CYAN
                }),
            slider(0.0..=dur_max, cur_pos, Message::PlayerScrubPreview)
                .on_release(Message::PlayerScrubCommit)
                .step(0.25)
                .height(26.0)
                .width(Length::Fill),
            text(format_seconds_hms(tel.duration_sec))
                .size(12)
                .font(FONT_MONO)
                .color(TEXT_SECONDARY),
        ]
        .spacing(12)
        .align_y(Alignment::Center),
    )
    .padding([4, 0])
    .width(Length::Fill);

    let mut transport = row![
        button(text("⏮ -60s").size(12))
            .padding([6, 11])
            .style(pill_button_style(false))
            .on_press(Message::PlayerSeekRelative(-60.0)),
        button(text("⏪ -10s").size(12))
            .padding([6, 11])
            .style(pill_button_style(false))
            .on_press(Message::PlayerStepKeyboardScrub(-1.0)),
        button(
            text(if tel.paused { "▶ Play" } else { "⏸ Pause" })
                .size(13)
                .font(FONT_BOLD)
        )
        .padding([7, 20])
        .style(primary_cyan_button_style)
        .on_press(Message::PlayerTogglePause),
        button(text("+10s ⏩").size(12))
            .padding([6, 11])
            .style(pill_button_style(false))
            .on_press(Message::PlayerStepKeyboardScrub(1.0)),
        button(text("+60s ⏭").size(12))
            .padding([6, 11])
            .style(pill_button_style(false))
            .on_press(Message::PlayerSeekRelative(60.0)),
        Space::with_width(Length::Fixed(10.0)),
        button(
            text(if tel.muted { "🔇 Muted" } else { "🔊 Vol" })
                .size(12)
                .font(FONT_MONO)
        )
        .padding([6, 10])
        .style(pill_button_style(tel.muted))
        .on_press(Message::PlayerToggleMute),
        slider(0.0..=130.0, tel.volume as f32, Message::PlayerVolumeChanged)
            .width(Length::Fixed(110.0)),
        Space::with_width(Length::Fill),
        text("Speed:").size(11).font(FONT_MONO).color(TEXT_DIM),
    ]
    .spacing(8)
    .align_y(Alignment::Center);

    for spd in [0.75, 1.0, 1.25, 1.5, 2.0] {
        let active = (tel.speed - spd).abs() < 0.05;
        transport = transport.push(
            button(text(format!("{spd:.2}x")).size(11).font(FONT_MONO))
                .padding([4, 8])
                .style(pill_button_style(active))
                .on_press(Message::PlayerSetSpeed(spd)),
        );
    }

    // Audio, Subtitle, Quality, & Chapter strips (+ live Scrub Preview pill when scrubbing/seeking)
    let mut tracks_col = column![].spacing(10);
    if player_state.is_scrubbing || player_state.pending_seek_target_sec.is_some() {
        let delta = display_sec - player_state.scrub_origin_sec;
        let dir_icon = if delta >= 0.0 { "▶▶" } else { "◀◀" };
        let chapter_suffix = tel
            .chapters
            .iter()
            .rfind(|c| c.time_sec <= display_sec + 0.5)
            .map(|c| format!("  ·  {}", truncate_label(&c.title, 28)))
            .unwrap_or_default();

        let badge_label = if player_state.is_scrubbing {
            "SCRUB PREVIEW"
        } else {
            "SEEKING..."
        };

        let mut preview_row = row![
            status_pill(badge_label.into(), ACCENT_CYAN),
            text(format!(
                "{dir_icon} {}  ·  {} / {}{chapter_suffix}",
                format_signed_delta_sec(delta),
                format_seconds_hms(display_sec),
                format_seconds_hms(tel.duration_sec)
            ))
            .size(13)
            .font(FONT_MONO)
            .color(TEXT_PRIMARY),
            Space::with_width(Length::Fill),
        ]
        .spacing(10)
        .align_y(Alignment::Center);

        if player_state.is_scrubbing {
            preview_row = preview_row
                .push(
                    button(text("Seek Now ↵").size(11).font(FONT_BOLD))
                        .padding([4, 12])
                        .style(primary_cyan_button_style)
                        .on_press(Message::PlayerScrubCommit),
                )
                .push(
                    button(text("Cancel Esc").size(11))
                        .padding([4, 10])
                        .style(pill_button_style(false))
                        .on_press(Message::PlayerCancelScrub),
                );
        }

        tracks_col = tracks_col.push(
            container(preview_row)
                .padding([8, 12])
                .width(Length::Fill)
                .style(active_cyan_card_style),
        );
    }
    tracks_col = tracks_col.push(scrub_row).push(transport);

    if !tel.audio_tracks.is_empty() {
        let mut a_row = row![text("Audio:")
            .size(11)
            .font(FONT_MONO)
            .color(TEXT_DIM)]
        .spacing(6)
        .align_y(Alignment::Center);
        for t in &tel.audio_tracks {
            a_row = a_row.push(
                button(text(truncate_label(&t.display_label(), 28)).size(11))
                    .padding([4, 10])
                    .style(pill_button_style(t.selected))
                    .on_press(Message::PlayerSelectAudio(t.id)),
            );
        }
        tracks_col = tracks_col.push(scrollable(a_row).direction(
            scrollable::Direction::Horizontal(
                scrollable::Scrollbar::new().width(4).scroller_width(4),
            ),
        ));
    }

    if !tel.subtitle_tracks.is_empty() {
        let any_sub = tel.subtitle_tracks.iter().any(|t| t.selected);
        let mut s_row = row![
            text("Subtitles:").size(11).font(FONT_MONO).color(TEXT_DIM),
            button(text("Off").size(11))
                .padding([4, 10])
                .style(pill_button_style(!any_sub))
                .on_press(Message::PlayerSelectSubtitle(None)),
        ]
        .spacing(6)
        .align_y(Alignment::Center);
        for t in &tel.subtitle_tracks {
            s_row = s_row.push(
                button(text(truncate_label(&t.display_label(), 28)).size(11))
                    .padding([4, 10])
                    .style(pill_button_style(t.selected))
                    .on_press(Message::PlayerSelectSubtitle(Some(t.id))),
            );
        }
        tracks_col = tracks_col.push(scrollable(s_row).direction(
            scrollable::Direction::Horizontal(
                scrollable::Scrollbar::new().width(4).scroller_width(4),
            ),
        ));
    }

    if tel.video_tracks.len() > 1 {
        let mut v_row = row![text("Quality:")
            .size(11)
            .font(FONT_MONO)
            .color(TEXT_DIM)]
        .spacing(6)
        .align_y(Alignment::Center);
        for t in &tel.video_tracks {
            v_row = v_row.push(
                button(text(truncate_label(&t.display_label(), 28)).size(11))
                    .padding([4, 10])
                    .style(pill_button_style(t.selected))
                    .on_press(Message::PlayerSelectVideo(t.id)),
            );
        }
        tracks_col = tracks_col.push(scrollable(v_row).direction(
            scrollable::Direction::Horizontal(
                scrollable::Scrollbar::new().width(4).scroller_width(4),
            ),
        ));
    }

    if !tel.chapters.is_empty() {
        let mut c_row = row![text("Chapters:")
            .size(11)
            .font(FONT_MONO)
            .color(TEXT_DIM)]
        .spacing(6)
        .align_y(Alignment::Center);
        for ch in &tel.chapters {
            let active = tel.current_chapter == Some(ch.index);
            c_row = c_row.push(
                button(
                    text(format!(
                        "{} ({})",
                        truncate_label(&ch.title, 22),
                        format_seconds_hms(ch.time_sec)
                    ))
                    .size(11),
                )
                .padding([4, 10])
                .style(pill_button_style(active))
                .on_press(Message::PlayerSelectChapter(ch.index)),
            );
        }
        tracks_col = tracks_col.push(scrollable(c_row).direction(
            scrollable::Direction::Horizontal(
                scrollable::Scrollbar::new().width(4).scroller_width(4),
            ),
        ));
    }

    col = col.push(
        container(tracks_col)
            .padding(14)
            .width(Length::Fill)
            .style(glass_card_style),
    );

    // 6. Movie Credits `"Next Recommended"` Bottom Poster Gallery
    let in_movie_credits_window = matches!(
        player_state.source,
        PlaybackSourceKind::Stremio { ref meta, .. } | PlaybackSourceKind::Jellyfin { ref meta, .. }
        if meta.media_type == "movie"
    ) && (tel.eof_reached
        || (tel.duration_sec > 120.0
            && (tel.position_sec / tel.duration_sec >= 0.88
                || (tel.duration_sec - tel.position_sec) <= 300.0)));

    if player_state.show_credits_drawer || in_movie_credits_window {
        if !player_state.credits_recommendations.is_empty() {
            let mut rec_strip = row![].spacing(12);
            for item in player_state.credits_recommendations.iter().take(14) {
                rec_strip = rec_strip.push(poster_card(item, posters, 160.0, 214.0));
            }
            col = col.push(
                container(
                    column![
                        section_header(
                            "Next Recommended",
                            "1-Click Stream Resolution Right from Rolling Credits"
                        ),
                        scrollable(rec_strip).direction(scrollable::Direction::Horizontal(
                            scrollable::Scrollbar::new().width(5).scroller_width(5),
                        )),
                    ]
                    .spacing(10),
                )
                .padding(14)
                .width(Length::Fill)
                .style(active_cyan_card_style),
            );
        } else if !player_state.yt_recommendations.is_empty() {
            let mut yt_strip = row![].spacing(12);
            for item in player_state.yt_recommendations.iter().take(10) {
                yt_strip = yt_strip.push(creator_video_card(
                    item,
                    posters,
                    dearrow_enabled,
                    dearrow_map,
                    false,
                ));
            }
            col = col.push(
                container(
                    column![
                        section_header("Up Next on YouTube", "Ad-Free Related Streams"),
                        scrollable(yt_strip).direction(scrollable::Direction::Horizontal(
                            scrollable::Scrollbar::new().width(5).scroller_width(5),
                        )),
                    ]
                    .spacing(10),
                )
                .padding(14)
                .width(Length::Fill)
                .style(active_cyan_card_style),
            );
        }
    }

    scrollable(col.padding([14, 22])).height(Length::Fill).into()
}

fn render_binge_next_card<'a>(binge: &'a BingeNextCardState) -> Element<'a, Message> {
    let ep = &binge.next_episode;
    let countdown_txt = if binge.countdown_active {
        format!("AUTOPLAY IN {:.0}s", binge.countdown_remaining_sec.ceil().max(0.0))
    } else if binge.resolving {
        "PRE-MATCHING STREAM...".to_string()
    } else {
        "UP NEXT READY".to_string()
    };

    let match_detail = if let Some(rk) = &binge.matched_stream {
        format!(
            "Matched: {} ({})",
            truncate_label(&rk.candidate.title, 38),
            if rk.matched_reasons.is_empty() {
                "top stream".to_string()
            } else {
                rk.matched_reasons.join(" · ")
            }
        )
    } else if binge.jellyfin_server_id.is_some() {
        "Jellyfin DirectPlay / HLS Ready".to_string()
    } else {
        "StreamSimilarityMatcher pre-resolving...".to_string()
    };

    let can_play = binge.matched_stream.is_some() || binge.jellyfin_server_id.is_some();

    container(
        column![
            row![
                text("KEEP WATCHING")
                    .size(11)
                    .font(FONT_MONO)
                    .color(ACCENT_CYAN),
                Space::with_width(Length::Fill),
                status_pill(countdown_txt, ACCENT_EMERALD),
            ]
            .align_y(Alignment::Center),
            text(format!(
                "S{:02}E{:02} · {}",
                ep.season,
                ep.episode,
                truncate_label(&ep.title, 32)
            ))
            .size(15)
            .font(FONT_BOLD)
            .color(TEXT_PRIMARY),
            text(match_detail).size(11).color(TEXT_SECONDARY),
            row![
                button(text("Stream Next ▶").size(13).font(FONT_BOLD))
                    .padding([8, 16])
                    .style(primary_cyan_button_style)
                    .on_press_maybe(if can_play {
                        Some(Message::BingePlayNextNow)
                    } else {
                        None
                    }),
                button(text("Watch Credits").size(12))
                    .padding([8, 12])
                    .style(pill_button_style(false))
                    .on_press(Message::BingeDismissWatchCredits),
            ]
            .spacing(8)
            .align_y(Alignment::Center),
        ]
        .spacing(8)
        .width(Length::Fixed(350.0)),
    )
    .padding(14)
    .style(active_cyan_card_style)
    .into()
}
