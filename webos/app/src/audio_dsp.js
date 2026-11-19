// Real-Time WebAudio 50%-300% Amplifier, 3-Band EQ & Dialogue DRC Engine
import { rpc } from "./rpc.js";

const STORAGE_KEY = "brootlite_audio_dsp_v1";

export const AUDIO_PRESETS = {
  Flat: { boostPct: 100, bassDb: 0, voiceDb: 0, trebleDb: 0, dialogueDrc: false },
  Projector: { boostPct: 200, bassDb: 2, voiceDb: 5, trebleDb: 3, dialogueDrc: true },
  Dialogue: { boostPct: 145, bassDb: -2, voiceDb: 6, trebleDb: 2, dialogueDrc: true },
  "Cinema Bass": { boostPct: 150, bassDb: 6, voiceDb: 1, trebleDb: 2, dialogueDrc: true },
  "Night DRC": { boostPct: 115, bassDb: -4, voiceDb: 4, trebleDb: -1, dialogueDrc: true },
};

export const PRESET_NAMES = ["Flat", "Projector", "Dialogue", "Cinema Bass", "Night DRC", "Custom"];

function clamp(v, min, max) {
  return Math.max(min, Math.min(max, v));
}

export class AudioDspEngine {
  constructor() {
    this.settings = {
      boostPct: 100,
      preset: "Flat",
      bassDb: 0,
      voiceDb: 0,
      trebleDb: 0,
      dialogueDrc: false,
    };
    this.ctx = null;
    this.sourceNode = null;
    this.attachedVideo = null;
    this.bassFilter = null;
    this.voiceFilter = null;
    this.trebleFilter = null;
    this.compressor = null;
    this.gainNode = null;
    this.dspActive = false;
    this.corsBypassMode = false;
    this._patchTimer = null;

    this._loadLocal();
  }

  _loadLocal() {
    try {
      const raw = window.localStorage && window.localStorage.getItem(STORAGE_KEY);
      if (!raw) return;
      const parsed = JSON.parse(raw);
      if (parsed && typeof parsed === "object") {
        this.applySettings(parsed, { persist: false });
      }
    } catch (_e) {
      // Ignore storage errors
    }
  }

  hydrateFromRemoteState(remoteAudio) {
    if (!remoteAudio || typeof remoteAudio !== "object") return;
    this.applySettings(remoteAudio, { persist: false, syncRemote: false });
    this._saveLocal();
  }

  _saveLocal() {
    try {
      if (window.localStorage) {
        window.localStorage.setItem(STORAGE_KEY, JSON.stringify(this.settings));
      }
    } catch (_e) {
      // Ignore quota errors
    }
  }

  _scheduleRemotePatch() {
    if (this._patchTimer) clearTimeout(this._patchTimer);
    this._patchTimer = setTimeout(() => {
      rpc("state.patch", { audioDsp: Object.assign({}, this.settings) }).catch(() => {});
    }, 300);
  }

  getSettings() {
    return Object.assign({}, this.settings);
  }

  getSummaryBadge() {
    if (this.settings.boostPct !== 100 || this.settings.preset !== "Flat" || this.settings.dialogueDrc) {
      return "Vol " + this.settings.boostPct + "% · " + this.settings.preset;
    }
    return "Vol 100%";
  }

  applySettings(next, opts = {}) {
    const s = this.settings;
    if (typeof next.preset === "string" && AUDIO_PRESETS[next.preset]) {
      const p = AUDIO_PRESETS[next.preset];
      s.preset = next.preset;
      s.boostPct = p.boostPct;
      s.bassDb = p.bassDb;
      s.voiceDb = p.voiceDb;
      s.trebleDb = p.trebleDb;
      s.dialogueDrc = p.dialogueDrc;
    }

    if (next.boostPct !== undefined) s.boostPct = clamp(Math.round(Number(next.boostPct) || 100), 50, 300);
    if (next.bassDb !== undefined) s.bassDb = clamp(Number(next.bassDb) || 0, -8, 8);
    if (next.voiceDb !== undefined) s.voiceDb = clamp(Number(next.voiceDb) || 0, -8, 8);
    if (next.trebleDb !== undefined) s.trebleDb = clamp(Number(next.trebleDb) || 0, -8, 8);
    if (next.dialogueDrc !== undefined) s.dialogueDrc = !!next.dialogueDrc;

    if (typeof next.preset === "string" && !AUDIO_PRESETS[next.preset]) {
      s.preset = "Custom";
    } else if (!next.preset) {
      // Detect if current values match a named preset
      let matched = "Custom";
      const keys = Object.keys(AUDIO_PRESETS);
      for (let i = 0; i < keys.length; i++) {
        const k = keys[i];
        const p = AUDIO_PRESETS[k];
        if (
          p.boostPct === s.boostPct &&
          p.bassDb === s.bassDb &&
          p.voiceDb === s.voiceDb &&
          p.trebleDb === s.trebleDb &&
          p.dialogueDrc === s.dialogueDrc
        ) {
          matched = k;
          break;
        }
      }
      s.preset = matched;
    }

    this._applyNodes();

    if (opts.persist !== false) {
      this._saveLocal();
      if (opts.syncRemote !== false) {
        this._scheduleRemotePatch();
      }
    }
  }

  setPreset(presetName) {
    if (AUDIO_PRESETS[presetName]) {
      this.applySettings({ preset: presetName });
    }
  }

