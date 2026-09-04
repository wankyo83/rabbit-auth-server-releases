package com.rabbit.authserver.androidtest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PaidContentResultTest {
    @Test public void lockedResultIsDistinctFromPendingResult() {
        WebAuthEngine.Result locked = WebAuthEngine.Result.locked("https://newtoki1.org/", "test-agent");

        assertTrue(locked.locked);
        assertFalse(locked.pending);
        assertTrue(locked.pages.isEmpty());
    }
}
