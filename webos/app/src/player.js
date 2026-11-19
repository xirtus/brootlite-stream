// Full-Screen Cinema Player + In-Play Scrub OSD + WebAudio DSP Popover + Binge Engine
import { rpc, resolveMediaUrl } from "./rpc.js";
import { AUDIO_PRESETS, PRESET_NAMES } from "./audio_dsp.js";
import { esc, formatTime, formatSpeed, isLocalOrProxyUrl, normalizeItem } from "./utils.js";

export class CinemaPlayer {
  constructor(opts) {
    this.audioDsp = opts.audioDsp;
    this.nav = opts.nav;
    this.getAppState = opts.getAppState;
    this.onExit = opts.onExit || (() => {});
    this.onOpenItem = opts.onOpenItem || (() => {});
    this.onToast = opts.onToast || (() => {});

    this.root = null;
    this.videoEl = null;
    this.session = null;
    this.osdVisible = true;
    this.eqOpen = false;
    this.subModalOpen = false;
    this.qualityModalOpen = false;
    this.osdTimer = null;
    this.statsTimer = null;
    this.historyTimer = null;
    this.bingeTimer = null;

    this.torrentStats = null;
    this.subtitleOffsetSec = 0;
    this.activeSubtitleIdx = -1;
    this.activeQualityIdx = 0;

    this.lastAutoSkip = null;
    this.skippedSegments = {};
    this.sponsorToastTimer = null;

    this.bingeDismissed = false;
    this.bingeCountdown = 0;
    this.nextEpisodeData = null;
    this.nextEpisodeFetched = false;
    this.recommendations = [];
    this.recommendationsFetched = false;
    this._lastRenderSecond = -1;
  }

  isOpen() {
    return !!this.session;
  }

  isEqOpen() {
    return this.eqOpen;
  }

  isModalOpen() {
    return this.subModalOpen || this.qualityModalOpen;
  }

  async open(session) {
    this.close(false);
    this.session = Object.assign(
      {
        title: "Playing",
        subtitle: "",
        url: "",
        type: "movie",
        id: "",
        poster: "",
        backdrop: "",
        season: undefined,
        episode: undefined,
        episodeTitle: "",
        infoHash: "",
        source: "stream",
        serverId: "",
        mediaSourceId: "",
        playSessionId: "",
        positionSec: 0,
        subtitles: [],
        qualities: [],
        sponsorSegments: [],
      },
      session || {}
    );

    this.osdVisible = true;
    this.eqOpen = false;
    this.subModalOpen = false;
    this.qualityModalOpen = false;
    this.torrentStats = null;
    this.subtitleOffsetSec = 0;
    this.activeSubtitleIdx = this.session.subtitles.length > 0 ? 0 : -1;
    this.activeQualityIdx = 0;
    this.lastAutoSkip = null;
    this.skippedSegments = {};
    this.bingeDismissed = false;
    this.bingeCountdown = 0;
    this.nextEpisodeData = null;
    this.nextEpisodeFetched = false;
    this.recommendations = [];
    this.recommendationsFetched = false;

    this._mountDom();
    this._startPlayback(this.session.url, true);
    this._startTimers();
    this._reportJellyfin("start");
    this.showOsd();
  }

  close(notifyExit = true) {
    if (!this.session) return;
    this._saveHistory(true);
    this._reportJellyfin("stop");

    if (this.session.infoHash) {
      rpc("torrent.stop", { infoHash: this.session.infoHash }).catch(() => {});
    }

    this._clearTimers();
    if (this.videoEl) {
      try {
        this.videoEl.pause();
        this.videoEl.removeAttribute("src");
        this.videoEl.load();
      } catch (_e) {}
      this.videoEl = null;
    }

    if (this.root && this.root.parentNode) {
      this.root.parentNode.removeChild(this.root);
    }
    this.root = null;
    this.session = null;
    this.eqOpen = false;
    this.subModalOpen = false;
    this.qualityModalOpen = false;

    if (notifyExit) {
      this.onExit();
    }
  }

  handleBack() {
    if (!this.session) return false;

    if (this.subModalOpen || this.qualityModalOpen) {
      this.subModalOpen = false;
      this.qualityModalOpen = false;
      this._renderOverlays();
      this.nav.ensureFocus();
      return true;
    }

    if (this.eqOpen) {
      this.eqOpen = false;
      this._renderOverlays();
      this.nav.ensureFocus();
      return true;
    }

    // Undo recent SponsorBlock auto-skip if pressed within 8s
    if (this.lastAutoSkip && Date.now() - this.lastAutoSkip.timestamp < 8000) {
      const undo = this.lastAutoSkip;
      this.skippedSegments[undo.key] = "undone";
      this.lastAutoSkip = null;
      if (this.videoEl) {
        this.videoEl.currentTime = undo.fromTime;
      }
      this._renderOverlays();
      this.onToast("Restored segment before " + undo.category);
      return true;
    }

    if (this.osdVisible) {
      this.osdVisible = false;
      this._updateOsdVisibility();
      return true;
    }

    this.close(true);
    return true;
  }

  handleMediaKey(action) {
    if (!this.session || !this.videoEl) return false;
    this.audioDsp.resumeContext();
    if (action === "play") {
      this.videoEl.play().catch(() => {});
      this.showOsd();
      return true;
    }
    if (action === "pause") {
      this.videoEl.pause();
      this.showOsd();
      return true;
    }
    if (action === "stop") {
      this.close(true);
      return true;
    }
    if (action === "rewind") {
      this.seekBy(-10);
      this.showOsd();
      return true;
    }
    if (action === "fastforward") {
      this.seekBy(30);
      this.showOsd();
      return true;
    }
    return false;
  }

  onUserActivity() {
    if (!this.session) return;
    if (!this.osdVisible) {
      this.showOsd();
    } else {
      this._resetOsdTimeout();
    }
  }

