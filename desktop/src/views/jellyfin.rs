//! View 3: `Jellyfin` — Strictly Isolated Multi-Server Browser, UDP 7359 LAN Scan, QuickConnect, & Password Auth.

use crate::poster_cache::PosterLruCache;
use crate::state::{
    IsolatedJellyfinServerState, JellyfinAddServerWizard, JellyfinSubMode, Message,
};
use crate::theme::{
    accent_indigo_button_style, active_cyan_card_style, danger_button_style, glass_card_style,
    pill_button_style, primary_cyan_button_style, search_input_style, ACCENT_CYAN, ACCENT_EMERALD,
    ACCENT_INDIGO, FONT_BOLD, FONT_MONO, FONT_SEMIBOLD, TEXT_DIM, TEXT_PRIMARY, TEXT_SECONDARY,
};
use crate::views::components::{poster_card, poster_grid_6col, section_header, status_pill};
use brootlite_core::models::JellyfinServerProfile;
use iced::widget::{button, column, container, row, scrollable, text, text_input, Space};
use iced::{Alignment, Element, Length};

pub fn view_jellyfin<'a>(
    servers: &'a [JellyfinServerProfile],
    active_server_id: Option<&'a str>,
    sub_mode: &'a JellyfinSubMode,
    isolated: &'a IsolatedJellyfinServerState,
    wizard: &'a JellyfinAddServerWizard,
    posters: &'a PosterLruCache,
) -> Element<'a, Message> {
    // 1. Multi-Server Switcher Header (`Server 1`, `Server 2`, `⚙ Settings`, `+ Add Server`)
    let mut srv_bar = row![text("Jellyfin Servers:")
        .size(12)
        .font(FONT_MONO)
        .color(TEXT_DIM)]
    .spacing(8)
    .align_y(Alignment::Center);

    for (idx, srv) in servers.iter().enumerate() {
        let is_active = active_server_id == Some(srv.server_id.as_str())
            && !matches!(sub_mode, JellyfinSubMode::AddServer);
        let label = if srv.server_name.trim().is_empty() {
            format!("Server {}", idx + 1)
        } else {
            format!("{} · {}", idx + 1, srv.server_name)
        };
        srv_bar = srv_bar.push(
            button(text(label).size(12).font(FONT_SEMIBOLD))
                .padding([6, 13])
                .style(pill_button_style(is_active))
                .on_press(Message::JellyfinSwitchServer(srv.server_id.clone())),
        );
    }

    srv_bar = srv_bar.push(Space::with_width(Length::Fill));

    if active_server_id.is_some() {
        srv_bar = srv_bar.push(
            button(text("⚙ Settings").size(12))
                .padding([6, 12])
                .style(pill_button_style(matches!(
                    sub_mode,
                    JellyfinSubMode::Settings
                )))
                .on_press(Message::JellyfinSetSubMode(JellyfinSubMode::Settings)),
        );
    }

    srv_bar = srv_bar.push(
        button(text("+ Add Server").size(12).font(FONT_SEMIBOLD))
            .padding([6, 14])
            .style(primary_cyan_button_style)
            .on_press(Message::JellyfinSetSubMode(JellyfinSubMode::AddServer)),
    );

    let srv_card = container(srv_bar)
        .padding(12)
        .width(Length::Fill)
        .style(glass_card_style);

    // If no server is configured or AddServer mode is active, render the onboarding wizard
    if servers.is_empty() || matches!(sub_mode, JellyfinSubMode::AddServer) {
        return scrollable(
            column![srv_card, render_add_server_wizard(wizard)]
                .spacing(16)
                .padding([16, 22]),
        )
        .height(Length::Fill)
        .into();
    }

    let Some(active_srv) = active_server_id.and_then(|id| servers.iter().find(|s| s.server_id == id))
    else {
        return scrollable(column![srv_card].padding([16, 22]))
            .height(Length::Fill)
            .into();
    };

    // 2. Strictly Isolated Per-Server Sub-Navigation Bar
    let mut sub_nav = row![
        status_pill(
            format!("ACTIVE: {}", active_srv.server_name),
            ACCENT_EMERALD
        ),
        button(text("Overview").size(12))
            .padding([5, 12])
            .style(pill_button_style(matches!(
                sub_mode,
                JellyfinSubMode::Overview
            )))
            .on_press(Message::JellyfinSetSubMode(JellyfinSubMode::Overview)),
        button(text("Resume").size(12))
            .padding([5, 12])
            .style(pill_button_style(matches!(
                sub_mode,
                JellyfinSubMode::Resume
            )))
            .on_press(Message::JellyfinSetSubMode(JellyfinSubMode::Resume)),
        button(text("Next Up").size(12))
            .padding([5, 12])
            .style(pill_button_style(matches!(
                sub_mode,
                JellyfinSubMode::NextUp
            )))
            .on_press(Message::JellyfinSetSubMode(JellyfinSubMode::NextUp)),
    ]
    .spacing(8)
    .align_y(Alignment::Center);

    for v in &isolated.views {
        let active_view = matches!(sub_mode, JellyfinSubMode::LibraryView(id) if id == &v.id);
        sub_nav = sub_nav.push(
            button(text(&v.name).size(12))
                .padding([5, 12])
                .style(pill_button_style(active_view))
                .on_press(Message::JellyfinSetSubMode(JellyfinSubMode::LibraryView(
                    v.id.clone(),
                ))),
        );
    }

    sub_nav = sub_nav.push(Space::with_width(Length::Fill));
    sub_nav = sub_nav.push(
        button(text("🔍 Server Search").size(12))
            .padding([5, 12])
            .style(pill_button_style(matches!(
                sub_mode,
                JellyfinSubMode::Search
            )))
            .on_press(Message::JellyfinSetSubMode(JellyfinSubMode::Search)),
    );

    let sub_nav_scroll = scrollable(sub_nav).direction(scrollable::Direction::Horizontal(
        scrollable::Scrollbar::new().width(4).scroller_width(4),
    ));

    let body: Element<'a, Message> = match sub_mode {
        JellyfinSubMode::Settings => render_server_settings(active_srv),
        JellyfinSubMode::Overview => render_overview(isolated, posters),
        JellyfinSubMode::Resume => column![
            section_header("Continue Watching", &active_srv.server_name),
            if isolated.resume_items.is_empty() {
                container(
                    text("No in-progress items on this Jellyfin server.")
                        .size(13)
                        .color(TEXT_SECONDARY),
                )
                .padding(20)
                .width(Length::Fill)
                .style(glass_card_style)
                .into()
            } else {
                poster_grid_6col(&isolated.resume_items, posters)
            }
        ]
        .spacing(12)
        .into(),
        JellyfinSubMode::NextUp => column![
            section_header("Next Up Episodes", &active_srv.server_name),
            if isolated.next_up_items.is_empty() {
                container(
                    text("No Next Up episodes queued on this Jellyfin server.")
                        .size(13)
                        .color(TEXT_SECONDARY),
                )
                .padding(20)
                .width(Length::Fill)
                .style(glass_card_style)
                .into()
            } else {
                poster_grid_6col(&isolated.next_up_items, posters)
            }
        ]
        .spacing(12)
        .into(),
        JellyfinSubMode::LibraryView(view_id) => {
            let vname = isolated
                .views
                .iter()
                .find(|v| &v.id == view_id)
                .map(|v| v.name.as_str())
                .unwrap_or("Library");
            render_library_view(vname, isolated, posters)
        }
        JellyfinSubMode::Search => render_server_search(active_srv, isolated, posters),
        JellyfinSubMode::AddServer => render_add_server_wizard(wizard),
    };

    scrollable(
        column![srv_card, sub_nav_scroll, body]
            .spacing(16)
            .padding([16, 22]),
    )
    .height(Length::Fill)
    .into()
}

