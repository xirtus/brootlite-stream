//! Xirtus Dark-Glass design tokens, bundled Geist typography, and `iced 0.13` widget styles.

use iced::font::{Family, Weight};
use iced::theme::Palette;
use iced::widget::{button, container, progress_bar, text_input};
use iced::{Background, Border, Color, Font, Shadow, Theme, Vector};

// ─────────────────────────────────────────────────────────────────────────────
// Bundled Geist & Geist Mono Fonts
// ─────────────────────────────────────────────────────────────────────────────

pub const GEIST_REGULAR_BYTES: &[u8] =
    include_bytes!("../../app/src/main/res/font/geist_regular.ttf");
pub const GEIST_SEMIBOLD_BYTES: &[u8] =
    include_bytes!("../../app/src/main/res/font/geist_semibold.ttf");
pub const GEIST_BOLD_BYTES: &[u8] = include_bytes!("../../app/src/main/res/font/geist_bold.ttf");
pub const GEIST_MONO_BYTES: &[u8] =
    include_bytes!("../../app/src/main/res/font/geist_mono_regular.ttf");

pub const FONT_REGULAR: Font = Font {
    family: Family::Name("Geist"),
    weight: Weight::Normal,
    stretch: iced::font::Stretch::Normal,
    style: iced::font::Style::Normal,
};

pub const FONT_SEMIBOLD: Font = Font {
    family: Family::Name("Geist"),
    weight: Weight::Semibold,
    stretch: iced::font::Stretch::Normal,
    style: iced::font::Style::Normal,
};

pub const FONT_BOLD: Font = Font {
    family: Family::Name("Geist"),
    weight: Weight::Bold,
    stretch: iced::font::Stretch::Normal,
    style: iced::font::Style::Normal,
};

pub const FONT_MONO: Font = Font {
    family: Family::Name("Geist Mono"),
    weight: Weight::Normal,
    stretch: iced::font::Stretch::Normal,
    style: iced::font::Style::Normal,
};

// ─────────────────────────────────────────────────────────────────────────────
// Xirtus Dark-Glass Color Palette
// ─────────────────────────────────────────────────────────────────────────────

/// `#090B10` — Deep obsidian canvas background
pub const BG_OBSIDIAN: Color = Color::from_rgb(0.0353, 0.0431, 0.0627);
/// `#0D1119` — Top navigation & elevated bar background
pub const BG_NAV: Color = Color::from_rgb(0.0510, 0.0667, 0.0980);
/// `#121722` — Frosted dark-slate card surface
pub const BG_CARD: Color = Color::from_rgb(0.0706, 0.0902, 0.1333);
/// `#182030` — Hovered / elevated dark-slate card surface
pub const BG_CARD_HOVER: Color = Color::from_rgb(0.0941, 0.1255, 0.1882);
/// `#1D273B` — Input & secondary pill surface
pub const BG_INPUT: Color = Color::from_rgb(0.1137, 0.1529, 0.2314);
/// `#263042` — Hairline `1px` dark-slate border
pub const BORDER_SLATE: Color = Color::from_rgb(0.1490, 0.1882, 0.2588);
/// `#38BDF8` — Cyan focus / active border & highlight
pub const ACCENT_CYAN: Color = Color::from_rgb(0.2196, 0.7412, 0.9725);
/// `#818CF8` — Indigo secondary accent
pub const ACCENT_INDIGO: Color = Color::from_rgb(0.5059, 0.5490, 0.9725);
/// `#10B981` — Emerald live / healthy status
pub const ACCENT_EMERALD: Color = Color::from_rgb(0.0627, 0.7255, 0.5059);
/// `#F59E0B` — Amber IMDb rating & warning accent
pub const ACCENT_AMBER: Color = Color::from_rgb(0.9608, 0.6196, 0.0431);
/// `#F43F5E` — Rose live badge / danger action
pub const ACCENT_ROSE: Color = Color::from_rgb(0.9569, 0.2471, 0.3686);

/// `#F1F5F9` — Primary crisp white-slate text
pub const TEXT_PRIMARY: Color = Color::from_rgb(0.9451, 0.9608, 0.9765);
/// `#94A3B8` — Secondary muted slate text
pub const TEXT_SECONDARY: Color = Color::from_rgb(0.5804, 0.6392, 0.7216);
/// `#64748B` — Dim metadata / placeholder text
pub const TEXT_DIM: Color = Color::from_rgb(0.3922, 0.4549, 0.5451);