  showOsd() {
    this.osdVisible = true;
    this._updateOsdVisibility();
    this._resetOsdTimeout();
    setTimeout(() => {
      if (this.session) this.nav.ensureFocus();
    }, 20);
  }

  _resetOsdTimeout() {
    if (this.osdTimer) clearTimeout(this.osdTimer);
    this.osdTimer = setTimeout(() => {
      if (!this.session) return;
      if (this.eqOpen || this.subModalOpen || this.qualityModalOpen) return;
      if (this.videoEl && !this.videoEl.paused) {
        this.osdVisible = false;
        this._updateOsdVisibility();
      }
    }, 6500);
  }

  _updateOsdVisibility() {
    if (!this.root) return;
    const topBar = this.root.querySelector(".player-top-bar");
    const osd = this.root.querySelector(".player-osd");
    if (topBar) topBar.classList.toggle("is-hidden", !this.osdVisible);
    if (osd) osd.classList.toggle("is-hidden", !this.osdVisible);
  }

  seekBy(deltaSec) {
    if (!this.videoEl) return;
    const dur = isFinite(this.videoEl.duration) ? this.videoEl.duration : Infinity;
    this.videoEl.currentTime = Math.max(0, Math.min(dur, (this.videoEl.currentTime || 0) + deltaSec));
    this._updateScrubBar();
  }

  togglePlayPause() {
    if (!this.videoEl) return;
    this.audioDsp.resumeContext();
    if (this.videoEl.paused) {
      this.videoEl.play().catch(() => {});
    } else {
      this.videoEl.pause();
    }
    this._updateScrubBar();
    this.showOsd();
  }

  _mountDom() {
    const existing = document.getElementById("player-overlay");
    if (existing && existing.parentNode) existing.parentNode.removeChild(existing);

    const el = document.createElement("div");
    el.id = "player-overlay";
    el.className = "player-overlay";
    el.innerHTML =
      '<video id="broot-video" class="player-video" playsinline autoplay></video>' +
      '<div class="player-top-bar">' +
      '  <div class="player-title-wrap">' +
      '    <div class="player-title">' + esc(this.session.title) + "</div>" +
      '    <div class="player-subtitle">' + esc(this.session.subtitle || "") + "</div>" +
      "  </div>" +
      '  <div style="display:flex;align-items:center;gap:12px;">' +
      '    <div id="player-swarm-hud" class="swarm-hud-badge" style="display:none;"></div>' +
      '    <button id="player-exit-btn" class="btn focusable" tabindex="0">✕ Exit</button>' +
      "  </div>" +
      "</div>" +
      '<div id="player-dynamic-overlays"></div>' +
      '<div class="player-osd">' +
      '  <div class="scrub-timeline-row">' +
      '    <span id="player-cur-time" class="time-label">00:00</span>' +
      '    <button id="player-scrub-btn" class="scrub-track-btn focusable" tabindex="0" title="Click to seek">' +
      '      <div class="scrub-bar"><div id="player-scrub-fill" class="scrub-fill" style="width:0%"></div></div>' +
      "    </button>" +
      '    <span id="player-dur-time" class="time-label" style="text-align:right;">00:00</span>' +
      "  </div>" +
      '  <div class="osd-controls-row">' +
      '    <div class="osd-group">' +
      '      <button id="osd-rew" class="btn focusable" tabindex="0">↺ -10s</button>' +
      '      <button id="osd-play" class="btn btn-primary focusable" tabindex="0" data-autofocus="1">⏸ Pause</button>' +
      '      <button id="osd-fwd" class="btn focusable" tabindex="0">+30s ↻</button>' +
      "    </div>" +
      '    <div class="osd-group">' +
      '      <button id="osd-vol-eq" class="btn focusable" tabindex="0">' + esc(this.audioDsp.getSummaryBadge()) + "</button>" +
      '      <button id="osd-subs" class="btn focusable" tabindex="0">💬 Subtitles</button>' +
      '      <button id="osd-quality" class="btn focusable" tabindex="0">⚙ Quality</button>' +
      '      <button id="osd-next-ep" class="btn btn-emerald focusable" tabindex="0" style="display:none;">Next Ep ⏭</button>' +
      "    </div>" +
      "  </div>" +
      "</div>";

    document.body.appendChild(el);
    this.root = el;
    this.videoEl = el.querySelector("#broot-video");

    this._bindPlayerEvents();
    this._attachSubtitles();
  }

  _startPlayback(rawUrl, tryCors = true) {
    if (!this.videoEl || !this.session) return;
    const mediaUrl = resolveMediaUrl(rawUrl);
    if (!mediaUrl) {
      this.onToast("No playable media URL returned");
      return;
    }

    const useCors = tryCors && (isLocalOrProxyUrl(mediaUrl) || tryCors === true);
    if (useCors) {
      this.videoEl.crossOrigin = "anonymous";
    } else {
      this.videoEl.removeAttribute("crossorigin");
    }

    this.videoEl.src = mediaUrl;
    if (this.session.positionSec > 3) {
      this.videoEl.addEventListener(
        "loadedmetadata",
        () => {
          if (this.videoEl && this.session && this.session.positionSec > 3) {
            try {
              this.videoEl.currentTime = this.session.positionSec;
            } catch (_e) {}
          }
        },
        { once: true }
      );
    }

    this.audioDsp.attachVideo(this.videoEl, useCors);

    // If a direct external host rejects CORS, transparently retry without crossOrigin
    if (useCors && !isLocalOrProxyUrl(mediaUrl)) {
      const onCorsFallback = () => {
        if (!this.videoEl || !this.session) return;
        this.videoEl.removeEventListener("error", onCorsFallback);
        this._startPlayback(rawUrl, false);
      };
      this.videoEl.addEventListener("error", onCorsFallback, { once: true });
    }

    this.videoEl.play().catch(() => {});
  }