fn render_overview<'a>(
    isolated: &'a IsolatedJellyfinServerState,
    posters: &'a PosterLruCache,
) -> Element<'a, Message> {
    let mut col = column![].spacing(18);
    if isolated.loading
        && isolated.resume_items.is_empty()
        && isolated.latest_by_view.is_empty()
    {
        return container(
            text("Loading isolated Jellyfin server overview (1.2s LAN-first / Remote-fallback)...")
                .size(13)
                .color(TEXT_SECONDARY),
        )
        .padding(24)
        .width(Length::Fill)
        .style(glass_card_style)
        .into();
    }

    if !isolated.resume_items.is_empty() {
        let mut strip = row![].spacing(12);
        for item in isolated.resume_items.iter().take(14) {
            strip = strip.push(poster_card(item, posters, 168.0, 226.0));
        }
        col = col.push(
            column![
                section_header("Continue Watching", "Server Resume Queue"),
                scrollable(strip).direction(scrollable::Direction::Horizontal(
                    scrollable::Scrollbar::new().width(5).scroller_width(5),
                )),
            ]
            .spacing(8),
        );
    }

    if !isolated.next_up_items.is_empty() {
        let mut strip = row![].spacing(12);
        for item in isolated.next_up_items.iter().take(14) {
            strip = strip.push(poster_card(item, posters, 168.0, 226.0));
        }
        col = col.push(
            column![
                section_header("Next Up", "Series Episodes Ready to Stream"),
                scrollable(strip).direction(scrollable::Direction::Horizontal(
                    scrollable::Scrollbar::new().width(5).scroller_width(5),
                )),
            ]
            .spacing(8),
        );
    }

    for (vname, items) in &isolated.latest_by_view {
        if items.is_empty() {
            continue;
        }
        let mut strip = row![].spacing(12);
        for item in items.iter().take(16) {
            strip = strip.push(poster_card(item, posters, 168.0, 226.0));
        }
        col = col.push(
            column![
                section_header(&format!("Latest in {vname}"), "DirectPlay / HLS Auto"),
                scrollable(strip).direction(scrollable::Direction::Horizontal(
                    scrollable::Scrollbar::new().width(5).scroller_width(5),
                )),
            ]
            .spacing(8),
        );
    }

    if isolated.resume_items.is_empty()
        && isolated.next_up_items.is_empty()
        && isolated.latest_by_view.is_empty()
    {
        col = col.push(
            container(
                text("Connected to Jellyfin server. Select a Library View tab above to browse items.")
                    .size(13)
                    .color(TEXT_SECONDARY),
            )
            .padding(20)
            .width(Length::Fill)
            .style(glass_card_style),
        );
    }

    col.into()
}

