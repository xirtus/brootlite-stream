use crate::config::ConfigStore;
use crate::error::CoreResult;
use crate::jellyfin::{JellyfinClient, JellyfinIds};
use crate::models::{
    CreatorVideoItem, MetaItem, RankedStreamCandidate, StreamCandidate, StreamSelectionContext,
    VideoEpisode,
};
use crate::stremio::StremioClient;
use crate::youtube::YouTubeClient;
use regex::Regex;
use std::collections::HashSet;
use std::sync::{Arc, OnceLock};

const IGNORED_BRACKET_TAGS: &[&str] = &[
    "2160p", "1440p", "1080p", "720p", "480p", "4k", "uhd", "fhd", "hd", "hevc", "x265", "h265",
    "x264", "h264", "avc", "av1", "10bit", "10-bit", "hdr", "hdr10", "dv", "web-dl", "webrip",
    "bluray", "bdrip", "remux", "aac", "eac3", "atmos", "multi", "dual-audio",
];

/// Ranks next-episode streams against the previously played [`StreamSelectionContext`] so
/// desktop/TV "Keep Watching / Stream Next Episode" automatically picks the exact same
/// release group, resolution, codec, audio/subtitle track flavor, and transport mode.
pub struct StreamSimilarityMatcher;

impl StreamSimilarityMatcher {
    pub fn build_context_from_stream(stream: &StreamCandidate) -> StreamSelectionContext {
        StreamSelectionContext {
            binge_group: stream.binge_group.clone(),
            addon_name: stream.addon_name.clone(),
            title: stream.title.clone(),
            filename: stream.filename.clone(),
            was_direct_http: stream.is_direct_http(),
            resolution_score: stream.resolution_score,
        }
    }

    pub fn extract_release_group(text: &str) -> Option<String> {
        static BRACKET_RE: OnceLock<Regex> = OnceLock::new();
        static SUFFIX_RE: OnceLock<Regex> = OnceLock::new();

        let b_re = BRACKET_RE.get_or_init(|| Regex::new(r"\[([A-Za-z0-9._ -]{2,28})\]").unwrap());
        for cap in b_re.captures_iter(text) {
            let tag = cap[1].trim();
            let lower = tag.to_ascii_lowercase();
            if !tag.is_empty()
                && !IGNORED_BRACKET_TAGS.contains(&lower.as_str())
                && !lower.chars().all(|c| c.is_ascii_digit())
            {
                return Some(lower);
            }
        }

        // Scene / P2P group suffix before optional file extension (`...1080p.WEB-DL.H264-NTb.mkv`)
        let stripped = text
            .trim()
            .trim_end_matches(".mkv")
            .trim_end_matches(".mp4")
            .trim_end_matches(".avi");
        let s_re =
            SUFFIX_RE.get_or_init(|| Regex::new(r"-([A-Za-z0-9]{2,18})(?:\s*\[.*\])?$").unwrap());
        if let Some(cap) = s_re.captures(stripped) {
            let grp = cap[1].to_ascii_lowercase();
            if !IGNORED_BRACKET_TAGS.contains(&grp.as_str()) {
                return Some(grp);
            }
        }
        None
    }