  _bindPlayerEvents() {
    const v = this.videoEl;
    if (!v || !this.root) return;

    this.root.querySelector("#player-exit-btn").onclick = () => this.close(true);
    this.root.querySelector("#osd-rew").onclick = () => {
      this.seekBy(-10);
      this.showOsd();
    };
    this.root.querySelector("#osd-play").onclick = () => this.togglePlayPause();
    this.root.querySelector("#osd-fwd").onclick = () => {
      this.seekBy(30);
      this.showOsd();
    };
    this.root.querySelector("#osd-vol-eq").onclick = () => {
      this.eqOpen = !this.eqOpen;
      this.subModalOpen = false;
      this.qualityModalOpen = false;
      this._renderOverlays();
      this.nav.ensureFocus();
    };
    this.root.querySelector("#osd-subs").onclick = () => {
      this.subModalOpen = !this.subModalOpen;
      this.eqOpen = false;
      this.qualityModalOpen = false;
      this._renderOverlays();
      this.nav.ensureFocus();
    };
    this.root.querySelector("#osd-quality").onclick = () => {
      this.qualityModalOpen = !this.qualityModalOpen;
      this.eqOpen = false;
      this.subModalOpen = false;
      this._renderOverlays();
      this.nav.ensureFocus();
    };
    this.root.querySelector("#osd-next-ep").onclick = () => this._playNextEpisode();

    const scrubBtn = this.root.querySelector("#player-scrub-btn");
    scrubBtn.onclick = (e) => {
      if (!v.duration || !isFinite(v.duration)) return;
      const rect = scrubBtn.getBoundingClientRect();
      const clickX = typeof e.clientX === "number" && e.clientX > 0 ? e.clientX : rect.left + rect.width / 2;
      const ratio = Math.max(0, Math.min(1, (clickX - rect.left) / Math.max(1, rect.width)));
      v.currentTime = ratio * v.duration;
      this._updateScrubBar();
      this.showOsd();
    };

    scrubBtn.addEventListener("keydown", (e) => {
      const code = e.keyCode || e.which;
      if (code === 37) {
        e.preventDefault();
        e.stopPropagation();
        this.seekBy(-10);
      } else if (code === 39) {
        e.preventDefault();
        e.stopPropagation();
        this.seekBy(30);
      }
    });

    v.addEventListener("click", () => this.showOsd());
    v.addEventListener("play", () => this._updatePlayButton());
    v.addEventListener("pause", () => {
      this._updatePlayButton();
      this._saveHistory(false);
      this._reportJellyfin("progress");
      this.showOsd();
    });
    v.addEventListener("timeupdate", () => this._onTimeUpdate());
    v.addEventListener("ended", () => this._onVideoEnded());
  }

  _updatePlayButton() {
    if (!this.root || !this.videoEl) return;
    const btn = this.root.querySelector("#osd-play");
    if (btn) {
      btn.textContent = this.videoEl.paused ? "▶ Play" : "⏸ Pause";
    }
  }

  _updateScrubBar() {
    if (!this.root || !this.videoEl) return;
    const cur = this.videoEl.currentTime || 0;
    const dur = isFinite(this.videoEl.duration) ? this.videoEl.duration : 0;
    const pct = dur > 0 ? Math.min(100, Math.max(0, (cur / dur) * 100)) : 0;
    const curEl = this.root.querySelector("#player-cur-time");
    const durEl = this.root.querySelector("#player-dur-time");
    const fillEl = this.root.querySelector("#player-scrub-fill");
    if (curEl) curEl.textContent = formatTime(cur);
    if (durEl) durEl.textContent = formatTime(dur);
    if (fillEl) fillEl.style.width = pct.toFixed(2) + "%";
  }

  _onTimeUpdate() {
    if (!this.videoEl || !this.session) return;
    this._updateScrubBar();

    const cur = this.videoEl.currentTime || 0;
    const dur = isFinite(this.videoEl.duration) ? this.videoEl.duration : 0;

    // SponsorBlock auto-skip check
    this._checkSponsorBlock(cur);

    // Binge / Recommendations trigger check in final 45s / 35s
    if (dur > 60) {
      const remaining = dur - cur;
      if (this.session.type === "series" && remaining <= 35 && !this.nextEpisodeFetched) {
        this.nextEpisodeFetched = true;
        this._fetchNextEpisode();
      }
      if (this.session.type !== "series" && remaining <= 45 && !this.recommendationsFetched) {
        this.recommendationsFetched = true;
        this._fetchRecommendations();
      }
    }

    const sec = Math.floor(cur);
    if (sec !== this._lastRenderSecond) {
      this._lastRenderSecond = sec;
      if (!this.eqOpen && !this.subModalOpen && !this.qualityModalOpen) {
        this._renderFloatingPrompts();
      }
    }
  }

  _onVideoEnded() {
    if (!this.session) return;
    if (this.session.type === "series" && this.nextEpisodeData && !this.bingeDismissed) {
      this._playNextEpisode();
      return;
    }
    this.showOsd();
    if (this.session.type !== "series" && !this.recommendationsFetched) {
      this.recommendationsFetched = true;
      this._fetchRecommendations();
    }
  }