pub fn xirtus_theme() -> Theme {
    Theme::custom(
        "Xirtus Dark-Glass".to_string(),
        Palette {
            background: BG_OBSIDIAN,
            text: TEXT_PRIMARY,
            primary: ACCENT_CYAN,
            success: ACCENT_EMERALD,
            danger: ACCENT_ROSE,
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Container Styles
// ─────────────────────────────────────────────────────────────────────────────

pub fn root_canvas_style(_theme: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(BG_OBSIDIAN)),
        text_color: Some(TEXT_PRIMARY),
        border: Border::default(),
        shadow: Shadow::default(),
    }
}

pub fn nav_bar_style(_theme: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(BG_NAV)),
        text_color: Some(TEXT_PRIMARY),
        border: Border {
            color: BORDER_SLATE,
            width: 1.0,
            radius: 0.0.into(),
        },
        shadow: Shadow::default(),
    }
}

pub fn glass_card_style(_theme: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(BG_CARD)),
        text_color: Some(TEXT_PRIMARY),
        border: Border {
            color: BORDER_SLATE,
            width: 1.0,
            radius: 12.0.into(),
        },
        shadow: Shadow {
            color: Color::from_rgba(0.0, 0.0, 0.0, 0.35),
            offset: Vector::new(0.0, 4.0),
            blur_radius: 14.0,
        },
    }
}

pub fn active_cyan_card_style(_theme: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(Color::from_rgb(0.078, 0.118, 0.185))),
        text_color: Some(TEXT_PRIMARY),
        border: Border {
            color: ACCENT_CYAN,
            width: 1.5,
            radius: 12.0.into(),
        },
        shadow: Shadow {
            color: Color::from_rgba(0.22, 0.74, 0.97, 0.18),
            offset: Vector::new(0.0, 4.0),
            blur_radius: 18.0,
        },
    }
}

pub fn indigo_banner_style(_theme: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(Color::from_rgb(0.090, 0.105, 0.195))),
        text_color: Some(TEXT_PRIMARY),
        border: Border {
            color: ACCENT_INDIGO,
            width: 1.0,
            radius: 12.0.into(),
        },
        shadow: Shadow::default(),
    }
}

pub fn poster_placeholder_style(_theme: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(Color::from_rgb(0.095, 0.122, 0.180))),
        text_color: Some(TEXT_SECONDARY),
        border: Border {
            color: BORDER_SLATE,
            width: 1.0,
            radius: 8.0.into(),
        },
        shadow: Shadow::default(),
    }
}

pub fn qr_box_style(_theme: &Theme) -> container::Style {
    container::Style {
        background: Some(Background::Color(Color::WHITE)),
        text_color: Some(Color::BLACK),
        border: Border {
            color: ACCENT_CYAN,
            width: 2.0,
            radius: 12.0.into(),
        },
        shadow: Shadow::default(),
    }
}