    pub fn extract_codec(text: &str) -> Option<&'static str> {
        let lower = text.to_ascii_lowercase();
        if lower.contains("hevc") || lower.contains("x265") || lower.contains("h265") || lower.contains("h.265") {
            Some("hevc")
        } else if lower.contains("av1") {
            Some("av1")
        } else if lower.contains("x264") || lower.contains("h264") || lower.contains("h.264") || lower.contains("avc") {
            Some("h264")
        } else {
            None
        }
    }

    pub fn extract_flavor_tokens(text: &str) -> HashSet<&'static str> {
        let lower = text.to_ascii_lowercase();
        let mut set = HashSet::new();
        for (needle, canonical) in [
            ("dv", "dolby-vision"),
            ("dolby vision", "dolby-vision"),
            ("hdr", "hdr"),
            ("10bit", "10bit"),
            ("10-bit", "10bit"),
            ("atmos", "atmos"),
            ("truehd", "truehd"),
            ("eac3", "eac3"),
            ("ddp", "eac3"),
            ("flac", "flac"),
            ("dual audio", "dual-audio"),
            ("dual-audio", "dual-audio"),
            ("multi-sub", "multi-subs"),
            ("multisub", "multi-subs"),
            ("dub", "dubbed"),
        ] {
            if lower.contains(needle) {
                set.insert(canonical);
            }
        }
        set
    }

    pub fn score_candidate(
        prev: &StreamSelectionContext,
        cand: &StreamCandidate,
    ) -> RankedStreamCandidate {
        let mut score = 0.0f64;
        let mut reasons = Vec::new();

        let prev_blob = format!("{} {}", prev.title, prev.filename);
        let cand_blob = format!("{} {} {}", cand.title, cand.subtitle, cand.filename);

        // 1. Exact Stremio `bingeGroup` match (strongest signal!)
        if !prev.binge_group.is_empty()
            && prev.binge_group.eq_ignore_ascii_case(&cand.binge_group)
        {
            score += 120.0;
            reasons.push("bingeGroup".to_string());
        }

        // 2. Addon origin match
        if !prev.addon_name.is_empty() && prev.addon_name.eq_ignore_ascii_case(&cand.addon_name) {
            score += 35.0;
            reasons.push("addon".to_string());
        }

        // 3. Release group match (`[SubsPlease]`, `-NTb`, etc.)
        if let (Some(pg), Some(cg)) = (
            Self::extract_release_group(&prev_blob),
            Self::extract_release_group(&cand_blob),
        ) {
            if pg == cg {
                score += 65.0;
                reasons.push(format!("group:{cg}"));
            }
        }

        // 4. Transport mode consistency (Direct HTTP Mirror vs P2P Swarm)
        let cand_http = cand.is_direct_http();
        if prev.was_direct_http == cand_http {
            score += 40.0;
            reasons.push(if cand_http {
                "transport:mirror".into()
            } else {
                "transport:p2p".into()
            });
        } else if cand_http {
            score += 10.0;
        }

        // 5. Resolution match
        if prev.resolution_score > 0 && cand.resolution_score > 0 {
            if prev.resolution_score == cand.resolution_score {
                score += 30.0;
                reasons.push(format!("{}p", cand.resolution_score));
            } else {
                let diff = (prev.resolution_score - cand.resolution_score).abs() as f64;
                score -= (diff / 360.0) * 6.0;
            }
        } else if cand.resolution_score >= 1080 {
            score += 8.0;
        }

        // 6. Video codec match
        if let (Some(pc), Some(cc)) = (
            Self::extract_codec(&prev_blob),
            Self::extract_codec(&cand_blob),
        ) {
            if pc == cc {
                score += 20.0;
                reasons.push(format!("codec:{cc}"));
            }
        }

        // 7. Audio / HDR / Subtitle flavor token overlap
        let prev_tokens = Self::extract_flavor_tokens(&prev_blob);
        let cand_tokens = Self::extract_flavor_tokens(&cand_blob);
        let shared = prev_tokens.intersection(&cand_tokens).count();
        if shared > 0 {
            score += (shared as f64) * 8.0;
            reasons.push(format!("tokens:{shared}"));
        }

        // 8. Logarithmic seeder health bonus (prevents dead 0-seeder torrents winning ties)
        if cand.seeders > 0 {
            let seed_bonus = ((1.0 + cand.seeders as f64).ln() * 4.5).min(36.0);
            score += seed_bonus;
        } else if cand.is_p2p() {
            score -= 25.0;
        }

        RankedStreamCandidate {
            candidate: cand.clone(),
            similarity_score: (score * 100.0).round() / 100.0,
            matched_reasons: reasons,
        }
    }

    pub fn rank_streams(
        prev: &StreamSelectionContext,
        candidates: Vec<StreamCandidate>,
    ) -> Vec<RankedStreamCandidate> {
        let mut ranked: Vec<RankedStreamCandidate> = candidates
            .iter()
            .map(|c| Self::score_candidate(prev, c))
            .collect();
        ranked.sort_by(|a, b| {
            b.similarity_score
                .partial_cmp(&a.similarity_score)
                .unwrap_or(std::cmp::Ordering::Equal)
        });
        ranked
    }

    pub fn pick_best_next_episode_stream(
        prev: &StreamSelectionContext,
        candidates: Vec<StreamCandidate>,
    ) -> Option<RankedStreamCandidate> {
        Self::rank_streams(prev, candidates).into_iter().next()
    }

    /// Finds the next episode in `(season, episode)` order after `current_video_id`.
    /// Skips Season 0 specials unless the user is currently watching Season 0.
    pub fn find_next_episode<'a>(
        episodes: &'a [VideoEpisode],
        current_video_id: &str,
    ) -> Option<&'a VideoEpisode> {
        let mut sorted: Vec<&VideoEpisode> = episodes.iter().collect();
        sorted.sort_by(|a, b| a.season.cmp(&b.season).then_with(|| a.episode.cmp(&b.episode)));
        let pos = sorted.iter().position(|e| e.id == current_video_id)?;
        let cur_season = sorted[pos].season;
        sorted[pos + 1..]
            .iter()
            .copied()
            .find(|ep| cur_season == 0 || ep.season > 0)
    }
}

