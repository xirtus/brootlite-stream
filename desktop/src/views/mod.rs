//! Top Navigation Header (`Board · Discover · Jellyfin · Streaming · Search · Library · Kodi · Passport ↗`) & View Exports.

pub mod board;
pub mod components;
pub mod detail_player;
pub mod discover;
pub mod jellyfin;
pub mod library_kodi;
pub mod passport;
pub mod search;
pub mod streaming;

use crate::state::{ActiveOverlay, Message, NavTab};
use crate::theme::{
    indigo_banner_style, nav_bar_style, nav_tab_button_style, pill_button_style,
    primary_cyan_button_style, ACCENT_CYAN, ACCENT_EMERALD, FONT_BOLD, FONT_MONO, FONT_SEMIBOLD,
    TEXT_DIM, TEXT_PRIMARY,
};
use crate::views::components::{status_pill, truncate_label};
use iced::widget::{button, container, row, text, Space};
use iced::{Alignment, Element, Length};

pub fn top_navigation_bar<'a>(
    active_tab: NavTab,
    overlay: ActiveOverlay,
    now_playing_title: Option<&'a str>,
    multipass_paired: bool,
) -> Element<'a, Message> {
    // brootlite "bL" mark from branding/ (same artwork as the app icons)
    static MARK_PNG: &[u8] = include_bytes!("../../../branding/mark-512.png");
    let mark = iced::widget::image(iced::widget::image::Handle::from_bytes(MARK_PNG))
        .width(Length::Fixed(26.0))
        .height(Length::Fixed(26.0));
    let brand = row![
        mark,
        text("BROOTLITE")
            .size(16)
            .font(FONT_BOLD)
            .color(ACCENT_CYAN),
        text("v2.1.3")
            .size(11)
            .font(FONT_MONO)
            .color(TEXT_DIM),
    ]
    .spacing(6)
    .align_y(Alignment::Center);

    let mut tabs = row![brand, Space::with_width(Length::Fixed(14.0))]
        .spacing(6)
        .align_y(Alignment::Center);

    for tab in NavTab::ALL {
        let is_selected = overlay == ActiveOverlay::None && active_tab == tab;
        tabs = tabs.push(
            button(text(tab.label()).size(13).font(FONT_SEMIBOLD))
                .padding([7, 13])
                .style(nav_tab_button_style(is_selected))
                .on_press(Message::SelectTab(tab)),
        );
    }

    tabs = tabs.push(Space::with_width(Length::Fill));

    if let Some(np_title) = now_playing_title {
        if overlay != ActiveOverlay::Player {
            tabs = tabs.push(
                button(
                    text(format!("▶ Now Playing: {}", truncate_label(np_title, 24)))
                        .size(12)
                        .font(FONT_SEMIBOLD),
                )
                .padding([6, 14])
                .style(primary_cyan_button_style)
                .on_press(Message::ReturnToPlayer),
            );
        }
    }

    if multipass_paired {
        tabs = tabs.push(status_pill("VAULT E2EE".into(), ACCENT_EMERALD));
    }

    container(tabs)
        .padding([10, 20])
        .width(Length::Fill)
        .style(nav_bar_style)
        .into()
}

pub fn toast_banner<'a>(msg: &'a str) -> Element<'a, Message> {
    container(
        row![
            text(msg).size(13).color(TEXT_PRIMARY),
            Space::with_width(Length::Fill),
            button(text("✕").size(11).font(FONT_MONO))
                .padding([3, 8])
                .style(pill_button_style(false))
                .on_press(Message::DismissToast),
        ]
        .spacing(12)
        .align_y(Alignment::Center),
    )
    .padding([8, 18])
    .width(Length::Fill)
    .style(indigo_banner_style)
    .into()
}
