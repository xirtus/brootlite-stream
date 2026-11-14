#include "CBrootLiteBridge.h"

#include <dlfcn.h>
#include <libgen.h>
#include <limits.h>
#include <locale.h>
#include <mach-o/dyld.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// ─────────────────────────────────────────────────────────────────────────────
// Exact libmpv ABI types (`mpv/client.h`, `mpv/render.h`, `mpv/render_gl.h`)
// ─────────────────────────────────────────────────────────────────────────────

typedef enum {
    MPV_FMT_NONE   = 0,
    MPV_FMT_STRING = 1,
    MPV_FMT_FLAG   = 3,
    MPV_FMT_INT64  = 4,
    MPV_FMT_DOUBLE = 5
} mpv_fmt_t;

typedef struct {
    const char *name;
    int format;
    void *data;
} mpv_ev_prop_t;

typedef struct {
    int reason;
    int error;
    int64_t playlist_entry_id;
    int64_t playlist_insert_id;
    int playlist_insert_num_entries;
} mpv_ev_end_file_t;

typedef struct {
    int event_id;
    int error;
    uint64_t reply_userdata;
    void *data;
} mpv_ev_t;

typedef enum {
    MPV_RP_INVALID            = 0,
    MPV_RP_API_TYPE           = 1,
    MPV_RP_OPENGL_INIT_PARAMS = 2,
    MPV_RP_OPENGL_FBO         = 3,
    MPV_RP_FLIP_Y             = 4,
    MPV_RP_ADVANCED_CONTROL   = 10,
    MPV_RP_BLOCK_FOR_TARGET   = 12
} mpv_rp_type_t;

typedef struct {
    int type;
    void *data;
} mpv_rp_t;

typedef struct {
    void *(*get_proc_address)(void *ctx, const char *name);
    void *get_proc_address_ctx;
} mpv_gl_init_params_t;

typedef struct {
    int fbo;
    int w;
    int h;
    int internal_format;
} mpv_gl_fbo_t;

// Function pointer table loaded via `dlopen`
typedef struct {
    void *dylib;
    void *gl_framework;
    char loaded_path[PATH_MAX];
    bool ok;

    bl_mpv_handle *(*create)(void);
    int (*initialize)(bl_mpv_handle *);
    void (*terminate_destroy)(bl_mpv_handle *);
    void (*free_mem)(void *);
    int (*set_option_string)(bl_mpv_handle *, const char *, const char *);
    int (*set_property)(bl_mpv_handle *, const char *, int, void *);
    int (*set_property_string)(bl_mpv_handle *, const char *, const char *);
    int (*get_property)(bl_mpv_handle *, const char *, int, void *);
    char *(*get_property_string)(bl_mpv_handle *, const char *);
    int (*observe_property)(bl_mpv_handle *, uint64_t, const char *, int);
    int (*command)(bl_mpv_handle *, const char **);
    int (*command_async)(bl_mpv_handle *, uint64_t, const char **);
    mpv_ev_t *(*wait_event)(bl_mpv_handle *, double);
    void (*set_wakeup_callback)(bl_mpv_handle *, void (*)(void *), void *);

    int (*render_context_create)(bl_mpv_render_ctx **, bl_mpv_handle *, mpv_rp_t *);
    void (*render_context_set_update_callback)(bl_mpv_render_ctx *, void (*)(void *), void *);
    uint64_t (*render_context_update)(bl_mpv_render_ctx *);
    int (*render_context_render)(bl_mpv_render_ctx *, mpv_rp_t *);
    void (*render_context_report_swap)(bl_mpv_render_ctx *);
    void (*render_context_free)(bl_mpv_render_ctx *);
} bl_mpv_table_t;

static bl_mpv_table_t g_mpv = {0};
static pthread_once_t g_mpv_once = PTHREAD_ONCE_INIT;

static void *bl_gl_get_proc_address(void *ctx, const char *name) {
    (void)ctx;
    if (g_mpv.gl_framework != NULL) {
        void *sym = dlsym(g_mpv.gl_framework, name);
        if (sym != NULL) {
            return sym;
        }
    }
    return dlsym(RTLD_DEFAULT, name);
}

#define LOAD_MPV_SYM(field, sym_name)                                    \
    do {                                                                 \
        g_mpv.field = dlsym(handle, sym_name);                           \
        if (g_mpv.field == NULL) {                                       \
            dlclose(handle);                                             \
            return false;                                                \
        }                                                                \
    } while (0)

