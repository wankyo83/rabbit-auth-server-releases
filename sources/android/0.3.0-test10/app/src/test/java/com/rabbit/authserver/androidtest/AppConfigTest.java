package com.rabbit.authserver.androidtest;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AppConfigTest {
    @Test public void androidServerRunsTwoAuthenticationJobs() {
        assertEquals(2, AppConfig.CONCURRENCY);
    }

    @Test public void updaterUsesDedicatedAndroidManifest() {
        assertEquals(
            "https://raw.githubusercontent.com/wankyo83/rabbit-auth-server-releases/main/updates/android.json",
            AppConfig.UPDATE_MANIFEST_URL
        );
    }
}
