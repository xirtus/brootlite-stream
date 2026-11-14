#ifndef CBROOTLITE_BRIDGE_H
#define CBROOTLITE_BRIDGE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// ─────────────────────────────────────────────────────────────────────────────
// 1. BrootLite Rust Core C-ABI (`libbrootlite_core.a`)
//    Matches `core/include/brootlite_core.h` 100%
// ─────────────────────────────────────────────────────────────────────────────

typedef struct BrootLiteEngineHandle BrootLiteEngineHandle;

typedef void (*BrootLiteAsyncCallback)(void *user_ctx, const char *response_json_utf8);

BrootLiteEngineHandle *brootlite_engine_new(const char *data_dir_utf8);
void brootlite_engine_free(BrootLiteEngineHandle *handle);

char *brootlite_call_json_sync(
    BrootLiteEngineHandle *handle,
    const char *method_utf8,
    const char *params_json_utf8
);

void brootlite_call_json_async(
    BrootLiteEngineHandle *handle,
    const char *method_utf8,
    const char *params_json_utf8,
    void *user_ctx,
    BrootLiteAsyncCallback callback
);

void brootlite_string_free(char *s);

// ─────────────────────────────────────────────────────────────────────────────
// 2. Dynamic `libmpv` (`hwdec=videotoolbox` + OpenGL Render Context) C Shim
// ─────────────────────────────────────────────────────────────────────────────

typedef struct mpv_handle bl_mpv_handle;
typedef struct mpv_render_context bl_mpv_render_ctx;

enum {
    BL_MPV_EVENT_NONE             = 0,
    BL_MPV_EVENT_SHUTDOWN         = 1,
    BL_MPV_EVENT_START_FILE       = 6,
    BL_MPV_EVENT_END_FILE         = 7,
    BL_MPV_EVENT_FILE_LOADED      = 8,
    BL_MPV_EVENT_VIDEO_RECONFIG   = 17,
    BL_MPV_EVENT_SEEK             = 20,
    BL_MPV_EVENT_PLAYBACK_RESTART = 21,
    BL_MPV_EVENT_PROPERTY_CHANGE  = 22
};

enum {
    BL_MPV_END_REASON_EOF      = 0,
    BL_MPV_END_REASON_STOP     = 2,
    BL_MPV_END_REASON_QUIT     = 3,
    BL_MPV_END_REASON_ERROR    = 4,
    BL_MPV_END_REASON_REDIRECT = 5
};

typedef struct {
    int32_t event_id;
    int32_t end_reason;
    int32_t end_error;
    char prop_name[64];
} bl_mpv_polled_event;

bool bl_mpv_is_available(void);
const char *bl_mpv_loaded_path(void);

bl_mpv_handle *bl_mpv_create_initialized(void);
void bl_mpv_terminate(bl_mpv_handle *ctx);

void bl_mpv_set_wakeup_callback(
    bl_mpv_handle *ctx,
    void (*cb)(void *userdata),
    void *userdata
);

bool bl_mpv_poll_event(bl_mpv_handle *ctx, bl_mpv_polled_event *out_ev);

int bl_mpv_set_property_string(bl_mpv_handle *ctx, const char *name, const char *val);
int bl_mpv_set_property_flag(bl_mpv_handle *ctx, const char *name, int flag);
int bl_mpv_set_property_double(bl_mpv_handle *ctx, const char *name, double val);
int bl_mpv_set_property_int64(bl_mpv_handle *ctx, const char *name, int64_t val);

int bl_mpv_get_property_double(bl_mpv_handle *ctx, const char *name, double *out_val);
int bl_mpv_get_property_flag(bl_mpv_handle *ctx, const char *name, int *out_val);
int bl_mpv_get_property_int64(bl_mpv_handle *ctx, const char *name, int64_t *out_val);

/**
 * Returns a `malloc`/`strdup` UTF-8 string that the caller frees with standard `free()`,
 * or NULL if unavailable.
 */
char *bl_mpv_copy_property_string(bl_mpv_handle *ctx, const char *name);

int bl_mpv_command_1(bl_mpv_handle *ctx, const char *a0);
int bl_mpv_command_2(bl_mpv_handle *ctx, const char *a0, const char *a1);
int bl_mpv_command_3(bl_mpv_handle *ctx, const char *a0, const char *a1, const char *a2);
int bl_mpv_command_4(bl_mpv_handle *ctx, const char *a0, const char *a1, const char *a2, const char *a3);

bl_mpv_render_ctx *bl_mpv_render_gl_create(
    bl_mpv_handle *ctx,
    void (*update_cb)(void *userdata),
    void *update_userdata
);
uint64_t bl_mpv_render_gl_update(bl_mpv_render_ctx *rctx);
int bl_mpv_render_gl_draw(bl_mpv_render_ctx *rctx, int fbo, int width, int height, int flip_y);
void bl_mpv_render_gl_report_swap(bl_mpv_render_ctx *rctx);
void bl_mpv_render_gl_free(bl_mpv_render_ctx *rctx);

/**
 * Creates a retained AppKit `NSView *` hosting a Retina `CAOpenGLLayer` wired to `mpv_ctx`.
 * Caller takes ownership via `Unmanaged<NSView>.fromOpaque(ptr).takeRetainedValue()`.
 */
void *bl_mpv_create_gl_host_view(bl_mpv_handle *mpv_ctx);

// ─────────────────────────────────────────────────────────────────────────────
// 3. macOS Mach Task Memory Footprint Helper (`mach_task_basic_info`)
// ─────────────────────────────────────────────────────────────────────────────

typedef struct {
    double resident_mb;
    double phys_footprint_mb;
} bl_process_memory_snapshot;

bl_process_memory_snapshot bl_get_process_memory(void);

#ifdef __cplusplus
}
#endif

#endif /* CBROOTLITE_BRIDGE_H */
