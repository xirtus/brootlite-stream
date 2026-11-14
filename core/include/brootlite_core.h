#ifndef BROOTLITE_CORE_H
#define BROOTLITE_CORE_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Opaque thread-safe handle to a BrootLiteEngine instance and its internal Tokio runtime.
 */
typedef struct BrootLiteEngineHandle BrootLiteEngineHandle;

/**
 * Async JSON-RPC completion callback invoked on a background worker thread.
 *
 * @param user_ctx           Opaque context pointer supplied to brootlite_call_json_async.
 * @param response_json_utf8 Borrowed NUL-terminated UTF-8 JSON string valid for the duration
 *                           of this callback invocation (`{"ok":true,"result":...}` or
 *                           `{"ok":false,"error":"..."}`). Do NOT call brootlite_string_free
 *                           on `response_json_utf8` inside the callback; Rust frees it
 *                           automatically right after `callback` returns.
 */
typedef void (*BrootLiteAsyncCallback)(void *user_ctx, const char *response_json_utf8);

/**
 * Creates a new BrootLiteEngine instance.
 *
 * @param data_dir_utf8 Optional NUL-terminated UTF-8 directory path for app state/cache.
 *                      Pass NULL or "" to use the default OS application support directory
 *                      (`~/Library/Application Support/app.brootlite.desktop` on macOS,
 *                      `~/.local/share/brootlite` on Linux, `%APPDATA%\BrootLite` on Windows).
 * @return Non-null handle on success, or NULL if initialization failed.
 */
BrootLiteEngineHandle *brootlite_engine_new(const char *data_dir_utf8);

/**
 * Shuts down active loopback streams, flushes state, and frees a BrootLiteEngineHandle.
 * Safe to call with NULL.
 */
void brootlite_engine_free(BrootLiteEngineHandle *handle);

/**
 * Synchronously executes a JSON-RPC method against BrootLiteEngine.
 *
 * @param handle           Valid engine handle created by brootlite_engine_new.
 * @param method_utf8      NUL-terminated method name (e.g. "status", "stremio.fetch_catalog").
 * @param params_json_utf8 Optional NUL-terminated UTF-8 JSON object string (NULL or "{}" if none).
 * @return Heap-allocated NUL-terminated UTF-8 JSON envelope (`{"ok":true,"result":...}` or
 *         `{"ok":false,"error":"..."}`). Caller MUST release the returned string via
 *         `brootlite_string_free`.
 */
char *brootlite_call_json_sync(
    BrootLiteEngineHandle *handle,
    const char *method_utf8,
    const char *params_json_utf8
);

/**
 * Asynchronously executes a JSON-RPC method on the engine's Tokio runtime and invokes `callback`
 * exactly once upon completion (never panics across the FFI boundary).
 *
 * @param handle           Valid engine handle created by brootlite_engine_new.
 * @param method_utf8      NUL-terminated method name.
 * @param params_json_utf8 Optional NUL-terminated UTF-8 JSON object string (NULL or "{}" if none).
 * @param user_ctx         Opaque caller context passed back to `callback` (e.g. Swift continuation box).
 * @param callback         Completion callback receiving a borrowed UTF-8 JSON envelope string.
 */
void brootlite_call_json_async(
    BrootLiteEngineHandle *handle,
    const char *method_utf8,
    const char *params_json_utf8,
    void *user_ctx,
    BrootLiteAsyncCallback callback
);

/**
 * Frees a heap-allocated C string previously returned by `brootlite_call_json_sync`.
 * Safe to call with NULL.
 */
void brootlite_string_free(char *s);

#ifdef __cplusplus
}
#endif

#endif /* BROOTLITE_CORE_H */