fn render_library_view<'a>(
    vname: &'a str,
    isolated: &'a IsolatedJellyfinServerState,
    posters: &'a PosterLruCache,
) -> Element<'a, Message> {
    // Genre, Year, & Sort bar
    let mut sort_row = row![
        section_header(vname, "Isolated Server Library"),
        Space::with_width(Length::Fill),
        text("Sort:").size(11).font(FONT_MONO).color(TEXT_DIM),
    ]
    .spacing(8)
    .align_y(Alignment::Center);

    for (sb, so, lbl) in [
        ("SortName", "Ascending", "A–Z"),
        ("DateCreated", "Descending", "Date Added"),
        ("PremiereDate", "Descending", "Release Date"),
        ("CommunityRating", "Descending", "Top Rated"),
    ] {
        let active = isolated.sort_by == sb && isolated.sort_order == so;
        sort_row = sort_row.push(
            button(text(lbl).size(11))
                .padding([4, 10])
                .style(pill_button_style(active))
                .on_press(Message::JellyfinSelectSort(sb.into(), so.into())),
        );
    }

    let mut genre_strip = row![text("Genre:").size(11).font(FONT_MONO).color(TEXT_DIM)]
        .spacing(6)
        .align_y(Alignment::Center);
    genre_strip = genre_strip.push(
        button(text("All").size(11))
            .padding([4, 10])
            .style(pill_button_style(isolated.selected_genre == "All"))
            .on_press(Message::JellyfinSelectGenre("All".into())),
    );
    for g in isolated.genres.iter().take(24) {
        genre_strip = genre_strip.push(
            button(text(g).size(11))
                .padding([4, 10])
                .style(pill_button_style(isolated.selected_genre == *g))
                .on_press(Message::JellyfinSelectGenre(g.clone())),
        );
    }

    let mut year_strip = row![text("Year:").size(11).font(FONT_MONO).color(TEXT_DIM)]
        .spacing(6)
        .align_y(Alignment::Center);
    year_strip = year_strip.push(
        button(text("All").size(11))
            .padding([4, 10])
            .style(pill_button_style(isolated.selected_year == "All"))
            .on_press(Message::JellyfinSelectYear("All".into())),
    );
    for y in isolated.years.iter().rev().take(20) {
        year_strip = year_strip.push(
            button(text(y).size(11).font(FONT_MONO))
                .padding([4, 10])
                .style(pill_button_style(isolated.selected_year == *y))
                .on_press(Message::JellyfinSelectYear(y.clone())),
        );
    }

    let filters = container(
        column![
            sort_row,
            scrollable(genre_strip).direction(scrollable::Direction::Horizontal(
                scrollable::Scrollbar::new().width(4).scroller_width(4),
            )),
            scrollable(year_strip).direction(scrollable::Direction::Horizontal(
                scrollable::Scrollbar::new().width(4).scroller_width(4),
            )),
        ]
        .spacing(10),
    )
    .padding(12)
    .width(Length::Fill)
    .style(glass_card_style);

    let pager = row![
        Space::with_width(Length::Fill),
        button(text("◀ Prev").size(12))
            .padding([5, 12])
            .style(pill_button_style(false))
            .on_press_maybe(if isolated.page_start >= 48 {
                Some(Message::JellyfinPageDelta(-48))
            } else {
                None
            }),
        text(format!("Offset {}", isolated.page_start))
            .size(12)
            .font(FONT_MONO)
            .color(TEXT_SECONDARY),
        button(text("Next ▶").size(12))
            .padding([5, 12])
            .style(pill_button_style(false))
            .on_press(Message::JellyfinPageDelta(48)),
    ]
    .spacing(8)
    .align_y(Alignment::Center);

    let grid = if isolated.library_items.is_empty() {
        container(
            text(if isolated.loading {
                "Loading library items..."
            } else {
                "No items matched in this Jellyfin library view."
            })
            .size(13)
            .color(TEXT_SECONDARY),
        )
        .padding(24)
        .width(Length::Fill)
        .style(glass_card_style)
        .into()
    } else {
        poster_grid_6col(&isolated.library_items, posters)
    };

    column![filters, pager, grid].spacing(14).into()
}

