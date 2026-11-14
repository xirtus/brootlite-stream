import SwiftUI

public struct PassportView: View {
    @Bindable public var vm: AppViewModel

    public init(vm: AppViewModel) {
        self.vm = vm
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                // 1. Live Process Telemetry & Engine Banner (`mach_task_basic_info`)
                GlassPanel(cornerRadius: 16, padding: 18) {
                    HStack(spacing: 18) {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(spacing: 8) {
                                Image(systemName: "cpu.fill")
                                    .foregroundStyle(XirtusTheme.cyanAccent)
                                Text("BrootLite Native macOS · Swift 6 + Rust Core + libmpv")
                                    .font(XirtusTheme.font(15.5, weight: .bold))
                                    .foregroundStyle(XirtusTheme.textPrimary)
                            }
                            Text("Loopback Media Server: \(vm.status?.mediaServerBaseUrl ?? "127.0.0.1") · Decoder: \(vm.player.backendBadge)")
                                .font(XirtusTheme.mono(11.5))
                                .foregroundStyle(XirtusTheme.textSecondary)
                        }

                        Spacer()

                        VStack(alignment: .trailing, spacing: 3) {
                            Text(
                                String(
                                    format: "Footprint %.1f MB · RSS %.1f MB",
                                    vm.footprintMemoryMB,
                                    vm.residentMemoryMB
                                )
                            )
                            .font(XirtusTheme.mono(12.5, weight: .bold))
                            .foregroundStyle(XirtusTheme.emerald)
                            Text("mach_task_basic_info · Zero WebView")
                                .font(XirtusTheme.mono(10.5))
                                .foregroundStyle(XirtusTheme.textMuted)
                        }
                    }
                }

                // 2. Leeloo Multipass v2 Optical `#pk` P-256 ECDH E2EE Vault
                GlassPanel(cornerRadius: 16, padding: 20, highlightBorder: true) {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack {
                            HStack(spacing: 8) {
                                Image(systemName: "lock.shield.fill")
                                    .font(.system(size: 17))
                                    .foregroundStyle(XirtusTheme.cyanAccent)
                                Text("Leeloo Multipass v2 · Zero-Knowledge Media Pouch")
                                    .font(XirtusTheme.font(17.5, weight: .bold))
                                    .foregroundStyle(XirtusTheme.textPrimary)
                            }
                            Spacer()
                            if vm.status?.multipassPaired == true {
                                Text(vm.status?.multipassProfile.vaultUnlocked == true || vm.keychainHasUnlockedMarker
                                     ? "PAIRED · E2EE UNLOCKED (KEYCHAIN)"
                                     : "PAIRED · VAULT LOCKED")
                                    .font(XirtusTheme.mono(10.5, weight: .bold))
                                    .foregroundStyle(.black)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 4)
                                    .background(XirtusTheme.emerald)
                                    .clipShape(Capsule())
                            }
                        }

                        Text("Scan the optical `#pk` P-256 ECDH QR code with your phone's Leeloo Multipass app to pair and unwrap your 256-bit Vault Master Key directly into macOS Keychain — zero passphrase typing required.")
                            .font(XirtusTheme.font(12.5))
                            .foregroundStyle(XirtusTheme.textSecondary)

                        HStack(spacing: 12) {
                            TextField("Multipass Endpoint URL (https://...)", text: $vm.multipassInputEndpoint)
                                .textFieldStyle(.roundedBorder)
                            TextField("Device Name", text: $vm.multipassDeviceName)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 190)

