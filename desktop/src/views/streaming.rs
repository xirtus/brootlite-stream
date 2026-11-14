//! View 4: `Streaming` — YouTube (QR OAuth, Brand Switcher, DASH 4K, SponsorBlock, RYD, DeArrow) + Twitch, Odysee, & Rumble.

use crate::poster_cache::PosterLruCache;
use crate::state::{
    CreatorSubTab, Message, StreamingPlatform, TwitchTab, YouTubeTab,
};
use crate::theme::{
    accent_indigo_button_style, active_cyan_card_style, danger_button_style, glass_card_style,
    pill_button_style, primary_cyan_button_style, qr_box_style, search_input_style, ACCENT_CYAN,
    ACCENT_EMERALD, ACCENT_INDIGO, ACCENT_ROSE, FONT_BOLD, FONT_MONO, FONT_SEMIBOLD, TEXT_DIM,
    TEXT_PRIMARY, TEXT_SECONDARY,
};
use crate::views::components::{creator_grid_4col, section_header, status_pill, truncate_label};
use brootlite_core::models::{
    CreatorCategory, CreatorChannel, CreatorFollowEntry, CreatorVideoItem, DeviceCodeSession,
    TwitchAuthProfile, YouTubeAccountProfile,
};
use iced::widget::{button, column, container, qr_code, row, scrollable, text, text_input, Space};
use iced::{Alignment, Element, Length};
use std::collections::{HashMap, HashSet};

#[allow(clippy::too_many_arguments)]
pub fn view_streaming<'a>(
    platform: StreamingPlatform,
    // YouTube
    yt_accounts: &'a [YouTubeAccountProfile],
    yt_active_id: &'a str,
    yt_device_sess: Option<&'a DeviceCodeSession>,
    yt_qr: Option<&'a qr_code::Data>,
    yt_tab: YouTubeTab,
    yt_playlist_input: &'a str,
    yt_search_query: &'a str,
    yt_suggestions: &'a [String],
    yt_feed: &'a [CreatorVideoItem],
    yt_has_more: bool,
    yt_channel: Option<&'a CreatorChannel>,
    sponsorblock_enabled: bool,
    ryd_enabled: bool,
    dearrow_enabled: bool,
    dearrow_map: &'a HashMap<String, String>,
    // Twitch
    twitch_profile: Option<&'a TwitchAuthProfile>,
    twitch_device_sess: Option<&'a DeviceCodeSession>,
    twitch_qr: Option<&'a qr_code::Data>,
    twitch_tab: &'a TwitchTab,
    twitch_items: &'a [CreatorVideoItem],
    twitch_categories: &'a [CreatorCategory],
    twitch_channel: Option<&'a CreatorChannel>,
    twitch_search_query: &'a str,
    // Odysee & Rumble
    odysee_tab: CreatorSubTab,
    odysee_items: &'a [CreatorVideoItem],
    odysee_search_query: &'a str,
    rumble_tab: CreatorSubTab,
    rumble_items: &'a [CreatorVideoItem],
    rumble_search_query: &'a str,
    follows: &'a [CreatorFollowEntry],
    loading: bool,
    posters: &'a PosterLruCache,
) -> Element<'a, Message> {
    let followed_keys: HashSet<(String, String)> = follows
        .iter()
        .map(|f| (f.platform.clone(), f.channel_id.clone()))
        .collect();

    // Top Platform Selector (`YouTube · Twitch · Odysee · Rumble`)
    let mut plat_row = row![text("Platform:")
        .size(12)
        .font(FONT_MONO)
        .color(TEXT_DIM)]
    .spacing(8)
    .align_y(Alignment::Center);

    for p in StreamingPlatform::ALL {
        plat_row = plat_row.push(
            button(text(p.label()).size(13).font(FONT_SEMIBOLD))
                .padding([6, 16])
                .style(pill_button_style(platform == p))
                .on_press(Message::StreamingSelectPlatform(p)),
        );
    }
    plat_row = plat_row.push(Space::with_width(Length::Fill));
    plat_row = plat_row.push(status_pill(
        "100% Ad-Free Native Loopback".into(),
        ACCENT_EMERALD,
    ));

    let plat_card = container(plat_row)
        .padding(12)
        .width(Length::Fill)
        .style(glass_card_style);

    let body: Element<'a, Message> = match platform {
        StreamingPlatform::YouTube => render_youtube_section(
            yt_accounts,
            yt_active_id,
            yt_device_sess,
            yt_qr,
            yt_tab,
            yt_playlist_input,
            yt_search_query,
            yt_suggestions,
            yt_feed,
            yt_has_more,
            yt_channel,
            sponsorblock_enabled,
            ryd_enabled,
            dearrow_enabled,
            dearrow_map,
            &followed_keys,
            loading,
            posters,
        ),
        StreamingPlatform::Twitch => render_twitch_section(
            twitch_profile,
            twitch_device_sess,
            twitch_qr,
            twitch_tab,
            twitch_items,
            twitch_categories,
            twitch_channel,
            twitch_search_query,
            follows,
            &followed_keys,
            dearrow_map,
            loading,
            posters,
        ),
        StreamingPlatform::Odysee => render_creator_platform_section(
            StreamingPlatform::Odysee,
            odysee_tab,
            odysee_items,
            odysee_search_query,
            follows,
            &followed_keys,
            dearrow_map,
            loading,
            posters,
        ),
        StreamingPlatform::Rumble => render_creator_platform_section(
            StreamingPlatform::Rumble,
            rumble_tab,
            rumble_items,
            rumble_search_query,
            follows,
            &followed_keys,
            dearrow_map,
            loading,
            posters,
        ),
    };

    scrollable(
        column![plat_card, body]
            .spacing(16)
            .padding([16, 22]),
    )
    .height(Length::Fill)
    .into()
}

