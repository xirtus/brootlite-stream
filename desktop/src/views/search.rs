//! View 5: `Search` — Prominent Stremio Catalog Search on top + Direct P2P Swarm Search below.

use crate::poster_cache::PosterLruCache;
use crate::state::{Message, SearchCatalogFilter, SwarmSourceTab};
use crate::theme::{
    glass_card_style, pill_button_style, primary_cyan_button_style, search_input_style,
    ACCENT_CYAN, ACCENT_INDIGO, FONT_MONO, FONT_SEMIBOLD, TEXT_DIM, TEXT_SECONDARY,
};
use crate::views::components::{
    poster_grid_6col, section_header, status_pill, stream_candidate_row,
};
use brootlite_core::models::{MetaItem, StreamCandidate};
use iced::widget::{button, column, container, row, scrollable, text, text_input, Space};
use iced::{Alignment, Element, Length};

const QUICK_SEARCH_CHIPS: &[&str] = &[
    "Dune",
    "Severance",
    "Interstellar",
    "Solo Leveling",
    "Arcane",
    "Cyberpunk Edgerunners",
    "The Bear",
    "Frieren",
];

pub fn view_search<'a>(
    query: &'a str,
    catalog_filter: SearchCatalogFilter,
    catalog_results: &'a [MetaItem],
    catalog_loading: bool,
    swarm_tab: SwarmSourceTab,
    swarm_results: &'a [StreamCandidate],
    swarm_featured: &'a [StreamCandidate],
    swarm_loading: bool,
    posters: &'a PosterLruCache,
) -> Element<'a, Message> {
    // Search input bar
    let top_bar = container(
        column![
            row![
                text_input(
                    "🔍 Search Stremio Catalogs (Movies, Series, Anime) & Direct P2P Swarm...",
                    query
                )
                .on_input(Message::SearchQueryChanged)
                .on_submit(Message::SearchSubmit)
                .padding([11, 16])
                .size(15)
                .style(search_input_style)
                .width(Length::Fill),
                button(text("Search All ↵").size(13).font(FONT_SEMIBOLD))
                    .padding([10, 18])
                    .style(primary_cyan_button_style)
                    .on_press(Message::SearchSubmit),
            ]
            .spacing(10)
            .align_y(Alignment::Center),
            {
                let mut chips = row![text("Quick Search:")
                    .size(11)
                    .font(FONT_MONO)
                    .color(TEXT_DIM)]
                .spacing(6)
                .align_y(Alignment::Center);
                for chip in QUICK_SEARCH_CHIPS {
                    chips = chips.push(
                        button(text(*chip).size(11))
                            .padding([4, 10])
                            .style(pill_button_style(query.eq_ignore_ascii_case(chip)))
                            .on_press(Message::SearchQuickChip((*chip).to_string())),
                    );
                }
                chips
            }
        ]
        .spacing(10),
    )
    .padding(14)
    .width(Length::Fill)
    .style(glass_card_style);

    // ── Section 1: Prominent Stremio Catalog Search ──────────────────────────
    let filtered_catalog: Vec<MetaItem> = catalog_results
        .iter()
        .filter(|m| match catalog_filter {
            SearchCatalogFilter::All => true,
            SearchCatalogFilter::Movies => m.media_type == "movie",
            SearchCatalogFilter::Series => m.media_type == "series",
            SearchCatalogFilter::Anime => {
                m.media_type == "anime"
                    || m.genres.iter().any(|g| {
                        g.eq_ignore_ascii_case("Animation") || g.eq_ignore_ascii_case("Anime")
                    })
            }
        })
        .cloned()
        .collect();

    let mut cat_header = row![
        section_header(
            "Stremio Catalog Search",
            "Cinemeta & Installed Catalog Addons"
        ),
        Space::with_width(Length::Fill),
    ]
    .spacing(8)
    .align_y(Alignment::Center);

    for f in [
        SearchCatalogFilter::All,
        SearchCatalogFilter::Movies,
        SearchCatalogFilter::Series,
        SearchCatalogFilter::Anime,
    ] {
        cat_header = cat_header.push(
            button(text(f.label()).size(12))
                .padding([5, 12])
                .style(pill_button_style(catalog_filter == f))
                .on_press(Message::SearchSelectCatalogFilter(f)),
        );
    }

    let cat_body: Element<'a, Message> = if query.trim().is_empty() && catalog_results.is_empty() {
        container(
            text("Type a title above or pick a Quick Search chip to search Stremio Movies, Series, & Anime.")
                .size(13)
                .color(TEXT_SECONDARY),
        )
        .padding(20)
        .width(Length::Fill)
        .style(glass_card_style)
        .into()
    } else if catalog_loading && filtered_catalog.is_empty() {
        container(
            text("Searching Stremio catalogs...")
                .size(13)
                .color(TEXT_SECONDARY),
        )
        .padding(20)
        .width(Length::Fill)
        .style(glass_card_style)
        .into()
    } else if filtered_catalog.is_empty() {
        container(
            text("No Stremio catalog items matched this filter.")
                .size(13)
                .color(TEXT_SECONDARY),
        )
        .padding(20)
        .width(Length::Fill)
        .style(glass_card_style)
        .into()
    } else {
        poster_grid_6col(&filtered_catalog, posters)
    };

    // ── Section 2: Direct P2P Swarm Search Below ─────────────────────────────
    let mut swarm_header = row![
        section_header(
            "Direct P2P Swarm",
            "Public Domain Archive · Custom RSS · Torznab"
        ),
        Space::with_width(Length::Fill),
    ]
    .spacing(8)
    .align_y(Alignment::Center);

    for tab in [
        SwarmSourceTab::AllSwarm,
        SwarmSourceTab::PublicDomain,
        SwarmSourceTab::CustomRss,
        SwarmSourceTab::Torznab,
    ] {
        swarm_header = swarm_header.push(
            button(text(tab.label()).size(12))
                .padding([5, 12])
                .style(pill_button_style(swarm_tab == tab))
                .on_press(Message::SearchSelectSwarmTab(tab)),
        );
    }

    let feed_bar = row![
        text("Curated Live Swarm Feeds:")
            .size(11)
            .font(FONT_MONO)
            .color(TEXT_DIM),
        button(text("Public Domain Films").size(11))
            .padding([4, 10])
            .style(pill_button_style(false))
            .on_press(Message::SearchLoadSwarmFeatured("archive_movies".into())),
        button(text("Classic Sci-Fi").size(11))
            .padding([4, 10])
            .style(pill_button_style(false))
            .on_press(Message::SearchLoadSwarmFeatured("archive_scifi".into())),
        button(text("Classic Animation").size(11))
            .padding([4, 10])
            .style(pill_button_style(false))
            .on_press(Message::SearchLoadSwarmFeatured("archive_animation".into())),
        button(text("Configured Torznab").size(11))
            .padding([4, 10])
            .style(pill_button_style(false))
            .on_press(Message::SearchLoadSwarmFeatured("torznab".into())),
    ]
    .spacing(8)
    .align_y(Alignment::Center);

    let active_pool = if !query.trim().is_empty() || !swarm_results.is_empty() {
        swarm_results
    } else {
        swarm_featured
    };

    let filtered_swarm: Vec<&StreamCandidate> = active_pool
        .iter()
        .filter(|s| {
            let src = s.addon_name.to_ascii_lowercase();
            match swarm_tab {
                SwarmSourceTab::AllSwarm => true,
                SwarmSourceTab::PublicDomain => {
                    src.contains("archive") || src.contains("public")
                }
                SwarmSourceTab::CustomRss => src.contains("rss"),
                SwarmSourceTab::Torznab => src.contains("torznab"),
            }
        })
        .collect();

    let mut swarm_list = column![].spacing(8);
    if swarm_loading && filtered_swarm.is_empty() {
        swarm_list = swarm_list.push(
            container(
                text("Querying decentralized P2P swarm indexers...")
                    .size(13)
                    .color(TEXT_SECONDARY),
            )
            .padding(20)
            .width(Length::Fill)
            .style(glass_card_style),
        );
    } else if filtered_swarm.is_empty() {
        swarm_list = swarm_list.push(
            container(
                row![
                    status_pill("P2P READY".into(), ACCENT_INDIGO),
                    text("Run a search or click a Curated Live Swarm Feed above to stream torrents directly via librqbit loopback.")
                        .size(13)
                        .color(TEXT_SECONDARY),
                ]
                .spacing(10)
                .align_y(Alignment::Center),
            )
            .padding(18)
            .width(Length::Fill)
            .style(glass_card_style),
        );
    } else {
        let _ = ACCENT_CYAN;
        for cand in filtered_swarm.into_iter().take(150) {
            swarm_list = swarm_list.push(stream_candidate_row(cand, None, None, None, None, 0));
        }
    }

    scrollable(
        column![
            top_bar,
            cat_header,
            cat_body,
            swarm_header,
            feed_bar,
            swarm_list,
        ]
        .spacing(16)
        .padding([16, 22]),
    )
    .height(Length::Fill)
    .into()
}