static bool try_load_mpv_from(const char *candidate) {
    if (candidate == NULL || candidate[0] == '\0') {
        return false;
    }
    void *handle = dlopen(candidate, RTLD_LAZY | RTLD_LOCAL);
    if (handle == NULL) {
        return false;
    }

    LOAD_MPV_SYM(create, "mpv_create");
    LOAD_MPV_SYM(initialize, "mpv_initialize");
    LOAD_MPV_SYM(terminate_destroy, "mpv_terminate_destroy");
    LOAD_MPV_SYM(free_mem, "mpv_free");
    LOAD_MPV_SYM(set_option_string, "mpv_set_option_string");
    LOAD_MPV_SYM(set_property, "mpv_set_property");
    LOAD_MPV_SYM(set_property_string, "mpv_set_property_string");
    LOAD_MPV_SYM(get_property, "mpv_get_property");
    LOAD_MPV_SYM(get_property_string, "mpv_get_property_string");
    LOAD_MPV_SYM(observe_property, "mpv_observe_property");
    LOAD_MPV_SYM(command, "mpv_command");
    LOAD_MPV_SYM(command_async, "mpv_command_async");
    LOAD_MPV_SYM(wait_event, "mpv_wait_event");
    LOAD_MPV_SYM(set_wakeup_callback, "mpv_set_wakeup_callback");
    LOAD_MPV_SYM(render_context_create, "mpv_render_context_create");
    LOAD_MPV_SYM(render_context_set_update_callback, "mpv_render_context_set_update_callback");
    LOAD_MPV_SYM(render_context_update, "mpv_render_context_update");
    LOAD_MPV_SYM(render_context_render, "mpv_render_context_render");
    LOAD_MPV_SYM(render_context_report_swap, "mpv_render_context_report_swap");
    LOAD_MPV_SYM(render_context_free, "mpv_render_context_free");

    g_mpv.dylib = handle;
    snprintf(g_mpv.loaded_path, sizeof(g_mpv.loaded_path), "%s", candidate);
    g_mpv.gl_framework = dlopen(
        "/System/Library/Frameworks/OpenGL.framework/OpenGL",
        RTLD_LAZY | RTLD_LOCAL
    );
    g_mpv.ok = true;
    return true;
}

static void init_mpv_once(void) {
    char exec_path[PATH_MAX] = {0};
    uint32_t exec_size = sizeof(exec_path);
    if (_NSGetExecutablePath(exec_path, &exec_size) == 0) {
        char real_exec[PATH_MAX] = {0};
        const char *resolved = realpath(exec_path, real_exec) ? real_exec : exec_path;
        char dir_buf[PATH_MAX] = {0};
        snprintf(dir_buf, sizeof(dir_buf), "%s", resolved);
        char *macos_dir = dirname(dir_buf);
        if (macos_dir != NULL) {
            char bundled_candidate[PATH_MAX] = {0};
            snprintf(
                bundled_candidate,
                sizeof(bundled_candidate),
                "%s/../Frameworks/libmpv.2.dylib",
                macos_dir
            );
            if (try_load_mpv_from(bundled_candidate)) {
                return;
            }
        }
    }

    const char *candidates[] = {
        "/opt/homebrew/opt/mpv/lib/libmpv.2.dylib",
        "/opt/homebrew/lib/libmpv.2.dylib",
        "/usr/local/opt/mpv/lib/libmpv.2.dylib",
        "/usr/local/lib/libmpv.2.dylib",
        "libmpv.2.dylib"
    };
    for (size_t i = 0; i < sizeof(candidates) / sizeof(candidates[0]); ++i) {
        if (try_load_mpv_from(candidates[i])) {
            return;
        }
    }
}

bool bl_mpv_is_available(void) {
    pthread_once(&g_mpv_once, init_mpv_once);
    return g_mpv.ok;
}

const char *bl_mpv_loaded_path(void) {
    pthread_once(&g_mpv_once, init_mpv_once);
    return g_mpv.ok ? g_mpv.loaded_path : "";
}