  toggle200Boost() {
    if (this.settings.boostPct === 200) {
      this.applySettings({ boostPct: 100 });
    } else {
      this.applySettings({ boostPct: 200 });
    }
  }

  adjustBoost(delta) {
    this.applySettings({ boostPct: this.settings.boostPct + delta });
  }

  adjustBand(band, delta) {
    const key = band === "bass" ? "bassDb" : band === "voice" ? "voiceDb" : "trebleDb";
    this.applySettings({ [key]: this.settings[key] + delta });
  }

  toggleDialogueDrc() {
    this.applySettings({ dialogueDrc: !this.settings.dialogueDrc });
  }

  resetFlat() {
    this.applySettings({ preset: "Flat" });
  }

  attachVideo(videoEl, corsEnabled = true) {
    if (!videoEl) return false;
    this.corsBypassMode = !corsEnabled;

    if (!corsEnabled) {
      this.dspActive = false;
      this._applyFallbackVolume(videoEl);
      return false;
    }

    const AudioCtx = window.AudioContext || window.webkitAudioContext;
    if (!AudioCtx) {
      this.dspActive = false;
      this._applyFallbackVolume(videoEl);
      return false;
    }

    try {
      if (!this.ctx) {
        this.ctx = new AudioCtx();
      }
      if (this.ctx.state === "suspended") {
        this.ctx.resume().catch(() => {});
      }

      if (this.attachedVideo !== videoEl) {
        if (this.sourceNode) {
          try {
            this.sourceNode.disconnect();
          } catch (_e) {}
          this.sourceNode = null;
        }
        this.sourceNode = this.ctx.createMediaElementSource(videoEl);
        this.attachedVideo = videoEl;

        this.bassFilter = this.ctx.createBiquadFilter();
        this.bassFilter.type = "lowshelf";
        this.bassFilter.frequency.value = 100;

        this.voiceFilter = this.ctx.createBiquadFilter();
        this.voiceFilter.type = "peaking";
        this.voiceFilter.frequency.value = 1800;
        this.voiceFilter.Q.value = 0.85;

        this.trebleFilter = this.ctx.createBiquadFilter();
        this.trebleFilter.type = "highshelf";
        this.trebleFilter.frequency.value = 7500;

        this.compressor = this.ctx.createDynamicsCompressor();
        this.gainNode = this.ctx.createGain();

        this.sourceNode.connect(this.bassFilter);
        this.bassFilter.connect(this.voiceFilter);
        this.voiceFilter.connect(this.trebleFilter);
        this.trebleFilter.connect(this.compressor);
        this.compressor.connect(this.gainNode);
        this.gainNode.connect(this.ctx.destination);
      }

      // Keep native element volume at 1.0 when WebAudio gain controls level
      videoEl.volume = 1.0;
      this.dspActive = true;
      this._applyNodes();
      return true;
    } catch (_err) {
      this.dspActive = false;
      this._applyFallbackVolume(videoEl);
      return false;
    }
  }

  resumeContext() {
    if (this.ctx && this.ctx.state === "suspended") {
      this.ctx.resume().catch(() => {});
    }
  }

  _applyFallbackVolume(videoEl) {
    if (!videoEl) return;
    try {
      videoEl.volume = clamp(this.settings.boostPct / 100, 0.05, 1.0);
    } catch (_e) {}
  }

  _applyNodes() {
    if (!this.dspActive || !this.ctx || !this.gainNode) {
      if (this.attachedVideo && this.corsBypassMode) {
        this._applyFallbackVolume(this.attachedVideo);
      }
      return;
    }

    const now = this.ctx.currentTime;
    const s = this.settings;

    try {
      this.bassFilter.gain.setTargetAtTime(s.bassDb, now, 0.02);
      this.voiceFilter.gain.setTargetAtTime(s.voiceDb, now, 0.02);
      this.trebleFilter.gain.setTargetAtTime(s.trebleDb, now, 0.02);

      // Transparent brickwall limiter when Dialogue DRC is on or boost > 100%
      const engageLimiter = s.dialogueDrc || s.boostPct > 100;
      if (engageLimiter) {
        this.compressor.threshold.setTargetAtTime(-2, now, 0.02);
        this.compressor.knee.setTargetAtTime(6, now, 0.02);
        this.compressor.ratio.setTargetAtTime(12, now, 0.02);
        this.compressor.attack.setTargetAtTime(0.003, now, 0.02);
        this.compressor.release.setTargetAtTime(0.15, now, 0.02);
      } else {
        this.compressor.threshold.setTargetAtTime(0, now, 0.02);
        this.compressor.knee.setTargetAtTime(0, now, 0.02);
        this.compressor.ratio.setTargetAtTime(1, now, 0.02);
      }

      const targetGain = (s.boostPct / 100) * (s.dialogueDrc ? 1.35 : 1.0);
      this.gainNode.gain.setTargetAtTime(targetGain, now, 0.02);
    } catch (_e) {
      // Fallback direct assignment for older WebAudio implementations
      this.bassFilter.gain.value = s.bassDb;
      this.voiceFilter.gain.value = s.voiceDb;
      this.trebleFilter.gain.value = s.trebleDb;
      this.gainNode.gain.value = (s.boostPct / 100) * (s.dialogueDrc ? 1.35 : 1.0);
    }
  }
}