#[allow(clippy::too_many_arguments)]
fn render_youtube_section<'a>(
    accounts: &'a [YouTubeAccountProfile],
    active_id: &'a str,
    device_sess: Option<&'a DeviceCodeSession>,
    qr_data: Option<&'a qr_code::Data>,
    tab: YouTubeTab,
    playlist_input: &'a str,
    search_query: &'a str,
    suggestions: &'a [String],
    feed: &'a [CreatorVideoItem],
    has_more: bool,
    channel: Option<&'a CreatorChannel>,
    sponsorblock_enabled: bool,
    ryd_enabled: bool,
    dearrow_enabled: bool,
    dearrow_map: &HashMap<String, String>,
    followed_keys: &HashSet<(String, String)>,
    loading: bool,
    posters: &PosterLruCache,
) -> Element<'a, Message> {
    // Account + Brand Account switcher + Enhancement toggles
    let mut acc_row = row![].spacing(8).align_y(Alignment::Center);
    if accounts.is_empty() {
        acc_row = acc_row.push(status_pill("YouTube Guest".into(), ACCENT_CYAN));
    } else {
        acc_row = acc_row.push(
            text("Account / Brand:")
                .size(11)
                .font(FONT_MONO)
                .color(TEXT_DIM),
        );
        for acc in accounts {
            let is_cur = acc.account_id == active_id;
            let lbl = if acc.page_id.is_empty() {
                acc.account_name.clone()
            } else {
                format!("{} (Brand)", acc.account_name)
            };
            acc_row = acc_row.push(
                button(text(lbl).size(11).font(FONT_SEMIBOLD))
                    .padding([4, 10])
                    .style(pill_button_style(is_cur))
                    .on_press(Message::YtSwitchAccount(acc.account_id.clone())),
            );
        }
        if !active_id.is_empty() {
            acc_row = acc_row.push(
                button(text("Sign Out").size(11))
                    .padding([4, 10])
                    .style(danger_button_style)
                    .on_press(Message::YtRemoveAccount(active_id.to_string())),
            );
        }
    }

    acc_row = acc_row.push(
        button(text("+ QR Sign In (youtube.com/activate)").size(11).font(FONT_SEMIBOLD))
            .padding([5, 12])
            .style(accent_indigo_button_style)
            .on_press(Message::YtStartDeviceAuth),
    );
    acc_row = acc_row.push(Space::with_width(Length::Fill));

    // Enhancement Toggles
    acc_row = acc_row.push(
        button(
            text(if sponsorblock_enabled {
                "✓ SponsorBlock"
            } else {
                "○ SponsorBlock"
            })
            .size(11)
            .font(FONT_MONO),
        )
        .padding([4, 10])
        .style(pill_button_style(sponsorblock_enabled))
        .on_press(Message::YtToggleSponsorBlock),
    );
    acc_row = acc_row.push(
        button(
            text(if ryd_enabled {
                "✓ Dislikes (RYD)"
            } else {
                "○ Dislikes (RYD)"
            })
            .size(11)
            .font(FONT_MONO),
        )
        .padding([4, 10])
        .style(pill_button_style(ryd_enabled))
        .on_press(Message::YtToggleRyd),
    );
    acc_row = acc_row.push(
        button(
            text(if dearrow_enabled {
                "✓ DeArrow"
            } else {
                "○ DeArrow"
            })
            .size(11)
            .font(FONT_MONO),
        )
        .padding([4, 10])
        .style(pill_button_style(dearrow_enabled))
        .on_press(Message::YtToggleDeArrow),
    );

    // Sub-tabs (`Home · Subscriptions · History · Playlists · Channel · Search`)
    let sub_tabs = row![
        button(text("Home").size(12))
            .padding([5, 12])
            .style(pill_button_style(tab == YouTubeTab::Home))
            .on_press(Message::YtSelectTab(YouTubeTab::Home)),
        button(text("Subscriptions").size(12))
            .padding([5, 12])
            .style(pill_button_style(tab == YouTubeTab::Subscriptions))
            .on_press(Message::YtSelectTab(YouTubeTab::Subscriptions)),
        button(text("History").size(12))
            .padding([5, 12])
            .style(pill_button_style(tab == YouTubeTab::History))
            .on_press(Message::YtSelectTab(YouTubeTab::History)),
        button(text("Playlists").size(12))
            .padding([5, 12])
            .style(pill_button_style(tab == YouTubeTab::Playlists))
            .on_press(Message::YtSelectTab(YouTubeTab::Playlists)),
        button(text("Channel").size(12))
            .padding([5, 12])
            .style(pill_button_style(tab == YouTubeTab::Channel))
            .on_press(Message::YtSelectTab(YouTubeTab::Channel)),
        button(text("🔍 Search").size(12))
            .padding([5, 12])
            .style(pill_button_style(tab == YouTubeTab::Search))
            .on_press(Message::YtSelectTab(YouTubeTab::Search)),
        Space::with_width(Length::Fill),
        text("1080p60 / 4K MPEG-DASH (.mpd)")
            .size(11)
            .font(FONT_MONO)
            .color(ACCENT_CYAN),
    ]
    .spacing(8)
    .align_y(Alignment::Center);

    let mut col = column![
        container(column![acc_row, sub_tabs].spacing(10))
            .padding(12)
            .width(Length::Fill)
            .style(glass_card_style)
    ]
    .spacing(14);

    // QR Device Code Card when active
    if let Some(sess) = device_sess {
        col = col.push(render_device_qr_banner(
            "YouTube TV OAuth2 Device Sign-In (Discovers Primary + Brand Accounts)",
            sess,
            qr_data,
        ));
    }

    match tab {
        YouTubeTab::Search => {
            let mut s_col = column![
                row![
                    text_input("🔍 Search YouTube videos & live streams...", search_query)
                        .on_input(Message::YtSearchQueryChanged)
                        .on_submit(Message::YtSearchSubmit)
                        .padding([10, 14])
                        .size(14)
                        .style(search_input_style)
                        .width(Length::Fill),
                    button(text("Search ↵").size(13).font(FONT_SEMIBOLD))
                        .padding([9, 16])
                        .style(primary_cyan_button_style)
                        .on_press(Message::YtSearchSubmit),
                ]
                .spacing(10)
                .align_y(Alignment::Center)
            ]
            .spacing(8);

            if !suggestions.is_empty() {
                let mut sug_row = row![text("Suggestions:")
                    .size(11)
                    .font(FONT_MONO)
                    .color(TEXT_DIM)]
                .spacing(6)
                .align_y(Alignment::Center);
                for s in suggestions.iter().take(6) {
                    sug_row = sug_row.push(
                        button(text(truncate_label(s, 26)).size(11))
                            .padding([3, 9])
                            .style(pill_button_style(false))
                            .on_press(Message::YtPickSuggestion(s.clone())),
                    );
                }
                s_col = s_col.push(sug_row);
            }
            col = col.push(
                container(s_col)
                    .padding(12)
                    .width(Length::Fill)
                    .style(glass_card_style),
            );
        }
        YouTubeTab::Playlists => {
            col = col.push(
                container(
                    row![
                        button(text("Watch Later (WL)").size(12))
                            .padding([6, 12])
                            .style(pill_button_style(playlist_input == "WL"))
                            .on_press(Message::YtPlaylistIdChanged("WL".into())),
                        button(text("Liked Videos (LL)").size(12))
                            .padding([6, 12])
                            .style(pill_button_style(playlist_input == "LL"))
                            .on_press(Message::YtPlaylistIdChanged("LL".into())),
                        text_input("Playlist ID (e.g. WL, LL, PL...)", playlist_input)
                            .on_input(Message::YtPlaylistIdChanged)
                            .on_submit(Message::YtLoadPlaylist)
                            .padding([8, 12])
                            .size(13)
                            .style(search_input_style)
                            .width(Length::Fixed(320.0)),
                        button(text("Load Playlist").size(12).font(FONT_SEMIBOLD))
                            .padding([7, 14])
                            .style(primary_cyan_button_style)
                            .on_press(Message::YtLoadPlaylist),
                    ]
                    .spacing(10)
                    .align_y(Alignment::Center),
                )
                .padding(12)
                .width(Length::Fill)
                .style(glass_card_style),
            );
        }
        YouTubeTab::Channel => {
            if let Some(ch) = channel {
                col = col.push(
                    container(
                        column![
                            row![
                                column![
                                    text(&ch.name).size(20).font(FONT_BOLD).color(TEXT_PRIMARY),
                                    text(format!("{} · {}", ch.handle, ch.subscriber_text))
                                        .size(12)
                                        .color(TEXT_SECONDARY),
                                ]
                                .spacing(4)
                                .width(Length::Fill),
                                button(
                                    text(if ch.is_subscribed {
                                        "✓ Subscribed"
                                    } else {
                                        "+ Subscribe"
                                    })
                                    .size(13)
                                    .font(FONT_SEMIBOLD)
                                )
                                .padding([8, 16])
                                .style(pill_button_style(ch.is_subscribed))
                                .on_press(Message::YtToggleSubscribe(
                                    ch.channel_id.clone(),
                                    !ch.is_subscribed,
                                )),
                            ]
                            .align_y(Alignment::Center),
                            text(truncate_label(&ch.description, 220))
                                .size(12)
                                .color(TEXT_SECONDARY),
                        ]
                        .spacing(8),
                    )
                    .padding(14)
                    .width(Length::Fill)
                    .style(active_cyan_card_style),
                );
                col = col.push(creator_grid_4col(
                    &ch.videos,
                    posters,
                    dearrow_enabled,
                    dearrow_map,
                    followed_keys,
                ));
                return col.into();
            } else {
                col = col.push(
                    container(
                        text("Click any @Channel pill under a YouTube video card to inspect its channel & subscribe.")
                            .size(13)
                            .color(TEXT_SECONDARY),
                    )
                    .padding(20)
                    .width(Length::Fill)
                    .style(glass_card_style),
                );
                return col.into();
            }
        }
        _ => {}
    }

    if feed.is_empty() {
        col = col.push(
            container(
                text(if loading {
                    "Fetching YouTube InnerTube feed..."
                } else {
                    "No videos in this feed yet. Sign in via QR for Subscriptions & History, or browse Home / Search."
                })
                .size(13)
                .color(TEXT_SECONDARY),
            )
            .padding(22)
            .width(Length::Fill)
            .style(glass_card_style),
        );
    } else {
        col = col.push(creator_grid_4col(
            feed,
            posters,
            dearrow_enabled,
            dearrow_map,
            followed_keys,
        ));
        if has_more {
            col = col.push(
                button(text("Load More Videos ▼").size(13).font(FONT_SEMIBOLD))
                    .padding([9, 20])
                    .style(pill_button_style(false))
                    .on_press(Message::YtLoadMoreFeed),
            );
        }
    }

    col.into()
}

