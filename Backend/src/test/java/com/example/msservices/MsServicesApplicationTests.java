package com.example.msservices;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin\",\"password\":\"qwertyuiop\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(login).path("token").asText();
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

    private byte[] testImage() throws Exception {
        BufferedImage image = new BufferedImage(900, 700, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            image.setRGB(x, y, ((x * 31 + y * 17) & 255) << 16 | ((x * 11 + y * 29) & 255) << 8 | ((x + y) & 255));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

}
