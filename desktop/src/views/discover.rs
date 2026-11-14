//! View 2: `Discover` — Media Type, Catalog/Network/By-Year Selector, Genre/Year Filter, Sort, & 6-Col Grid.

use crate::poster_cache::PosterLruCache;
use crate::state::{DiscoverCatalogChoice, Message};
use crate::theme::{
    glass_card_style, pill_button_style, primary_cyan_button_style, FONT_MONO, FONT_SEMIBOLD,
    TEXT_DIM, TEXT_PRIMARY, TEXT_SECONDARY,
};
use crate::views::components::{poster_grid_6col, section_header};
use brootlite_core::models::{DiscoverSortMode, MetaItem};
use iced::widget::{button, column, container, row, scrollable, text, Space};
use iced::{Alignment, Element, Length};

const YEAR_FILTERS: &[&str] = &[
    "All", "2026", "2025", "2024", "2023", "2022", "2021", "2020", "2010-2019", "2000-2009",
    "1990-1999",
];

pub fn view_discover<'a>(
    media_types: &'a [String],
    active_type: &'a str,
    catalogs: &'a [DiscoverCatalogChoice],
    selected_catalog_idx: usize,
    selected_genre: &'a str,
    selected_year: &'a str,
    sort_mode: DiscoverSortMode,
    page_skip: u32,
    items: &'a [MetaItem],
    loading: bool,
    posters: &'a PosterLruCache,
) -> Element<'a, Message> {
    // Row 1: Media Type selector + Quick Search jump + Sort Mode pills
    let mut type_row = row![text("Type:").size(12).font(FONT_MONO).color(TEXT_DIM)]
        .spacing(8)
        .align_y(Alignment::Center);
    for mt in media_types {
        let active = mt == active_type;
        let pretty = match mt.as_str() {
            "movie" => "Movies".to_string(),
            "series" => "Series".to_string(),
            "anime" => "Anime".to_string(),
            other => {
                let mut c = other.chars();
                match c.next() {
                    Some(f) => format!("{}{}", f.to_uppercase(), c.as_str()),
                    None => other.to_string(),
                }
            }
        };
        type_row = type_row.push(
            button(text(pretty).size(12).font(FONT_SEMIBOLD))
                .padding([6, 12])
                .style(pill_button_style(active))
                .on_press(Message::DiscoverSelectMediaType(mt.clone())),
        );
    }

    type_row = type_row.push(Space::with_width(Length::Fill));

    // Sort Mode selector
    type_row = type_row.push(text("Sort:").size(12).font(FONT_MONO).color(TEXT_DIM));
    for (mode, label) in [
        (DiscoverSortMode::Popular, "Popular"),
        (DiscoverSortMode::TopRated, "Top Rated"),
        (DiscoverSortMode::YearDesc, "Newest"),
        (DiscoverSortMode::TitleAsc, "A–Z"),
    ] {
        type_row = type_row.push(
            button(text(label).size(12))
                .padding([5, 11])
                .style(pill_button_style(sort_mode == mode))
                .on_press(Message::DiscoverSelectSort(mode)),
        );
    }

    type_row = type_row.push(
        button(text("🔍 Search").size(12).font(FONT_SEMIBOLD))
            .padding([6, 14])
            .style(primary_cyan_button_style)
            .on_press(Message::DiscoverJumpToSearch),
    );

    // Row 2: Catalog / Streaming Network selector
    let mut cat_strip = row![text("Catalog / Network:")
        .size(12)
        .font(FONT_MONO)
        .color(TEXT_DIM)]
    .spacing(8)
    .align_y(Alignment::Center);

    for (idx, choice) in catalogs.iter().enumerate() {
        cat_strip = cat_strip.push(
            button(text(&choice.label).size(12))
                .padding([5, 12])
                .style(pill_button_style(idx == selected_catalog_idx))
                .on_press(Message::DiscoverSelectCatalog(idx)),
        );
    }
    let cat_scroll = scrollable(cat_strip).direction(scrollable::Direction::Horizontal(
        scrollable::Scrollbar::new().width(4).scroller_width(4),
    ));

    // Row 3: Genre extra filter
    let mut genres: Vec<&str> = vec!["All"];
    if let Some(cur) = catalogs.get(selected_catalog_idx) {
        for g in &cur.genres {
            if !g.eq_ignore_ascii_case("All") {
                genres.push(g.as_str());
            }
        }
    }
    let mut genre_strip = row![text("Genre:").size(12).font(FONT_MONO).color(TEXT_DIM)]
        .spacing(6)
        .align_y(Alignment::Center);
    for g in genres {
        genre_strip = genre_strip.push(
            button(text(g).size(11))
                .padding([4, 10])
                .style(pill_button_style(selected_genre.eq_ignore_ascii_case(g)))
                .on_press(Message::DiscoverSelectGenre(g.to_string())),
        );
    }
    let genre_scroll = scrollable(genre_strip).direction(scrollable::Direction::Horizontal(
        scrollable::Scrollbar::new().width(4).scroller_width(4),
    ));

    // Row 4: By-Year filter
    let mut year_strip = row![text("By Year:").size(12).font(FONT_MONO).color(TEXT_DIM)]
        .spacing(6)
        .align_y(Alignment::Center);
    for yr in YEAR_FILTERS {
        year_strip = year_strip.push(
            button(text(*yr).size(11).font(FONT_MONO))
                .padding([4, 10])
                .style(pill_button_style(selected_year == *yr))
                .on_press(Message::DiscoverSelectYear((*yr).to_string())),
        );
    }
    let year_scroll = scrollable(year_strip).direction(scrollable::Direction::Horizontal(
        scrollable::Scrollbar::new().width(4).scroller_width(4),
    ));

    let filter_deck = container(
        column![type_row, cat_scroll, genre_scroll, year_scroll].spacing(10),
    )
    .padding(14)
    .width(Length::Fill)
    .style(glass_card_style);

    // Pagination bar
    let page_num = (page_skip / 36) + 1;
    let pager = row![
        section_header(
            "Discover Catalog",
            if loading {
                "Fetching catalog items..."
            } else {
                "6-Column Hardware-Accelerated Poster Grid"
            }
        ),
        Space::with_width(Length::Fill),
        button(text("◀ Prev Page").size(12))
            .padding([6, 12])
            .style(pill_button_style(false))
            .on_press_maybe(if page_skip >= 36 {
                Some(Message::DiscoverPageDelta(-36))
            } else {
                None
            }),
        text(format!("Page {page_num} (skip {page_skip})"))
            .size(12)
            .font(FONT_MONO)
            .color(TEXT_PRIMARY),
        button(text("Next Page ▶").size(12))
            .padding([6, 12])
            .style(pill_button_style(false))
            .on_press(Message::DiscoverPageDelta(36)),
    ]
    .spacing(10)
    .align_y(Alignment::Center);

    let grid: Element<'a, Message> = if items.is_empty() {
        container(
            text(if loading {
                "Loading posters..."
            } else {
                "No items matched the selected catalog/genre/year filters."
            })
            .size(14)
            .color(TEXT_SECONDARY),
        )
        .padding(28)
        .width(Length::Fill)
        .style(glass_card_style)
        .into()
    } else {
        poster_grid_6col(items, posters)
    };

    scrollable(
        column![filter_deck, pager, grid]
            .spacing(16)
            .padding([16, 22]),
    )
    .height(Length::Fill)
    .into()
}
