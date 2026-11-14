//! View 8: `Passport ↗` — Leeloo Multipass v2 Optical `#pk` P-256 ECDH QR Pairing, CAS Vault Sync, Stremio QR, & Engine Tuning.

use crate::state::Message;
use crate::theme::{
    accent_indigo_button_style, active_cyan_card_style, danger_button_style, glass_card_style,
    pill_button_style, primary_cyan_button_style, qr_box_style, search_input_style, ACCENT_AMBER,
    ACCENT_CYAN, ACCENT_EMERALD, ACCENT_INDIGO, FONT_BOLD, FONT_MONO, FONT_SEMIBOLD, TEXT_DIM,
    TEXT_PRIMARY, TEXT_SECONDARY,
};
use crate::views::components::{section_header, status_pill, truncate_label};
use brootlite_core::models::{InstalledAddon, MultipassProfile, SwarmFilter};
use brootlite_core::multipass::{MultipassPairingSession, MultipassSyncReport};
use brootlite_core::stremio::StremioLinkSession;
use iced::widget::{button, column, container, qr_code, row, scrollable, text, text_input, Space};
use iced::{Alignment, Element, Length};

#[allow(clippy::too_many_arguments)]
pub fn view_passport<'a>(
    mp_profile: &'a MultipassProfile,
    mp_endpoint_input: &'a str,
    mp_device_name_input: &'a str,
    mp_passphrase_input: &'a str,
    mp_pairing_session: Option<&'a MultipassPairingSession>,
    mp_pairing_qr: Option<&'a qr_code::Data>,
    mp_status_text: &'a str,
    mp_last_report: Option<&'a MultipassSyncReport>,
    mp_syncing: bool,
    // Stremio Link & Addons
    stremio_linked: bool,
    stremio_email: &'a str,
    stremio_qr_sess: Option<&'a StremioLinkSession>,
    stremio_qr_data: Option<&'a qr_code::Data>,
    addon_url_input: &'a str,
    installed_addons: &'a [InstalledAddon],
    // YouTube Enhancements
    sponsorblock_enabled: bool,
    ryd_enabled: bool,
    dearrow_enabled: bool,
    // Swarm & Torrent Tuning
    swarm_filter: SwarmFilter,
    max_cache_str: &'a str,
    window_ahead_str: &'a str,
    prebuffer_str: &'a str,
    max_conns_str: &'a str,
    torznab_url_input: &'a str,
    torznab_key_input: &'a str,
    torznab_has_key: bool,
    // Live Telemetry
    data_dir: &str,
    media_server_url: &str,
    mpv_status: &str,
) -> Element<'a, Message> {
    // ── 1. Leeloo Multipass v2 E2EE Vault Card ───────────────────────────────
    let paired = !mp_profile.endpoint_url.trim().is_empty() && !mp_profile.device_id.is_empty();
    let mut mp_col = column![
        row![
            section_header(
                "Leeloo Multipass v2 — Zero-Knowledge E2EE Media Vault",
                "Optical #pk NIST P-256 ECDH + HKDF-SHA256 + AES-256-GCM"
            ),
            Space::with_width(Length::Fill),
            if paired {
                status_pill(
                    if mp_profile.vault_unlocked {
                        "✓ PAIRED · VMK SEALED IN KEYCHAIN".into()
                    } else {
                        "PAIRED · PASSPHRASE UNLOCK NEEDED".into()
                    },
                    if mp_profile.vault_unlocked {
                        ACCENT_EMERALD
                    } else {
                        ACCENT_AMBER
                    },
                )
            } else {
                status_pill("UNPAIRED".into(), TEXT_SECONDARY)
            }
        ]
        .align_y(Alignment::Center),
        row![
            column![
                text("Multipass Endpoint Origin").size(12).color(TEXT_SECONDARY),
                text_input("https://leeloo.example.com", mp_endpoint_input)
                    .on_input(Message::MpEndpointChanged)
                    .padding([9, 12])
                    .size(13)
                    .style(search_input_style),
            ]
            .spacing(4)
            .width(Length::Fill),
            column![
                text("Desktop Device Label").size(12).color(TEXT_SECONDARY),
                text_input("BrootLite Linux Desktop", mp_device_name_input)
                    .on_input(Message::MpDeviceNameChanged)
                    .padding([9, 12])
                    .size(13)
                    .style(search_input_style),
            ]
            .spacing(4)
            .width(Length::Fixed(260.0)),
            column![
                text("Recovery Passphrase (Optional)").size(12).color(TEXT_SECONDARY),
                text_input("Zero-passphrase when QR paired!", mp_passphrase_input)
                    .on_input(Message::MpPassphraseChanged)
                    .secure(true)
                    .padding([9, 12])
                    .size(13)
                    .style(search_input_style),
            ]
            .spacing(4)
            .width(Length::Fixed(260.0)),
        ]
        .spacing(12),
        row![
            button(
                text("📱 Pair via Optical #pk P-256 QR")
                    .size(13)
                    .font(FONT_SEMIBOLD)
            )
            .padding([9, 16])
            .style(accent_indigo_button_style)
            .on_press(Message::MpStartPairing),
            button(
                text(if mp_syncing {
                    "Syncing Vault (CAS)..."
                } else {
                    "⇄ Sync Vault Now (Pull + CRDT Merge + Push)"
                })
                .size(13)
                .font(FONT_SEMIBOLD)
            )
            .padding([9, 18])
            .style(primary_cyan_button_style)
            .on_press(Message::MpSyncVault),
            Space::with_width(Length::Fill),
            if paired || !mp_profile.endpoint_url.is_empty() {
                Element::from(
                    button(text("Disconnect Vault").size(12))
                        .padding([8, 14])
                        .style(danger_button_style)
                        .on_press(Message::MpDisconnect),
                )
            } else {
                Element::from(Space::with_width(Length::Shrink))
            }
        ]
        .spacing(10)
        .align_y(Alignment::Center),
    ]
    .spacing(12);

    if let Some(sess) = mp_pairing_session {
        let qr_widget: Element<'a, Message> = if let Some(qr) = mp_pairing_qr {
            container(qr_code(qr).cell_size(4))
                .padding(10)
                .style(qr_box_style)
                .into()
        } else {
            Space::with_width(Length::Shrink).into()
        };
        mp_col = mp_col.push(
            container(
                row![
                    qr_widget,
                    column![
                        text("Scan with Leeloo Multipass on Your Phone (Zero Passphrase Typing!)")
                            .size(15)
                            .font(FONT_SEMIBOLD)
                            .color(TEXT_PRIMARY),
                        text("The QR URL carries your ephemeral 65-byte SEC1 P-256 public key inside `#pk=...` (URL fragment — never transmitted to the server!).")
                            .size(12)
                            .color(TEXT_SECONDARY),
                        row![
                            text("Pairing Code:").size(13).color(TEXT_SECONDARY),
                            text(&sess.user_code)
                                .size(28)
                                .font(FONT_BOLD)
                                .color(ACCENT_CYAN),
                        ]
                        .spacing(10)
                        .align_y(Alignment::Center),
                        text(truncate_label(&sess.verification_url, 72))
                            .size(11)
                            .font(FONT_MONO)
                            .color(TEXT_DIM),
                    ]
                    .spacing(6)
                    .width(Length::Fill),
                ]
                .spacing(18)
                .align_y(Alignment::Center),
            )
            .padding(14)
            .width(Length::Fill)
            .style(active_cyan_card_style),
        );
    }

    if !mp_status_text.is_empty() {
        mp_col = mp_col.push(
            text(mp_status_text)
                .size(12)
                .font(FONT_MONO)
                .color(ACCENT_CYAN),
        );
    }

    if let Some(rep) = mp_last_report {
        mp_col = mp_col.push(
            text(format!(
                "Last Vault Sync (rev {}): {} addons · {} Jellyfin · {} YouTube · {} creator follows · {} history · {} favorites",
                rep.revision,
                rep.addons_imported,
                rep.jellyfin_servers_synced,
                rep.youtube_accounts_synced,
                rep.creator_follows_synced,
                rep.watch_history_merged,
                rep.favorites_merged,
            ))
            .size(12)
            .font(FONT_MONO)
            .color(ACCENT_EMERALD),
        );
    }

    let mp_card = container(mp_col)
        .padding(16)
        .width(Length::Fill)
        .style(glass_card_style);

    // ── 2. Stremio QR Account Link & Addon Manager ───────────────────────────
    let mut stremio_col = column![
        row![
            section_header(
                "Stremio Account & Addon Collection",
                "QR Link (`link.stremio.com`) + Two-Way Continue Watching Sync"
            ),
            Space::with_width(Length::Fill),
            if stremio_linked {
                status_pill(format!("LINKED: {stremio_email}"), ACCENT_EMERALD)
            } else {
                status_pill("GUEST MODE".into(), ACCENT_CYAN)
            },
        ]
        .align_y(Alignment::Center),
        row![
            button(text("📱 Link Stremio via QR").size(12).font(FONT_SEMIBOLD))
                .padding([8, 14])
                .style(accent_indigo_button_style)
                .on_press(Message::StremioStartQr),
            if stremio_linked {
                Element::from(
                    row![
                        button(text("↻ Sync Stremio Addons & History").size(12).font(FONT_SEMIBOLD))
                            .padding([8, 14])
                            .style(primary_cyan_button_style)
                            .on_press(Message::StremioSyncNow),
                        button(text("Unlink").size(12))
                            .padding([8, 12])
                            .style(danger_button_style)
                            .on_press(Message::StremioLogout),
                    ]
                    .spacing(8),
                )
            } else {
                Element::from(Space::with_width(Length::Shrink))
            },
            Space::with_width(Length::Fill),
            text_input(
                "Manifest URL or provider-pack.json URL / local path",
                addon_url_input
            )
            .on_input(Message::AddonUrlInputChanged)
            .on_submit(Message::InstallAddonSubmit)
            .padding([8, 12])
            .size(13)
            .style(search_input_style)
            .width(Length::Fixed(400.0)),
            button(text("+ Install / Import Pack").size(12).font(FONT_SEMIBOLD))
                .padding([8, 14])
                .style(primary_cyan_button_style)
                .on_press(Message::InstallAddonSubmit),
        ]
        .spacing(10)
        .align_y(Alignment::Center),
    ]
    .spacing(12);

    if let Some(sess) = stremio_qr_sess {
        let qr_el: Element<'a, Message> = if let Some(qr) = stremio_qr_data {
            container(qr_code(qr).cell_size(4))
                .padding(10)
                .style(qr_box_style)
                .into()
        } else {
            Space::with_width(Length::Shrink).into()
        };
        stremio_col = stremio_col.push(
            container(
                row![
                    qr_el,
                    column![
                        text("Scan to Link Your Stremio Account")
                            .size(15)
                            .font(FONT_SEMIBOLD)
                            .color(TEXT_PRIMARY),
                        text(&sess.link)
                            .size(12)
                            .font(FONT_MONO)
                            .color(ACCENT_CYAN),
                        text(format!("Code: {} — polling automatically...", sess.code))
                            .size(12)
                            .font(FONT_MONO)
                            .color(ACCENT_EMERALD),
                    ]
                    .spacing(6),
                ]
                .spacing(16)
                .align_y(Alignment::Center),
            )
            .padding(14)
            .width(Length::Fill)
            .style(active_cyan_card_style),
        );
    }

    let mut addon_list = column![].spacing(6);
    for addon in installed_addons {
        let is_cinemeta = addon.id == "com.linvo.cinemeta";
        addon_list = addon_list.push(
            row![
                text(format!("{} v{}", addon.name, addon.version))
                    .size(13)
                    .font(FONT_SEMIBOLD)
                    .color(TEXT_PRIMARY),
                status_pill(addon.resources.join(", "), ACCENT_INDIGO),
                text(truncate_label(&addon.transport_url, 56))
                    .size(11)
                    .font(FONT_MONO)
                    .color(TEXT_DIM),
                Space::with_width(Length::Fill),
                if is_cinemeta {
                    Element::from(text("Core").size(11).font(FONT_MONO).color(TEXT_DIM))
                } else {
                    Element::from(
                        button(text("Remove").size(11))
                            .padding([4, 10])
                            .style(danger_button_style)
                            .on_press(Message::RemoveAddon(addon.id.clone())),
                    )
                }
            ]
            .spacing(10)
            .align_y(Alignment::Center),
        );
    }
    stremio_col = stremio_col.push(addon_list);

    let stremio_card = container(stremio_col)
        .padding(16)
        .width(Length::Fill)
        .style(glass_card_style);

    // ── 3. YouTube Enhancements + Torrent Engine + Torznab Tuning ────────────
    let tuning_card = container(
        column![
            section_header(
                "YouTube Enhancements, Swarm Safety Filter, & P2P Engine Tuning",
                "Synced E2EE via Multipass Streaming Preferences"
            ),
            row![
                text("YouTube:").size(12).font(FONT_MONO).color(TEXT_DIM),
                button(
                    text(if sponsorblock_enabled {
                        "✓ SponsorBlock Auto-Skip"
                    } else {
                        "○ SponsorBlock Auto-Skip"
                    })
                    .size(12)
                )
                .padding([5, 12])
                .style(pill_button_style(sponsorblock_enabled))
                .on_press(Message::YtToggleSponsorBlock),
                button(
                    text(if ryd_enabled {
                        "✓ ReturnYouTubeDislike"
                    } else {
                        "○ ReturnYouTubeDislike"
                    })
                    .size(12)
                )
                .padding([5, 12])
                .style(pill_button_style(ryd_enabled))
                .on_press(Message::YtToggleRyd),
                button(
                    text(if dearrow_enabled {
                        "✓ DeArrow Clean Titles"
                    } else {
                        "○ DeArrow Clean Titles"
                    })
                    .size(12)
                )
                .padding([5, 12])
                .style(pill_button_style(dearrow_enabled))
                .on_press(Message::YtToggleDeArrow),
                Space::with_width(Length::Fill),
                text("Swarm Filter:").size(12).font(FONT_MONO).color(TEXT_DIM),
                button(text("Safe Only").size(12))
                    .padding([5, 11])
                    .style(pill_button_style(swarm_filter == SwarmFilter::SafeOnly))
                    .on_press(Message::SetSwarmFilter(SwarmFilter::SafeOnly)),
                button(text("All").size(12))
                    .padding([5, 11])
                    .style(pill_button_style(swarm_filter == SwarmFilter::All))
                    .on_press(Message::SetSwarmFilter(SwarmFilter::All)),
                button(text("Adult Only").size(12))
                    .padding([5, 11])
                    .style(pill_button_style(swarm_filter == SwarmFilter::AdultOnly))
                    .on_press(Message::SetSwarmFilter(SwarmFilter::AdultOnly)),
            ]
            .spacing(8)
            .align_y(Alignment::Center),
            row![
                column![
                    text("Max Cache (MB)").size(11).color(TEXT_SECONDARY),
                    text_input("512", max_cache_str)
                        .on_input(Message::TorrentMaxCacheChanged)
                        .padding([7, 10])
                        .size(13)
                        .style(search_input_style),
                ]
                .spacing(4)
                .width(Length::Fill),
                column![
                    text("Window Ahead (MB)").size(11).color(TEXT_SECONDARY),
                    text_input("48", window_ahead_str)
                        .on_input(Message::TorrentWindowAheadChanged)
                        .padding([7, 10])
                        .size(13)
                        .style(search_input_style),
                ]
                .spacing(4)
                .width(Length::Fill),
                column![
                    text("Prebuffer (MB)").size(11).color(TEXT_SECONDARY),
                    text_input("12", prebuffer_str)
                        .on_input(Message::TorrentPrebufferChanged)
                        .padding([7, 10])
                        .size(13)
                        .style(search_input_style),
                ]
                .spacing(4)
                .width(Length::Fill),
                column![
                    text("Max Peer Connections").size(11).color(TEXT_SECONDARY),
                    text_input("120", max_conns_str)
                        .on_input(Message::TorrentMaxConnsChanged)
                        .padding([7, 10])
                        .size(13)
                        .style(search_input_style),
                ]
                .spacing(4)
                .width(Length::Fill),
                button(text("Save Torrent Tuning").size(12).font(FONT_SEMIBOLD))
                    .padding([9, 16])
                    .style(primary_cyan_button_style)
                    .on_press(Message::SaveTorrentConfig),
            ]
            .spacing(12)
            .align_y(Alignment::End),
            row![
                text_input(
                    "Torznab Indexer URL (e.g. http://127.0.0.1:9696/1/api)",
                    torznab_url_input
                )
                .on_input(Message::TorznabUrlChanged)
                .padding([8, 12])
                .size(13)
                .style(search_input_style)
                .width(Length::Fill),
                text_input(
                    if torznab_has_key {
                        "Torznab API Key (sealed — enter new to replace)"
                    } else {
                        "Torznab API Key"
                    },
                    torznab_key_input
                )
                .on_input(Message::TorznabKeyChanged)
                .secure(true)
                .padding([8, 12])
                .size(13)
                .style(search_input_style)
                .width(Length::Fixed(300.0)),
                button(text("Save Torznab").size(12).font(FONT_SEMIBOLD))
                    .padding([8, 14])
                    .style(pill_button_style(torznab_has_key))
                    .on_press(Message::SaveTorznabConfig),
            ]
            .spacing(10)
            .align_y(Alignment::Center),
        ]
        .spacing(12),
    )
    .padding(16)
    .width(Length::Fill)
    .style(glass_card_style);

    // ── 4. Live Native Desktop Telemetry Footer ──────────────────────────────
    let telemetry_card = container(
        column![
            section_header(
                "Native Desktop Runtime Telemetry",
                "Zero Electron / Zero WebView — Pure Rust iced GPU + Dynamic libmpv"
            ),
            text(format!("State & Keychain Dir: {data_dir}"))
                .size(12)
                .font(FONT_MONO)
                .color(TEXT_SECONDARY),
            text(format!("Embedded Loopback Media Server: {media_server_url}"))
                .size(12)
                .font(FONT_MONO)
                .color(ACCENT_CYAN),
            text(format!("Hardware Video Engine: {mpv_status}"))
                .size(12)
                .font(FONT_MONO)
                .color(ACCENT_EMERALD),
        ]
        .spacing(6),
    )
    .padding(14)
    .width(Length::Fill)
    .style(glass_card_style);

    scrollable(
        column![mp_card, stremio_card, tuning_card, telemetry_card]
            .spacing(16)
            .padding([16, 22]),
    )
    .height(Length::Fill)
    .into()
}
