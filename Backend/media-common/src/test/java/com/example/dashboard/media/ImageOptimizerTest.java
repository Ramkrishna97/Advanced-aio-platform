package com.example.dashboard.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageOptimizerTest {
    @Test
    void producesJpegWithinStorageLimit() throws Exception {
        System.setProperty("MEDIA_MAX_STORED_IMAGE_BYTES", "50000");
        System.setProperty("MEDIA_MAX_IMAGE_EDGE", "1600");
        try {
            BufferedImage source = new BufferedImage(2200, 1400, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++)
                source.setRGB(x, y, ((x * 73 + y * 31) & 255) << 16 | ((x * 19 + y * 47) & 255) << 8 | ((x + y) & 255));
            ByteArrayOutputStream input = new ByteArrayOutputStream();
            ImageIO.write(source, "png", input);

            byte[] result = ImageOptimizer.optimize(input.toByteArray());

            assertTrue(result.length <= 50_000);
            assertEquals(0xff, result[0] & 0xff);
            assertEquals(0xd8, result[1] & 0xff);
        } finally {
            System.clearProperty("MEDIA_MAX_STORED_IMAGE_BYTES");
            System.clearProperty("MEDIA_MAX_IMAGE_EDGE");
        }
    }
}
