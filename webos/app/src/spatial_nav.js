// LG Magic Remote Air-Mouse + 5-Way D-Pad Spatial Navigation & Hardware Remote Key Engine

export class SpatialNavigator {
  constructor(options = {}) {
    this.onBack = options.onBack || (() => false);
    this.onMediaKey = options.onMediaKey || (() => false);
    this.onUserActivity = options.onUserActivity || (() => {});
    this.lastMouseX = -1;
    this.lastMouseY = -1;
    this._boundKeyDown = this._handleKeyDown.bind(this);
    this._boundMouseMove = this._handleMouseMove.bind(this);
    this._boundFocusIn = this._handleFocusIn.bind(this);
  }

  mount() {
    window.addEventListener("keydown", this._boundKeyDown, true);
    window.addEventListener("mousemove", this._boundMouseMove, { passive: true });
    document.addEventListener("focusin", this._boundFocusIn, true);
  }

  destroy() {
    window.removeEventListener("keydown", this._boundKeyDown, true);
    window.removeEventListener("mousemove", this._boundMouseMove);
    document.removeEventListener("focusin", this._boundFocusIn, true);
  }

  getActiveScope() {
    const modals = document.querySelectorAll(".modal-backdrop");
    if (modals.length > 0) {
      return modals[modals.length - 1];
    }
    const eq = document.getElementById("eq-popover");
    if (eq && eq.offsetParent !== null) {
      return eq;
    }
    const player = document.getElementById("player-overlay");
    if (player && player.offsetParent !== null) {
      return player;
    }
    const detail = document.getElementById("detail-overlay");
    if (detail && detail.offsetParent !== null) {
      return detail;
    }
    return document.getElementById("app") || document.body;
  }

  getCandidates(scope) {
    const root = scope || this.getActiveScope();
    const list = root.querySelectorAll(".focusable");
    const visible = [];
    for (let i = 0; i < list.length; i++) {
      const el = list[i];
      if (el.disabled || el.getAttribute("aria-disabled") === "true") continue;
      if (el.offsetWidth <= 0 || el.offsetHeight <= 0) continue;
      const style = window.getComputedStyle(el);
      if (style.display === "none" || style.visibility === "hidden" || style.opacity === "0") {
        continue;
      }
      // Skip elements inside a hidden OSD wrapper
      if (el.closest(".is-hidden")) continue;
      visible.push(el);
    }
    return visible;
  }

