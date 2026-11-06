package com.example.dashboard.media;

public final class MediaLimits {
    public static final long DEPLOYMENT_IMAGE_UPLOAD_BYTES = 10_000_000L;
    public static final int DEPLOYMENT_STORED_IMAGE_BYTES = 30_000;
    public static final long DEPLOYMENT_VIDEO_BYTES = 2_000_000L;

    private static final long LOCAL_IMAGE_UPLOAD_BYTES = 50_000_000L;
    private static final int LOCAL_STORED_IMAGE_BYTES = 5_000_000;
    private static final long LOCAL_VIDEO_BYTES = 2_000_000_000L;

    private MediaLimits() {}

    public static boolean resourceConstrained() {
        return Boolean.parseBoolean(value("MEDIA_RESOURCE_LIMITS", "false"));
    }

    public static long maxImageUploadBytes() {
        return number("MEDIA_MAX_IMAGE_UPLOAD_BYTES",
                resourceConstrained() ? DEPLOYMENT_IMAGE_UPLOAD_BYTES : LOCAL_IMAGE_UPLOAD_BYTES);
    }

    public static int maxStoredImageBytes() {
        long value = number("MEDIA_MAX_STORED_IMAGE_BYTES",
                resourceConstrained() ? DEPLOYMENT_STORED_IMAGE_BYTES : LOCAL_STORED_IMAGE_BYTES);
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    public static int maxImageEdge() {
        return (int) number("MEDIA_MAX_IMAGE_EDGE", resourceConstrained() ? 1_600 : 4_096);
    }

    public static long maxVideoBytes() {
        return number("MEDIA_MAX_VIDEO_BYTES",
                resourceConstrained() ? DEPLOYMENT_VIDEO_BYTES : LOCAL_VIDEO_BYTES);
    }

    public static long maxOtherFileBytes() {
        return number("MEDIA_MAX_OTHER_FILE_BYTES",
                resourceConstrained() ? DEPLOYMENT_VIDEO_BYTES : LOCAL_VIDEO_BYTES);
    }

    public static int maxUploadChunks() {
        long largestUpload = Math.max(maxVideoBytes(), maxImageUploadBytes());
        return (int) Math.min(2_048, Math.max(1, (largestUpload + 999_999L) / 1_000_000L));
    }

    public static String megabytes(long bytes) {
        return Math.max(1, bytes / 1_000_000L) + " MB";
    }

    static long number(String name, long fallback) {
        try { return Long.parseLong(value(name, Long.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static String value(String name, String fallback) {
        String property = System.getProperty(name);
        if (property != null && !property.isBlank()) return property;
        String environment = System.getenv(name);
        return environment == null || environment.isBlank() ? fallback : environment;
    }
}