fn render_server_search<'a>(
    srv: &'a JellyfinServerProfile,
    isolated: &'a IsolatedJellyfinServerState,
    posters: &'a PosterLruCache,
) -> Element<'a, Message> {
    let bar = container(
        row![
            text_input(
                &format!("🔍 Search {} only (strictly isolated)...", srv.server_name),
                &isolated.search_query
            )
            .on_input(Message::JellyfinSearchChanged)
            .on_submit(Message::JellyfinSearchSubmit)
            .padding([10, 14])
            .size(14)
            .style(search_input_style)
            .width(Length::Fill),
            button(text("Search Server ↵").size(13).font(FONT_SEMIBOLD))
                .padding([9, 16])
                .style(primary_cyan_button_style)
                .on_press(Message::JellyfinSearchSubmit),
        ]
        .spacing(10)
        .align_y(Alignment::Center),
    )
    .padding(12)
    .width(Length::Fill)
    .style(glass_card_style);

    let results: Element<'a, Message> = if isolated.search_results.is_empty() {
        container(
            text("Enter a movie, series, or episode title to search this Jellyfin server.")
                .size(13)
                .color(TEXT_SECONDARY),
        )
        .padding(20)
        .width(Length::Fill)
        .style(glass_card_style)
        .into()
    } else {
        poster_grid_6col(&isolated.search_results, posters)
    };

    column![bar, results].spacing(14).into()
}

