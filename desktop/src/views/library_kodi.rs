//! Views 6 & 7: `Library` (`All · Stremio · Jellyfin · Streaming · Kodi · Torrents`) & Disposable Python 3 `Kodi` Explorer.

use crate::poster_cache::PosterLruCache;
use crate::state::{LibraryFilter, Message};
use crate::theme::{
    glass_card_style, pill_button_style, primary_cyan_button_style, ACCENT_CYAN, ACCENT_EMERALD,
    ACCENT_INDIGO, ACCENT_ROSE, FONT_MONO, FONT_SEMIBOLD, TEXT_DIM, TEXT_PRIMARY, TEXT_SECONDARY,
};
use crate::views::components::{poster_grid_6col, section_header, status_pill, truncate_label};
use brootlite_core::jellyfin::JellyfinIds;
use brootlite_core::models::{
    FavoriteEntry, KodiAddonInfo, KodiDirectoryItem, MetaItem, WatchHistoryEntry,
};
use iced::widget::{button, column, container, row, scrollable, text, Space};
use iced::{Alignment, Element, Length};

pub fn view_library<'a>(
    filter: LibraryFilter,
    favorites: &'a [FavoriteEntry],
    history: &'a [WatchHistoryEntry],
    posters: &'a PosterLruCache,
) -> Element<'a, Message> {
    let mut filter_bar = row![
        section_header("My Library", "Multipass E2EE Synced Favorites & History"),
        Space::with_width(Length::Fill),
    ]
    .spacing(8)
    .align_y(Alignment::Center);

    for f in LibraryFilter::ALL {
        filter_bar = filter_bar.push(
            button(text(f.label()).size(12))
                .padding([5, 12])
                .style(pill_button_style(filter == f))
                .on_press(Message::LibrarySelectFilter(f)),
        );
    }

    let filter_card = container(filter_bar)
        .padding(14)
        .width(Length::Fill)
        .style(glass_card_style);

    let matching_favs: Vec<MetaItem> = favorites
        .iter()
        .filter(|fav| matches_library_filter(&fav.item_id, &fav.media_type, filter))
        .map(|fav| MetaItem {
            id: fav.item_id.clone(),
            media_type: fav.media_type.clone(),
            name: fav.title.clone(),
            poster: fav.poster.clone(),
            imdb_rating: fav.imdb_rating.clone(),
            release_info: fav.release_info.clone(),
            ..Default::default()
        })
        .collect();

    let fav_section: Element<'a, Message> = if matching_favs.is_empty() {
        container(
            text("No favorites in this category yet. Open any Movie/Series detail page and click `♥ Add to Library`.")
                .size(13)
                .color(TEXT_SECONDARY),
        )
        .padding(22)
        .width(Length::Fill)
        .style(glass_card_style)
        .into()
    } else {
        poster_grid_6col(&matching_favs, posters)
    };

    // Recent Watch History list below Favorites
    let matching_hist: Vec<&WatchHistoryEntry> = history
        .iter()
        .filter(|h| matches_library_filter(&h.item_id, &h.media_type, filter))
        .take(24)
        .collect();

    let mut hist_col = column![section_header(
        "Recent Watch History",
        "Synced Across Stremio, Jellyfin, & Multipass Vault"
    )]
    .spacing(8);

    if matching_hist.is_empty() {
        hist_col = hist_col.push(
            container(
                text("No watch history entries for this filter.")
                    .size(13)
                    .color(TEXT_SECONDARY),
            )
            .padding(18)
            .width(Length::Fill)
            .style(glass_card_style),
        );
    } else {
        for h in matching_hist {
            let pct = (h.progress_ratio() * 100.0).round() as u32;
            let as_meta = MetaItem {
                id: h.item_id.clone(),
                media_type: h.media_type.clone(),
                name: h.title.clone(),
                poster: h.poster.clone(),
                ..Default::default()
            };
            hist_col = hist_col.push(
                container(
                    row![
                        status_pill(h.media_type.to_uppercase(), ACCENT_INDIGO),
                        column![
                            text(&h.title).size(14).font(FONT_SEMIBOLD).color(TEXT_PRIMARY),
                            text(if h.episode_title.is_empty() {
                                format!("{pct}% watched")
                            } else {
                                format!("{} · {pct}% watched", h.episode_title)
                            })
                            .size(12)
                            .color(TEXT_SECONDARY),
                        ]
                        .spacing(3)
                        .width(Length::Fill),
                        button(text("Open / Resume ▶").size(12).font(FONT_SEMIBOLD))
                            .padding([7, 14])
                            .style(primary_cyan_button_style)
                            .on_press(Message::OpenMetaDetail(as_meta)),
                    ]
                    .spacing(12)
                    .align_y(Alignment::Center),
                )
                .padding(11)
                .width(Length::Fill)
                .style(glass_card_style),
            );
        }
    }

    scrollable(
        column![filter_card, fav_section, hist_col]
            .spacing(18)
            .padding([16, 22]),
    )
    .height(Length::Fill)
    .into()
}

