//! Reusable Xirtus Dark-Glass UI cards & widgets (`2:3` Poster Card, `16:9` Creator Card, Stream Row).

use crate::poster_cache::PosterLruCache;
use crate::state::{
    extract_display_title, ContinueWatchingCard, Message,
};
use crate::theme::{
    active_cyan_card_style, badge_style, cyan_progress_style, ghost_card_button_style,
    pill_button_style, poster_placeholder_style, primary_cyan_button_style, ACCENT_AMBER,
    ACCENT_CYAN, ACCENT_EMERALD, ACCENT_INDIGO, ACCENT_ROSE, BG_INPUT, BORDER_SLATE, FONT_BOLD,
    FONT_MONO, FONT_SEMIBOLD, TEXT_DIM, TEXT_PRIMARY, TEXT_SECONDARY,
};
use brootlite_core::models::{CreatorVideoItem, MetaItem, RankedStreamCandidate, StreamCandidate};
use iced::widget::{button, column, container, image, progress_bar, row, text, Space};
use iced::{Alignment, Color, ContentFit, Element, Length};
use std::collections::HashMap;

pub fn truncate_label(raw: &str, max_chars: usize) -> String {
    let trimmed = raw.trim();
    if trimmed.chars().count() <= max_chars {
        trimmed.to_string()
    } else {
        let head: String = trimmed.chars().take(max_chars.saturating_sub(1)).collect();
        format!("{head}…")
    }
}

pub fn section_header<'a>(
    title: impl Into<String>,
    subtitle: impl Into<String>,
) -> Element<'a, Message> {
    let title_str = title.into();
    let subtitle_str = subtitle.into();
    let mut r = row![text(title_str).size(19).font(FONT_SEMIBOLD).color(TEXT_PRIMARY)]
        .spacing(10)
        .align_y(Alignment::Center);
    if !subtitle_str.is_empty() {
        r = r.push(text(subtitle_str).size(13).color(TEXT_SECONDARY));
    }
    r.into()
}

pub fn status_pill<'a>(label: String, accent: Color) -> Element<'a, Message> {
    container(text(label).size(11).font(FONT_MONO).color(accent))
        .padding([3, 8])
        .style(badge_style(
            Color::from_rgba(accent.r, accent.g, accent.b, 0.14),
            accent,
        ))
        .into()
}

/// Renders a crisp `2:3` poster card (`width` x `height`) backed by `PosterLruCache`.
pub fn poster_card<'a>(
    item: &MetaItem,
    posters: &PosterLruCache,
    card_w: f32,
    poster_h: f32,
) -> Element<'a, Message> {
    let art: Element<'a, Message> = if let Some(handle) = posters.peek(&item.poster) {
        container(
            image(handle.clone())
                .width(Length::Fixed(card_w - 16.0))
                .height(Length::Fixed(poster_h))
                .content_fit(ContentFit::Cover),
        )
        .width(Length::Fixed(card_w - 16.0))
        .height(Length::Fixed(poster_h))
        .style(poster_placeholder_style)
        .into()
    } else {
        let initials: String = item
            .name
            .split_whitespace()
            .filter_map(|w| w.chars().next())
            .take(2)
            .collect();
        container(
            column![
                Space::with_height(Length::Fill),
                text(if initials.is_empty() {
                    "BL".to_string()
                } else {
                    initials.to_uppercase()
                })
                .size(24)
                .font(FONT_BOLD)
                .color(ACCENT_CYAN),
                text(item.media_type.to_uppercase())
                    .size(10)
                    .font(FONT_MONO)
                    .color(TEXT_DIM),
                Space::with_height(Length::Fill),
            ]
            .spacing(4)
            .align_x(Alignment::Center)
            .width(Length::Fill),
        )
        .width(Length::Fixed(card_w - 16.0))
        .height(Length::Fixed(poster_h))
        .style(poster_placeholder_style)
        .into()
    };

    let mut meta_line = row![].spacing(6).align_y(Alignment::Center);
    if !item.imdb_rating.trim().is_empty() {
        meta_line = meta_line.push(
            text(format!("★ {}", item.imdb_rating.trim()))
                .size(11)
                .font(FONT_MONO)
                .color(ACCENT_AMBER),
        );
    }
    if !item.release_info.trim().is_empty() {
        meta_line = meta_line.push(
            text(truncate_label(&item.release_info, 11))
                .size(11)
                .color(TEXT_SECONDARY),
        );
    } else if !item.media_type.is_empty() {
        meta_line = meta_line.push(
            text(item.media_type.to_uppercase())
                .size(10)
                .font(FONT_MONO)
                .color(TEXT_DIM),
        );
    }

    let body = column![
        art,
        text(truncate_label(&item.name, 22))
            .size(13)
            .font(FONT_SEMIBOLD)
            .color(TEXT_PRIMARY),
        meta_line,
    ]
    .spacing(6)
    .width(Length::Fixed(card_w - 16.0));

    button(body)
        .padding(8)
        .width(Length::Fixed(card_w))
        .style(ghost_card_button_style)
        .on_press(Message::OpenMetaDetail(item.clone()))
        .into()
}

