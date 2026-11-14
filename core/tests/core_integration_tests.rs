use aes_gcm::{
    aead::{Aead, KeyInit, Payload},
    Aes256Gcm, Nonce,
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use brootlite_core::{
    binge::{rank_catalog_recommendations, StreamSimilarityMatcher},
    config::ConfigStore,
    creators::{rewrite_master_playlist_for_proxy, strip_stitched_ads_from_media_playlist},
    ffi::{
        brootlite_call_json_async, brootlite_call_json_sync, brootlite_engine_free,
        brootlite_engine_new, brootlite_string_free,
    },
    kodi::{parse_kodi_stream_url, KodiRunner},
    models::{
        CreatorFollowEntry, FavoriteEntry, JellyfinServerProfile, MetaItem, MultipassProfile,
        StreamCandidate, StreamSelectionContext, TwitchAuthProfile, VideoEpisode,
        WatchHistoryEntry, YouTubeAccountProfile,
    },
    multipass::{
        merge_media_sync_payloads, MediaSyncDocumentV2, MediaSyncPayload, MultipassCrypto,
        MultipassPairingPollOutcome, SyncedAddonRef, SyncedJellyfinServer, SyncedStremioAuth,
        DEFAULT_PBKDF2_ITERATIONS, MEDIA_SYNC_V1_AAD,
    },
    stremio::{default_addons, parse_manifest_value, resolve_resource_type},
    swarm::{parse_rss_torrent_xml, parse_torznab_xml},
    torrent::LocalMediaServer,
    youtube::{
        build_dash_mpd_xml, eval_youtube_nsig_transform, eval_youtube_sig_decipher,
        DashRepresentationFormat,
    },
    BrootLiteEngine,
};
use pbkdf2::pbkdf2_hmac;
use serde_json::{json, Value};
use sha2::Sha256;
use std::collections::{HashMap, HashSet};
use std::ffi::{c_char, c_void, CStr, CString};
use std::path::PathBuf;
use std::sync::{Arc, Condvar, Mutex};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use uuid::Uuid;

fn temp_test_dir(prefix: &str) -> PathBuf {
    let p = std::env::temp_dir().join(format!("brootlite-core-test-{prefix}-{}", Uuid::new_v4()));
    std::fs::create_dir_all(&p).unwrap();
    p
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Keystore & ConfigStore Zero-Plaintext Audit
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_keystore_and_config_never_persist_plaintext_tokens() {
    let dir = temp_test_dir("keystore");
    let store = ConfigStore::open(Some(dir.clone())).expect("open ConfigStore");

    let secret_stremio = "stremio_super_secret_auth_998877";
    let secret_mp_tok = "mpd_live_visa_token_abcdef123456";
    let secret_jf_tok = "jf_access_token_xyz_987654";
    let secret_jf_boot = "jf_bootstrap_token_554433";
    let secret_yt_ref = "1//0g_youtube_refresh_token_secret";
    let secret_tw_ref = "twitch_single_use_refresh_secret";
    let secret_torznab = "torznab_indexer_api_key_777";
    let vmk = [0x42u8; 32];

    store
        .set_stremio_auth(Some(secret_stremio), "alice@example.com", Some(1_700_000_000_000))
        .unwrap();
    store.seal_multipass_vmk(&vmk).unwrap();
    store
        .save_multipass_profile(MultipassProfile {
            endpoint_url: "https://multipass.example.org".into(),
            pair_token: secret_mp_tok.into(),
            vault_unlocked: true,
            device_id: "dev-1".into(),
            device_name: "Mac Studio".into(),
            handle: "@alice".into(),
            last_synced_ms: 100,
            last_synced_revision: 3,
        })
        .unwrap();
    store
        .upsert_jellyfin_server(JellyfinServerProfile {
            server_id: "srv-1".into(),
            server_name: "Home NAS".into(),
            lan_url: "http://192.168.1.50:8096".into(),
            remote_url: "https://jf.example.org".into(),
            user_id: "u-1".into(),
            username: "alice".into(),
            access_token: secret_jf_tok.into(),
            bootstrap_token: secret_jf_boot.into(),
            last_verified_ms: 100,
            updated_at_ms: 100,
        })
        .unwrap();
    store
        .upsert_youtube_account(YouTubeAccountProfile {
            account_id: "yt-1".into(),
            account_name: "Alice Channel".into(),
            channel_handle: "@alice".into(),
            avatar_url: String::new(),
            page_id: "109922".into(),
            refresh_token: secret_yt_ref.into(),
            access_token: "ya29.ephemeral_access".into(),
            expires_at_ms: 9_999_999_999_999,
            oauth_client_id: "cid".into(),
            oauth_client_secret: "csec_secret_val".into(),
            updated_at_ms: 100,
        })
        .unwrap();
    store
        .set_twitch_profile(Some(TwitchAuthProfile {
            user_id: "tw-1".into(),
            login: "alice_tw".into(),
            display_name: "AliceTW".into(),
            access_token: "tw_access_secret".into(),
            refresh_token: secret_tw_ref.into(),
            updated_at_ms: 100,
        }))
        .unwrap();
    store
        .set_torznab_config("http://127.0.0.1:9117/api", secret_torznab)
        .unwrap();

    let raw_disk = store.raw_disk_json_for_audit().unwrap();
    for forbidden in [
        secret_stremio,
        secret_mp_tok,
        secret_jf_tok,
        secret_jf_boot,
        secret_yt_ref,
        secret_tw_ref,
        secret_torznab,
        "csec_secret_val",
        "ya29.ephemeral_access",
        "tw_access_secret",
    ] {
        assert!(
            !raw_disk.contains(forbidden),
            "Plaintext secret `{forbidden}` leaked into state.json!"
        );
    }
    assert!(raw_disk.contains("blseal2:"));

    // Reopen from disk in a brand-new ConfigStore instance and verify full unsealing
    drop(store);
    let reopened = ConfigStore::open(Some(dir.clone())).unwrap();
    assert_eq!(reopened.stremio_auth().0.as_deref(), Some(secret_stremio));
    assert_eq!(reopened.multipass_profile().pair_token, secret_mp_tok);
    assert_eq!(&reopened.unseal_multipass_vmk().unwrap()[..], &vmk[..]);
    assert_eq!(
        reopened.jellyfin_server("srv-1").unwrap().access_token,
        secret_jf_tok
    );
    assert_eq!(
        reopened.active_youtube_account().unwrap().refresh_token,
        secret_yt_ref
    );
    assert_eq!(
        reopened.twitch_profile().unwrap().refresh_token,
        secret_tw_ref
    );
    assert_eq!(reopened.torznab_config().1, secret_torznab);

    let _ = std::fs::remove_dir_all(dir);
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. Multipass v2 P-256 `#pk` ECDH + AES-256-GCM + Strict Schema + v1 + CRDT
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_multipass_v2_p256_ecdh_wrap_unwrap_and_tamper_rejection() {
    let tv_kp = MultipassCrypto::generate_ephemeral_p256_keypair();
    let decoded_pub = URL_SAFE_NO_PAD.decode(&tv_kp.public_key_b64_url).unwrap();
    assert_eq!(decoded_pub.len(), 65);
    assert_eq!(decoded_pub[0], 0x04);

    let phone_vmk = MultipassCrypto::generate_vault_master_key();
    let wrapped = MultipassCrypto::wrap_paired_vault_master_key(
        &phone_vmk[..],
        &tv_kp.public_key_b64_url,
        "abcd-2345",
        None,
    )
    .expect("wrap paired VMK");

    let unwrapped =
        MultipassCrypto::unwrap_paired_vault_master_key(&tv_kp, &wrapped, "ABCD2345")
            .expect("unwrap paired VMK");
    assert_eq!(&unwrapped[..], &phone_vmk[..]);

    // Wrong userCode MUST fail HKDF + GCM authentication
    assert!(
        MultipassCrypto::unwrap_paired_vault_master_key(&tv_kp, &wrapped, "ZZZZ-9999").is_err()
    );

    // Wrong ephemeral TV keypair MUST fail
    let attacker_kp = MultipassCrypto::generate_ephemeral_p256_keypair();
    assert!(
        MultipassCrypto::unwrap_paired_vault_master_key(&attacker_kp, &wrapped, "ABCD-2345")
            .is_err()
    );
}

#[test]
fn test_multipass_v2_vault_roundtrip_strict_keys_and_v1_compat() {
    let vmk = MultipassCrypto::generate_vault_master_key();
    let slot = MultipassCrypto::create_passphrase_key_slot(
        &vmk[..],
        "correct-horse-battery-staple",
        "primary-passphrase",
        100_000,
    )
    .expect("create keySlot");

    let payload = MediaSyncPayload {
        version: 2,
        exported_at: 1_700_000_123_000,
        source: "brootlite-test".into(),
        stremio_auth: Some(SyncedStremioAuth {
            auth_key: "stremio-key-abc".into(),
            email: "lee@multipass.org".into(),
            updated_at_ms: 500,
            revoked_at_ms: None,
        }),
        addons: vec![SyncedAddonRef {
            transport_url: "https://publicdomainmovies.org/stremio/manifest.json".into(),
            name: "Public Domain Movies".into(),
            id: "org.stremio.publicdomainmovies".into(),
            added_at_ms: 10,
        }],
        ..Default::default()
    };

    let doc_v2: MediaSyncDocumentV2 =
        MultipassCrypto::encrypt_v2_with_vmk(&payload, &vmk[..], vec![slot]).unwrap();

    // Verify Zod `.strict()` outer & inner JSON key invariants!
    let doc_json = serde_json::to_value(&doc_v2).unwrap();
    let top_keys: HashSet<&str> = doc_json
        .as_object()
        .unwrap()
        .keys()
        .map(String::as_str)
        .collect();
    assert_eq!(
        top_keys,
        ["schemaVersion", "mode", "keySlots", "envelope"]
            .into_iter()
            .collect()
    );
    let env_keys: HashSet<&str> = doc_json["envelope"]
        .as_object()
        .unwrap()
        .keys()
        .map(String::as_str)
        .collect();
    assert_eq!(
        env_keys,
        ["hkdfSalt", "cipher", "iv", "aad", "ciphertext"]
            .into_iter()
            .collect()
    );

    // Fast-path decrypt with VMK (zero PBKDF2!)
    let dec_fast = MultipassCrypto::decrypt_v2_with_vmk(&doc_v2, &vmk[..]).unwrap();
    assert_eq!(
        dec_fast.stremio_auth.as_ref().unwrap().auth_key,
        "stremio-key-abc"
    );

    // Recovery passphrase unwrap via keySlots when VMK is None
    let (dec_pass, recovered_vmk, _) = MultipassCrypto::decrypt_any_document(
        &doc_json,
        None,
        Some("correct-horse-battery-staple"),
    )
    .unwrap();
    assert_eq!(&recovered_vmk.unwrap()[..], &vmk[..]);
    assert_eq!(dec_pass.addons.len(), 1);

    // Legacy v1 E2EE backward compatibility check
    let salt_v1 = [7u8; 16];
    let iv_v1 = [9u8; 12];
    let mut k_v1 = [0u8; 32];
    pbkdf2_hmac::<Sha256>(b"legacy-pass-123", &salt_v1, 100_000, &mut k_v1);
    let cipher_v1 = Aes256Gcm::new_from_slice(&k_v1).unwrap();
    let ct_v1 = cipher_v1
        .encrypt(
            &Nonce::from(iv_v1),
            Payload {
                msg: &serde_json::to_vec(&payload).unwrap(),
                aad: MEDIA_SYNC_V1_AAD.as_bytes(),
            },
        )
        .unwrap();
    let legacy_doc = json!({
        "schemaVersion": 1,
        "mode": "e2ee-aes-256-gcm",
        "kdf": {
            "algorithm": "PBKDF2-SHA256",
            "iterations": 100000,
            "salt": URL_SAFE_NO_PAD.encode(salt_v1)
        },
        "iv": URL_SAFE_NO_PAD.encode(iv_v1),
        "aad": MEDIA_SYNC_V1_AAD,
        "ciphertext": URL_SAFE_NO_PAD.encode(ct_v1)
    });
    let (dec_v1, _, _) =
        MultipassCrypto::decrypt_any_document(&legacy_doc, None, Some("legacy-pass-123")).unwrap();
    assert_eq!(
        dec_v1.stremio_auth.unwrap().email,
        "lee@multipass.org"
    );
    let _ = DEFAULT_PBKDF2_ITERATIONS;
}

#[test]
fn test_multipass_crdt_lww_merge_semantics() {
    let mut remote_dev_tokens = HashMap::new();
    remote_dev_tokens.insert("tv-living-room".into(), "tok-tv".into());

    let remote = MediaSyncPayload {
        stremio_auth: Some(SyncedStremioAuth {
            auth_key: "old-key".into(),
            email: "a@b.com".into(),
            updated_at_ms: 1000,
            revoked_at_ms: None,
        }),
        watch_progress: vec![WatchHistoryEntry {
            item_id: "tt0133093".into(),
            media_type: "movie".into(),
            title: "The Matrix".into(),
            position_ms: 900_000,
            duration_ms: 8_000_000,
            updated_at_ms: 5000,
            ..Default::default()
        }],
        jellyfin_servers: vec![SyncedJellyfinServer {
            server_id: "jf-home".into(),
            server_name: "Home".into(),
            device_tokens: remote_dev_tokens,
            bootstrap_token: "boot-1".into(),
            updated_at_ms: 1000,
            ..Default::default()
        }],
        creator_follows: vec![CreatorFollowEntry {
            platform: "youtube".into(),
            channel_id: "UC123".into(),
            name: "TechChan".into(),
            added_at_ms: 2000,
            removed_at_ms: None,
            ..Default::default()
        }],
        ..Default::default()
    };

    let mut local_dev_tokens = HashMap::new();
    local_dev_tokens.insert("macbook-pro".into(), "tok-mac".into());

    let local = MediaSyncPayload {
        stremio_auth: Some(SyncedStremioAuth {
            auth_key: String::new(),
            email: String::new(),
            updated_at_ms: 2000,
            revoked_at_ms: Some(2000),
        }),
        watch_progress: vec![WatchHistoryEntry {
            item_id: "tt0133093".into(),
            media_type: "movie".into(),
            title: "The Matrix".into(),
            position_ms: 300_000,
            duration_ms: 8_000_000,
            updated_at_ms: 2000, // Older than remote!
            ..Default::default()
        }],
        jellyfin_servers: vec![SyncedJellyfinServer {
            server_id: "jf-home".into(),
            server_name: "Home Updated".into(),
            device_tokens: local_dev_tokens,
            updated_at_ms: 3000,
            ..Default::default()
        }],
        creator_follows: vec![CreatorFollowEntry {
            platform: "youtube".into(),
            channel_id: "UC123".into(),
            name: "TechChan".into(),
            added_at_ms: 2000,
            removed_at_ms: Some(4000), // Tombstoned locally!
            ..Default::default()
        }],
        ..Default::default()
    };

    let merged = merge_media_sync_payloads(&remote, &local);
    // Revocation tombstone wins over older authKey
    assert_eq!(merged.stremio_auth.unwrap().revoked_at_ms, Some(2000));
    // Remote watch_progress (ts 5000) beats local (ts 2000)
    assert_eq!(merged.watch_progress[0].position_ms, 900_000);
    // Both devices' Jellyfin tokens AND bootstrap_token are preserved!
    let jf = &merged.jellyfin_servers[0];
    assert_eq!(jf.server_name, "Home Updated");
    assert_eq!(jf.device_tokens.len(), 2);
    assert_eq!(jf.bootstrap_token, "boot-1");
    // Unfollow tombstone wins
    assert_eq!(merged.creator_follows[0].removed_at_ms, Some(4000));
}

#[tokio::test]
async fn test_multipass_e2e_pairing_and_409_cas_retry_against_mock_server() {
    let dir_a = temp_test_dir("mp-dev-a");
    let dir_b = temp_test_dir("mp-dev-b");
    let engine_a = BrootLiteEngine::new(Some(dir_a.clone())).await.unwrap();
    let engine_b = BrootLiteEngine::new(Some(dir_b.clone())).await.unwrap();

    // Shared mock Multipass server state
    let phone_vmk = MultipassCrypto::generate_vault_master_key();
    let slot = MultipassCrypto::create_passphrase_key_slot(
        &phone_vmk[..],
        "multipass-recovery-88",
        "primary-passphrase",
        100_000,
    )
    .unwrap();

    #[derive(Default)]
    struct MockVault {
        tv_pub_key: String,
        revision: u64,
        doc: Option<Value>,
        inject_one_409: bool,
    }
    let state = Arc::new(Mutex::new(MockVault {
        inject_one_409: true,
        ..Default::default()
    }));

    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let origin = format!("http://127.0.0.1:{port}");

    let srv_state = state.clone();
    let phone_vmk_clone = *phone_vmk;
    let slot_clone = slot.clone();
    tokio::spawn(async move {
        while let Ok((mut sock, _)) = listener.accept().await {
            let st = srv_state.clone();
            let slot_c = slot_clone.clone();
            tokio::spawn(async move {
                let mut buf = vec![0u8; 65_536];
                let mut total = 0usize;
                loop {
                    let n = sock.read(&mut buf[total..]).await.unwrap_or(0);
                    if n == 0 {
                        break;
                    }
                    total += n;
                    if let Some(hdr_end) = buf[..total]
                        .windows(4)
                        .position(|w| w == b"\r\n\r\n")
                        .map(|p| p + 4)
                    {
                        let hdr_txt = String::from_utf8_lossy(&buf[..hdr_end]);
                        let clen = hdr_txt
                            .lines()
                            .find_map(|l| {
                                let (k, v) = l.split_once(':')?;
                                if k.trim().eq_ignore_ascii_case("content-length") {
                                    v.trim().parse::<usize>().ok()
                                } else {
                                    None
                                }
                            })
                            .unwrap_or(0);
                        if total >= hdr_end + clen {
                            let first = hdr_txt.lines().next().unwrap_or("");
                            let body_slice = &buf[hdr_end..hdr_end + clen];
                            let (status, resp_json) = if first.starts_with("POST /v1/media-sync/pair/start") {
                                (
                                    "200 OK",
                                    json!({
                                        "pairingId": "pair-123",
                                        "pollSecret": "sec-456",
                                        "userCode": "K9M4-P7Q2",
                                        "verificationUrl": "/networks#media-sync",
                                        "requestedScope": "media_sync:readwrite",
                                        "expiresIn": 600,
                                        "intervalSeconds": 2
                                    }),
                                )
                            } else if first.starts_with("POST /v1/media-sync/pair/poll") {
                                let pub_b64 = st.lock().unwrap().tv_pub_key.clone();
                                let wrapped = MultipassCrypto::wrap_paired_vault_master_key(
                                    &phone_vmk_clone,
                                    &pub_b64,
                                    "K9M4-P7Q2",
                                    Some(vec![slot_c]),
                                )
                                .unwrap();
                                (
                                    "200 OK",
                                    json!({
                                        "status": "approved",
                                        "deviceId": "dev-mac-1",
                                        "deviceName": "MacBook M5",
                                        "deviceToken": "mpd_test_token_123",
                                        "scope": "media_sync:readwrite",
                                        "wrappedVaultKey": wrapped
                                    }),
                                )
                            } else if first.starts_with("GET /v1/media-sync") {
                                let g = st.lock().unwrap();
                                (
                                    "200 OK",
                                    json!({
                                        "handle": "@korben",
                                        "exists": g.doc.is_some(),
                                        "revision": g.revision,
                                        "document": g.doc
                                    }),
                                )
                            } else if first.starts_with("PUT /v1/media-sync") {
                                let put_val: Value = serde_json::from_slice(body_slice).unwrap();
                                let exp = put_val["expectedRevision"].as_u64().unwrap();
                                let mut g = st.lock().unwrap();
                                if g.inject_one_409 {
                                    g.inject_one_409 = false;
                                    // Simulate concurrent write from another device!
                                    let concurrent_payload = MediaSyncPayload {
                                        favorites: vec![FavoriteEntry {
                                            item_id: "tt0816692".into(),
                                            media_type: "movie".into(),
                                            title: "Interstellar".into(),
                                            added_at_ms: 999,
                                            ..Default::default()
                                        }],
                                        ..Default::default()
                                    };
                                    let doc = MultipassCrypto::encrypt_v2_with_vmk(
                                        &concurrent_payload,
                                        &phone_vmk_clone,
                                        vec![slot_c],
                                    )
                                    .unwrap();
                                    g.revision = 1;
                                    g.doc = Some(serde_json::to_value(doc).unwrap());
                                    ("409 Conflict", json!({ "error": "revision_conflict" }))
                                } else if exp != g.revision {
                                    ("409 Conflict", json!({ "error": "revision_conflict" }))
                                } else {
                                    g.revision += 1;
                                    g.doc = Some(put_val["document"].clone());
                                    ("200 OK", json!({ "revision": g.revision }))
                                }
                            } else {
                                ("404 Not Found", json!({}))
                            };

                            let body_bytes = serde_json::to_vec(&resp_json).unwrap();
                            let resp_hdr = format!(
                                "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                                body_bytes.len()
                            );
                            let _ = sock.write_all(resp_hdr.as_bytes()).await;
                            let _ = sock.write_all(&body_bytes).await;
                            break;
                        }
                    }
                }
            });
        }
    });

    // 1. Device A starts pairing -> extracts `#pk=` fragment
    let pair_sess = engine_a
        .multipass
        .start_device_pairing(&origin, "MacBook M5")
        .await
        .unwrap();
    let pk_frag = pair_sess
        .qr_verification_url
        .split("#pk=")
        .nth(1)
        .expect("QR URL must carry #pk=");
    state.lock().unwrap().tv_pub_key = pk_frag.to_string();

    // 2. Device A polls -> unwraps VMK and saves mpd_... token
    let outcome = engine_a
        .multipass
        .poll_device_pairing(
            &pair_sess.origin_url,
            &pair_sess.pairing_id,
            &pair_sess.poll_secret,
            &pair_sess.user_code,
        )
        .await
        .unwrap();
    assert!(matches!(
        outcome,
        MultipassPairingPollOutcome::Approved {
            e2ee_unlocked: true,
            ..
        }
    ));

    // 3. Device A adds local favorite "Dune" and pushes (triggers 409 conflict -> auto-merges "Interstellar" + "Dune" -> revision 2!)
    engine_a
        .config
        .toggle_favorite(FavoriteEntry {
            item_id: "tt1160419".into(),
            media_type: "movie".into(),
            title: "Dune".into(),
            added_at_ms: 1500,
            ..Default::default()
        })
        .unwrap();
    let rev = engine_a
        .multipass
        .push_current_setup_to_vault(Some("multipass-recovery-88"))
        .await
        .unwrap();
    assert_eq!(rev, 2);

    // 4. Device B pairs with token only (simulating recovery passphrase unlock) and pulls!
    engine_b
        .config
        .save_multipass_profile(MultipassProfile {
            endpoint_url: origin.clone(),
            pair_token: "mpd_test_token_123".into(),
            device_id: "dev-linux-2".into(),
            device_name: "Linux iced".into(),
            ..Default::default()
        })
        .unwrap();
    let pull_locked = engine_b
        .multipass
        .sync_from_endpoint(None, &engine_b.stremio, &engine_b.jellyfin)
        .await
        .unwrap();
    assert!(pull_locked.vault_locked_needs_passphrase);

    let pull_unlocked = engine_b
        .multipass
        .sync_from_endpoint(
            Some("multipass-recovery-88"),
            &engine_b.stremio,
            &engine_b.jellyfin,
        )
        .await
        .unwrap();
    assert!(!pull_unlocked.vault_locked_needs_passphrase);
    assert_eq!(pull_unlocked.favorites_merged, 2);
    assert_eq!(engine_b.config.favorites().len(), 2);

    engine_a.shutdown().await;
    engine_b.shutdown().await;
    let _ = std::fs::remove_dir_all(dir_a);
    let _ = std::fs::remove_dir_all(dir_b);
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. StreamSimilarityMatcher & RecommendationEngine Tests
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_stream_similarity_matcher_and_next_episode_ranking() {
    let prev_ctx = StreamSelectionContext {
        binge_group: "community|1080p|subsplease|hevc".into(),
        addon_name: "CommunityAddon".into(),
        title: "[SubsPlease] Frieren - 01 (1080p) [HEVC] [Dual-Audio].mkv".into(),
        filename: "[SubsPlease] Frieren - 01 (1080p) [HEVC] [Dual-Audio].mkv".into(),
        was_direct_http: false,
        resolution_score: 1080,
    };

    let candidates = vec![
        StreamCandidate {
            title: "Frieren.S01E02.720p.WEB.x264-OTHER".into(),
            addon_name: "OtherAddon".into(),
            info_hash: Some("1111111111111111111111111111111111111111".into()),
            binge_group: "other|720p".into(),
            seeders: 500,
            resolution_score: 720,
            ..Default::default()
        },
        StreamCandidate {
            title: "[SubsPlease] Frieren - 02 (1080p) [HEVC] [Dual-Audio].mkv".into(),
            filename: "[SubsPlease] Frieren - 02 (1080p) [HEVC] [Dual-Audio].mkv".into(),
            addon_name: "CommunityAddon".into(),
            info_hash: Some("2222222222222222222222222222222222222222".into()),
            binge_group: "community|1080p|subsplease|hevc".into(),
            seeders: 185,
            resolution_score: 1080,
            ..Default::default()
        },
        StreamCandidate {
            title: "[SubsPlease] Frieren - 02 (1080p) [HEVC] DEAD".into(),
            addon_name: "CommunityAddon".into(),
            info_hash: Some("3333333333333333333333333333333333333333".into()),
            binge_group: String::new(),
            seeders: 0,
            resolution_score: 1080,
            ..Default::default()
        },
    ];

    let ranked = StreamSimilarityMatcher::rank_streams(&prev_ctx, candidates);
    assert_eq!(
        ranked[0].candidate.info_hash.as_deref(),
        Some("2222222222222222222222222222222222222222")
    );
    assert!(ranked[0].matched_reasons.contains(&"bingeGroup".to_string()));
    assert!(ranked[0].matched_reasons.contains(&"group:subsplease".to_string()));
    assert!(ranked[0].matched_reasons.contains(&"codec:hevc".to_string()));

    // Verify Season 0 specials are skipped when advancing from S1E2 -> S2E1
    let episodes = vec![
        VideoEpisode {
            id: "tt1:0:1".into(),
            title: "OVA Special".into(),
            season: 0,
            episode: 1,
            ..Default::default()
        },
        VideoEpisode {
            id: "tt1:1:1".into(),
            title: "Ep 1".into(),
            season: 1,
            episode: 1,
            ..Default::default()
        },
        VideoEpisode {
            id: "tt1:1:2".into(),
            title: "Ep 2".into(),
            season: 1,
            episode: 2,
            ..Default::default()
        },
    ];
    let next = StreamSimilarityMatcher::find_next_episode(&episodes, "tt1:1:1").unwrap();
    assert_eq!(next.id, "tt1:1:2");

    // Verify Movie Credits RecommendationEngine ranking
    let seed = MetaItem {
        id: "tt0816692".into(),
        media_type: "movie".into(),
        name: "Interstellar".into(),
        genres: vec!["Sci-Fi".into(), "Adventure".into(), "Drama".into()],
        imdb_rating: "8.7".into(),
        ..Default::default()
    };
    let pool = vec![
        seed.clone(),
        MetaItem {
            id: "tt0111161".into(),
            media_type: "movie".into(),
            name: "Romantic Comedy".into(),
            genres: vec!["Comedy".into(), "Romance".into()],
            imdb_rating: "7.5".into(),
            ..Default::default()
        },
        MetaItem {
            id: "tt1160419".into(),
            media_type: "movie".into(),
            name: "Dune: Part Two".into(),
            genres: vec!["Sci-Fi".into(), "Adventure".into(), "Drama".into()],
            imdb_rating: "8.6".into(),
            ..Default::default()
        },
    ];
    let recs = rank_catalog_recommendations(&seed, &pool, 5);
    assert_eq!(recs.len(), 2);
    assert_eq!(recs[0].id, "tt1160419");
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. Synthetic YouTube 4K60 DASH `.mpd` & Pure-Rust `boa_engine` JS Deciphering
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_synthetic_youtube_4k60_dash_mpd_and_js_signature_solver() {
    let reps = vec![
        DashRepresentationFormat {
            itag: 315,
            mime_type: "video/webm".into(),
            codecs: "vp9".into(),
            base_url: "https://rr1---sn-example.googlevideo.com/videoplayback?itag=315&expire=123".into(),
            bitrate: 18_500_000,
            width: Some(3840),
            height: Some(2160),
            fps: Some(60),
            audio_sample_rate: None,
            init_range: (0, 219),
            index_range: (220, 1480),
            content_length: Some(450_000_000),
        },
        DashRepresentationFormat {
            itag: 140,
            mime_type: "audio/mp4".into(),
            codecs: "mp4a.40.2".into(),
            base_url: "https://rr1---sn-example.googlevideo.com/videoplayback?itag=140&expire=123".into(),
            bitrate: 129_500,
            width: None,
            height: None,
            fps: None,
            audio_sample_rate: Some(44100),
            init_range: (0, 631),
            index_range: (632, 1920),
            content_length: Some(12_000_000),
        },
    ];

    let mpd = build_dash_mpd_xml(345, &reps);
    assert!(mpd.contains("mediaPresentationDuration=\"PT345S\""));
    assert!(mpd.contains("width=\"3840\" height=\"2160\" frameRate=\"60\""));
    assert!(mpd.contains("<SegmentBase indexRange=\"220-1480\">"));
    assert!(mpd.contains("<Initialization range=\"0-219\"/>"));
    assert!(mpd.contains("itag=315&amp;expire=123"));
    assert!(mpd.contains("AudioChannelConfiguration"));

    // Test pure-Rust `boa_engine` YouTube signature deciphering & n-param transformation!
    let mock_player_js = r#"
        var Zx = {
            rv: function(a, b) { a.reverse(); },
            sp: function(a, b) { a.splice(0, b); }
        };
        DecFn = function(a) { a = a.split(""); Zx.sp(a, 2); Zx.rv(a, 1); return a.join("") };
        c && d.set(e, encodeURIComponent(DecFn(decodeURIComponent(c))));
        var NArr = [NTrans];
        NTrans=function(a){return "unthrottled_" + a.slice(1)};
        if(x.get("n"))&&(b=NArr[0](b))
    "#;
    let deciphered = eval_youtube_sig_decipher(mock_player_js, "XY987654321")
        .expect("boa_engine sig decipher");
    assert_eq!(deciphered, "123456789");

    let transformed_n = eval_youtube_nsig_transform(mock_player_js, "Xthrottle99")
        .expect("boa_engine nsig transform");
    assert_eq!(transformed_n, "unthrottled_throttle99");
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. Twitch Ad-Free M3U8 Stripping (Pre-roll Sequence Bump + Mid-roll Discontinuity)
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_twitch_ad_free_hls_media_and_master_rewriter() {
    let raw_media = "\
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:2
#EXT-X-MEDIA-SEQUENCE:100
#EXT-X-DATERANGE:ID=\"stitched-ad-1234\",CLASS=\"twitch-stitched-ad\",DURATION=4.0
#EXT-X-SCTE35-OUT:0xFC3025
#EXTINF:2.000,Amazon
ad_preroll_0.ts
#EXTINF:2.000,Amazon
ad_preroll_1.ts
#EXTINF:2.000,live
seg_102.ts
#EXTINF:2.000,live
seg_103.ts
#EXT-X-DATERANGE:ID=\"stitched-ad-5678\",CLASS=\"twitch-stitched-ad\",DURATION=2.0
#EXTINF:2.000,stitched-ad-segment
ad_midroll_0.ts
#EXTINF:2.000,live
seg_105.ts
";

    let cleaned = strip_stitched_ads_from_media_playlist(
        raw_media,
        Some("https://video-weaver.jfk04.hls.ttvnw.net/v1/playlist/abc.m3u8"),
    );

    assert!(!cleaned.contains("Amazon"));
    assert!(!cleaned.contains("stitched-ad"));
    assert!(!cleaned.contains("SCTE35"));
    assert!(!cleaned.contains("ad_preroll"));
    assert!(!cleaned.contains("ad_midroll"));
    // 2 pre-roll ad segments were stripped, so MEDIA-SEQUENCE MUST advance from 100 -> 102!
    assert!(cleaned.contains("#EXT-X-MEDIA-SEQUENCE:102"));
    // Mid-roll ad gap between seg_103 and seg_105 MUST insert #EXT-X-DISCONTINUITY!
    assert!(cleaned.contains("#EXT-X-DISCONTINUITY\n#EXTINF:2.000,live\nhttps://video-weaver.jfk04.hls.ttvnw.net/v1/playlist/seg_105.ts"));
    // Relative segment URLs resolved cleanly!
    assert!(cleaned.contains("https://video-weaver.jfk04.hls.ttvnw.net/v1/playlist/seg_102.ts"));

    let raw_master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080\nchunked/index.m3u8\n";
    let rewritten_master = rewrite_master_playlist_for_proxy(
        raw_master,
        "https://usher.ttvnw.net/api/channel/hls/shroud.m3u8",
        "http://127.0.0.1:45555/twitch/tok1/media.m3u8?u=",
    );
    assert!(rewritten_master.contains("http://127.0.0.1:45555/twitch/tok1/media.m3u8?u="));
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. Local Loopback HTTP `206 Partial Content` Range Server + DASH + Twitch Proxy
// ─────────────────────────────────────────────────────────────────────────────

#[tokio::test]
async fn test_local_loopback_http_206_range_dash_and_twitch_server() {
    let dir = temp_test_dir("loopback-srv");
    let config = Arc::new(ConfigStore::open(Some(dir.clone())).unwrap());
    let http = reqwest::Client::new();
    let server = LocalMediaServer::start(http.clone(), config).await.unwrap();

    // 1. Register a deterministic 32 KB byte buffer and verify HTTP 200, HEAD, 206 Ranges, and 416
    let payload: Vec<u8> = (0..32_768u32).map(|i| (i % 251) as u8).collect();
    let (_id, range_url) = server.register_memory_range_source(payload.clone(), "video/mp4");

    let head_resp = http.head(&range_url).send().await.unwrap();
    assert_eq!(head_resp.status().as_u16(), 200);
    assert_eq!(
        head_resp
            .headers()
            .get("accept-ranges")
            .unwrap()
            .to_str()
            .unwrap(),
        "bytes"
    );
    assert_eq!(
        head_resp
            .headers()
            .get("content-length")
            .unwrap()
            .to_str()
            .unwrap(),
        "32768"
    );

    // Mid-stream seek Range: bytes=4096-8191
    let r206 = http
        .get(&range_url)
        .header("Range", "bytes=4096-8191")
        .send()
        .await
        .unwrap();
    assert_eq!(r206.status().as_u16(), 206);
    assert_eq!(
        r206.headers()
            .get("content-range")
            .unwrap()
            .to_str()
            .unwrap(),
        "bytes 4096-8191/32768"
    );
    let body_slice = r206.bytes().await.unwrap();
    assert_eq!(body_slice.len(), 4096);
    assert_eq!(&body_slice[..], &payload[4096..=8191]);

    // Suffix Range: bytes=-512 (MKV Cues / trailing moov atom probe!)
    let rsuffix = http
        .get(&range_url)
        .header("Range", "bytes=-512")
        .send()
        .await
        .unwrap();
    assert_eq!(rsuffix.status().as_u16(), 206);
    let suffix_bytes = rsuffix.bytes().await.unwrap();
    assert_eq!(&suffix_bytes[..], &payload[32_768 - 512..]);

    // Out-of-bounds Range -> 416 Range Not Satisfiable
    let r416 = http
        .get(&range_url)
        .header("Range", "bytes=99999-100050")
        .send()
        .await
        .unwrap();
    assert_eq!(r416.status().as_u16(), 416);

    // 2. Verify synthetic YouTube `.mpd` manifest serving
    let mpd_url = server.register_dash_manifest("dQw4w9WgXcQ", "<MPD>ok</MPD>".into());
    let mpd_resp = http.get(&mpd_url).send().await.unwrap();
    assert_eq!(mpd_resp.status().as_u16(), 200);
    assert!(mpd_resp
        .headers()
        .get("content-type")
        .unwrap()
        .to_str()
        .unwrap()
        .contains("application/dash+xml"));
    assert_eq!(mpd_resp.text().await.unwrap(), "<MPD>ok</MPD>");

    server.shutdown();
    let _ = std::fs::remove_dir_all(dir);
}

// ─────────────────────────────────────────────────────────────────────────────
// 7. Swarm XML Parsers, Stremio Cross-Type Routing, & Disposable Kodi Runner
// ─────────────────────────────────────────────────────────────────────────────

#[tokio::test]
async fn test_swarm_parsers_stremio_routing_and_disposable_kodi_runner() {
    let rss_xml = r#"<?xml version="1.0" encoding="UTF-8"?>
    <rss version="2.0" xmlns:torrent="https://example.org/xmlns/torrent">
      <channel>
        <item>
          <title>Big Buck Bunny - 04 (1080p) [OPEN].mkv</title>
          <torrent:infoHash>a1b2c3d4e5f60718293a4b5c6d7e8f9012345678</torrent:infoHash>
          <torrent:seeders>412</torrent:seeders>
          <torrent:size>1.3 GiB</torrent:size>
        </item>
      </channel>
    </rss>"#;
    let parsed_rss = parse_rss_torrent_xml(rss_xml, "CustomRSS");
    assert_eq!(parsed_rss.len(), 1);
    assert_eq!(parsed_rss[0].seeders, 412);
    assert_eq!(parsed_rss[0].resolution_score, 1080);

    let torznab_xml = r#"<?xml version="1.0" encoding="UTF-8"?>
    <rss version="2.0" xmlns:torznab="http://torznab.com/schemas/2015/feed">
      <channel>
        <item>
          <title>Night.of.the.Living.Dead.1968.2160p.UHD.BluRay.HEVC-OPEN</title>
          <size>21474836480</size>
          <torznab:attr name="infohash" value="fedcba9876543210fedcba9876543210fedcba98"/>
          <torznab:attr name="seeders" value="95"/>
        </item>
      </channel>
    </rss>"#;
    let parsed_tz = parse_torznab_xml(torznab_xml);
    assert_eq!(parsed_tz.len(), 1);
    assert_eq!(parsed_tz[0].resolution_score, 2160);
    assert_eq!(parsed_tz[0].seeders, 95);

    // Cross-type Stremio stream resolution (`anime` catalog item with `tt...` ID resolves via `movie`/`series` addon!)
    let community_manifest = json!({
        "id": "org.example.publicdomain.addon",
        "name": "PublicDomainStreams",
        "version": "1.0.0",
        "types": ["movie", "series"],
        "resources": ["stream"],
        "idPrefixes": ["tt", "kitsu"]
    });
    let addon = parse_manifest_value(
        "https://example.org/stremio/manifest.json",
        &community_manifest,
    )
    .unwrap();
    assert_eq!(
        resolve_resource_type(&addon, "stream", "anime", "tt1234567:1:2").as_deref(),
        Some("series")
    );
    assert_eq!(default_addons().len(), 4); // Cinemeta, Streaming Catalogs, OpenSubtitles v3, Halloween

    // Disposable Python 3 Kodi Addon execution (`0 MB` idle RAM!)
    let dir = temp_test_dir("kodi-runner");
    let config = Arc::new(ConfigStore::open(Some(dir.clone())).unwrap());
    let addon_dir = config
        .kodi_env_dir()
        .join("addons")
        .join("plugin.video.brootmock");
    std::fs::create_dir_all(&addon_dir).unwrap();
    std::fs::write(
        addon_dir.join("addon.xml"),
        r#"<?xml version="1.0" encoding="UTF-8"?>
<addon id="plugin.video.brootmock" name="BrootMock Video" version="1.2.0">
  <extension point="xbmc.python.pluginsource" library="default.py">
    <provides>video</provides>
  </extension>
  <extension point="xbmc.addon.metadata">
    <summary>Mock Kodi video plugin</summary>
  </extension>
</addon>"#,
    )
    .unwrap();
    std::fs::write(
        addon_dir.join("default.py"),
        r#"import sys, xbmcplugin, xbmcgui
handle = int(sys.argv[1])
q = sys.argv[2] if len(sys.argv) > 2 else ""
if "play=1" in q:
    li = xbmcgui.ListItem(path="https://cdn.example.org/master.m3u8|User-Agent=KodiTest&Referer=https://example.org")
    xbmcplugin.setResolvedUrl(handle, True, li)
else:
    li = xbmcgui.ListItem(label="Play Sample Stream")
    li.setProperty("IsPlayable", "true")
    xbmcplugin.addDirectoryItem(handle, "plugin://plugin.video.brootmock/?play=1", li, isFolder=False)
    xbmcplugin.endOfDirectory(handle, True)
"#,
    )
    .unwrap();

    let runner = KodiRunner::new(config);
    let scanned = runner.scan_installed_video_addons();
    assert_eq!(scanned.len(), 1);
    assert_eq!(scanned[0].id, "plugin.video.brootmock");

    let dir_res = runner
        .execute_addon("plugin.video.brootmock", None, 15)
        .await
        .unwrap();
    assert!(dir_res.ok);
    assert_eq!(dir_res.items.len(), 1);
    assert_eq!(dir_res.items[0].label, "Play Sample Stream");

    let play_res = runner
        .execute_addon(
            "plugin.video.brootmock",
            Some("plugin://plugin.video.brootmock/?play=1"),
            15,
        )
        .await
        .unwrap();
    assert!(play_res.ok);
    let (clean_url, hdrs) = parse_kodi_stream_url(&play_res.resolved_url);
    assert_eq!(clean_url, "https://cdn.example.org/master.m3u8");
    assert_eq!(hdrs.get("User-Agent").map(String::as_str), Some("KodiTest"));
    assert_eq!(
        hdrs.get("Referer").map(String::as_str),
        Some("https://example.org")
    );

    let _ = std::fs::remove_dir_all(dir);
}

// ─────────────────────────────────────────────────────────────────────────────
// 8. C ABI FFI Sync & Async JSON-RPC Dispatch (`ffi.rs`)
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn test_c_ffi_sync_and_async_json_rpc_dispatch() {
    let dir = temp_test_dir("c-ffi");
    let dir_cstr = CString::new(dir.to_str().unwrap()).unwrap();

    unsafe {
        let handle = brootlite_engine_new(dir_cstr.as_ptr());
        assert!(!handle.is_null(), "brootlite_engine_new returned NULL");

        // 1. Synchronous `engine.status` call
        let m_status = CString::new("engine.status").unwrap();
        let p_empty = CString::new("{}").unwrap();
        let raw_ptr = brootlite_call_json_sync(handle, m_status.as_ptr(), p_empty.as_ptr());
        assert!(!raw_ptr.is_null());
        let json_str = CStr::from_ptr(raw_ptr).to_str().unwrap().to_string();
        brootlite_string_free(raw_ptr);

        let env: Value = serde_json::from_str(&json_str).unwrap();
        assert_eq!(env["ok"], true);
        assert!(env["result"]["mediaServerPort"].as_u64().unwrap() > 0);

        // 2. Synchronous `config.toggle_favorite` call
        let m_fav = CString::new("config.toggle_favorite").unwrap();
        let p_fav = CString::new(
            r#"{"itemId":"tt0133093","type":"movie","title":"The Matrix","imdbRating":"8.7"}"#,
        )
        .unwrap();
        let fav_ptr = brootlite_call_json_sync(handle, m_fav.as_ptr(), p_fav.as_ptr());
        let fav_env: Value =
            serde_json::from_str(CStr::from_ptr(fav_ptr).to_str().unwrap()).unwrap();
        brootlite_string_free(fav_ptr);
        assert_eq!(fav_env["ok"], true);
        assert_eq!(fav_env["result"]["isFavorite"], true);

        // 3. Asynchronous `config.get_favorites` callback (simulating Swift 6 `withCheckedContinuation`)
        type WaitPair = (Mutex<Option<String>>, Condvar);
        let pair: Arc<WaitPair> = Arc::new((Mutex::new(None), Condvar::new()));
        let ctx_ptr = Arc::into_raw(pair.clone()) as *mut c_void;

        extern "C" fn on_complete(user_ctx: *mut c_void, resp_utf8: *const c_char) {
            unsafe {
                let arc = Arc::from_raw(user_ctx as *const (Mutex<Option<String>>, Condvar));
                let text = CStr::from_ptr(resp_utf8).to_string_lossy().into_owned();
                let (lock, cvar) = &*arc;
                *lock.lock().unwrap() = Some(text);
                cvar.notify_one();
            }
        }

        let m_get_favs = CString::new("config.get_favorites").unwrap();
        brootlite_call_json_async(
            handle,
            m_get_favs.as_ptr(),
            p_empty.as_ptr(),
            ctx_ptr,
            Some(on_complete),
        );

        let (lock, cvar) = &*pair;
        let mut guard = lock.lock().unwrap();
        while guard.is_none() {
            guard = cvar.wait(guard).unwrap();
        }
        let async_resp: Value = serde_json::from_str(guard.as_ref().unwrap()).unwrap();
        drop(guard);
        assert_eq!(async_resp["ok"], true);
        assert_eq!(async_resp["result"][0]["itemId"], "tt0133093");

        brootlite_engine_free(handle);
    }

    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn test_provider_pack_deserialization_and_addon_hydration() {
    let raw_pack = r#"{
        "schemaVersion": 1,
        "name": "Example Community Pack",
        "trackers": ["udp://tracker.example.org:6969/announce"],
        "stremioAddons": [{
            "id": "org.example.streams",
            "name": "Example Streams",
            "version": "1.0.0",
            "description": "Declarative test addon",
            "transportUrl": "https://addons.example.org/manifest.json",
            "types": ["movie", "series"],
            "resources": ["stream"],
            "idPrefixes": ["tt"]
        }],
        "swarmIndexers": [{
            "id": "example_rss",
            "name": "Example RSS",
            "kind": "rss",
            "searchUrlTemplate": "https://feeds.example.org/rss?q={query}&c={cat}",
            "defaultCategory": "0_0"
        }]
    }"#;

    let pack: brootlite_core::pack::ProviderPack =
        serde_json::from_str(raw_pack).expect("deserialize ProviderPack");
    assert!(pack.is_non_empty());
    assert_eq!(pack.name, "Example Community Pack");
    assert!(pack
        .merged_tracker_sources()
        .iter()
        .any(|t| t == "tracker:udp://tracker.example.org:6969/announce"));

    let mut addons = default_addons();
    let before = addons.len();
    brootlite_core::pack::apply_pack_stremio_addons(&pack, &mut addons);
    assert_eq!(addons.len(), before + 1);
    let added = addons.iter().find(|a| a.id == "org.example.streams").unwrap();
    assert_eq!(added.base_url, "https://addons.example.org");
    assert_eq!(added.resources, vec!["stream"]);

    // Idempotent when applied twice
    brootlite_core::pack::apply_pack_stremio_addons(&pack, &mut addons);
    assert_eq!(addons.len(), before + 1);
}

