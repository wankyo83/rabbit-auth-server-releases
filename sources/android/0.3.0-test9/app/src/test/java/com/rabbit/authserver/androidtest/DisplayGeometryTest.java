package com.rabbit.authserver.androidtest;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DisplayGeometryTest {
    @Test public void preservesTabletPixelsAndDensity() {
        DisplayGeometry value = DisplayGeometry.of(2944, 1840, 240);
        assertEquals(2944, value.widthPixels);
        assertEquals(1840, value.heightPixels);
        assertEquals(240, value.densityDpi);
        assertTrue(value.landscape());
        assertTrue(value.tablet());
    }

    @Test public void detectsPortraitPhone() {
        DisplayGeometry value = DisplayGeometry.of(1080, 2400, 420);
        assertFalse(value.landscape());
        assertFalse(value.tablet());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroSizedDisplay() {
        DisplayGeometry.of(0, 1920, 320);
    }
}
