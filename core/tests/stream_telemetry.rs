//! Stream rows must keep seeds, size, codec, and upstream indexer metadata visible.
use brootlite_core::stremio::{default_addons, parse_stream_candidate};
use serde_json::json;

#[test]
fn stream_row_keeps_seeds_size_and_indexer() {
    let s = json!({
        "name": "PublicDomain\n360p",
        "title": "Classic Anthology Season 1 - threesixtyp\nClassic Anthology S01 (360p dvdrip)/Classic Anthology S01E01 Pilot.mp4\n👤 4 💾 63.3 MB ⚙️ ArchiveOrg",
        "infoHash": "746e56c39fcc76b2fa4a1942624c424db5635359",
        "fileIdx": 0,
        "behaviorHints": {"bingeGroup": "archive|746e", "filename": "Classic Anthology S01E01 Pilot.mp4"}
    });
    let c = parse_stream_candidate(&s, "PublicDomain").unwrap();
    let t = c.telemetry_line();
    assert!(t.contains("▲ 4 seeds"), "{t}");
    assert!(t.contains("MB"), "{t}");
    assert!(t.contains("⚙ ArchiveOrg"), "{t}");
}

#[test]
fn hevc_row_shows_codec() {
    let s = json!({
        "name": "CommunityAddon\n4k HDR",
        "title": "Big.Buck.Bunny.2008.2160p.WEB-DL.DDP5.1.HDR.x265-OPEN\n👤 312 💾 14.2 GB ⚙️ OpenTracker",
        "infoHash": "0123456789abcdef0123456789abcdef01234567"
    });
    let t = parse_stream_candidate(&s, "CommunityAddon")
        .unwrap()
        .telemetry_line();
    assert!(
        t.contains("▲ 312 seeds")
            && t.contains("4K")
            && t.contains("HEVC/H.265")
            && t.contains("⚙ OpenTracker"),
        "{t}"
    );
}

#[test]
fn defaults_include_official_stremio_catalogs_only() {
    let d = default_addons();
    assert_eq!(d.len(), 4);
    assert!(d.iter().any(|a| a.id == "com.linvo.cinemeta"));
    assert!(d.iter().any(|a| a.id == "org.stremio.opensubtitlesv3"));
    assert!(d
        .iter()
        .any(|a| a.id == "org.stremio.cinemeta-catalogs-halloweenpopular"));
}