  _checkSponsorBlock(cur) {
    const appState = this.getAppState();
    const ytOpts = (appState && appState.youtubeOptions) || {};
    const sponsorEnabled = ytOpts.sponsorBlock !== false;
    const segs = Array.isArray(this.session.sponsorSegments) ? this.session.sponsorSegments : [];
    if (!sponsorEnabled || segs.length === 0) return;

    for (let i = 0; i < segs.length; i++) {
      const seg = segs[i];
      const start = Number(seg.startTime !== undefined ? seg.startTime : seg.segment ? seg.segment[0] : seg.start);
      const end = Number(seg.endTime !== undefined ? seg.endTime : seg.segment ? seg.segment[1] : seg.end);
      const cat = seg.category || "sponsor";
      const key = i + "_" + start.toFixed(1);
      if (!isFinite(start) || !isFinite(end) || end <= start) continue;
      if (cat === "intro" || cat === "recap") continue; // Handled by Skip Intro pill

      if (this.skippedSegments[key]) continue;
      if (cur >= start && cur < end - 0.5) {
        this.skippedSegments[key] = "skipped";
        this.lastAutoSkip = {
          key: key,
          fromTime: start,
          toTime: end,
          category: cat,
          timestamp: Date.now(),
        };
        this.videoEl.currentTime = end;
        if (this.sponsorToastTimer) clearTimeout(this.sponsorToastTimer);
        this.sponsorToastTimer = setTimeout(() => {
          this.lastAutoSkip = null;
          this._renderFloatingPrompts();
        }, 7500);
        this._renderFloatingPrompts();
        break;
      }
    }
  }

  _getActiveIntroSegment() {
    if (!this.session || !this.videoEl) return null;
    const cur = this.videoEl.currentTime || 0;
    const segs = Array.isArray(this.session.sponsorSegments) ? this.session.sponsorSegments : [];
    for (let i = 0; i < segs.length; i++) {
      const seg = segs[i];
      const cat = seg.category || "";
      if (cat !== "intro" && cat !== "recap") continue;
      const start = Number(seg.startTime !== undefined ? seg.startTime : seg.segment ? seg.segment[0] : seg.start);
      const end = Number(seg.endTime !== undefined ? seg.endTime : seg.segment ? seg.segment[1] : seg.end);
      if (cur >= start && cur < end - 1.5) {
        return { start: start, end: end, label: cat === "recap" ? "Skip Recap" : "Skip Intro" };
      }
    }
    return null;
  }

  _startTimers() {
    if (this.session.infoHash) {
      this._pollTorrentStats();
      this.statsTimer = setInterval(() => this._pollTorrentStats(), 1000);
    }
    if (this.session.type === "series") {
      this._fetchNextEpisode();
    }
    this.historyTimer = setInterval(() => {
      this._saveHistory(false);
      this._reportJellyfin("progress");
    }, 10000);
  }

  _clearTimers() {
    if (this.osdTimer) clearTimeout(this.osdTimer);
    if (this.statsTimer) clearInterval(this.statsTimer);
    if (this.historyTimer) clearInterval(this.historyTimer);
    if (this.bingeTimer) clearInterval(this.bingeTimer);
    if (this.sponsorToastTimer) clearTimeout(this.sponsorToastTimer);
    this.osdTimer = null;
    this.statsTimer = null;
    this.historyTimer = null;
    this.bingeTimer = null;
    this.sponsorToastTimer = null;
  }

  async _pollTorrentStats() {
    if (!this.session || !this.session.infoHash || !this.root) return;
    try {
      const stats = await rpc("torrent.stats", { infoHash: this.session.infoHash });
      if (!stats || !this.root) return;
      this.torrentStats = stats;
      const hud = this.root.querySelector("#player-swarm-hud");
      if (hud) {
        const down = formatSpeed(stats.downloadSpeed || stats.downSpeed || 0);
        const peers = stats.peers !== undefined ? stats.peers : 0;
        const seeds = stats.seeds !== undefined ? stats.seeds : stats.seeders !== undefined ? stats.seeders : 0;
        const winPct = Math.round(Number(stats.windowProgress || stats.progress || 0));
        hud.textContent = "↓ " + down + " · " + peers + " Peers · " + seeds + " Seeds · Window " + winPct + "%";
        hud.style.display = "inline-flex";
      }
    } catch (_e) {}
  }

  _saveHistory(force) {
    if (!this.session || !this.videoEl || !this.session.id) return;
    const pos = Math.floor(this.videoEl.currentTime || 0);
    const dur = Math.floor(isFinite(this.videoEl.duration) ? this.videoEl.duration : 0);
    if (!force && pos < 3) return;
    rpc("history.upsert", {
      id: this.session.id,
      type: this.session.type,
      title: this.session.title,
      poster: this.session.poster,
      backdrop: this.session.backdrop,
      season: this.session.season,
      episode: this.session.episode,
      episodeTitle: this.session.episodeTitle,
      positionSec: pos,
      durationSec: dur,
      source: this.session.source,
      providerId: this.session.providerId,
      serverId: this.session.serverId,
      itemUrl: this.session.url,
    }).catch(() => {});
  }

  _reportJellyfin(event) {
    if (!this.session || this.session.source !== "jellyfin" || !this.session.serverId || !this.videoEl) return;
    const ticks = Math.floor((this.videoEl.currentTime || 0) * 10000000);
    rpc("jellyfin.report", {
      serverId: this.session.serverId,
      itemId: this.session.id,
      event: event,
      positionTicks: ticks,
      mediaSourceId: this.session.mediaSourceId,
      playSessionId: this.session.playSessionId,
    }).catch(() => {});
  }

  async _fetchNextEpisode() {
    if (!this.session || this.session.type !== "series") return;
    try {
      const res = await rpc("binge.nextEpisode", {
        id: this.session.id,
        type: this.session.type,
        season: this.session.season,
        episode: this.session.episode,
        serverId: this.session.serverId,
        source: this.session.source,
      });
      const nextEp = res && (res.nextEpisode || res.episode || (res.season && res));
      if (nextEp && (nextEp.episode !== undefined || nextEp.id)) {
        this.nextEpisodeData = nextEp;
        const btn = this.root && this.root.querySelector("#osd-next-ep");
        if (btn) btn.style.display = "inline-flex";
        this._maybeStartBingeCountdown();
      }
    } catch (_e) {}
  }

