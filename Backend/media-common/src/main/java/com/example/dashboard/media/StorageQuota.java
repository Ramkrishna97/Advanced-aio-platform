package com.example.dashboard.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class StorageQuota {
    private StorageQuota() {}

    public static void reserve(long additionalBytes) {
        long limit = MediaLimits.number("MEDIA_STORAGE_LIMIT_BYTES", 0);
        String configuredRoot = System.getenv("STORAGE_ROOT");
        if (limit <= 0 || configuredRoot == null || configuredRoot.isBlank()) return;

        Path root = Path.of(configuredRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            long used;
            try (var files = Files.walk(root)) {
                used = files.filter(Files::isRegularFile).mapToLong(path -> {
                    try { return Files.size(path); }
                    catch (IOException ignored) { return 0L; }
                }).sum();
            }
            if (additionalBytes > limit || used > limit - additionalBytes) {
                throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE,
                        "Deployment storage is full. Delete an existing upload and try again.");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE,
                    "Deployment storage is unavailable. Try again later.", exception);
        }
    }
}