                            Button {
                                Task { await vm.startMultipassOpticalPairing() }
                            } label: {
                                Text("Pair via Optical #pk QR")
                                    .font(XirtusTheme.font(12.5, weight: .bold))
                                    .foregroundStyle(.black)
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 7)
                                    .background(XirtusTheme.cyanAccent)
                                    .clipShape(Capsule())
                            }
                            .buttonStyle(.plain)
                        }

                        if let sess = vm.multipassPairingSession {
                            HStack(spacing: 24) {
                                QRCodeView(payload: sess.qrVerificationUrl, size: 176)

                                VStack(alignment: .leading, spacing: 8) {
                                    Text("Scan on Phone to Unlock E2EE Media Pouch")
                                        .font(XirtusTheme.font(15, weight: .bold))
                                        .foregroundStyle(XirtusTheme.textPrimary)
                                    Text("Ephemeral P-256 Public Key embedded in `#pk` URL fragment (never sent to server during initiation).")
                                        .font(XirtusTheme.font(12))
                                        .foregroundStyle(XirtusTheme.textSecondary)

                                    HStack(spacing: 8) {
                                        Text("Verification Code:")
                                            .font(XirtusTheme.font(12.5))
                                            .foregroundStyle(XirtusTheme.textSecondary)
                                        Text(sess.userCode)
                                            .font(XirtusTheme.mono(20, weight: .bold))
                                            .foregroundStyle(XirtusTheme.cyanAccent)
                                    }

                                    HStack(spacing: 10) {
                                        ProgressView().controlSize(.small)
                                        Text("Polling ECDH approval...")
                                            .font(XirtusTheme.mono(11.5))
                                            .foregroundStyle(XirtusTheme.textMuted)
                                        Button("Cancel") {
                                            vm.multipassPollTask?.cancel()
                                            vm.multipassPairingSession = nil
                                        }
                                        .buttonStyle(.bordered)
                                    }
                                }
                            }
                            .padding(.top, 6)
                        }

                        if vm.status?.multipassPaired == true {
                            Divider()
                            HStack(spacing: 12) {
                                SecureField("Optional Vault Passphrase (only if locked)", text: $vm.multipassPassphrase)
                                    .textFieldStyle(.roundedBorder)
                                    .frame(width: 260)

                                Button {
                                    Task { await vm.syncMultipassVaultNow() }
                                } label: {
                                    HStack(spacing: 6) {
                                        if vm.isSyncingVault {
                                            ProgressView().controlSize(.small)
                                        } else {
                                            Image(systemName: "arrow.triangle.2.circlepath")
                                        }
                                        Text("Sync Vault (Pull + Merge + Push)")
                                            .font(XirtusTheme.font(12.5, weight: .bold))
                                    }
                                    .foregroundStyle(.black)
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 8)
                                    .background(XirtusTheme.cyanAccent)
                                    .clipShape(Capsule())
                                }
                                .buttonStyle(.plain)
                                .disabled(vm.isSyncingVault)

                                Spacer()

                                if let prof = vm.status?.multipassProfile, prof.lastSyncedRevision > 0 {
                                    Text("Rev #\(prof.lastSyncedRevision)")
                                        .font(XirtusTheme.mono(11.5))
                                        .foregroundStyle(XirtusTheme.textSecondary)
                                }

                                Button("Disconnect") {
                                    Task { await vm.disconnectMultipass() }
                                }
                                .buttonStyle(.bordered)
                                .foregroundStyle(XirtusTheme.rose)
                            }
                        }
                    }
                }

                // 3. Stremio Account Link & Addon Manager
                GlassPanel(cornerRadius: 16, padding: 20) {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack {
                            Text("Stremio Account & Installed Addons")
                                .font(XirtusTheme.font(17, weight: .bold))
                                .foregroundStyle(XirtusTheme.textPrimary)
                            Spacer()
                            if vm.status?.stremioLinked == true {
                                Text("Linked · \(vm.status?.stremioEmail ?? "Stremio")")
                                    .font(XirtusTheme.mono(11.5, weight: .semibold))
                                    .foregroundStyle(XirtusTheme.emerald)
                                Button("Sync Addons") {
                                    Task { await vm.syncStremioNow() }
                                }
                                .buttonStyle(.bordered)
                                Button("Sign Out") {
                                    Task { await vm.logoutStremio() }
                                }
                                .buttonStyle(.bordered)
                            } else {
                                XirtusChip(title: "Link Stremio via QR", icon: "qrcode") {
                                    Task { await vm.startStremioQrLink() }
                                }
                            }
                        }

                        if let qr = vm.stremioQrSession {
                            HStack(spacing: 22) {
                                QRCodeView(payload: qr.link, size: 152)
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("Scan QR to Link Stremio Account")
                                        .font(XirtusTheme.font(15, weight: .bold))
                                        .foregroundStyle(XirtusTheme.textPrimary)
                                    Text(qr.link)
                                        .font(XirtusTheme.mono(12))
                                        .foregroundStyle(XirtusTheme.cyanAccent)
                                    Text("Code: \(qr.code)")
                                        .font(XirtusTheme.mono(16, weight: .bold))
                                        .foregroundStyle(XirtusTheme.textPrimary)
                                    Button("Cancel") {
                                        vm.stremioQrPollTask?.cancel()
                                        vm.stremioQrSession = nil
                                    }
                                    .buttonStyle(.bordered)
                                }
                            }
                        }

                        HStack(spacing: 10) {
                            TextField("Install Addon Manifest URL (https://.../manifest.json or stremio://...)", text: $vm.addonInstallUrl)
                                .textFieldStyle(.roundedBorder)
                            Button("Install Addon") {
                                Task { await vm.installAddonFromManifestURL() }
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(XirtusTheme.cyanAccent)
                        }

                        VStack(spacing: 8) {
                            ForEach(vm.addons) { addon in
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("\(addon.name) · v\(addon.version)")
                                            .font(XirtusTheme.font(13, weight: .semibold))
                                            .foregroundStyle(XirtusTheme.textPrimary)
                                        Text("\(addon.resources.joined(separator: ", ")) · \(addon.types.joined(separator: ", "))")
                                            .font(XirtusTheme.mono(11))
                                            .foregroundStyle(XirtusTheme.textMuted)
                                    }
                                    Spacer()
                                    if addon.id != "com.linvo.cinemeta" {
                                        Button {
                                            Task { await vm.removeInstalledAddon(addon.id) }
                                        } label: {
                                            Image(systemName: "trash")
                                                .foregroundStyle(XirtusTheme.rose)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .background(XirtusTheme.surfaceElevated.opacity(0.65))
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                            }
                        }
                    }
                }

                // 4. YouTube Enhancements & P2P Swarm Sliding-Window Config
                HStack(alignment: .top, spacing: 18) {
                    GlassPanel(cornerRadius: 16, padding: 18) {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("YouTube Playback Enhancements")
                                .font(XirtusTheme.font(16, weight: .bold))
                                .foregroundStyle(XirtusTheme.textPrimary)

                            Toggle("SponsorBlock Auto-Skip (Press Z or ⌫ to Undo)", isOn: $vm.sponsorBlockEnabled)
                                .font(XirtusTheme.font(13))
                                .foregroundStyle(XirtusTheme.textPrimary)
                            Toggle("ReturnYouTubeDislike Live 👍 / 👎 Counts", isOn: $vm.rydEnabled)
                                .font(XirtusTheme.font(13))
                                .foregroundStyle(XirtusTheme.textPrimary)
                            Toggle("DeArrow Crowdsourced Anti-Clickbait Titles", isOn: $vm.deArrowEnabled)
                                .font(XirtusTheme.font(13))
                                .foregroundStyle(XirtusTheme.textPrimary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    GlassPanel(cornerRadius: 16, padding: 18) {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("P2P Torrent Window & Torznab")
                                .font(XirtusTheme.font(16, weight: .bold))
                                .foregroundStyle(XirtusTheme.textPrimary)

                            HStack(spacing: 12) {
                                Stepper(
                                    "Prebuffer: \(vm.torrentDraftConfig.prebufferMb) MB",
                                    value: $vm.torrentDraftConfig.prebufferMb,
                                    in: 4...64,
                                    step: 4
                                )
                                .font(XirtusTheme.mono(11.5))

                                Stepper(
                                    "Window: \(vm.torrentDraftConfig.windowAheadMb) MB",
                                    value: $vm.torrentDraftConfig.windowAheadMb,
                                    in: 16...256,
                                    step: 16
                                )
                                .font(XirtusTheme.mono(11.5))
                            }

                            TextField("Torznab Indexer URL (Jackett / Prowlarr)", text: $vm.torznabUrl)
                                .textFieldStyle(.roundedBorder)
                            SecureField(
                                vm.torznabHasSavedKey ? "Torznab API Key (Saved — type to replace)" : "Torznab API Key",
                                text: $vm.torznabApiKey
                            )
                            .textFieldStyle(.roundedBorder)

                            Button("Save Swarm & Torznab Config") {
                                Task { await vm.saveTorrentAndTorznabSettings() }
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(XirtusTheme.cyanAccent)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            .padding(24)
        }
    }
}