/// Renders a 6-column responsive poster grid from a slice of `MetaItem`s.
pub fn poster_grid_6col<'a>(
    items: &[MetaItem],
    posters: &PosterLruCache,
) -> Element<'a, Message> {
    let mut rows_col = column![].spacing(14);
    for chunk in items.chunks(6) {
        let mut r = row![].spacing(12);
        for item in chunk {
            r = r.push(poster_card(item, posters, 192.0, 258.0));
        }
        rows_col = rows_col.push(r);
    }
    rows_col.into()
}

/// Renders a Continue Watching landscape card with progress bar & source badge.
pub fn continue_watching_card<'a>(
    idx: usize,
    card: &'a ContinueWatchingCard,
    posters: &'a PosterLruCache,
) -> Element<'a, Message> {
    let thumb: Element<'a, Message> = if let Some(handle) = posters.peek(&card.poster_url) {
        container(
            image(handle.clone())
                .width(Length::Fixed(228.0))
                .height(Length::Fixed(122.0))
                .content_fit(ContentFit::Cover),
        )
        .width(Length::Fixed(228.0))
        .height(Length::Fixed(122.0))
        .style(poster_placeholder_style)
        .into()
    } else {
        container(
            column![
                Space::with_height(Length::Fill),
                text("▶ RESUME")
                    .size(14)
                    .font(FONT_BOLD)
                    .color(ACCENT_CYAN),
                text(card.badge.clone())
                    .size(11)
                    .font(FONT_MONO)
                    .color(TEXT_SECONDARY),
                Space::with_height(Length::Fill),
            ]
            .spacing(4)
            .align_x(Alignment::Center)
            .width(Length::Fill),
        )
        .width(Length::Fixed(228.0))
        .height(Length::Fixed(122.0))
        .style(poster_placeholder_style)
        .into()
    };

    let pct = (card.progress_ratio * 100.0).clamp(2.0, 100.0);
    let body = column![
        thumb,
        progress_bar(0.0..=100.0, pct)
            .height(Length::Fixed(4.0))
            .style(cyan_progress_style),
        row![
            status_pill(card.badge.clone(), ACCENT_INDIGO),
            Space::with_width(Length::Fill),
            text(format!("{:.0}%", card.progress_ratio * 100.0))
                .size(11)
                .font(FONT_MONO)
                .color(ACCENT_CYAN),
        ]
        .align_y(Alignment::Center),
        text(truncate_label(&card.title, 26))
            .size(13)
            .font(FONT_SEMIBOLD)
            .color(TEXT_PRIMARY),
        text(truncate_label(&card.subtitle, 30))
            .size(11)
            .color(TEXT_SECONDARY),
    ]
    .spacing(6)
    .width(Length::Fixed(228.0));

    button(body)
        .padding(9)
        .width(Length::Fixed(246.0))
        .style(ghost_card_button_style)
        .on_press(Message::TriggerContinueWatching(idx))
        .into()
}

