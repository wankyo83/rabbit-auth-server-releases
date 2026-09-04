package com.rabbit.authserver.androidtest;

final class DisplayGeometry {
    final int widthPixels;
    final int heightPixels;
    final int densityDpi;
    final int widthDp;
    final int heightDp;

    private DisplayGeometry(int widthPixels, int heightPixels, int densityDpi) {
        this.widthPixels = widthPixels;
        this.heightPixels = heightPixels;
        this.densityDpi = densityDpi;
        this.widthDp = Math.max(1, Math.round(widthPixels * 160f / densityDpi));
        this.heightDp = Math.max(1, Math.round(heightPixels * 160f / densityDpi));
    }

    static DisplayGeometry of(int widthPixels, int heightPixels, int densityDpi) {
        if (widthPixels <= 0 || heightPixels <= 0 || densityDpi <= 0) {
            throw new IllegalArgumentException("Display dimensions and density must be positive");
        }
        return new DisplayGeometry(widthPixels, heightPixels, densityDpi);
    }

    boolean landscape() {
        return widthPixels > heightPixels;
    }

    boolean tablet() {
        return Math.min(widthDp, heightDp) >= 600;
    }

    String summary() {
        return (tablet() ? "태블릿" : "휴대폰") + " · " + widthPixels + "×" + heightPixels +
            " · " + (landscape() ? "가로" : "세로") + " · " + densityDpi + "dpi";
    }
}
