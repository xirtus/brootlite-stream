//! View 1: `Board` — Inline Stremio Search Bar, Merged Continue Watching, & Synced Stremio Rows.

use crate::poster_cache::PosterLruCache;
use crate::state::{CatalogRow, ContinueWatchingCard, Message};
use crate::theme::{
    glass_card_style, pill_button_style, primary_cyan_button_style, search_input_style,
    ACCENT_CYAN, ACCENT_EMERALD, FONT_MONO, FONT_SEMIBOLD, TEXT_DIM, TEXT_PRIMARY, TEXT_SECONDARY,
};
use crate::views::components::{
    continue_watching_card, poster_card, section_header, status_pill,
};
use iced::widget::{button, column, container, row, scrollable, text, text_input, Space};
use iced::{Alignment, Element, Length};

pub fn view_board<'a>(
    inline_query: &'a str,
    continue_watching: &'a [ContinueWatchingCard],
    catalog_rows: &'a [CatalogRow],
    loading: bool,
    stremio_linked: bool,
    stremio_email: &'a str,
    jf_count: usize,
    yt_count: usize,
    posters: &'a PosterLruCache,
) -> Element<'a, Message> {
    // 1. Top Inline Stremio Search Input Bar
    let search_bar = container(
        row![
            text_input(
                "🔍 Search Stremio Movies, Series, Anime & Addons...",
                inline_query
            )
            .on_input(Message::BoardSearchInputChanged)
            .on_submit(Message::BoardSearchSubmit)
            .padding([11, 16])
            .size(15)
            .style(search_input_style)
            .width(Length::Fill),
            button(
                text("Search Catalog + Swarm ↵")
                    .size(13)
                    .font(FONT_SEMIBOLD)
            )
            .padding([10, 18])
            .style(primary_cyan_button_style)
            .on_press(Message::BoardSearchSubmit),
            button(text("↻ Refresh").size(13).font(FONT_SEMIBOLD))
                .padding([10, 14])
                .style(pill_button_style(false))
                .on_press(Message::RefreshBoard),
        ]
        .spacing(10)
        .align_y(Alignment::Center),
    )
    .padding(14)
    .width(Length::Fill)
    .style(glass_card_style);

    // Quick ecosystem status strip
    let mut eco_row = row![].spacing(8).align_y(Alignment::Center);
    if stremio_linked {
        eco_row = eco_row.push(status_pill(
            format!("Stremio Synced ({stremio_email})"),
            ACCENT_EMERALD,
        ));
    } else {
        eco_row = eco_row.push(status_pill(
            "Stremio Guest (Cinemeta + Streaming Catalogs)".into(),
            ACCENT_CYAN,
        ));
    }
    if jf_count > 0 {
        eco_row = eco_row.push(status_pill(
            format!("{jf_count} Jellyfin Server(s)"),
            ACCENT_EMERALD,
        ));
    }
    if yt_count > 0 {
        eco_row = eco_row.push(status_pill(
            format!("{yt_count} YouTube Account(s)"),
            ACCENT_EMERALD,
        ));
    }
    eco_row = eco_row.push(Space::with_width(Length::Fill));
    eco_row = eco_row.push(
        text(format!("LRU Poster Cache: {}/64", posters.len()))
            .size(11)
            .font(FONT_MONO)
            .color(TEXT_DIM),
    );

    let mut content = column![search_bar, eco_row].spacing(18);

    // 2. Merged Continue Watching Section
    if !continue_watching.is_empty() {
        let mut cw_strip = row![].spacing(12);
        for (idx, item) in continue_watching.iter().take(16).enumerate() {
            cw_strip = cw_strip.push(continue_watching_card(idx, item, posters));
        }

        let cw_scroll = scrollable(cw_strip).direction(scrollable::Direction::Horizontal(
            scrollable::Scrollbar::new().width(5).scroller_width(5),
        ));

        content = content.push(
            column![
                section_header(
                    "Continue Watching",
                    "Stremio · Multi-Server Jellyfin · YouTube & Local Resume"
                ),
                cw_scroll,
            ]
            .spacing(10),
        );
    }

    // 3. Synced Stremio Catalog Poster Rows
    if catalog_rows.is_empty() {
        content = content.push(
            container(
                text(if loading {
                    "Loading Stremio catalog rows..."
                } else {
                    "No catalog rows loaded yet. Click ↻ Refresh or check your network."
                })
                .size(14)
                .color(TEXT_SECONDARY),
            )
            .padding(24)
            .width(Length::Fill)
            .style(glass_card_style),
        );
    } else {
        for cat_row in catalog_rows {
            if cat_row.items.is_empty() {
                continue;
            }
            let mut poster_strip = row![].spacing(12);
            for item in cat_row.items.iter().take(18) {
                poster_strip = poster_strip.push(poster_card(item, posters, 168.0, 226.0));
            }
            let horiz = scrollable(poster_strip).direction(scrollable::Direction::Horizontal(
                scrollable::Scrollbar::new().width(5).scroller_width(5),
            ));
            content = content.push(
                column![
                    row![
                        text(&cat_row.title)
                            .size(18)
                            .font(FONT_SEMIBOLD)
                            .color(TEXT_PRIMARY),
                        text(&cat_row.subtitle).size(12).color(TEXT_SECONDARY),
                    ]
                    .spacing(10)
                    .align_y(Alignment::Center),
                    horiz,
                ]
                .spacing(10),
            );
        }
    }

    scrollable(content.padding([16, 22])).height(Length::Fill).into()
}