  _maybeStartBingeCountdown() {
    if (this.bingeTimer || this.bingeDismissed || !this.nextEpisodeData || !this.videoEl) return;
    const dur = isFinite(this.videoEl.duration) ? this.videoEl.duration : 0;
    const cur = this.videoEl.currentTime || 0;
    if (dur > 60 && dur - cur <= 35) {
      this.bingeCountdown = 15;
      this._renderFloatingPrompts();
      this.bingeTimer = setInterval(() => {
        if (!this.session || this.bingeDismissed) {
          clearInterval(this.bingeTimer);
          this.bingeTimer = null;
          return;
        }
        if (this.videoEl && this.videoEl.paused) return;
        this.bingeCountdown--;
        if (this.bingeCountdown <= 0) {
          clearInterval(this.bingeTimer);
          this.bingeTimer = null;
          this._playNextEpisode();
        } else {
          this._renderFloatingPrompts();
        }
      }, 1000);
    }
  }

  async _playNextEpisode() {
    if (!this.session || !this.nextEpisodeData) return;
    const next = this.nextEpisodeData;
    const nextSeason = next.season !== undefined ? next.season : this.session.season;
    const nextEpNum = next.episode !== undefined ? next.episode : (this.session.episode || 0) + 1;
    this.onToast("Loading S" + String(nextSeason).padStart(2, "0") + "E" + String(nextEpNum).padStart(2, "0") + "...");

    try {
      if (this.session.source === "jellyfin" && this.session.serverId && next.id) {
        const jfRes = await rpc("jellyfin.resolvePlay", {
          serverId: this.session.serverId,
          itemId: next.id,
          resumePositionTicks: 0,
        });
        const url = jfRes && (jfRes.url || jfRes.streamUrl);
        if (url) {
          this.open(
            Object.assign({}, this.session, {
              id: next.id,
              season: nextSeason,
              episode: nextEpNum,
              episodeTitle: next.title || next.name || "",
              subtitle: "S" + String(nextSeason).padStart(2, "0") + "E" + String(nextEpNum).padStart(2, "0") + " · " + (next.title || ""),
              url: url,
              positionSec: 0,
              subtitles: jfRes.subtitles || [],
            })
          );
          return;
        }
      }

      const streamsRes = await rpc("streams.fetch", {
        type: "series",
        id: this.session.id,
        season: nextSeason,
        episode: nextEpNum,
        title: this.session.title,
      });
      const list = (streamsRes && streamsRes.streams) || [];
      if (list.length === 0) {
        this.onToast("No streams found for next episode");
        return;
      }
      const resolved = await rpc("streams.resolvePlayable", {
        stream: list[0],
        meta: { id: this.session.id, type: "series", title: this.session.title, season: nextSeason, episode: nextEpNum },
      });
      const playUrl = resolved && (resolved.url || resolved.streamUrl);
      if (playUrl) {
        this.open(
          Object.assign({}, this.session, {
            season: nextSeason,
            episode: nextEpNum,
            episodeTitle: next.title || next.name || "",
            subtitle: "S" + String(nextSeason).padStart(2, "0") + "E" + String(nextEpNum).padStart(2, "0") + " · " + (next.title || ""),
            url: playUrl,
            infoHash: (resolved && resolved.infoHash) || list[0].infoHash || "",
            positionSec: 0,
            subtitles: (resolved && resolved.subtitles) || [],
          })
        );
      }
    } catch (err) {
      this.onToast("Failed to load next episode: " + (err.message || err));
    }
  }

  async _fetchRecommendations() {
    if (!this.session) return;
    try {
      const res = await rpc("binge.recommendations", {
        type: this.session.type,
        id: this.session.id,
        title: this.session.title,
      });
      const rawItems = (res && (res.items || res.recommendations || res.results)) || [];
      this.recommendations = rawItems.map((it) => normalizeItem(it, "movie")).filter(Boolean).slice(0, 6);
      this._renderFloatingPrompts();
    } catch (_e) {}
  }

  _attachSubtitles() {
    if (!this.videoEl || !this.session) return;
    const oldTracks = this.videoEl.querySelectorAll("track");
    for (let i = 0; i < oldTracks.length; i++) {
      oldTracks[i].parentNode.removeChild(oldTracks[i]);
    }
    const subs = Array.isArray(this.session.subtitles) ? this.session.subtitles : [];
    for (let i = 0; i < subs.length; i++) {
      const sub = subs[i];
      const trackEl = document.createElement("track");
      trackEl.kind = "subtitles";
      trackEl.label = sub.label || sub.lang || "Track " + (i + 1);
      trackEl.srclang = sub.lang || "en";
      trackEl.src = resolveMediaUrl(sub.url || sub.src || "");
      if (i === this.activeSubtitleIdx) trackEl.default = true;
      this.videoEl.appendChild(trackEl);
    }

    setTimeout(() => this._syncSubtitleModeAndOffset(), 120);
  }

  _syncSubtitleModeAndOffset() {
    if (!this.videoEl || !this.videoEl.textTracks) return;
    const tracks = this.videoEl.textTracks;
    for (let i = 0; i < tracks.length; i++) {
      const t = tracks[i];
      t.mode = i === this.activeSubtitleIdx ? "showing" : "disabled";
      if (i === this.activeSubtitleIdx && t.cues) {
        for (let c = 0; c < t.cues.length; c++) {
          const cue = t.cues[c];
          if (cue.__origStart === undefined) {
            cue.__origStart = cue.startTime;
            cue.__origEnd = cue.endTime;
          }
          cue.startTime = Math.max(0, cue.__origStart + this.subtitleOffsetSec);
          cue.endTime = Math.max(0.1, cue.__origEnd + this.subtitleOffsetSec);
        }
      }
    }
  }

  _renderFloatingPrompts() {
    if (!this.root || !this.session) return;
    if (this.eqOpen || this.subModalOpen || this.qualityModalOpen) return;
    this._renderOverlays();
  }