/// Renders a `16:9` video card for YouTube, Twitch, Odysee, & Rumble feeds.
pub fn creator_video_card<'a>(
    item: &CreatorVideoItem,
    posters: &PosterLruCache,
    dearrow_enabled: bool,
    dearrow_map: &HashMap<String, String>,
    is_following: bool,
) -> Element<'a, Message> {
    let card_w = 286.0;
    let thumb_w = card_w - 18.0;
    let thumb_h = 150.0;

    let thumb: Element<'a, Message> = if let Some(handle) = posters.peek(&item.thumbnail_url) {
        container(
            image(handle.clone())
                .width(Length::Fixed(thumb_w))
                .height(Length::Fixed(thumb_h))
                .content_fit(ContentFit::Cover),
        )
        .width(Length::Fixed(thumb_w))
        .height(Length::Fixed(thumb_h))
        .style(poster_placeholder_style)
        .into()
    } else {
        container(
            column![
                Space::with_height(Length::Fill),
                text(item.platform.to_uppercase())
                    .size(14)
                    .font(FONT_BOLD)
                    .color(ACCENT_CYAN),
                text(if item.is_live {
                    "● LIVE STREAM"
                } else {
                    "▶ 1080p STREAM"
                })
                .size(11)
                .font(FONT_MONO)
                .color(if item.is_live {
                    ACCENT_ROSE
                } else {
                    TEXT_SECONDARY
                }),
                Space::with_height(Length::Fill),
            ]
            .spacing(4)
            .align_x(Alignment::Center)
            .width(Length::Fill),
        )
        .width(Length::Fixed(thumb_w))
        .height(Length::Fixed(thumb_h))
        .style(poster_placeholder_style)
        .into()
    };

    let display_title = extract_display_title(item, dearrow_enabled, dearrow_map);
    let badge_el = if item.is_live {
        status_pill("● LIVE".into(), ACCENT_ROSE)
    } else if !item.duration_text.is_empty() {
        status_pill(item.duration_text.clone(), ACCENT_CYAN)
    } else {
        status_pill(item.platform.to_uppercase(), ACCENT_INDIGO)
    };

    let mut top_meta = row![badge_el, Space::with_width(Length::Fill)].align_y(Alignment::Center);
    if !item.view_count_text.is_empty() {
        top_meta = top_meta.push(
            text(truncate_label(&item.view_count_text, 18))
                .size(11)
                .color(TEXT_SECONDARY),
        );
    }

    let mut col = column![
        thumb,
        top_meta,
        text(truncate_label(display_title, 48))
            .size(13)
            .font(FONT_SEMIBOLD)
            .color(TEXT_PRIMARY),
    ]
    .spacing(6)
    .width(Length::Fixed(thumb_w));

    if item.resume_progress_pct > 0 {
        col = col.push(
            progress_bar(0.0..=100.0, item.resume_progress_pct as f32)
                .height(Length::Fixed(3.0))
                .style(cyan_progress_style),
        );
    }

    let play_btn = button(col)
        .padding(9)
        .width(Length::Fixed(card_w))
        .style(ghost_card_button_style)
        .on_press(Message::PlayCreatorItem(item.clone()));

    // Channel bar underneath card for 1-click channel jump & follow toggle
    let chan_label = if item.channel_name.is_empty() {
        item.platform.clone()
    } else {
        truncate_label(&item.channel_name, 22)
    };

    let mut footer = row![].spacing(6).align_y(Alignment::Center).width(Length::Fixed(card_w));
    if item.platform == "youtube" && !item.channel_id.is_empty() {
        footer = footer.push(
            button(text(format!("@{chan_label}")).size(11).color(TEXT_SECONDARY))
                .padding([3, 8])
                .style(pill_button_style(false))
                .on_press(Message::YtOpenChannel(item.channel_id.clone())),
        );
    } else if item.platform == "twitch" && !item.channel_id.is_empty() {
        footer = footer.push(
            button(text(format!("@{chan_label}")).size(11).color(TEXT_SECONDARY))
                .padding([3, 8])
                .style(pill_button_style(false))
                .on_press(Message::TwitchOpenChannel(item.channel_id.clone())),
        );
    } else {
        footer = footer.push(text(chan_label).size(11).color(TEXT_SECONDARY));
    }

    footer = footer.push(Space::with_width(Length::Fill));

    if !item.channel_id.is_empty() || !item.channel_name.is_empty() {
        let cid = if item.channel_id.is_empty() {
            item.channel_name.clone()
        } else {
            item.channel_id.clone()
        };
        footer = footer.push(
            button(
                text(if is_following { "✓ Following" } else { "+ Follow" })
                    .size(10)
                    .font(FONT_MONO),
            )
            .padding([3, 8])
            .style(pill_button_style(is_following))
            .on_press(Message::ToggleCreatorFollow {
                platform: item.platform.clone(),
                channel_id: cid.clone(),
                name: if item.channel_name.is_empty() {
                    cid
                } else {
                    item.channel_name.clone()
                },
                handle: String::new(),
                avatar_url: item.channel_avatar.clone(),
            }),
        );
    }

    column![play_btn, footer].spacing(5).into()
}

