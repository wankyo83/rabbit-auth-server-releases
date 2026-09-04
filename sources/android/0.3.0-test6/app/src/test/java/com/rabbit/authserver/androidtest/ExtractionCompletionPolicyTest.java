package com.rabbit.authserver.androidtest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ExtractionCompletionPolicyTest {
    @Test public void exactExpectedCountCompletesImmediately() {
        assertTrue(ExtractionCompletionPolicy.complete(85, 85, false, 0));
    }

    @Test public void partialExpectedCountDoesNotComplete() {
        assertFalse(ExtractionCompletionPolicy.complete(85, 84, true, 5_000));
    }

    @Test public void unknownCountNeedsShortStableWindow() {
        assertFalse(ExtractionCompletionPolicy.complete(0, 85, true, 599));
        assertTrue(ExtractionCompletionPolicy.complete(0, 85, true, 600));
    }

    @Test public void unknownCountNeedsFinishedPage() {
        assertFalse(ExtractionCompletionPolicy.complete(0, 85, false, 2_000));
    }
}