  _renderOverlays() {
    if (!this.root || !this.session) return;
    const container = this.root.querySelector("#player-dynamic-overlays");
    if (!container) return;

    let html = "";

    // SponsorBlock Undo Toast
    if (this.lastAutoSkip && Date.now() - this.lastAutoSkip.timestamp < 8000) {
      html +=
        '<div class="sponsorblock-toast">' +
        '<span>⏭ Skipped ' + esc(this.lastAutoSkip.category) + '</span>' +
        '<span class="mono" style="color:var(--cyan);">Press BACK to undo</span>' +
        "</div>";
    }

    // Floating Skip Intro Pill
    const intro = this._getActiveIntroSegment();
    if (intro && !this.eqOpen && !this.subModalOpen && !this.qualityModalOpen) {
      html +=
        '<button id="floating-skip-intro" class="skip-intro-pill focusable" tabindex="0">' +
        esc(intro.label) +
        " ⏭</button>";
    }

    // Bottom-Right Keep Watching Autoplay Card
    const dur = this.videoEl && isFinite(this.videoEl.duration) ? this.videoEl.duration : 0;
    const cur = this.videoEl ? this.videoEl.currentTime || 0 : 0;
    if (
      this.session.type === "series" &&
      this.nextEpisodeData &&
      !this.bingeDismissed &&
      dur > 60 &&
      dur - cur <= 35 &&
      !this.eqOpen
    ) {
      this._maybeStartBingeCountdown();
      const sNum = String(this.nextEpisodeData.season !== undefined ? this.nextEpisodeData.season : this.session.season || 1).padStart(2, "0");
      const eNum = String(this.nextEpisodeData.episode !== undefined ? this.nextEpisodeData.episode : (this.session.episode || 0) + 1).padStart(2, "0");
      html +=
        '<div class="binge-card">' +
        '  <div class="binge-kicker">KEEP WATCHING · S' + esc(sNum) + "E" + esc(eNum) + "</div>" +
        '  <div style="font-size:21px;font-weight:600;">' + esc(this.nextEpisodeData.title || this.nextEpisodeData.name || "Next Episode") + "</div>" +
        '  <div class="muted" style="font-size:15px;">Auto-playing in ' + esc(this.bingeCountdown || 15) + "s</div>" +
        '  <div class="form-row">' +
        '    <button id="binge-stream-next" class="btn btn-primary focusable" tabindex="0">Stream Next ▶</button>' +
        '    <button id="binge-watch-credits" class="btn focusable" tabindex="0">Watch Credits</button>' +
        "  </div>" +
        "</div>";
    }

    // Movie Credits Next Recommended Bottom Poster Gallery
    if (
      this.session.type !== "series" &&
      this.recommendations.length > 0 &&
      dur > 60 &&
      dur - cur <= 45 &&
      !this.eqOpen
    ) {
      html +=
        '<div class="credits-gallery">' +
        '  <div class="binge-kicker">NEXT RECOMMENDED</div>' +
        '  <div class="poster-rail" style="margin-bottom:0;padding-bottom:6px;">';
      for (let i = 0; i < this.recommendations.length; i++) {
        const rec = this.recommendations[i];
        html +=
          '<button class="poster-card focusable" tabindex="0" data-rec-idx="' + i + '" style="width:165px;min-width:165px;">' +
          '  <div class="poster-media">' +
          (rec.poster ? '<img src="' + esc(rec.poster) + '" alt="" />' : '<div class="poster-placeholder">' + esc(rec.title) + "</div>") +
          "  </div>" +
          '  <div class="poster-body"><div class="poster-title" style="font-size:15px;">' + esc(rec.title) + "</div></div>" +
          "</button>";
      }
      html += "</div></div>";
    }

    // Frosted-Glass Audio Boost & 3-Band EQ Popover
    if (this.eqOpen) {
      const s = this.audioDsp.getSettings();
      const boostPctNorm = ((s.boostPct - 50) / 250) * 100;
      const bandBar = (db) => ((db + 8) / 16) * 100;
      html +=
        '<div id="eq-popover" class="eq-popover">' +
        '  <div class="panel-head">' +
        '    <div>' +
        '      <div class="panel-title">Audio Boost & 3-Band EQ</div>' +
        '      <div class="mono muted" style="font-size:13px;">' +
        (this.audioDsp.dspActive ? "WebAudio DSP Active · Peak Limiter Ready" : "Direct Native Volume Fallback (No CORS)") +
        "      </div>" +
        "    </div>" +
        '    <button id="eq-close" class="btn btn-sm focusable" tabindex="0">✕</button>' +
        "  </div>" +
        '  <div class="pill-strip" style="margin-bottom:4px;">';
      for (let i = 0; i < PRESET_NAMES.length; i++) {
        const p = PRESET_NAMES[i];
        if (p === "Custom" && s.preset !== "Custom") continue;
        html +=
          '<button class="pill focusable' +
          (s.preset === p ? " is-active" : "") +
          '" tabindex="0" data-eq-preset="' +
          esc(p) +
          '">' +
          esc(p) +
          "</button>";
      }
      html +=
        "  </div>" +
        '  <div class="eq-band-row">' +
        '    <span>Volume Boost</span>' +
        '    <button id="eq-boost-dec" class="btn btn-sm focusable" tabindex="0">-10%</button>' +
        '    <div class="eq-meter-track"><div class="eq-meter-fill" style="width:' + boostPctNorm.toFixed(1) + '%"></div></div>' +
        '    <button id="eq-boost-inc" class="btn btn-sm focusable" tabindex="0">+10%</button>' +
        '    <span class="mono" style="text-align:right;color:var(--cyan);">' + s.boostPct + "%</span>" +
        "  </div>" +
        '  <div class="eq-band-row">' +
        '    <span>Bass (100Hz)</span>' +
        '    <button id="eq-bass-dec" class="btn btn-sm focusable" tabindex="0">-1dB</button>' +
        '    <div class="eq-meter-track"><div class="eq-meter-fill" style="width:' + bandBar(s.bassDb) + '%"></div></div>' +
        '    <button id="eq-bass-inc" class="btn btn-sm focusable" tabindex="0">+1dB</button>' +
        '    <span class="mono" style="text-align:right;">' + (s.bassDb > 0 ? "+" : "") + s.bassDb + " dB</span>" +
        "  </div>" +
        '  <div class="eq-band-row">' +
        '    <span>Voice (1.8kHz)</span>' +
        '    <button id="eq-voice-dec" class="btn btn-sm focusable" tabindex="0">-1dB</button>' +
        '    <div class="eq-meter-track"><div class="eq-meter-fill" style="width:' + bandBar(s.voiceDb) + '%"></div></div>' +
        '    <button id="eq-voice-inc" class="btn btn-sm focusable" tabindex="0">+1dB</button>' +
        '    <span class="mono" style="text-align:right;">' + (s.voiceDb > 0 ? "+" : "") + s.voiceDb + " dB</span>" +
        "  </div>" +
        '  <div class="eq-band-row">' +
        '    <span>Treble (7.5kHz)</span>' +
        '    <button id="eq-treble-dec" class="btn btn-sm focusable" tabindex="0">-1dB</button>' +
        '    <div class="eq-meter-track"><div class="eq-meter-fill" style="width:' + bandBar(s.trebleDb) + '%"></div></div>' +
        '    <button id="eq-treble-inc" class="btn btn-sm focusable" tabindex="0">+1dB</button>' +
        '    <span class="mono" style="text-align:right;">' + (s.trebleDb > 0 ? "+" : "") + s.trebleDb + " dB</span>" +
        "  </div>" +
        '  <div class="form-row" style="justify-content:space-between;">' +
        '    <button id="eq-boost-200" class="btn btn-primary focusable' + (s.boostPct === 200 ? " is-active" : "") + '" tabindex="0" data-autofocus="1">⚡ 200% Boost</button>' +
        '    <button id="eq-drc-toggle" class="btn focusable' + (s.dialogueDrc ? " btn-emerald" : "") + '" tabindex="0">Dialogue DRC: ' + (s.dialogueDrc ? "ON" : "OFF") + "</button>" +
        '    <button id="eq-reset-flat" class="btn focusable" tabindex="0">Reset Flat</button>' +
        "  </div>" +
        "</div>";
    }

    // Subtitles Picker Modal
    if (this.subModalOpen) {
      const subs = Array.isArray(this.session.subtitles) ? this.session.subtitles : [];
      html +=
        '<div class="modal-backdrop">' +
        '  <div class="modal-card">' +
        '    <div class="panel-head">' +
        '      <div class="panel-title">Subtitles & Timing Offset</div>' +
        '      <button id="sub-modal-close" class="btn btn-sm focusable" tabindex="0">✕</button>' +
        "    </div>" +
        '    <div class="form-row" style="justify-content:space-between;padding:12px 16px;background:rgba(255,255,255,0.03);border-radius:14px;">' +
        '      <span>Cue Offset</span>' +
        '      <div class="form-row">' +
        '        <button id="sub-offset-dec" class="btn btn-sm focusable" tabindex="0">-0.5s</button>' +
        '        <span class="mono" style="min-width:74px;text-align:center;color:var(--cyan);">' + (this.subtitleOffsetSec >= 0 ? "+" : "") + this.subtitleOffsetSec.toFixed(1) + "s</span>" +
        '        <button id="sub-offset-inc" class="btn btn-sm focusable" tabindex="0">+0.5s</button>' +
        "      </div>" +
        "    </div>" +
        '    <div class="stream-list">' +
        '      <button class="pill focusable' + (this.activeSubtitleIdx === -1 ? " is-active" : "") + '" tabindex="0" data-sub-idx="-1" data-autofocus="1">Off (Disabled)</button>';
      for (let i = 0; i < subs.length; i++) {
        const sub = subs[i];
        html +=
          '<button class="pill focusable' + (this.activeSubtitleIdx === i ? " is-active" : "") + '" tabindex="0" data-sub-idx="' + i + '">' +
          esc(sub.label || sub.lang || "Subtitle " + (i + 1)) +
          "</button>";
      }
      if (subs.length === 0) {
        html += '<div class="muted" style="padding:10px 4px;">No WebVTT subtitle tracks attached to this stream.</div>';
      }
      html += "    </div></div></div>";
    }

    // Quality Switcher Modal
    if (this.qualityModalOpen) {
      const qualities = Array.isArray(this.session.qualities) ? this.session.qualities : [];
      html +=
        '<div class="modal-backdrop">' +
        '  <div class="modal-card">' +
        '    <div class="panel-head">' +
        '      <div class="panel-title">Video Quality</div>' +
        '      <button id="quality-modal-close" class="btn btn-sm focusable" tabindex="0">✕</button>' +
        "    </div>" +
        '    <div class="stream-list">';
      if (qualities.length === 0) {
        html += '<div class="muted">Single adaptive stream active.</div>';
      } else {
        for (let i = 0; i < qualities.length; i++) {
          const q = qualities[i];
          html +=
            '<button class="pill focusable' + (this.activeQualityIdx === i ? " is-active" : "") + '" tabindex="0" data-q-idx="' + i + '" ' + (i === 0 ? 'data-autofocus="1"' : "") + ">" +
            esc(q.label || q.quality || "Variant " + (i + 1)) +
            "</button>";
        }
      }
      html += "    </div></div></div>";
    }

    container.innerHTML = html;
    this._bindOverlayEvents(container, intro);
    this._syncVolEqLabel();
  }

