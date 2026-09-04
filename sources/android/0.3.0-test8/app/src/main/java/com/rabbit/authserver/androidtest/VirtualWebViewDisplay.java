package com.rabbit.authserver.androidtest;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.FrameLayout;

final class VirtualWebViewDisplay implements AutoCloseable {
    final WebView webView;
    final DisplayGeometry geometry;
    private final ImageReader imageReader;
    private final VirtualDisplay virtualDisplay;
    private final Presentation presentation;
    private boolean closed;

    private VirtualWebViewDisplay(
        WebView webView,
        DisplayGeometry geometry,
        ImageReader imageReader,
        VirtualDisplay virtualDisplay,
        Presentation presentation
    ) {
        this.webView = webView;
        this.geometry = geometry;
        this.imageReader = imageReader;
        this.virtualDisplay = virtualDisplay;
        this.presentation = presentation;
    }

    static DisplayGeometry readDeviceGeometry(Context context) {
        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics metrics = new DisplayMetrics();
        if (display != null) {
            display.getRealMetrics(metrics);
        } else {
            metrics.setTo(context.getResources().getDisplayMetrics());
        }
        return DisplayGeometry.of(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);
    }

    static VirtualWebViewDisplay create(Context context, Handler main) {
        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) throw new IllegalStateException("DisplayManager unavailable");
        DisplayGeometry geometry = readDeviceGeometry(context);
        ImageReader reader = null;
        VirtualDisplay display = null;
        Presentation presentation = null;
        WebView webView = null;
        try {
            reader = ImageReader.newInstance(
                geometry.widthPixels,
                geometry.heightPixels,
                PixelFormat.RGBA_8888,
                2
            );
            reader.setOnImageAvailableListener(source -> {
                Image image = null;
                try {
                    image = source.acquireLatestImage();
                } finally {
                    if (image != null) image.close();
                }
            }, main);
            display = manager.createVirtualDisplay(
                "RabbitAuthWebView",
                geometry.widthPixels,
                geometry.heightPixels,
                geometry.densityDpi,
                reader.getSurface(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY |
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            );
            if (display == null || display.getDisplay() == null) {
                throw new IllegalStateException("VirtualDisplay creation failed");
            }

            Context displayContext = context.createDisplayContext(display.getDisplay());
            presentation = new Presentation(displayContext, display.getDisplay());
            FrameLayout root = new FrameLayout(presentation.getContext());
            root.setBackgroundColor(Color.WHITE);
            webView = new WebView(presentation.getContext());
            root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
            presentation.setContentView(root);
            Window window = presentation.getWindow();
            if (window != null) window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            presentation.show();
            if (window != null) window.setLayout(geometry.widthPixels, geometry.heightPixels);
            root.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(geometry.widthPixels, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(geometry.heightPixels, android.view.View.MeasureSpec.EXACTLY)
            );
            root.layout(0, 0, geometry.widthPixels, geometry.heightPixels);
            webView.onResume();
            webView.requestFocus(View.FOCUS_DOWN);
            return new VirtualWebViewDisplay(webView, geometry, reader, display, presentation);
        } catch (Throwable error) {
            if (webView != null) webView.destroy();
            if (presentation != null) presentation.dismiss();
            if (display != null) display.release();
            if (reader != null) reader.close();
            if (error instanceof RuntimeException runtime) throw runtime;
            if (error instanceof Error fatal) throw fatal;
            throw new IllegalStateException(error);
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        webView.stopLoading();
        webView.loadUrl("about:blank");
        webView.clearHistory();
        webView.removeAllViews();
        webView.onPause();
        presentation.dismiss();
        webView.destroy();
        virtualDisplay.release();
        imageReader.close();
    }
}