#[allow(clippy::too_many_arguments)]
fn render_twitch_section<'a>(
    profile: Option<&'a TwitchAuthProfile>,
    device_sess: Option<&'a DeviceCodeSession>,
    qr_data: Option<&'a qr_code::Data>,
    tab: &'a TwitchTab,
    items: &'a [CreatorVideoItem],
    categories: &'a [CreatorCategory],
    channel: Option<&'a CreatorChannel>,
    search_query: &'a str,
    follows: &'a [CreatorFollowEntry],
    followed_keys: &HashSet<(String, String)>,
    dearrow_map: &HashMap<String, String>,
    loading: bool,
    posters: &PosterLruCache,
) -> Element<'a, Message> {
    let mut header = row![
        button(text("Top Live").size(12))
            .padding([5, 12])
            .style(pill_button_style(matches!(tab, TwitchTab::TopLive)))
            .on_press(Message::TwitchSelectTab(TwitchTab::TopLive)),
        button(text("Following").size(12))
            .padding([5, 12])
            .style(pill_button_style(matches!(tab, TwitchTab::FollowedLive)))
            .on_press(Message::TwitchSelectTab(TwitchTab::FollowedLive)),
        button(text("Categories").size(12))
            .padding([5, 12])
            .style(pill_button_style(matches!(
                tab,
                TwitchTab::Categories | TwitchTab::CategoryStreams(_)
            )))
            .on_press(Message::TwitchSelectTab(TwitchTab::Categories)),
        button(text("Channel").size(12))
            .padding([5, 12])
            .style(pill_button_style(matches!(tab, TwitchTab::Channel)))
            .on_press(Message::TwitchSelectTab(TwitchTab::Channel)),
        button(text("🔍 Search").size(12))
            .padding([5, 12])
            .style(pill_button_style(matches!(tab, TwitchTab::Search)))
            .on_press(Message::TwitchSelectTab(TwitchTab::Search)),
        Space::with_width(Length::Fill),
    ]
    .spacing(8)
    .align_y(Alignment::Center);

    if let Some(tp) = profile {
        header = header.push(status_pill(
            format!("Twitch: @{}", tp.display_name),
            ACCENT_EMERALD,
        ));
        header = header.push(
            button(text("Sign Out").size(11))
                .padding([4, 10])
                .style(danger_button_style)
                .on_press(Message::TwitchLogout),
        );
    } else {
        header = header.push(
            button(text("+ QR Sign In (twitch.tv/activate)").size(11).font(FONT_SEMIBOLD))
                .padding([5, 12])
                .style(accent_indigo_button_style)
                .on_press(Message::TwitchStartDeviceAuth),
        );
    }
    header = header.push(status_pill("Ad-Free HLS Proxy".into(), ACCENT_INDIGO));

    let mut col = column![
        container(header)
            .padding(12)
            .width(Length::Fill)
            .style(glass_card_style)
    ]
    .spacing(14);

    if let Some(sess) = device_sess {
        col = col.push(render_device_qr_banner(
            "Twitch OAuth2 Device Sign-In (Syncs Live Followed Channels)",
            sess,
            qr_data,
        ));
    }

    match tab {
        TwitchTab::Categories => {
            let mut cat_grid = column![section_header("Top Twitch Categories", "Click to view live streams")].spacing(12);
            for chunk in categories.chunks(5) {
                let mut r = row![].spacing(12);
                for c in chunk {
                    r = r.push(
                        button(
                            column![
                                text(truncate_label(&c.name, 24))
                                    .size(13)
                                    .font(FONT_SEMIBOLD)
                                    .color(TEXT_PRIMARY),
                                text(&c.viewers_text)
                                    .size(11)
                                    .font(FONT_MONO)
                                    .color(ACCENT_ROSE),
                            ]
                            .spacing(4)
                            .width(Length::Fixed(210.0)),
                        )
                        .padding(12)
                        .style(crate::theme::ghost_card_button_style)
                        .on_press(Message::TwitchSelectTab(TwitchTab::CategoryStreams(
                            c.name.clone(),
                        ))),
                    );
                }
                cat_grid = cat_grid.push(r);
            }
            col = col.push(cat_grid);
            return col.into();
        }
        TwitchTab::Search => {
            col = col.push(
                container(
                    row![
                        text_input("🔍 Search Twitch live channels & VODs...", search_query)
                            .on_input(Message::TwitchSearchChanged)
                            .on_submit(Message::TwitchSearchSubmit)
                            .padding([10, 14])
                            .size(14)
                            .style(search_input_style)
                            .width(Length::Fill),
                        button(text("Search ↵").size(13).font(FONT_SEMIBOLD))
                            .padding([9, 16])
                            .style(primary_cyan_button_style)
                            .on_press(Message::TwitchSearchSubmit),
                    ]
                    .spacing(10)
                    .align_y(Alignment::Center),
                )
                .padding(12)
                .width(Length::Fill)
                .style(glass_card_style),
            );
        }
        TwitchTab::Channel => {
            if let Some(ch) = channel {
                let mut ch_items = Vec::new();
                if let Some(live) = &ch.live_stream {
                    ch_items.push(live.clone());
                }
                ch_items.extend(ch.videos.iter().cloned());
                col = col.push(
                    container(
                        column![
                            text(&ch.name).size(20).font(FONT_BOLD).color(TEXT_PRIMARY),
                            text(format!("@{} · {}", ch.handle, ch.subscriber_text))
                                .size(12)
                                .color(TEXT_SECONDARY),
                            text(truncate_label(&ch.description, 220))
                                .size(12)
                                .color(TEXT_DIM),
                        ]
                        .spacing(6),
                    )
                    .padding(14)
                    .width(Length::Fill)
                    .style(active_cyan_card_style),
                );
                col = col.push(creator_grid_4col(
                    &ch_items,
                    posters,
                    false,
                    dearrow_map,
                    followed_keys,
                ));
                return col.into();
            }
        }
        TwitchTab::FollowedLive => {
            let local_tw: Vec<&CreatorFollowEntry> =
                follows.iter().filter(|f| f.platform == "twitch").collect();
            if !local_tw.is_empty() {
                let mut f_row = row![text("Local Multipass Follows:")
                    .size(11)
                    .font(FONT_MONO)
                    .color(TEXT_DIM)]
                .spacing(6)
                .align_y(Alignment::Center);
                for f in local_tw.iter().take(10) {
                    f_row = f_row.push(
                        button(text(format!("@{}", f.name)).size(11))
                            .padding([4, 10])
                            .style(pill_button_style(false))
                            .on_press(Message::TwitchOpenChannel(f.channel_id.clone())),
                    );
                }
                col = col.push(f_row);
            }
        }
        TwitchTab::CategoryStreams(game) => {
            col = col.push(section_header(game, "Live Category Streams"));
        }
        TwitchTab::TopLive => {}
    }

    if items.is_empty() {
        col = col.push(
            container(
                text(if loading {
                    "Loading Twitch streams..."
                } else {
                    "No streams found in this view."
                })
                .size(13)
                .color(TEXT_SECONDARY),
            )
            .padding(20)
            .width(Length::Fill)
            .style(glass_card_style),
        );
    } else {
        col = col.push(creator_grid_4col(
            items,
            posters,
            false,
            dearrow_map,
            followed_keys,
        ));
    }

    col.into()
}

