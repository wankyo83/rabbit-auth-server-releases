package com.rabbit.authserver.androidtest;

import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class AppUpdaterReleaseValidationTest {
    private static final String HASH = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

    @Test public void acceptsRepositoryReleaseApk() {
        AppUpdater.validateRelease(new AppUpdater.ReleaseInfo(
            "0.3.0-test7",
            11,
            "RabbitAuthServer-Android-0.3.0-test7.apk",
            "https://github.com/wankyo83/rabbit-auth-server-releases/releases/download/android-v0.3.0-test7/RabbitAuthServer-Android-0.3.0-test7.apk",
            HASH
        ));
    }

    @Test public void rejectsNonHttpsDownload() {
        assertThrows(IllegalArgumentException.class, () -> AppUpdater.validateRelease(new AppUpdater.ReleaseInfo(
            "0.3.0-test7",
            11,
            "RabbitAuthServer-Android-0.3.0-test7.apk",
            "http://github.com/wankyo83/rabbit-auth-server-releases/releases/download/android-v0.3.0-test7/RabbitAuthServer-Android-0.3.0-test7.apk",
            HASH
        )));
    }

    @Test public void rejectsAnotherRepository() {
        assertThrows(IllegalArgumentException.class, () -> AppUpdater.validateRelease(new AppUpdater.ReleaseInfo(
            "0.3.0-test7",
            11,
            "RabbitAuthServer-Android-0.3.0-test7.apk",
            "https://github.com/someone/else/releases/download/v1/RabbitAuthServer-Android-0.3.0-test7.apk",
            HASH
        )));
    }

    @Test public void rejectsInvalidSha256() {
        assertThrows(IllegalArgumentException.class, () -> AppUpdater.validateRelease(new AppUpdater.ReleaseInfo(
            "0.3.0-test7",
            11,
            "RabbitAuthServer-Android-0.3.0-test7.apk",
            "https://github.com/wankyo83/rabbit-auth-server-releases/releases/download/android-v0.3.0-test7/RabbitAuthServer-Android-0.3.0-test7.apk",
            "not-a-hash"
        )));
    }
}