pub fn badge_style(bg: Color, border_col: Color) -> impl Fn(&Theme) -> container::Style {
    move |_theme: &Theme| container::Style {
        background: Some(Background::Color(bg)),
        text_color: Some(TEXT_PRIMARY),
        border: Border {
            color: border_col,
            width: 1.0,
            radius: 6.0.into(),
        },
        shadow: Shadow::default(),
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Button Styles
// ─────────────────────────────────────────────────────────────────────────────

pub fn nav_tab_button_style(active: bool) -> impl Fn(&Theme, button::Status) -> button::Style {
    move |_theme: &Theme, status: button::Status| {
        let hovered = matches!(status, button::Status::Hovered | button::Status::Pressed);
        let bg = if active {
            Color::from_rgba(0.2196, 0.7412, 0.9725, 0.16)
        } else if hovered {
            BG_CARD_HOVER
        } else {
            Color::TRANSPARENT
        };
        let border_col = if active {
            ACCENT_CYAN
        } else if hovered {
            BORDER_SLATE
        } else {
            Color::TRANSPARENT
        };
        button::Style {
            background: Some(Background::Color(bg)),
            text_color: if active { ACCENT_CYAN } else { TEXT_PRIMARY },
            border: Border {
                color: border_col,
                width: 1.0,
                radius: 8.0.into(),
            },
            shadow: Shadow::default(),
        }
    }
}

pub fn pill_button_style(active: bool) -> impl Fn(&Theme, button::Status) -> button::Style {
    move |_theme: &Theme, status: button::Status| {
        let hovered = matches!(status, button::Status::Hovered | button::Status::Pressed);
        let bg = if active {
            Color::from_rgba(0.2196, 0.7412, 0.9725, 0.20)
        } else if hovered {
            BG_CARD_HOVER
        } else {
            BG_CARD
        };
        let border_col = if active || hovered {
            ACCENT_CYAN
        } else {
            BORDER_SLATE
        };
        button::Style {
            background: Some(Background::Color(bg)),
            text_color: if active { ACCENT_CYAN } else { TEXT_PRIMARY },
            border: Border {
                color: border_col,
                width: 1.0,
                radius: 999.0.into(),
            },
            shadow: Shadow::default(),
        }
    }
}

pub fn primary_cyan_button_style(_theme: &Theme, status: button::Status) -> button::Style {
    let bg = match status {
        button::Status::Hovered => Color::from_rgb(0.32, 0.80, 0.99),
        button::Status::Pressed => Color::from_rgb(0.16, 0.64, 0.88),
        button::Status::Disabled => BG_INPUT,
        button::Status::Active => ACCENT_CYAN,
    };
    button::Style {
        background: Some(Background::Color(bg)),
        text_color: Color::from_rgb(0.03, 0.05, 0.09),
        border: Border {
            color: ACCENT_CYAN,
            width: 1.0,
            radius: 8.0.into(),
        },
        shadow: Shadow::default(),
    }
}

pub fn accent_indigo_button_style(_theme: &Theme, status: button::Status) -> button::Style {
    let bg = match status {
        button::Status::Hovered => Color::from_rgb(0.58, 0.62, 0.99),
        button::Status::Pressed => Color::from_rgb(0.42, 0.46, 0.88),
        button::Status::Disabled => BG_INPUT,
        button::Status::Active => ACCENT_INDIGO,
    };
    button::Style {
        background: Some(Background::Color(bg)),
        text_color: Color::from_rgb(0.04, 0.05, 0.11),
        border: Border {
            color: ACCENT_INDIGO,
            width: 1.0,
            radius: 8.0.into(),
        },
        shadow: Shadow::default(),
    }
}

pub fn ghost_card_button_style(_theme: &Theme, status: button::Status) -> button::Style {
    let hovered = matches!(status, button::Status::Hovered | button::Status::Pressed);
    button::Style {
        background: Some(Background::Color(if hovered {
            BG_CARD_HOVER
        } else {
            BG_CARD
        })),
        text_color: TEXT_PRIMARY,
        border: Border {
            color: if hovered { ACCENT_CYAN } else { BORDER_SLATE },
            width: 1.0,
            radius: 10.0.into(),
        },
        shadow: Shadow::default(),
    }
}

pub fn danger_button_style(_theme: &Theme, status: button::Status) -> button::Style {
    let hovered = matches!(status, button::Status::Hovered | button::Status::Pressed);
    button::Style {
        background: Some(Background::Color(if hovered {
            Color::from_rgba(0.9569, 0.2471, 0.3686, 0.28)
        } else {
            Color::from_rgba(0.9569, 0.2471, 0.3686, 0.14)
        })),
        text_color: ACCENT_ROSE,
        border: Border {
            color: ACCENT_ROSE,
            width: 1.0,
            radius: 8.0.into(),
        },
        shadow: Shadow::default(),
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Text Input & Progress Bar Styles
// ─────────────────────────────────────────────────────────────────────────────

pub fn search_input_style(_theme: &Theme, status: text_input::Status) -> text_input::Style {
    let border_col = match status {
        text_input::Status::Focused => ACCENT_CYAN,
        text_input::Status::Hovered => Color::from_rgb(0.28, 0.38, 0.54),
        _ => BORDER_SLATE,
    };
    text_input::Style {
        background: Background::Color(BG_INPUT),
        border: Border {
            color: border_col,
            width: 1.2,
            radius: 10.0.into(),
        },
        icon: ACCENT_CYAN,
        placeholder: TEXT_DIM,
        value: TEXT_PRIMARY,
        selection: Color::from_rgba(0.2196, 0.7412, 0.9725, 0.30),
    }
}

pub fn cyan_progress_style(_theme: &Theme) -> progress_bar::Style {
    progress_bar::Style {
        background: Background::Color(BG_INPUT),
        bar: Background::Color(ACCENT_CYAN),
        border: Border {
            color: Color::TRANSPARENT,
            width: 0.0,
            radius: 4.0.into(),
        },
    }
}