#[allow(clippy::too_many_arguments)]
fn render_creator_platform_section<'a>(
    platform: StreamingPlatform,
    sub_tab: CreatorSubTab,
    items: &'a [CreatorVideoItem],
    search_query: &'a str,
    follows: &'a [CreatorFollowEntry],
    followed_keys: &HashSet<(String, String)>,
    dearrow_map: &HashMap<String, String>,
    loading: bool,
    posters: &PosterLruCache,
) -> Element<'a, Message> {
    let nav = container(
        row![
            button(text("Featured").size(12))
                .padding([5, 12])
                .style(pill_button_style(sub_tab == CreatorSubTab::Featured))
                .on_press(Message::CreatorSelectSubTab(
                    platform,
                    CreatorSubTab::Featured
                )),
            button(text("● Live").size(12))
                .padding([5, 12])
                .style(pill_button_style(sub_tab == CreatorSubTab::Live))
                .on_press(Message::CreatorSelectSubTab(platform, CreatorSubTab::Live)),
            button(text("Following").size(12))
                .padding([5, 12])
                .style(pill_button_style(sub_tab == CreatorSubTab::Following))
                .on_press(Message::CreatorSelectSubTab(
                    platform,
                    CreatorSubTab::Following
                )),
            button(text("🔍 Search").size(12))
                .padding([5, 12])
                .style(pill_button_style(sub_tab == CreatorSubTab::Search))
                .on_press(Message::CreatorSelectSubTab(
                    platform,
                    CreatorSubTab::Search
                )),
            Space::with_width(Length::Fill),
            status_pill(
                format!("{} Direct 1080p", platform.label()),
                ACCENT_CYAN
            ),
        ]
        .spacing(8)
        .align_y(Alignment::Center),
    )
    .padding(12)
    .width(Length::Fill)
    .style(glass_card_style);

    let mut col = column![nav].spacing(14);

    if sub_tab == CreatorSubTab::Search {
        col = col.push(
            container(
                row![
                    text_input(
                        &format!("🔍 Search {} videos & creators...", platform.label()),
                        search_query
                    )
                    .on_input(move |s| Message::CreatorSearchChanged(platform, s))
                    .on_submit(Message::CreatorSearchSubmit(platform))
                    .padding([10, 14])
                    .size(14)
                    .style(search_input_style)
                    .width(Length::Fill),
                    button(text("Search ↵").size(13).font(FONT_SEMIBOLD))
                        .padding([9, 16])
                        .style(primary_cyan_button_style)
                        .on_press(Message::CreatorSearchSubmit(platform)),
                ]
                .spacing(10)
                .align_y(Alignment::Center),
            )
            .padding(12)
            .width(Length::Fill)
            .style(glass_card_style),
        );
    }

    if sub_tab == CreatorSubTab::Following {
        let slug = platform.slug();
        let plat_follows: Vec<&CreatorFollowEntry> =
            follows.iter().filter(|f| f.platform == slug).collect();
        if plat_follows.is_empty() {
            col = col.push(
                container(
                    text(format!(
                        "No followed {} creators yet. Click `+ Follow` under any video card — follows sync E2EE across devices via Multipass!",
                        platform.label()
                    ))
                    .size(13)
                    .color(TEXT_SECONDARY),
                )
                .padding(20)
                .width(Length::Fill)
                .style(glass_card_style),
            );
            return col.into();
        } else {
            let mut f_row = row![text("Followed Creators:")
                .size(11)
                .font(FONT_MONO)
                .color(TEXT_DIM)]
            .spacing(6)
            .align_y(Alignment::Center);
            for f in plat_follows {
                f_row = f_row.push(
                    button(text(&f.name).size(11))
                        .padding([4, 10])
                        .style(pill_button_style(true))
                        .on_press(Message::CreatorSearchChanged(platform, f.name.clone())),
                );
            }
            col = col.push(f_row);
        }
    }

    if items.is_empty() {
        col = col.push(
            container(
                text(if loading {
                    "Fetching creator streams..."
                } else {
                    "No videos loaded for this tab."
                })
                .size(13)
                .color(TEXT_SECONDARY),
            )
            .padding(20)
            .width(Length::Fill)
            .style(glass_card_style),
        );
    } else {
        col = col.push(creator_grid_4col(
            items,
            posters,
            false,
            dearrow_map,
            followed_keys,
        ));
    }

    col.into()
}

