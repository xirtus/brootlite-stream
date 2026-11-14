#define GL_SILENCE_DEPRECATION 1

#import <AppKit/AppKit.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/gl3.h>
#import <QuartzCore/QuartzCore.h>

#include "CBrootLiteBridge.h"

@interface BLMpvOpenGLLayer : CAOpenGLLayer
@property (nonatomic, assign) bl_mpv_handle *mpvHandle;
@end

static void bl_layer_render_update_cb(void *userdata) {
    if (userdata == NULL) {
        return;
    }
    BLMpvOpenGLLayer *layer = (__bridge BLMpvOpenGLLayer *)userdata;
    dispatch_async(dispatch_get_main_queue(), ^{
        [layer setNeedsDisplay];
    });
}

@implementation BLMpvOpenGLLayer {
    bl_mpv_render_ctx *_renderCtx;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        self.opaque = YES;
        self.asynchronous = NO;
        self.needsDisplayOnBoundsChange = YES;
        CGFloat scale = NSScreen.mainScreen ? NSScreen.mainScreen.backingScaleFactor : 2.0;
        self.contentsScale = scale;
    }
    return self;
}

- (instancetype)initWithLayer:(id)layer {
    self = [super initWithLayer:layer];
    if (self && [layer isKindOfClass:[BLMpvOpenGLLayer class]]) {
        BLMpvOpenGLLayer *other = (BLMpvOpenGLLayer *)layer;
        _mpvHandle = other.mpvHandle;
    }
    return self;
}

- (CGLPixelFormatObj)copyCGLPixelFormatForDisplayMask:(uint32_t)mask {
    CGLPixelFormatAttribute attrs[] = {
        kCGLPFAOpenGLProfile, (CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core,
        kCGLPFAAccelerated,
        kCGLPFADoubleBuffer,
        kCGLPFAColorSize, (CGLPixelFormatAttribute)24,
        kCGLPFAAlphaSize, (CGLPixelFormatAttribute)8,
        kCGLPFABackingStore,
        kCGLPFAAllowOfflineRenderers,
        (CGLPixelFormatAttribute)0
    };
    CGLPixelFormatObj pix = NULL;
    GLint npix = 0;
    CGLChoosePixelFormat(attrs, &pix, &npix);
    if (pix != NULL) {
        return pix;
    }
    return [super copyCGLPixelFormatForDisplayMask:mask];
}

- (CGLContextObj)copyCGLContextForPixelFormat:(CGLPixelFormatObj)pf {
    CGLContextObj ctx = [super copyCGLContextForPixelFormat:pf];
    if (ctx != NULL) {
        CGLSetCurrentContext(ctx);
        GLint swapInterval = 1;
        CGLSetParameter(ctx, kCGLCPSwapInterval, &swapInterval);
        if (_renderCtx == NULL && _mpvHandle != NULL) {
            _renderCtx = bl_mpv_render_gl_create(
                _mpvHandle,
                bl_layer_render_update_cb,
                (__bridge void *)self
            );
        }
    }
    return ctx;
}

- (BOOL)canDrawInCGLContext:(CGLContextObj)ctx
                pixelFormat:(CGLPixelFormatObj)pf
               forLayerTime:(CFTimeInterval)t
                displayTime:(const CVTimeStamp *)ts {
    return YES;
}

- (void)drawInCGLContext:(CGLContextObj)ctx
             pixelFormat:(CGLPixelFormatObj)pf
            forLayerTime:(CFTimeInterval)t
             displayTime:(const CVTimeStamp *)ts {
    CGLSetCurrentContext(ctx);
    if (_renderCtx == NULL) {
        glClearColor(0.035f, 0.043f, 0.063f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glFlush();
        return;
    }

    (void)bl_mpv_render_gl_update(_renderCtx);

    GLint fbo = 0;
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &fbo);

    GLint viewport[4] = { 0, 0, 1, 1 };
    glGetIntegerv(GL_VIEWPORT, viewport);
    int w = viewport[2] > 0 ? viewport[2] : 1;
    int h = viewport[3] > 0 ? viewport[3] : 1;

    (void)bl_mpv_render_gl_draw(_renderCtx, fbo, w, h, 1);
    glFlush();
    bl_mpv_render_gl_report_swap(_renderCtx);
}

- (void)releaseCGLContext:(CGLContextObj)ctx {
    CGLSetCurrentContext(ctx);
    if (_renderCtx != NULL) {
        bl_mpv_render_gl_free(_renderCtx);
        _renderCtx = NULL;
    }
    [super releaseCGLContext:ctx];
}

@end

@interface BLMpvHostView : NSView
- (instancetype)initWithMpvHandle:(bl_mpv_handle *)mpvHandle;
@end

@implementation BLMpvHostView {
    BLMpvOpenGLLayer *_glLayer;
}

- (instancetype)initWithMpvHandle:(bl_mpv_handle *)mpvHandle {
    self = [super initWithFrame:NSZeroRect];
    if (self) {
        self.wantsLayer = YES;
        self.layerContentsRedrawPolicy = NSViewLayerContentsRedrawOnSetNeedsDisplay;
        _glLayer = [[BLMpvOpenGLLayer alloc] init];
        _glLayer.mpvHandle = mpvHandle;
        self.layer = _glLayer;
    }
    return self;
}

- (void)viewDidChangeBackingProperties {
    [super viewDidChangeBackingProperties];
    if (self.window != nil) {
        _glLayer.contentsScale = self.window.backingScaleFactor;
        [_glLayer setNeedsDisplay];
    }
}

- (void)setFrameSize:(NSSize)newSize {
    [super setFrameSize:newSize];
    _glLayer.frame = self.bounds;
    [_glLayer setNeedsDisplay];
}

@end

void *bl_mpv_create_gl_host_view(bl_mpv_handle *mpv_ctx) {
    BLMpvHostView *view = [[BLMpvHostView alloc] initWithMpvHandle:mpv_ctx];
    return (__bridge_retained void *)view;
}
