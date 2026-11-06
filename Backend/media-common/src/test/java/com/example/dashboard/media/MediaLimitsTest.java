package com.example.dashboard.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MediaLimitsTest {
    @AfterEach
    void clearOverrides() {
        System.clearProperty("MEDIA_RESOURCE_LIMITS");
        System.clearProperty("MEDIA_MAX_VIDEO_BYTES");
        System.clearProperty("MEDIA_MAX_STORED_IMAGE_BYTES");
    }

    @Test
    void localDefaultsRemainGenerous() {
        System.setProperty("MEDIA_RESOURCE_LIMITS", "false");
        assertTrue(MediaLimits.maxVideoBytes() > MediaLimits.DEPLOYMENT_VIDEO_BYTES);
        assertTrue(MediaLimits.maxStoredImageBytes() > MediaLimits.DEPLOYMENT_STORED_IMAGE_BYTES);
    }

    @Test
    void deploymentOverridesAreApplied() {
        System.setProperty("MEDIA_RESOURCE_LIMITS", "true");
        System.setProperty("MEDIA_MAX_VIDEO_BYTES", "2000000");
        System.setProperty("MEDIA_MAX_STORED_IMAGE_BYTES", "50000");
        assertEquals(2_000_000L, MediaLimits.maxVideoBytes());
        assertEquals(50_000, MediaLimits.maxStoredImageBytes());
    }
}