bl_mpv_handle *bl_mpv_create_initialized(void) {
    if (!bl_mpv_is_available()) {
        return NULL;
    }
    setlocale(LC_NUMERIC, "C");

    bl_mpv_handle *ctx = g_mpv.create();
    if (ctx == NULL) {
        return NULL;
    }

    g_mpv.set_option_string(ctx, "config", "no");
    g_mpv.set_option_string(ctx, "vo", "libmpv");
    g_mpv.set_option_string(ctx, "hwdec", "videotoolbox");
    g_mpv.set_option_string(ctx, "hwdec-codecs", "all");
    g_mpv.set_option_string(ctx, "keep-open", "yes");
    g_mpv.set_option_string(ctx, "ytdl", "no");
    g_mpv.set_option_string(ctx, "input-default-bindings", "no");
    g_mpv.set_option_string(ctx, "input-vo-keyboard", "no");
    g_mpv.set_option_string(ctx, "osd-level", "0");
    g_mpv.set_option_string(ctx, "sub-auto", "fuzzy");
    g_mpv.set_option_string(ctx, "sub-font-size", "42");
    g_mpv.set_option_string(ctx, "cache", "yes");
    g_mpv.set_option_string(ctx, "demuxer-max-bytes", "64MiB");
    g_mpv.set_option_string(ctx, "demuxer-max-back-bytes", "24MiB");
    g_mpv.set_option_string(ctx, "network-timeout", "20");

    if (g_mpv.initialize(ctx) < 0) {
        g_mpv.terminate_destroy(ctx);
        return NULL;
    }

    g_mpv.observe_property(ctx, 1, "time-pos", MPV_FMT_NONE);
    g_mpv.observe_property(ctx, 2, "duration", MPV_FMT_NONE);
    g_mpv.observe_property(ctx, 3, "pause", MPV_FMT_NONE);
    g_mpv.observe_property(ctx, 4, "eof-reached", MPV_FMT_NONE);
    g_mpv.observe_property(ctx, 5, "paused-for-cache", MPV_FMT_NONE);
    g_mpv.observe_property(ctx, 6, "track-list", MPV_FMT_NONE);
    g_mpv.observe_property(ctx, 7, "chapter-list", MPV_FMT_NONE);
    g_mpv.observe_property(ctx, 8, "hwdec-current", MPV_FMT_NONE);

    return ctx;
}

void bl_mpv_terminate(bl_mpv_handle *ctx) {
    if (ctx != NULL && g_mpv.ok && g_mpv.terminate_destroy != NULL) {
        g_mpv.terminate_destroy(ctx);
    }
}

void bl_mpv_set_wakeup_callback(
    bl_mpv_handle *ctx,
    void (*cb)(void *userdata),
    void *userdata
) {
    if (ctx != NULL && g_mpv.ok) {
        g_mpv.set_wakeup_callback(ctx, cb, userdata);
    }
}

bool bl_mpv_poll_event(bl_mpv_handle *ctx, bl_mpv_polled_event *out_ev) {
    if (ctx == NULL || out_ev == NULL || !g_mpv.ok) {
        return false;
    }
    memset(out_ev, 0, sizeof(*out_ev));
    mpv_ev_t *ev = g_mpv.wait_event(ctx, 0.0);
    if (ev == NULL || ev->event_id == BL_MPV_EVENT_NONE) {
        return false;
    }
    out_ev->event_id = ev->event_id;
    if (ev->event_id == BL_MPV_EVENT_PROPERTY_CHANGE && ev->data != NULL) {
        mpv_ev_prop_t *prop = (mpv_ev_prop_t *)ev->data;
        if (prop->name != NULL) {
            snprintf(out_ev->prop_name, sizeof(out_ev->prop_name), "%s", prop->name);
        }
    } else if (ev->event_id == BL_MPV_EVENT_END_FILE && ev->data != NULL) {
        mpv_ev_end_file_t *ef = (mpv_ev_end_file_t *)ev->data;
        out_ev->end_reason = ef->reason;
        out_ev->end_error = ef->error;
    }
    return true;
}

int bl_mpv_set_property_string(bl_mpv_handle *ctx, const char *name, const char *val) {
    if (ctx == NULL || name == NULL || !g_mpv.ok) {
        return -1;
    }
    return g_mpv.set_property_string(ctx, name, val ? val : "");
}

int bl_mpv_set_property_flag(bl_mpv_handle *ctx, const char *name, int flag) {
    if (ctx == NULL || name == NULL || !g_mpv.ok) {
        return -1;
    }
    int v = flag ? 1 : 0;
    return g_mpv.set_property(ctx, name, MPV_FMT_FLAG, &v);
}

int bl_mpv_set_property_double(bl_mpv_handle *ctx, const char *name, double val) {
    if (ctx == NULL || name == NULL || !g_mpv.ok) {
        return -1;
    }
    double v = val;
    return g_mpv.set_property(ctx, name, MPV_FMT_DOUBLE, &v);
}

int bl_mpv_set_property_int64(bl_mpv_handle *ctx, const char *name, int64_t val) {
    if (ctx == NULL || name == NULL || !g_mpv.ok) {
        return -1;
    }
    int64_t v = val;
    return g_mpv.set_property(ctx, name, MPV_FMT_INT64, &v);
}

int bl_mpv_get_property_double(bl_mpv_handle *ctx, const char *name, double *out_val) {
    if (ctx == NULL || name == NULL || out_val == NULL || !g_mpv.ok) {
        return -1;
    }
    return g_mpv.get_property(ctx, name, MPV_FMT_DOUBLE, out_val);
}

int bl_mpv_get_property_flag(bl_mpv_handle *ctx, const char *name, int *out_val) {
    if (ctx == NULL || name == NULL || out_val == NULL || !g_mpv.ok) {
        return -1;
    }
    return g_mpv.get_property(ctx, name, MPV_FMT_FLAG, out_val);
}