fn matches_library_filter(item_id: &str, media_type: &str, filter: LibraryFilter) -> bool {
    match filter {
        LibraryFilter::All => true,
        LibraryFilter::Jellyfin => JellyfinIds::is_jellyfin_id(item_id),
        LibraryFilter::Streaming => {
            item_id.starts_with("yt:")
                || item_id.starts_with("twitch:")
                || item_id.starts_with("odysee:")
                || item_id.starts_with("rumble:")
                || matches!(media_type, "youtube" | "twitch" | "odysee" | "rumble")
        }
        LibraryFilter::Kodi => item_id.starts_with("plugin://") || media_type == "kodi",
        LibraryFilter::Torrents => item_id.starts_with("btih:") || media_type == "torrent",
        LibraryFilter::Stremio => {
            !JellyfinIds::is_jellyfin_id(item_id)
                && !item_id.starts_with("yt:")
                && !item_id.starts_with("twitch:")
                && !item_id.starts_with("plugin://")
                && !item_id.starts_with("btih:")
        }
    }
}

pub fn view_kodi<'a>(
    addons: &'a [KodiAddonInfo],
    active_addon_id: Option<&'a str>,
    breadcrumbs: &'a [(String, String)],
    items: &'a [KodiDirectoryItem],
    loading: bool,
    last_error: Option<&'a str>,
    kodi_addons_path: &str,
) -> Element<'a, Message> {
    let top_bar = container(
        row![
            column![
                section_header(
                    "Disposable Python 3 Kodi Addon Bridge",
                    "0 MB Idle & Playback RAM — Subprocess Reaped Immediately Before libmpv Starts"
                ),
                text(format!("Addons Directory: {kodi_addons_path}"))
                    .size(11)
                    .font(FONT_MONO)
                    .color(TEXT_DIM),
            ]
            .spacing(4)
            .width(Length::Fill),
            status_pill("0 MB IDLE PYTHON".into(), ACCENT_EMERALD),
            button(text("↻ Rescan Addons").size(12).font(FONT_SEMIBOLD))
                .padding([7, 14])
                .style(pill_button_style(false))
                .on_press(Message::KodiRefreshAddons),
        ]
        .spacing(12)
        .align_y(Alignment::Center),
    )
    .padding(14)
    .width(Length::Fill)
    .style(glass_card_style);

    let mut col = column![top_bar].spacing(14);

    if let Some(err) = last_error {
        col = col.push(
            container(text(err).size(12).font(FONT_MONO).color(ACCENT_ROSE))
                .padding(12)
                .width(Length::Fill)
                .style(glass_card_style),
        );
    }

    if addons.is_empty() {
        col = col.push(
            container(
                column![
                    text("No Kodi `plugin.video.*` addons installed yet.")
                        .size(14)
                        .font(FONT_SEMIBOLD)
                        .color(TEXT_PRIMARY),
                    text(format!(
                        "Unzip any standard Kodi Python 3 video plugin (`plugin.video.example/addon.xml`) into `{kodi_addons_path}` and click `↻ Rescan Addons`."
                    ))
                    .size(13)
                    .color(TEXT_SECONDARY),
                ]
                .spacing(6),
            )
            .padding(22)
            .width(Length::Fill)
            .style(glass_card_style),
        );
        return scrollable(col.padding([16, 22])).height(Length::Fill).into();
    }

    // Installed Addon Selector Pills
    let mut addon_strip = row![text("Installed Video Addons:")
        .size(11)
        .font(FONT_MONO)
        .color(TEXT_DIM)]
    .spacing(8)
    .align_y(Alignment::Center);

    for a in addons {
        let active = active_addon_id == Some(a.id.as_str());
        addon_strip = addon_strip.push(
            button(
                text(format!("{} v{}", a.name, a.version))
                    .size(12)
                    .font(FONT_SEMIBOLD),
            )
            .padding([6, 14])
            .style(pill_button_style(active))
            .on_press(Message::KodiOpenAddon(a.id.clone())),
        );
    }
    col = col.push(addon_strip);

    // Breadcrumb & Directory Explorer
    if let Some(aid) = active_addon_id {
        let mut crumb_row = row![
            button(text("⬆ Up").size(12))
                .padding([5, 12])
                .style(pill_button_style(false))
                .on_press_maybe(if breadcrumbs.len() > 1 {
                    Some(Message::KodiNavigateUp)
                } else {
                    None
                }),
        ]
        .spacing(8)
        .align_y(Alignment::Center);

        for (lbl, _) in breadcrumbs {
            crumb_row = crumb_row.push(
                text(format!("/ {}", truncate_label(lbl, 28)))
                    .size(12)
                    .font(FONT_MONO)
                    .color(ACCENT_CYAN),
            );
        }
        col = col.push(crumb_row);

        if loading {
            col = col.push(
                container(
                    text("Running disposable Python 3 Kodi bridge (`kill_on_drop(true)`)...")
                        .size(13)
                        .color(TEXT_SECONDARY),
                )
                .padding(20)
                .width(Length::Fill)
                .style(glass_card_style),
            );
        } else {
            let mut list = column![].spacing(8);
            for item in items {
                let is_folder = item.is_folder && !item.is_playable;
                let badge = if is_folder {
                    status_pill("📁 FOLDER".into(), ACCENT_INDIGO)
                } else {
                    status_pill("▶ STREAM".into(), ACCENT_EMERALD)
                };
                let action_msg = if is_folder {
                    Message::KodiOpenDirectory {
                        addon_id: aid.to_string(),
                        label: item.label.clone(),
                        url: item.url.clone(),
                    }
                } else {
                    Message::KodiPlayItem {
                        addon_id: aid.to_string(),
                        label: item.label.clone(),
                        url: item.url.clone(),
                    }
                };
                list = list.push(
                    container(
                        row![
                            badge,
                            column![
                                text(truncate_label(&item.label, 84))
                                    .size(14)
                                    .font(FONT_SEMIBOLD)
                                    .color(TEXT_PRIMARY),
                                text(truncate_label(
                                    if item.plot.is_empty() {
                                        &item.url
                                    } else {
                                        &item.plot
                                    },
                                    100
                                ))
                                .size(11)
                                .color(TEXT_SECONDARY),
                            ]
                            .spacing(3)
                            .width(Length::Fill),
                            if is_folder {
                                button(text("Browse →").size(12).font(FONT_SEMIBOLD))
                                    .padding([7, 14])
                                    .style(pill_button_style(false))
                                    .on_press(action_msg)
                            } else {
                                button(text("Resolve & Play ▶").size(12).font(FONT_SEMIBOLD))
                                    .padding([7, 14])
                                    .style(primary_cyan_button_style)
                                    .on_press(action_msg)
                            },
                        ]
                        .spacing(12)
                        .align_y(Alignment::Center),
                    )
                    .padding(11)
                    .width(Length::Fill)
                    .style(glass_card_style),
                );
            }
            col = col.push(list);
        }
    }

    scrollable(col.padding([16, 22])).height(Length::Fill).into()
}
