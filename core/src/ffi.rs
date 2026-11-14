use crate::engine::BrootLiteEngine;
use serde_json::{json, Value};
use std::ffi::{c_char, c_void, CStr, CString};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::ptr;
use std::sync::Arc;
use tokio::runtime::{Builder as RuntimeBuilder, Runtime};

/// Opaque C-ABI handle exposed via `core/include/brootlite_core.h`.
pub struct BrootLiteEngineHandle {
    runtime: Runtime,
    engine: Arc<BrootLiteEngine>,
}

/// Callback type matching `BrootLiteAsyncCallback` in `brootlite_core.h`.
pub type BrootLiteAsyncCallback =
    extern "C" fn(user_ctx: *mut c_void, response_json_utf8: *const c_char);

#[derive(Clone, Copy)]
struct SendableUserCtx(usize);

impl SendableUserCtx {
    fn as_ptr(self) -> *mut c_void {
        self.0 as *mut c_void
    }
}

/// Creates a new `BrootLiteEngineHandle` backed by a lightweight multi-thread Tokio runtime.
#[no_mangle]
pub unsafe extern "C" fn brootlite_engine_new(
    data_dir_utf8: *const c_char,
) -> *mut BrootLiteEngineHandle {
    let result = catch_unwind(|| {
        let explicit_dir = cstr_to_opt_string(data_dir_utf8)
            .filter(|s| !s.trim().is_empty())
            .map(PathBuf::from);

        let runtime = RuntimeBuilder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .thread_name("brootlite-core")
            .build()
            .ok()?;

        let engine = runtime.block_on(BrootLiteEngine::new(explicit_dir)).ok()?;
        Some(Box::into_raw(Box::new(BrootLiteEngineHandle {
            runtime,
            engine,
        })))
    });
    match result {
        Ok(Some(ptr)) => ptr,
        _ => ptr::null_mut(),
    }
}

/// Gracefully stops active streams/servers and frees the `BrootLiteEngineHandle`.
#[no_mangle]
pub unsafe extern "C" fn brootlite_engine_free(handle: *mut BrootLiteEngineHandle) {
    if handle.is_null() {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let boxed = Box::from_raw(handle);
        boxed.runtime.block_on(boxed.engine.shutdown());
        drop(boxed);
    }));
}

/// Synchronously dispatches a JSON-RPC method and returns a heap-allocated UTF-8 C string
/// (`{"ok":true,"result":...}` or `{"ok":false,"error":"..."}`).
/// Caller MUST free the returned pointer with `brootlite_string_free`.
#[no_mangle]
pub unsafe extern "C" fn brootlite_call_json_sync(
    handle: *mut BrootLiteEngineHandle,
    method_utf8: *const c_char,
    params_json_utf8: *const c_char,
) -> *mut c_char {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        if handle.is_null() {
            return json_to_cstring_ptr(&json!({
                "ok": false,
                "error": "BrootLiteEngineHandle is NULL"
            }));
        }
        let h = &*handle;
        let Some(method) = cstr_to_opt_string(method_utf8) else {
            return json_to_cstring_ptr(&json!({
                "ok": false,
                "error": "method_utf8 is NULL or invalid UTF-8"
            }));
        };
        let params = match parse_params_cstr(params_json_utf8) {
            Ok(v) => v,
            Err(e) => {
                return json_to_cstring_ptr(&json!({
                    "ok": false,
                    "error": format!("Invalid params JSON: {e}")
                }));
            }
        };
        let env = h.runtime.block_on(h.engine.call_json_envelope(&method, params));
        json_to_cstring_ptr(&env)
    }));

    match outcome {
        Ok(ptr) => ptr,
        Err(_) => json_to_cstring_ptr(&json!({
            "ok": false,
            "error": "Internal panic caught at FFI boundary"
        })),
    }
}

/// Asynchronously dispatches a JSON-RPC method on the engine's Tokio runtime and invokes
/// `callback(user_ctx, borrowed_json_cstr)` on completion.
#[no_mangle]
pub unsafe extern "C" fn brootlite_call_json_async(
    handle: *mut BrootLiteEngineHandle,
    method_utf8: *const c_char,
    params_json_utf8: *const c_char,
    user_ctx: *mut c_void,
    callback: Option<BrootLiteAsyncCallback>,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let Some(cb) = callback else {
            return;
        };
        if handle.is_null() {
            invoke_callback_with_json(
                cb,
                user_ctx,
                &json!({ "ok": false, "error": "BrootLiteEngineHandle is NULL" }),
            );
            return;
        }
        let h = &*handle;
        let Some(method) = cstr_to_opt_string(method_utf8) else {
            invoke_callback_with_json(
                cb,
                user_ctx,
                &json!({ "ok": false, "error": "method_utf8 is NULL or invalid UTF-8" }),
            );
            return;
        };
        let params = match parse_params_cstr(params_json_utf8) {
            Ok(v) => v,
            Err(e) => {
                invoke_callback_with_json(
                    cb,
                    user_ctx,
                    &json!({ "ok": false, "error": format!("Invalid params JSON: {e}") }),
                );
                return;
            }
        };

        let engine = h.engine.clone();
        let send_ctx = SendableUserCtx(user_ctx as usize);

        h.runtime.spawn(async move {
            let envelope = engine.call_json_envelope(&method, params).await;
            let _ = catch_unwind(AssertUnwindSafe(|| {
                invoke_callback_with_json(cb, send_ctx.as_ptr(), &envelope);
            }));
        });
    }));
}

/// Frees a heap-allocated string returned by `brootlite_call_json_sync`.
#[no_mangle]
pub unsafe extern "C" fn brootlite_string_free(s: *mut c_char) {
    if s.is_null() {
        return;
    }
    let _ = catch_unwind(|| {
        drop(CString::from_raw(s));
    });
}

unsafe fn cstr_to_opt_string(ptr: *const c_char) -> Option<String> {
    if ptr.is_null() {
        return None;
    }
    CStr::from_ptr(ptr).to_str().ok().map(String::from)
}

unsafe fn parse_params_cstr(ptr: *const c_char) -> Result<Value, serde_json::Error> {
    if ptr.is_null() {
        return Ok(json!({}));
    }
    let s = CStr::from_ptr(ptr).to_string_lossy();
    let trimmed = s.trim();
    if trimmed.is_empty() {
        return Ok(json!({}));
    }
    serde_json::from_str(trimmed)
}

fn json_to_cstring_ptr(val: &Value) -> *mut c_char {
    let serialized = serde_json::to_string(val)
        .unwrap_or_else(|_| r#"{"ok":false,"error":"JSON serialization error"}"#.to_string());
    // Strip any interior NUL byte just in case
    let sanitized = serialized.replace('\0', "");
    CString::new(sanitized)
        .unwrap_or_else(|_| CString::new(r#"{"ok":false,"error":"CString NUL"}"#).unwrap())
        .into_raw()
}

fn invoke_callback_with_json(cb: BrootLiteAsyncCallback, user_ctx: *mut c_void, val: &Value) {
    let serialized = serde_json::to_string(val)
        .unwrap_or_else(|_| r#"{"ok":false,"error":"JSON serialization error"}"#.to_string())
        .replace('\0', "");
    if let Ok(cstr) = CString::new(serialized) {
        cb(user_ctx, cstr.as_ptr());
    }
}
