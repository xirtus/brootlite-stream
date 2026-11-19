// Luna Service + Local HTTP RPC Client Bridge for app.brootlite.tv.service

const SERVICE_URI = "luna://app.brootlite.tv.service";
const DEFAULT_HTTP_BASE = "http://127.0.0.1:19477";

class RpcBridge {
  constructor() {
    this.mediaBaseUrl = DEFAULT_HTTP_BASE;
    this.httpRpcUrl = DEFAULT_HTTP_BASE + "/rpc";
    this.transport = "detecting";
    this.keepAliveHandle = null;
    this.connected = false;
    this.bootInfo = null;
  }

  hasWebOSService() {
    return !!(
      typeof window !== "undefined" &&
      window.webOS &&
      window.webOS.service &&
      typeof window.webOS.service.request === "function"
    );
  }

  hasPalmBridge() {
    return typeof window !== "undefined" && typeof window.PalmServiceBridge === "function";
  }

  startKeepAlive() {
    if (this.keepAliveHandle) return;

    if (this.hasWebOSService()) {
      this.transport = "luna-webos";
      try {
        this.keepAliveHandle = window.webOS.service.request(SERVICE_URI, {
          method: "keepAlive",
          parameters: { subscribe: true },
          onSuccess: (res) => {
            this.connected = true;
            if (res && res.mediaBaseUrl) {
              this.setMediaBaseUrl(res.mediaBaseUrl);
            }
          },
          onFailure: () => {
            // Keep-alive may retry or fall back to HTTP if needed
          },
        });
      } catch (_err) {
        // Ignore keepAlive setup errors
      }
      return;
    }

    if (this.hasPalmBridge()) {
      this.transport = "luna-palm";
      try {
        const bridge = new window.PalmServiceBridge();
        bridge.onservicecallback = (msg) => {
          try {
            const data = typeof msg === "string" ? JSON.parse(msg) : msg;
            if (data && data.returnValue !== false) {
              this.connected = true;
              if (data.mediaBaseUrl) {
                this.setMediaBaseUrl(data.mediaBaseUrl);
              }
            }
          } catch (_e) {
            // Ignore malformed keepAlive packet
          }
        };
        bridge.call(SERVICE_URI + "/keepAlive", JSON.stringify({ subscribe: true }));
        this.keepAliveHandle = bridge;
      } catch (_err) {
        // Ignore PalmServiceBridge failure
      }
      return;
    }

    this.transport = "http";
  }

  setMediaBaseUrl(url) {
    if (!url || typeof url !== "string") return;
    const clean = url.replace(/\/+$/, "");
    this.mediaBaseUrl = clean;
    this.httpRpcUrl = clean + "/rpc";
  }

  resolveMediaUrl(url) {
    if (!url || typeof url !== "string") return "";
    if (/^https?:\/\//i.test(url) || url.indexOf("blob:") === 0 || url.indexOf("data:") === 0) {
      return url;
    }
    if (url.charAt(0) === "/") {
      return this.mediaBaseUrl + url;
    }
    return this.mediaBaseUrl + "/" + url;
  }

  async call(method, params = {}) {
    if (this.hasWebOSService()) {
      this.transport = "luna-webos";
      try {
        const res = await this._callWebOSService(method, params);
        this.connected = true;
        return res;
      } catch (err) {
        // Fall through to HTTP fallback if running hybrid/dev
        if (!err || err.fallbackHttp !== false) {
          return this._callHttp(method, params);
        }
        throw err;
      }
    }

    if (this.hasPalmBridge()) {
      this.transport = "luna-palm";
      try {
        const res = await this._callPalmBridge(method, params);
        this.connected = true;
        return res;
      } catch (err) {
        return this._callHttp(method, params);
      }
    }

    this.transport = "http";
    return this._callHttp(method, params);
  }

  _unwrapEnvelope(res, fallbackMsg) {
    if (!res || res.returnValue === false || res.ok === false) {
      const msg = (res && (res.errorText || (res.error && res.error.message) || res.error)) || fallbackMsg;
      const err = new Error(typeof msg === "string" ? msg : JSON.stringify(msg));
      err.fallbackHttp = false;
      throw err;
    }
    if (res.data !== undefined) return res.data;
    if (res.result !== undefined) return res.result;
    return res;
  }

  _callWebOSService(method, params) {
    return new Promise((resolve, reject) => {
      try {
        window.webOS.service.request(SERVICE_URI, {
          method: method === "keepAlive" ? "keepAlive" : "rpc",
          parameters: { method: method, params: params || {} },
          onSuccess: (res) => {
            try {
              resolve(this._unwrapEnvelope(res, "Luna RPC failed"));
            } catch (err) {
              reject(err);
            }
          },
          onFailure: (err) => {
            const msg = (err && (err.errorText || err.message)) || "Luna service unavailable";
            const e = new Error(msg);
            e.fallbackHttp = true;
            reject(e);
          },
        });
      } catch (e) {
        reject(e);
      }
    });
  }

  _callPalmBridge(method, params) {
    return new Promise((resolve, reject) => {
      try {
        const bridge = new window.PalmServiceBridge();
        bridge.onservicecallback = (raw) => {
          try {
            const res = typeof raw === "string" ? JSON.parse(raw) : raw;
            if (!res || res.returnValue === false) {
              const msg = (res && (res.errorText || res.error)) || "PalmServiceBridge RPC failed";
              const err = new Error(msg);
              err.fallbackHttp = !!(res && res.errorCode === -1);
              reject(err);
              return;
            }
            resolve(this._unwrapEnvelope(res, "PalmServiceBridge RPC failed"));
          } catch (parseErr) {
            reject(parseErr);
          }
        };
        bridge.call(
          SERVICE_URI + "/rpc",
          JSON.stringify({ method: method, params: params || {} })
        );
      } catch (err) {
        reject(err);
      }
    });
  }

  async _callHttp(method, params) {
    const resp = await fetch(this.httpRpcUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ method: method, params: params || {} }),
    });
    const text = await resp.text();
    let payload = null;
    try {
      payload = text ? JSON.parse(text) : {};
    } catch (_e) {
      throw new Error("Invalid JSON from RPC HTTP endpoint");
    }
    if (!resp.ok || (payload && (payload.ok === false || payload.error))) {
      const msg =
        (payload && ((payload.error && payload.error.message) || payload.error)) ||
        ("HTTP " + resp.status);
      throw new Error(typeof msg === "string" ? msg : JSON.stringify(msg));
    }
    this.connected = true;
    return this._unwrapEnvelope(payload, "HTTP RPC failed");
  }

  async boot() {
    this.startKeepAlive();
    const info = await this.call("system.boot", {});
    this.bootInfo = info || {};
    if (info && info.mediaBaseUrl) {
      this.setMediaBaseUrl(info.mediaBaseUrl);
    }
    return this.bootInfo;
  }
}

export const rpcBridge = new RpcBridge();

export function rpc(method, params) {
  return rpcBridge.call(method, params);
}

export function resolveMediaUrl(url) {
  return rpcBridge.resolveMediaUrl(url);
}
