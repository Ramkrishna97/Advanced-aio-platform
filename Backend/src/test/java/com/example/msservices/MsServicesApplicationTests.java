package com.example.msservices;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:unified-test;DB_CLOSE_DELAY=-1",
        "app.snapshot-dir=${java.io.tmpdir}/all-ms-service-test-snapshots",
        "app.user-resource-dir=${java.io.tmpdir}/all-ms-service-test-users",
        "app.product-resource-dir=${java.io.tmpdir}/all-ms-service-test-products",
        "app.resource-dir=${java.io.tmpdir}/all-ms-service-test-classroom",
        "app.quiz-dir=${java.io.tmpdir}/all-ms-service-test-quizzes",
        "app.video-dir=${java.io.tmpdir}/all-ms-service-test-videos",
        "app.media-dir=${java.io.tmpdir}/all-ms-service-test-feed"
})
@AutoConfigureMockMvc
class MsServicesApplicationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void contextLoads() {
    }

    @Test
    void sellerCanUploadThreeOptimizedProductImages() throws Exception {
        String token = adminToken();
        String product = mvc.perform(post("/api/products").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Slideshow test\",\"description\":\"Three image product\",\"category\":\"Test\",\"price\":99,\"currency\":\"INR\",\"stock\":1}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long productId = mapper.readTree(product).path("id").asLong();
        byte[] source = testImage();

        mvc.perform(multipart("/api/products/{id}/images", productId)
                        .file(new MockMultipartFile("files", "one.png", "image/png", source))
                        .file(new MockMultipartFile("files", "two.png", "image/png", source))
                        .file(new MockMultipartFile("files", "three.png", "image/png", source))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imageFiles.length()").value(3));

        for (int index = 0; index < 3; index++) {
            byte[] stored = mvc.perform(get("/api/products/{id}/images/{position}", productId, index))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertThat(stored.length).isLessThanOrEqualTo(30_000);
        }
    }

    @Test
    void backendCompressesProfilePhotoBelowTenKilobytes() throws Exception {
        String token = adminToken();
        mvc.perform(multipart("/api/auth/me/photo")
                        .file(new MockMultipartFile("file", "large-profile.png", "image/png", testImage()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasPhoto").value(true));

        var photo = mvc.perform(get("/api/auth/me/photo").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(photo.getContentType()).isEqualTo(MediaType.IMAGE_JPEG_VALUE);
        assertThat(photo.getContentAsByteArray().length).isLessThanOrEqualTo(10_000);
    }

    @Test
    void classroomGifUploadPreservesGifAndRejectsFilesAboveFiftyKilobytes() throws Exception {
        String token = adminToken();
        byte[] gif = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==");
        UUID uploadId = UUID.randomUUID();
        mvc.perform(put("/api/feed/media/uploads/{id}/0", uploadId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(gif))
                .andExpect(status().isOk());
        String completed = mvc.perform(post("/api/feed/media/uploads/{id}/complete", uploadId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"animation.gif\",\"type\":\"image/gif\",\"size\":" + gif.length + ",\"chunks\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value(MediaType.IMAGE_GIF_VALUE))
                .andReturn().getResponse().getContentAsString();
        String url = mapper.readTree(completed).path("url").asText();
        var stored = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(stored.getContentType()).isEqualTo(MediaType.IMAGE_GIF_VALUE);
        assertThat(stored.getContentAsByteArray()).containsExactly(gif);

        mvc.perform(post("/api/feed/media/uploads/{id}/complete", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"too-large.gif\",\"type\":\"image/gif\",\"size\":50001,\"chunks\":1}"))
                .andExpect(status().isPayloadTooLarge());
    }

    private String adminToken() throws Exception {
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin\",\"password\":\"qwertyuiop\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(login).path("token").asText();
    }

    private byte[] testImage() throws Exception {
        BufferedImage image = new BufferedImage(900, 700, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            image.setRGB(x, y, ((x * 31 + y * 17) & 255) << 16 | ((x * 11 + y * 29) & 255) << 8 | ((x + y) & 255));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

}
