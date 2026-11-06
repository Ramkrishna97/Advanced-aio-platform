package com.example.dashboard.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

public final class ImageOptimizer {
    private static final long MAX_PIXELS = 40_000_000L;

    private ImageOptimizer() {}

    public static byte[] optimize(InputStream input) throws IOException {
        return optimize(input, MediaLimits.maxStoredImageBytes());
    }

    public static byte[] optimize(InputStream input, int targetBytes) throws IOException {
        return optimize(input, targetBytes, false);
    }

    public static byte[] optimizeAnyDimensions(InputStream input, int targetBytes) throws IOException {
        return optimize(input, targetBytes, true);
    }

    private static byte[] optimize(InputStream input, int targetBytes, boolean allowLargeDimensions) throws IOException {
        if (targetBytes < 1) throw new IllegalArgumentException("Image storage limit must be positive");
        BufferedImage source = readSafely(input, allowLargeDimensions);

        BufferedImage image = resize(source, scaleForEdge(source, MediaLimits.maxImageEdge()));
        while (true) {
            for (float quality = 0.82f; quality >= 0.22f; quality -= 0.10f) {
                byte[] encoded = jpeg(image, quality);
                if (encoded.length <= targetBytes) return encoded;
            }
            if (image.getWidth() <= 160 && image.getHeight() <= 160)
                throw new IllegalArgumentException("Image could not be reduced below the configured storage limit");
            image = resize(image, 0.75d);
        }
    }

    private static BufferedImage readSafely(InputStream input, boolean allowLargeDimensions) throws IOException {
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) throw new IllegalArgumentException("Choose a valid image file");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw new IllegalArgumentException("Choose a valid image file");
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (!allowLargeDimensions && (long) width * height > MAX_PIXELS)
                    throw new IllegalArgumentException("Image dimensions are too large");
                int maxEdge = MediaLimits.maxImageEdge();
                int sample = Math.max(1, (int) Math.ceil((double) Math.max(width, height) / maxEdge));
                ImageReadParam parameters = reader.getDefaultReadParam();
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage image = reader.read(0, parameters);
                if (image == null) throw new IllegalArgumentException("Choose a valid image file");
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private static double scaleForEdge(BufferedImage image, int maxEdge) {
        int edge = Math.max(image.getWidth(), image.getHeight());
        return edge > maxEdge ? (double) maxEdge / edge : 1d;
    }

    private static BufferedImage resize(BufferedImage source, double scale) {
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("JPEG encoder is unavailable");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), parameters);
            writer.dispose();
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    public static byte[] optimize(byte[] input) throws IOException {
        return optimize(new ByteArrayInputStream(input));
    }

    public static byte[] optimize(byte[] input, int targetBytes) throws IOException {
        return optimize(new ByteArrayInputStream(input), targetBytes);
    }
}