  _syncVolEqLabel() {
    if (!this.root) return;
    const btn = this.root.querySelector("#osd-vol-eq");
    if (btn) btn.textContent = this.audioDsp.getSummaryBadge();
  }

  _bindOverlayEvents(container, intro) {
    const skipBtn = container.querySelector("#floating-skip-intro");
    if (skipBtn && intro) {
      skipBtn.onclick = () => {
        if (this.videoEl) this.videoEl.currentTime = intro.end;
        this._renderFloatingPrompts();
      };
    }

    const bingePlay = container.querySelector("#binge-stream-next");
    if (bingePlay) bingePlay.onclick = () => this._playNextEpisode();
    const bingeCredits = container.querySelector("#binge-watch-credits");
    if (bingeCredits) {
      bingeCredits.onclick = () => {
        this.bingeDismissed = true;
        this._renderFloatingPrompts();
      };
    }

    const recBtns = container.querySelectorAll("[data-rec-idx]");
    for (let i = 0; i < recBtns.length; i++) {
      recBtns[i].onclick = () => {
        const idx = Number(recBtns[i].getAttribute("data-rec-idx"));
        const item = this.recommendations[idx];
        if (item) {
          this.close(false);
          this.onOpenItem(item);
        }
      };
    }

    // EQ Popover bindings
    if (this.eqOpen) {
      const bind = (id, fn) => {
        const el = container.querySelector("#" + id);
        if (el) {
          el.onclick = () => {
            fn();
            this._renderOverlays();
            const refocus = container.querySelector("#" + id);
            if (refocus) this.nav.focusElement(refocus, false);
          };
        }
      };
      const closeBtn = container.querySelector("#eq-close");
      if (closeBtn) {
        closeBtn.onclick = () => {
          this.eqOpen = false;
          this._renderOverlays();
          this.nav.ensureFocus();
        };
      }
      bind("eq-boost-dec", () => this.audioDsp.adjustBoost(-10));
      bind("eq-boost-inc", () => this.audioDsp.adjustBoost(10));
      bind("eq-bass-dec", () => this.audioDsp.adjustBand("bass", -1));
      bind("eq-bass-inc", () => this.audioDsp.adjustBand("bass", 1));
      bind("eq-voice-dec", () => this.audioDsp.adjustBand("voice", -1));
      bind("eq-voice-inc", () => this.audioDsp.adjustBand("voice", 1));
      bind("eq-treble-dec", () => this.audioDsp.adjustBand("treble", -1));
      bind("eq-treble-inc", () => this.audioDsp.adjustBand("treble", 1));
      bind("eq-boost-200", () => this.audioDsp.toggle200Boost());
      bind("eq-drc-toggle", () => this.audioDsp.toggleDialogueDrc());
      bind("eq-reset-flat", () => this.audioDsp.resetFlat());

      const presetBtns = container.querySelectorAll("[data-eq-preset]");
      for (let i = 0; i < presetBtns.length; i++) {
        presetBtns[i].onclick = () => {
          const name = presetBtns[i].getAttribute("data-eq-preset");
          if (AUDIO_PRESETS[name]) {
            this.audioDsp.setPreset(name);
            this._renderOverlays();
            const target = container.querySelector('[data-eq-preset="' + name + '"]');
            if (target) this.nav.focusElement(target, false);
          }
        };
      }
    }

    // Subtitles Modal bindings
    if (this.subModalOpen) {
      const closeSub = container.querySelector("#sub-modal-close");
      if (closeSub) {
        closeSub.onclick = () => {
          this.subModalOpen = false;
          this._renderOverlays();
          this.nav.ensureFocus();
        };
      }
      const dec = container.querySelector("#sub-offset-dec");
      if (dec) {
        dec.onclick = () => {
          this.subtitleOffsetSec = Math.round((this.subtitleOffsetSec - 0.5) * 10) / 10;
          this._syncSubtitleModeAndOffset();
          this._renderOverlays();
          const btn = container.querySelector("#sub-offset-dec");
          if (btn) this.nav.focusElement(btn, false);
        };
      }
      const inc = container.querySelector("#sub-offset-inc");
      if (inc) {
        inc.onclick = () => {
          this.subtitleOffsetSec = Math.round((this.subtitleOffsetSec + 0.5) * 10) / 10;
          this._syncSubtitleModeAndOffset();
          this._renderOverlays();
          const btn = container.querySelector("#sub-offset-inc");
          if (btn) this.nav.focusElement(btn, false);
        };
      }
      const subItems = container.querySelectorAll("[data-sub-idx]");
      for (let i = 0; i < subItems.length; i++) {
        subItems[i].onclick = () => {
          this.activeSubtitleIdx = Number(subItems[i].getAttribute("data-sub-idx"));
          this._syncSubtitleModeAndOffset();
          this.subModalOpen = false;
          this._renderOverlays();
          this.nav.ensureFocus();
        };
      }
    }

    // Quality Modal bindings
    if (this.qualityModalOpen) {
      const closeQ = container.querySelector("#quality-modal-close");
      if (closeQ) {
        closeQ.onclick = () => {
          this.qualityModalOpen = false;
          this._renderOverlays();
          this.nav.ensureFocus();
        };
      }
      const qBtns = container.querySelectorAll("[data-q-idx]");
      for (let i = 0; i < qBtns.length; i++) {
        qBtns[i].onclick = () => {
          const idx = Number(qBtns[i].getAttribute("data-q-idx"));
          const q = this.session.qualities[idx];
          if (q && q.url) {
            const curTime = this.videoEl ? this.videoEl.currentTime || 0 : 0;
            this.activeQualityIdx = idx;
            this.session.positionSec = curTime;
            this.session.url = q.url;
            this._startPlayback(q.url, true);
          }
          this.qualityModalOpen = false;
          this._renderOverlays();
          this.nav.ensureFocus();
        };
      }
    }
  }
}