fn render_server_settings<'a>(srv: &'a JellyfinServerProfile) -> Element<'a, Message> {
    container(
        column![
            section_header(
                &srv.server_name,
                "Isolated Jellyfin Server Profile & Dual-URL Router"
            ),
            text(format!("Server ID: {}", srv.server_id))
                .size(12)
                .font(FONT_MONO)
                .color(TEXT_SECONDARY),
            text(format!("User: {} ({})", srv.username, srv.user_id))
                .size(13)
                .color(TEXT_PRIMARY),
            text(format!(
                "LAN Endpoint: {}",
                if srv.lan_url.is_empty() {
                    "(not set)"
                } else {
                    &srv.lan_url
                }
            ))
            .size(12)
            .font(FONT_MONO)
            .color(ACCENT_CYAN),
            text(format!(
                "Remote Endpoint: {}",
                if srv.remote_url.is_empty() {
                    "(not set)"
                } else {
                    &srv.remote_url
                }
            ))
            .size(12)
            .font(FONT_MONO)
            .color(ACCENT_INDIGO),
            text("Tokens are sealed at rest via blseal2: OS Keychain / Secret Service and synced E2EE via Leeloo Multipass v2.")
                .size(12)
                .color(TEXT_DIM),
            button(text("Remove This Jellyfin Server").size(12).font(FONT_SEMIBOLD))
                .padding([8, 16])
                .style(danger_button_style)
                .on_press(Message::JellyfinRemoveServer(srv.server_id.clone())),
        ]
        .spacing(10),
    )
    .padding(18)
    .width(Length::Fill)
    .style(glass_card_style)
    .into()
}