fn render_device_qr_banner<'a>(
    heading: &'a str,
    sess: &'a DeviceCodeSession,
    qr_opt: Option<&'a qr_code::Data>,
) -> Element<'a, Message> {
    let qr_el: Element<'a, Message> = if let Some(qr) = qr_opt {
        container(qr_code(qr).cell_size(4))
            .padding(10)
            .style(qr_box_style)
            .into()
    } else {
        Space::with_width(Length::Shrink).into()
    };

    container(
        row![
            qr_el,
            column![
                text(heading)
                    .size(15)
                    .font(FONT_SEMIBOLD)
                    .color(TEXT_PRIMARY),
                text(format!("1. Scan QR or visit: {}", sess.verification_url))
                    .size(13)
                    .color(TEXT_SECONDARY),
                row![
                    text("2. Enter Device Code:").size(13).color(TEXT_SECONDARY),
                    text(&sess.user_code)
                        .size(26)
                        .font(FONT_BOLD)
                        .color(ACCENT_CYAN),
                ]
                .spacing(10)
                .align_y(Alignment::Center),
                text("Waiting for approval... (polling automatically)")
                    .size(11)
                    .font(FONT_MONO)
                    .color(ACCENT_EMERALD),
            ]
            .spacing(6)
            .width(Length::Fill),
        ]
        .spacing(18)
        .align_y(Alignment::Center),
    )
    .padding(16)
    .width(Length::Fill)
    .style(active_cyan_card_style)
    .into()
}
