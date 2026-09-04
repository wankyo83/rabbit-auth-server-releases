package com.rabbit.authserver.androidtest;

final class ExtractionCompletionPolicy {
    static final long UNKNOWN_COUNT_STABLE_MS = 600L;

    private ExtractionCompletionPolicy() {}

    static boolean complete(int expected, int actual, boolean pageFinished, long stableMillis) {
        if (actual <= 0) return false;
        if (expected > 0) return actual == expected;
        return pageFinished && stableMillis >= UNKNOWN_COUNT_STABLE_MS;
    }
}