fn render_add_server_wizard<'a>(wizard: &'a JellyfinAddServerWizard) -> Element<'a, Message> {
    // 1. UDP 7359 LAN Discovery Card
    let mut lan_col = column![
        row![
            section_header(
                "1. Automatic LAN Server Discovery",
                "UDP Port 7359 Broadcast (`Who is JellyfinServer?`)"
            ),
            Space::with_width(Length::Fill),
            button(
                text(if wizard.scanning_lan {
                    "Scanning LAN..."
                } else {
                    "📡 Scan LAN (UDP 7359)"
                })
                .size(13)
                .font(FONT_SEMIBOLD)
            )
            .padding([8, 16])
            .style(primary_cyan_button_style)
            .on_press(Message::JfWizardScanLan),
        ]
        .align_y(Alignment::Center),
    ]
    .spacing(10);

    if !wizard.discovered_lan.is_empty() {
        let mut found_row = row![].spacing(10);
        for d in &wizard.discovered_lan {
            found_row = found_row.push(
                button(
                    column![
                        text(&d.name).size(13).font(FONT_SEMIBOLD).color(TEXT_PRIMARY),
                        text(&d.address).size(11).font(FONT_MONO).color(ACCENT_CYAN),
                    ]
                    .spacing(3),
                )
                .padding([8, 14])
                .style(pill_button_style(wizard.lan_url_input == d.address))
                .on_press(Message::JfWizardPickDiscovered(d.clone())),
            );
        }
        lan_col = lan_col.push(found_row);
    }

    let lan_card = container(lan_col)
        .padding(16)
        .width(Length::Fill)
        .style(glass_card_style);

    // 2. Endpoint URLs + QuickConnect / Username-Password Auth
    let endpoints_card = container(
        column![
            section_header(
                "2. Server Endpoints",
                "1.2s LAN-First Probe with Seamless Remote Tailscale / Reverse-Proxy Fallback"
            ),
            row![
                column![
                    text("Primary / LAN URL").size(12).color(TEXT_SECONDARY),
                    text_input("http://192.168.1.50:8096", &wizard.lan_url_input)
                        .on_input(Message::JfWizardLanUrlChanged)
                        .padding([9, 12])
                        .size(14)
                        .style(search_input_style),
                ]
                .spacing(5)
                .width(Length::Fill),
                column![
                    text("Optional Remote / Tailscale URL")
                        .size(12)
                        .color(TEXT_SECONDARY),
                    text_input("https://jellyfin.example.com", &wizard.remote_url_input)
                        .on_input(Message::JfWizardRemoteUrlChanged)
                        .padding([9, 12])
                        .size(14)
                        .style(search_input_style),
                ]
                .spacing(5)
                .width(Length::Fill),
            ]
            .spacing(14),
        ]
        .spacing(10),
    )
    .padding(16)
    .width(Length::Fill)
    .style(glass_card_style);

    // 3A. 6-Digit QuickConnect Card
    let mut qc_col = column![
        section_header(
            "3A. 6-Digit QuickConnect",
            "Zero-Password Approval from Phone / Web Dashboard"
        ),
        button(
            text("⚡ Generate 6-Digit QuickConnect Code")
                .size(13)
                .font(FONT_SEMIBOLD)
        )
        .padding([9, 16])
        .style(accent_indigo_button_style)
        .on_press(Message::JfWizardStartQuickConnect),
    ]
    .spacing(10);

    if let Some(qc) = &wizard.quick_connect {
        qc_col = qc_col.push(
            container(
                column![
                    text("Enter this code in Jellyfin -> Settings -> Quick Connect:")
                        .size(12)
                        .color(TEXT_SECONDARY),
                    text(&qc.code).size(32).font(FONT_BOLD).color(ACCENT_CYAN),
                    text("Auto-polling server every 3s for approval...")
                        .size(11)
                        .font(FONT_MONO)
                        .color(ACCENT_EMERALD),
                ]
                .spacing(6),
            )
            .padding(14)
            .width(Length::Fill)
            .style(active_cyan_card_style),
        );
    }

    // 3B. Username & Password Card
    let pw_col = column![
        section_header("3B. Username & Password", "Direct MediaBrowser Token Auth"),
        text_input("Username", &wizard.username_input)
            .on_input(Message::JfWizardUsernameChanged)
            .padding([9, 12])
            .size(14)
            .style(search_input_style),
        text_input("Password (leave blank if passwordless LAN user)", &wizard.password_input)
            .on_input(Message::JfWizardPasswordChanged)
            .on_submit(Message::JfWizardLoginPassword)
            .secure(true)
            .padding([9, 12])
            .size(14)
            .style(search_input_style),
        button(text("Sign In & Seal Token").size(13).font(FONT_SEMIBOLD))
            .padding([9, 18])
            .style(primary_cyan_button_style)
            .on_press(Message::JfWizardLoginPassword),
    ]
    .spacing(10);

    let auth_row = row![
        container(qc_col)
            .padding(16)
            .width(Length::Fill)
            .style(glass_card_style),
        container(pw_col)
            .padding(16)
            .width(Length::Fill)
            .style(glass_card_style),
    ]
    .spacing(14);

    let mut out = column![lan_card, endpoints_card, auth_row].spacing(14);
    if !wizard.status_msg.is_empty() {
        out = out.push(
            container(
                text(&wizard.status_msg)
                    .size(13)
                    .font(FONT_MONO)
                    .color(ACCENT_CYAN),
            )
            .padding(12)
            .width(Length::Fill)
            .style(glass_card_style),
        );
    }
    out.into()
}