/// Renders a 4-column `16:9` grid for YouTube / Twitch / Odysee / Rumble items.
pub fn creator_grid_4col<'a>(
    items: &[CreatorVideoItem],
    posters: &PosterLruCache,
    dearrow_enabled: bool,
    dearrow_map: &HashMap<String, String>,
    followed_keys: &std::collections::HashSet<(String, String)>,
) -> Element<'a, Message> {
    let mut col = column![].spacing(16);
    for chunk in items.chunks(4) {
        let mut r = row![].spacing(14);
        for item in chunk {
            let cid = if item.channel_id.is_empty() {
                &item.channel_name
            } else {
                &item.channel_id
            };
            let following = followed_keys.contains(&(item.platform.clone(), cid.clone()));
            r = r.push(creator_video_card(
                item,
                posters,
                dearrow_enabled,
                dearrow_map,
                following,
            ));
        }
        col = col.push(r);
    }
    col.into()
}

/// Renders a rich Direct HTTP Mirror / P2P Swarm stream candidate row.
pub fn stream_candidate_row<'a>(
    cand: &'a StreamCandidate,
    ranked_info: Option<&'a RankedStreamCandidate>,
    meta: Option<MetaItem>,
    video_id: Option<String>,
    episode_title: Option<String>,
    resume_ms: u64,
) -> Element<'a, Message> {
    let is_direct = cand.is_direct_http();
    let transport_badge = if is_direct {
        status_pill("⚡ DIRECT MIRROR".into(), ACCENT_EMERALD)
    } else {
        status_pill("🧲 P2P SWARM".into(), ACCENT_INDIGO)
    };

    let res_label = match cand.resolution_score {
        2160.. => Some(("4K UHD", ACCENT_AMBER)),
        1440..=2159 => Some(("1440p", ACCENT_CYAN)),
        1080..=1439 => Some(("1080p", ACCENT_CYAN)),
        720..=1079 => Some(("720p", TEXT_SECONDARY)),
        _ => None,
    };

    let mut badges = row![transport_badge].spacing(8).align_y(Alignment::Center);
    if let Some((lbl, col)) = res_label {
        badges = badges.push(status_pill(lbl.into(), col));
    }
    if !cand.addon_name.is_empty() {
        badges = badges.push(status_pill(cand.addon_name.clone(), TEXT_SECONDARY));
    }
    if let Some(rk) = ranked_info {
        if rk.similarity_score >= 40.0 && !rk.matched_reasons.is_empty() {
            badges = badges.push(status_pill(
                format!("★ Binge Match ({})", rk.matched_reasons.join(", ")),
                ACCENT_CYAN,
            ));
        }
    }

    // Seeds, size, resolution, codec and provider on their own line, never truncated.
    let telemetry = cand.telemetry_line();
    let telemetry_color = if cand.is_p2p() && cand.seeders == 0 {
        TEXT_SECONDARY
    } else if cand.has_preferred_modern_codec() {
        ACCENT_CYAN
    } else {
        ACCENT_EMERALD
    };

    let mut left = column![badges].spacing(5).width(Length::Fill);
    if !telemetry.is_empty() {
        left = left.push(text(telemetry).size(12).font(FONT_MONO).color(telemetry_color));
    }
    left = left.push(
        text(cand.title.clone())
            .size(14)
            .font(FONT_SEMIBOLD)
            .color(TEXT_PRIMARY),
    );

    if !cand.subtitle.trim().is_empty() {
        left = left.push(
            text(cand.subtitle.replace('\n', " · "))
                .size(12)
                .color(TEXT_SECONDARY),
        );
    }

    let play_btn = button(
        text(if is_direct { "▶ Play Direct" } else { "▶ Stream P2P" })
            .size(13)
            .font(FONT_SEMIBOLD),
    )
    .padding([8, 16])
    .style(primary_cyan_button_style)
    .on_press(Message::PlayStreamCandidate {
        candidate: cand.clone(),
        meta,
        video_id,
        episode_title,
        resume_ms,
    });

    let card = container(
        row![left, play_btn]
            .spacing(16)
            .align_y(Alignment::Center),
    )
    .padding(12)
    .width(Length::Fill)
    .style(if ranked_info.map(|r| r.similarity_score >= 80.0).unwrap_or(false) {
        active_cyan_card_style
    } else {
        crate::theme::glass_card_style
    });

    let _ = BG_INPUT;
    let _ = BORDER_SLATE;
    card.into()
}