int bl_mpv_get_property_int64(bl_mpv_handle *ctx, const char *name, int64_t *out_val) {
    if (ctx == NULL || name == NULL || out_val == NULL || !g_mpv.ok) {
        return -1;
    }
    return g_mpv.get_property(ctx, name, MPV_FMT_INT64, out_val);
}

char *bl_mpv_copy_property_string(bl_mpv_handle *ctx, const char *name) {
    if (ctx == NULL || name == NULL || !g_mpv.ok) {
        return NULL;
    }
    char *raw = g_mpv.get_property_string(ctx, name);
    if (raw == NULL) {
        return NULL;
    }
    char *copy = strdup(raw);
    g_mpv.free_mem(raw);
    return copy;
}

int bl_mpv_command_1(bl_mpv_handle *ctx, const char *a0) {
    if (ctx == NULL || a0 == NULL || !g_mpv.ok) {
        return -1;
    }
    const char *cmd[] = { a0, NULL };
    return g_mpv.command_async(ctx, 0, cmd);
}

int bl_mpv_command_2(bl_mpv_handle *ctx, const char *a0, const char *a1) {
    if (ctx == NULL || a0 == NULL || a1 == NULL || !g_mpv.ok) {
        return -1;
    }
    const char *cmd[] = { a0, a1, NULL };
    return g_mpv.command_async(ctx, 0, cmd);
}

int bl_mpv_command_3(bl_mpv_handle *ctx, const char *a0, const char *a1, const char *a2) {
    if (ctx == NULL || a0 == NULL || a1 == NULL || a2 == NULL || !g_mpv.ok) {
        return -1;
    }
    const char *cmd[] = { a0, a1, a2, NULL };
    return g_mpv.command_async(ctx, 0, cmd);
}

int bl_mpv_command_4(
    bl_mpv_handle *ctx,
    const char *a0,
    const char *a1,
    const char *a2,
    const char *a3
) {
    if (ctx == NULL || a0 == NULL || a1 == NULL || a2 == NULL || a3 == NULL || !g_mpv.ok) {
        return -1;
    }
    const char *cmd[] = { a0, a1, a2, a3, NULL };
    return g_mpv.command_async(ctx, 0, cmd);
}

bl_mpv_render_ctx *bl_mpv_render_gl_create(
    bl_mpv_handle *ctx,
    void (*update_cb)(void *userdata),
    void *update_userdata
) {
    if (ctx == NULL || !g_mpv.ok) {
        return NULL;
    }
    mpv_gl_init_params_t gl_init = {
        .get_proc_address = bl_gl_get_proc_address,
        .get_proc_address_ctx = NULL
    };
    int adv = 0;
    mpv_rp_t params[] = {
        { MPV_RP_API_TYPE, (void *)"opengl" },
        { MPV_RP_OPENGL_INIT_PARAMS, &gl_init },
        { MPV_RP_ADVANCED_CONTROL, &adv },
        { MPV_RP_INVALID, NULL }
    };
    bl_mpv_render_ctx *rctx = NULL;
    if (g_mpv.render_context_create(&rctx, ctx, params) < 0 || rctx == NULL) {
        return NULL;
    }
    if (update_cb != NULL) {
        g_mpv.render_context_set_update_callback(rctx, update_cb, update_userdata);
    }
    return rctx;
}

uint64_t bl_mpv_render_gl_update(bl_mpv_render_ctx *rctx) {
    if (rctx == NULL || !g_mpv.ok) {
        return 0;
    }
    return g_mpv.render_context_update(rctx);
}

int bl_mpv_render_gl_draw(
    bl_mpv_render_ctx *rctx,
    int fbo,
    int width,
    int height,
    int flip_y
) {
    if (rctx == NULL || !g_mpv.ok || width <= 0 || height <= 0) {
        return -1;
    }
    mpv_gl_fbo_t target = {
        .fbo = fbo,
        .w = width,
        .h = height,
        .internal_format = 0
    };
    int fy = flip_y ? 1 : 0;
    int block_for_target = 0;
    mpv_rp_t params[] = {
        { MPV_RP_OPENGL_FBO, &target },
        { MPV_RP_FLIP_Y, &fy },
        { MPV_RP_BLOCK_FOR_TARGET, &block_for_target },
        { MPV_RP_INVALID, NULL }
    };
    return g_mpv.render_context_render(rctx, params);
}

void bl_mpv_render_gl_report_swap(bl_mpv_render_ctx *rctx) {
    if (rctx != NULL && g_mpv.ok) {
        g_mpv.render_context_report_swap(rctx);
    }
}

void bl_mpv_render_gl_free(bl_mpv_render_ctx *rctx) {
    if (rctx != NULL && g_mpv.ok) {
        g_mpv.render_context_set_update_callback(rctx, NULL, NULL);
        g_mpv.render_context_free(rctx);
    }
}