/// Unified Movie Credits / Up-Next recommendation engine across Jellyfin, Stremio, and YouTube.
pub struct RecommendationEngine {
    config: Arc<ConfigStore>,
    stremio: Arc<StremioClient>,
    jellyfin: Arc<JellyfinClient>,
    youtube: Arc<YouTubeClient>,
}

impl RecommendationEngine {
    pub fn new(
        config: Arc<ConfigStore>,
        stremio: Arc<StremioClient>,
        jellyfin: Arc<JellyfinClient>,
        youtube: Arc<YouTubeClient>,
    ) -> Self {
        Self {
            config,
            stremio,
            jellyfin,
            youtube,
        }
    }

    /// Resolves similar Movie / Series titles for the end-of-movie Credits "Next Recommended" row.
    pub async fn get_recommendations_for_meta(
        &self,
        seed: &MetaItem,
        limit: usize,
    ) -> CoreResult<Vec<MetaItem>> {
        let cap = limit.clamp(1, 24);
        if JellyfinIds::is_jellyfin_id(&seed.id) {
            if let Some((srv_id, _)) = JellyfinIds::parse_id(&seed.id) {
                if let Some(profile) = self.config.jellyfin_server(&srv_id) {
                    return self
                        .jellyfin
                        .get_similar_items(&profile, &seed.id, cap as u32)
                        .await;
                }
            }
            return Ok(Vec::new());
        }

        // Stremio: fetch catalog items for the seed's primary genre(s) and rank by genre overlap + rating
        let addons = self.config.addons();
        let primary_genre = seed.genres.first().map(|s| s.as_str());
        let mut pool = Vec::new();

        for addon in &addons {
            for cat in &addon.catalogs {
                if cat.media_type == seed.media_type && (cat.supports_genre || primary_genre.is_none()) {
                    if let Ok(items) = self
                        .stremio
                        .fetch_catalog(
                            addon,
                            &cat.media_type,
                            &cat.id,
                            None,
                            primary_genre,
                            None,
                            0,
                        )
                        .await
                    {
                        pool.extend(items);
                        if pool.len() >= 60 {
                            break;
                        }
                    }
                }
            }
            if pool.len() >= 60 {
                break;
            }
        }

        Ok(rank_catalog_recommendations(seed, &pool, cap))
    }

    pub async fn get_youtube_recommendations(
        &self,
        video_id: &str,
        limit: usize,
    ) -> CoreResult<Vec<CreatorVideoItem>> {
        let mut items = self.youtube.get_related_videos(video_id).await?;
        items.truncate(limit.clamp(1, 30));
        Ok(items)
    }
}

/// Pure deterministic similarity ranker over a pool of [`MetaItem`]s (genre overlap + IMDb rating).
pub fn rank_catalog_recommendations(
    seed: &MetaItem,
    pool: &[MetaItem],
    limit: usize,
) -> Vec<MetaItem> {
    let seed_genres: HashSet<String> = seed
        .genres
        .iter()
        .map(|g| g.trim().to_ascii_lowercase())
        .filter(|g| !g.is_empty())
        .collect();

    let mut seen = HashSet::new();
    seen.insert(seed.id.clone());

    let mut scored: Vec<(f64, MetaItem)> = Vec::new();
    for item in pool {
        if item.id.is_empty() || !seen.insert(item.id.clone()) {
            continue;
        }
        let overlap = item
            .genres
            .iter()
            .filter(|g| seed_genres.contains(&g.trim().to_ascii_lowercase()))
            .count();
        let rating = item.imdb_rating.trim().parse::<f64>().unwrap_or(6.0);
        let score = (overlap as f64) * 12.0 + rating;
        scored.push((score, item.clone()));
    }

    scored.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
    scored.into_iter().take(limit).map(|(_, m)| m).collect()
}
