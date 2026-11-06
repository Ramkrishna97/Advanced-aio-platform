package com.example.dashboard.classroom.video;

import com.example.dashboard.media.MediaLimits;
import com.example.dashboard.media.StorageQuota;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Component
public class VideoStore {
    private final Path videoDir;

    public VideoStore(@Value("${app.video-dir:../resources/classroom/videos}") String dir) {
        this.videoDir = Path.of(dir).toAbsolutePath().normalize();
    }

    public SavedVideo save(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a video file");
        }
        if (file.getContentType() == null || !file.getContentType().startsWith("video/"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a video file");
        if (file.getSize() > MediaLimits.maxVideoBytes())
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Videos must be " + MediaLimits.megabytes(MediaLimits.maxVideoBytes()) + " or smaller");
        String originalName = Objects.requireNonNullElse(file.getOriginalFilename(), "video");
        String filename = UUID.randomUUID() + "-" + Path.of(originalName).getFileName();
        Path destination = videoDir.resolve(filename).normalize();
        try {
            StorageQuota.reserve(file.getSize());
            Files.createDirectories(videoDir);
            Files.copy(file.getInputStream(), destination);
            return new SavedVideo(filename, originalName, file.getContentType());
        } catch (Exception ex) {
            try { Files.deleteIfExists(destination); } catch (Exception ignored) {}
            if (ex instanceof ResponseStatusException statusException) throw statusException;
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Video upload failed", ex);
        }
    }

    public void delete(String filename) {
        if (filename != null) {
            try {
                Files.deleteIfExists(videoDir.resolve(filename).normalize());
            } catch (Exception ignored) {}
        }
    }

    public Path resolve(String filename) {
        return videoDir.resolve(filename).normalize();
    }

    public boolean exists(String filename) {
        if (filename == null) return false;
        Path path = resolve(filename);
        return path.startsWith(videoDir) && Files.isRegularFile(path);
    }

    public record SavedVideo(String filename, String originalName, String contentType) {}
}