  focusElement(el, scroll = true) {
    if (!el || typeof el.focus !== "function") return false;
    try {
      el.focus({ preventScroll: true });
    } catch (_e) {
      el.focus();
    }
    if (scroll && typeof el.scrollIntoView === "function") {
      try {
        el.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "smooth" });
      } catch (_e) {
        el.scrollIntoView(false);
      }
    }
    this._syncFocusedClass(el);
    return true;
  }

  focusFirst(scope) {
    const candidates = this.getCandidates(scope);
    if (candidates.length === 0) return false;
    const preferred = candidates.find((el) => el.hasAttribute("data-autofocus"));
    return this.focusElement(preferred || candidates[0], false);
  }

  ensureFocus() {
    const scope = this.getActiveScope();
    const active = document.activeElement;
    if (active && active !== document.body && scope.contains(active) && active.classList.contains("focusable")) {
      if (active.offsetWidth > 0 && active.offsetHeight > 0 && !active.closest(".is-hidden")) {
        this._syncFocusedClass(active);
        return true;
      }
    }
    return this.focusFirst(scope);
  }

  _syncFocusedClass(activeEl) {
    const prev = document.querySelectorAll(".focusable.is-focused");
    for (let i = 0; i < prev.length; i++) {
      if (prev[i] !== activeEl) prev[i].classList.remove("is-focused");
    }
    if (activeEl && activeEl.classList && activeEl.classList.contains("focusable")) {
      activeEl.classList.add("is-focused");
    }
  }

  _handleFocusIn(e) {
    const target = e.target && e.target.closest ? e.target.closest(".focusable") : null;
    if (target) {
      this._syncFocusedClass(target);
    }
  }

  _handleMouseMove(e) {
    const dx = Math.abs(e.clientX - this.lastMouseX);
    const dy = Math.abs(e.clientY - this.lastMouseY);
    if (dx < 3 && dy < 3) return;
    this.lastMouseX = e.clientX;
    this.lastMouseY = e.clientY;
    this.onUserActivity("pointer");

    const target = e.target && e.target.closest ? e.target.closest(".focusable") : null;
    if (target && !target.closest(".is-hidden") && !target.disabled) {
      const scope = this.getActiveScope();
      if (scope.contains(target) && document.activeElement !== target) {
        this.focusElement(target, false);
      }
    }
  }

  _handleKeyDown(e) {
    const code = e.keyCode || e.which;
    const key = e.key || "";
    const active = document.activeElement;
    const isInput =
      active &&
      (active.tagName === "INPUT" || active.tagName === "TEXTAREA" || active.tagName === "SELECT");

    this.onUserActivity("key", code);

    // LG Hardware Media Keys
    if (code === 415) {
      e.preventDefault();
      this.onMediaKey("play");
      return;
    }
    if (code === 19) {
      e.preventDefault();
      this.onMediaKey("pause");
      return;
    }
    if (code === 413) {
      e.preventDefault();
      this.onMediaKey("stop");
      return;
    }
    if (code === 412) {
      e.preventDefault();
      this.onMediaKey("rewind");
      return;
    }
    if (code === 417) {
      e.preventDefault();
      this.onMediaKey("fastforward");
      return;
    }

    // LG webOS Back (461), Escape (27), Backspace (8 when not in input)
    if (code === 461 || code === 27 || key === "Escape" || (code === 8 && !isInput)) {
      e.preventDefault();
      e.stopPropagation();
      const handled = this.onBack();
      if (!handled && window.webOS && typeof window.webOS.platformBack === "function") {
        window.webOS.platformBack();
      }
      return;
    }

    // Enter inside input can trigger submit callback via button or change; otherwise click focusable
    if (code === 13 || key === "Enter") {
      if (isInput) {
        const submitId = active.getAttribute("data-enter-target");
        if (submitId) {
          const btn = document.getElementById(submitId);
          if (btn) {
            e.preventDefault();
            btn.click();
          }
        }
        return;
      }
      if (active && active.classList && active.classList.contains("focusable")) {
        e.preventDefault();
        active.click();
      }
      return;
    }

    // 5-Way D-Pad Arrows
    let dir = null;
    if (code === 37 || key === "ArrowLeft") dir = "left";
    else if (code === 39 || key === "ArrowRight") dir = "right";
    else if (code === 38 || key === "ArrowUp") dir = "up";
    else if (code === 40 || key === "ArrowDown") dir = "down";

    if (!dir) return;

    // Allow text cursor movement inside text inputs unless at edges
    if (isInput && (dir === "left" || dir === "right") && typeof active.selectionStart === "number") {
      const len = (active.value || "").length;
      if (dir === "left" && active.selectionStart > 0) return;
      if (dir === "right" && active.selectionStart < len) return;
    }

    e.preventDefault();
    this.move(dir);
  }

  move(dir) {
    const scope = this.getActiveScope();
    const candidates = this.getCandidates(scope);
    if (candidates.length === 0) return false;

    const current = document.activeElement;
    if (!current || !scope.contains(current) || !current.classList.contains("focusable")) {
      return this.focusFirst(scope);
    }

    const curRect = current.getBoundingClientRect();
    const cx = curRect.left + curRect.width / 2;
    const cy = curRect.top + curRect.height / 2;

    let best = null;
    let bestScore = Infinity;

    for (let i = 0; i < candidates.length; i++) {
      const el = candidates[i];
      if (el === current) continue;
      const r = el.getBoundingClientRect();
      const tx = r.left + r.width / 2;
      const ty = r.top + r.height / 2;

      const dx = tx - cx;
      const dy = ty - cy;

      if (dir === "left" && dx >= -4) continue;
      if (dir === "right" && dx <= 4) continue;
      if (dir === "up" && dy >= -4) continue;
      if (dir === "down" && dy <= 4) continue;

      const primary = dir === "left" || dir === "right" ? Math.abs(dx) : Math.abs(dy);
      const secondary = dir === "left" || dir === "right" ? Math.abs(dy) : Math.abs(dx);

      // Overlap boost when elements share the same row/column
      const sameLane =
        dir === "left" || dir === "right"
          ? Math.min(curRect.bottom, r.bottom) - Math.max(curRect.top, r.top) > 6
          : Math.min(curRect.right, r.right) - Math.max(curRect.left, r.left) > 6;

      const penalty = sameLane ? 0.65 : 2.6;
      const score = primary + secondary * penalty;

      if (score < bestScore) {
        bestScore = score;
        best = el;
      }
    }

    if (best) {
      return this.focusElement(best, true);
    }
    return false;
  }
}
